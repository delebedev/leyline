---
summary: "Compressed archive of per-mechanic wire protocol patterns from March 2026 investigation."
read_when:
  - "implementing a new mechanic and need wire protocol reference"
  - "debugging annotation sequences for an existing mechanic"
  - "investigating how Arena encodes a specific game action on the wire"
---

# Wire Spec Archive

Distilled from 24 per-mechanic plan files (March 2026). Each entry captures the confirmed wire protocol pattern and non-obvious gotchas. Source recordings referenced inline.

---

## Zone Changes

### Play Land (ETB tapped)
Object arrives with `isTapped: true` on the `GameObjectInfo` — **no TappedUntappedPermanent annotation**. No ReplacementEffect (62) or ReplacementEffectApplied (77) annotation either. Tapped state is purely an object field.

Sequence: `ObjectIdChanged → ZoneTransfer(category="PlayLand") → UserActionTaken(actionType=3)`.

ETB triggers (lifegain, scry) fire as `AbilityInstanceCreated` in the same GSM, resolve in subsequent diffs.

### Mill (Library → Graveyard)
Each milled card gets its own `ObjectIdChanged + ZoneTransfer` pair — **not batched**. Category: `"Mill"`. All pairs share the same `affectorId` (the resolving spell/ability instance). Hidden library IDs get new public IDs via ObjectIdChanged.

**Gotcha:** `CardMilled` event in our code lacks `sourceForgeCardId` — affectorId emits as 0.

### Exile (Battlefield → Exile)
Category: `"Exile"`. Same ObjectIdChanged-before-ZoneTransfer pattern. For temporary exile (auras, Stasis Snare), a persistent `DisplayCardUnderCard` annotation fires: `affectorId=enchantmentIid, affectedIds=[exiledCardNewIid], details={TemporaryZoneTransfer:1, Disable:0}`.

**Gotcha:** DisplayCardUnderCard shape is identical for aura-exile and non-aura-enchantment-exile. Our builder is missing `affectorId`.

### Discard (Hand → Graveyard)
Category: `"Discard"`. Double-discard (e.g. Kiora) fires two separate ObjectIdChanged+ZoneTransfer pairs, same affectorId, one ResolutionComplete at end.

**Bug found:** SelectNReq for discard uses wrong context/listType. Wire shows `context=Resolution_a163`, `listType=Dynamic`, `optionContext=Resolution_a9d7` — not `Discard_a163`/`Static`.

### Library Search (Library → Hand, via tutor)
Category: **`"Put"` (not `"Draw"` or `"Search"`)**. `SearchReq`/`SearchResp` are pure handshake messages with **no payload** — the actual card choice is communicated by engine state, not the response.

**Gotcha:** Our zone-pair heuristic maps Library→Hand to `Draw`. Search-to-hand must override to `Put`. Library→Battlefield search uses `Search`.

### Shuffle
`Shuffle` annotation carries `OldIds` (pre-shuffle library instanceIds) and `NewIds` (post-shuffle). Every remaining library card gets a fresh instanceId. `affectorId` = resolving spell, `affectedIds` = [seatId].

### Countered (Stack → Graveyard)
Category: `"Countered"`. No ResolutionStart/Complete for the countered spell — those belong to the counterspell itself. `affectorId` on the countered spell's ZoneTransfer = ward trigger instance ID (confirmed from ward counter recording). First live confirmation of "Countered" string from ward-counter session.

### SBA Deaths (Battlefield → Graveyard)
Two distinct categories: `"SBA_ZeroToughness"` (toughness ≤ 0) and `"SBA_Damage"` (lethal damage). Both have **no affectorId** (rule-driven, no source). Our code falls back to `"Destroy"` for both.

---

## Combat

### Damage Dealt
One `DamageDealt` annotation per source→target pair. `affectorId` = source creature, `affectedIds` = [target creature or player seatId], `details: {damage: N, type: 1, markDamage: 1}`. Blocker→attacker damage included.

**Bug found:** Our `damageDealt()` builder never sets `affectorId` and puts source (not target) in `affectedIds`.

### Combat Object State Fields
Attackers: `attackState=Attacking`, `attackInfo={targetId: N}`. After blocker declaration: `blockState=Blocked|Unblocked`. Blockers: `blockState=Blocking`, `blockInfo={attackerIds: [N]}`.

**Bug found:** Our ObjectMapper never populates `attackInfo.targetId` or `blockInfo.attackerIds`.

---

## Stack / Spells

### Modal Spells (CastingTimeOptionsReq)
`type=Modal`, `isRequired=true`, options listed by grpId. Client's `CastingTimeOptionsResp` carries **no payload** — mode is inferred from subsequent targeting. Same mechanism for all modal spells (Bushwhack fight/search, etc.).

### Target Selection Protocol
Multi-round handshake: client sends `SelectTargetsResp` one targetIdx at a time → server echoes back `SelectTargetsReq` with updated state → repeat → client sends `SubmitTargetsReq` (no payload) to finalize. Fight mode uses two simultaneous targetIdx groups.

Top-level `abilityGrpId` = spell grpId. Per-target `targetingAbilityGrpId` = mode ability grpId.

### Adventure (CastAdventure)
ActionType 16. Adventure face on stack has `type: Adventure(10)`, grpId = adventure face grpId. Cast from exile uses standard `Cast` (not CastAdventure). Exile→Stack should map to `TransferCategory.CastSpell`, not ZoneTransfer.

**Gotcha:** `inferCategory` has no Exile→Stack branch for CastSpell. ActionMapper doesn't emit CastAdventure actions. MatchSession has no CastAdventure handler.

### Resolution Pattern
`ResolutionStart → [effect annotations] → ResolutionComplete → ObjectIdChanged(affectorId=seatId) → ZoneTransfer(category="Resolve")`. Spell's own GY transfer always uses `affectorId=seatId`.

---

## Card Selection / Prompts

### Scry (GroupReq)
Prompt type: `GroupReq` (not SelectNReq). Two groupSpecs: Library/Top and Library/Bottom, both with `upperBound=N`. `context="Scry"`, `sourceId=abilityInstanceId`. GroupReq only sent to human player — AI scry resolved server-side.

Scry annotation: `affectorId=abilityInstanceId`, `affectedIds=[scryed card instanceIds]`. Details: `topIds="?"` (always string sentinel), `bottomIds=[instanceIds sent to bottom]`. No ZoneTransfer annotations for scry (unlike surveil).

**Bugs found:** Our builder uses wrong keys (`topCount`/`bottomCount` vs `topIds`/`bottomIds`), wrong affectedIds (seatId vs card IDs), missing affectorId.

### Surveil
Expected to use GroupReq similar to scry, but live wire data showed SelectNReq for the related mill+return spell. True surveil recording still needed. Category: `"Surveil"` (not `"Mill"`). Each card gets individual ZoneTransfer.

**Gotcha:** `player.allCards` bug in multi-card GroupResp — cards in zoneless limbo during `requestChoice` are invisible. Must use `game.findById()`.

### SelectNReq (General)
Used for discard selection, punisher choices, graveyard return picks. Common fields: `context="Resolution_a163"`, `optionContext="Resolution_a9d7"`, `listType="Dynamic"`, `idType="InstanceId_ab2c"`. Punisher effects (Perforating Artist) use two-level SelectN: first picks cost type (`idType="PromptParameterIndex"`), second picks the permanent/card.

### ChoiceResult (Type 58)
Fires after SelectNResp for sacrifice/discard choices. `affectorId` = source permanent (not ability instance), `affectedIds` = [chooser seatId]. `Choice_Value` = chosen instanceId, `Choice_Sentiment` = 1 (sacrifice/cost). **Does not fire for "lose life" punisher path.** Placement: annotation [0] in the batch (before ZoneTransfer consequences). Transient only.

---

## Keyword Mechanics

### Ward
Two ward abilities on same creature → two independent triggers (AbilityInstanceCreated from same affectorId). Each resolves via `PayCostsReq` (promptId=11) sent to the spell's controller. `ManaPaid.affectedIds` = [targeted spell iid], not ward trigger. If not paid, ZoneTransfer with `category="Countered"` fires immediately.

**Gotcha:** Second ward trigger may skip PayCostsReq entirely if opponent can't pay — goes straight to counter.

### Threshold (AbilityWordActive)
Persistent annotation `AbilityWordActive` born at ETB with `{threshold: 7, value: 0, AbilityWordName: "Threshold"}`. Value updates every GY change. At crossing: persistent `LayeredEffect` annotations added, object P/T updates. **No transient annotation at crossing** — purely persistent state change + object diff.

### Raid
Not a distinct annotation type. Raid condition is engine-internal. Effects surface as normal annotations (CounterAdded for +1/+1 counter, ModifiedLife for life loss, etc.). Likely works already.

### Explore
Reveal step uses `RevealedCardCreated` annotation + RevealedCard proxy objects in Revealed zone (18/19). `InstanceRevealedToOpponent` persistent annotation marks revealed cards.

**Gotcha:** RevealedCard proxy objects (type 8) need synthesizing in zone lists. Proxy cleanup via `diffDeletedInstanceIds`, not a dedicated annotation.

---

## Attachment

### Equipment and Aura — Same Shape
`AttachmentCreated` (transient) + `Attachment` (persistent): `affectorId=equipmentOrAuraIid, affectedIds=[hostCreatureIid]`.

On re-equip: `Attachment` pAnn gets fresh ID, but `ModifiedPower/Toughness+LayeredEffect` pAnn **keeps same ID** with updated affectedIds.

**Bug found:** Our builder omits affectorId and puts both IDs in affectedIds — wire shows affectorId=source, affectedIds=[target only].

---

## Tokens

### Token Creation
`TokenCreated` annotation: `affectorId=triggerAbilityInstanceIid, affectedIds=[tokenIid]`. Token object has `type=Token`.

### Treasure Sacrifice
Activated via `ActivateMana`. Sequence: `AbilityInstanceCreated → TappedUntappedPermanent → ObjectIdChanged → ZoneTransfer(category="Sacrifice") → ManaPaid → AbilityInstanceDeleted → TokenDeleted`. Token briefly visits GY before `TokenDeleted` fires.

---

## Advisory Annotations

### ShouldntPlay (Legendary)
Fires when player holds duplicate legendary. Sent to **opponent's** seat only (not hand-owner). `affectorId` = battlefield legendary, `affectedIds` = [hand card]. Transient (added + deleted same GSM). Fires at main-phase entries and stack-clear events, not every priority point.

### DisplayCardUnderCard
Persistent. Marks temporary exile relationships. `affectorId` = enchantment iid, `affectedIds` = [exiled card new iid]. `TemporaryZoneTransfer:1` = will return when source leaves. Used for both aura-exile and standalone-enchantment-exile.

---

## Protocol Patterns (Cross-Cutting)

### ObjectIdChanged Always Precedes ZoneTransfer
Every zone transfer that changes instanceId: ObjectIdChanged fires first with `orig_id`/`new_id`, then ZoneTransfer references the **new** ID.

### affectorId Conventions
- Player-initiated cast: no affectorId on CastSpell ZoneTransfer
- Spell/ability effect: affectorId = resolving spell/ability instanceId
- SBA deaths: no affectorId (rule-driven)
- Spell→GY after resolve: affectorId = seatId

### AbilityInstanceCreated / Deleted Pairing
Created: `affectorId=sourcePermanentIid, affectedIds=[abilityInstanceIid]`. Deleted: same shape. The ability's grpId on stack is the **ability grpId**, not the card's grpId.

### Persistent Annotation Lifecycle
Transient pattern: annotation appears in both `annotations` and `diffDeletedPersistentAnnotationIds` in the same GSM. LayeredEffect slots pre-announced at ETB via `LayeredEffectCreated`, activated later via persistent `[ModifiedPower, LayeredEffect]` annotations.

### Revealed Card Protocol
RevealedCard proxy objects (type 8) appear alongside real cards. Proxy goes in Revealed zone (18/19). Cleanup: proxy instanceIds in `diffDeletedInstanceIds` on next diff. `InstanceRevealedToOpponent` (type 75) = persistent annotation marking hand cards as visible to opponent.
