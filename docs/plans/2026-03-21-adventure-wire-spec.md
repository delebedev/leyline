# Adventure Wire Spec

**Date:** 2026-03-21
**Recording:** `recordings/2026-03-21_21-22-14/`
**Card:** Threadbind Clique (grpId 86968) / Rip the Seams (grpId 86969)
**Catalog status:** `cast-adventure: missing`

---

## Recording coverage gap

Threadbind Clique (grpId 86968) is **not present** in this recording.
grpId 86969 does appear (twice, lines 26 and 300) but as an `Ability` typed object on the stack — it is the ETB triggered ability from Novice Inspector (88949), not Rip the Seams. The card lookup collision is a red herring in the notes.

No `CastAdventure` action type appears anywhere in the recording.
The exile zone (zoneId 29) receives zero objects across the entire session.
The creature half was never cast from exile — the full adventure lifecycle is unobserved.

Action types present in this recording: `Activate`, `ActivateMana`, `Cast`, `FloatMana`, `Pass`, `Play`.

**This recording cannot be used as a ground-truth trace for adventure conformance.**
A new recording with an adventure deck is needed.

---

## Protocol spec (inferred from rosetta + Forge source)

Despite the recording gap the protocol shape is deterministic from:
- `docs/rosetta.md` — ActionType 16 (`CastAdventure`), ObjectType 10 (`Adventure`)
- Forge `SpellAbility.isAdventure()` — `getCardStateName() == CardStateName.Secondary && subtype("Adventure")`
- Forge `Card.isOnAdventure()` — card is exiled with its `exiledWith` pointer pointing back to itself in the Secondary state
- Forge `CardFactoryUtil.setupAdventureAbility()` — replacement effect that exiles the adventure spell then creates a `MayPlay` static ability on the exiled card

### Phase 1 — Cast adventure half (hand → stack)

Client sends `PerformActionResp` with `actionType = CastAdventure`, `instanceId` = card instanceId.

**Expected action in ActionsAvailableReq:**
```
Action {
  actionType: CastAdventure   // 16
  instanceId: <card iid>
  grpId: <adventure grpId, e.g. 86969>
  facetId: <card iid>
  shouldStop: true
  manaCost: [...]              // adventure spell cost, not creature cost
  abilityGrpId: <secondary SA grpId>
}
```

The action grpId must be the **adventure face grpId** (86969), not the creature face (86968). The `facetId` matches the instanceId.

**Outbound GSM diff (hand → stack):**
- Zone update: hand loses instanceId, stack gains new instanceId (realloc on cast)
- Object entry: `GameObjectInfo { type: Adventure(10), grpId: 86969, zoneId: STACK, ... }`
- Annotations (mirroring `CastSpell` category — see `AnnotationPipeline.annotationsForTransfer`):
  - `ObjectIdChanged { orig_id: <hand iid>, new_id: <stack iid> }`
  - `ZoneTransfer { zone_src: HAND, zone_dest: STACK, category: "CastAdventure" }` — **new category label needed**
  - Mana payment block (same as CastSpell — ObjectIdChanged, TappedUntappedPermanent, ManaPaid per land)
  - `UserActionTaken { actionType: 1 }`

**Open question:** Does the real server use `category: "CastAdventure"` or reuse `"CastSpell"`? Needs a live recording to confirm the exact `category` string in the ZoneTransfer detail. The existing `TransferCategory.CastSpell` label is `"CastSpell"`.

### Phase 2 — Adventure spell resolves (stack → exile)

When the adventure spell resolves, the card does **not** go to graveyard — Forge's replacement effect intercepts and routes to exile.

**Expected GSM diff:**
- Stack loses the adventure instanceId
- Exile zone (zoneId 29) gains a new instanceId (realloc again — ObjectIdChanged)
- Object entry: `GameObjectInfo { type: Adventure(10), grpId: 86969, zoneId: EXILE, ... }` — still typed as Adventure, NOT reverting to Card type
- Annotations:
  - `ResolutionStart { affectorId: <stack iid>, grpid: 86969 }`
  - `ResolutionComplete { affectorId: <stack iid>, grpid: 86969 }`
  - `ZoneTransfer { zone_src: STACK, zone_dest: EXILE, category: "Resolve" }` — this is just Resolve, same as normal resolution
  - Destroy/exile effect on target (if any) — separate ZoneTransfer with `category: "Exile"`
  - `DisplayCardUnderCard { affectedIds: [<exile iid>], Disable: 0, TemporaryZoneTransfer: 1 }` — annotation 38, currently MISSING in rosetta and AnnotationBuilder. This annotation tells the client to visually display the exiled card "under" the original card in exile UI.
  - `ObjectIdChanged { orig_id: <stack iid>, new_id: <exile iid> }` — realloc on exile landing

### Phase 3 — Creature half offered from exile

Once exiled via adventure, the `MayPlay` static ability on the exiled card becomes active. The client should be offered a `Cast` action (NOT `CastAdventure`) for the creature face.

**Expected action in ActionsAvailableReq:**
```
Action {
  actionType: Cast            // standard Cast (not CastAdventure)
  instanceId: <exile iid>
  grpId: 86968                // creature grpId (Threadbind Clique)
  facetId: <exile iid>
  shouldStop: true
  manaCost: [...]             // creature mana cost
}
```

The object in exile is still typed as `Adventure(10)` — the grpId switch (86969 → 86968) happens in the action, not the object type.

**Open question:** Does the action's `grpId` field carry the creature grpId (86968) or the adventure grpId (86969)? The object in exile has grpId 86969. Need recording to confirm.

### Phase 4 — Creature cast from exile (exile → stack)

Client sends `PerformActionResp` with `actionType = Cast`, `instanceId` = exile instanceId.

MatchSession currently maps `ActionType.Cast` → `PlayerAction.CastSpell(forgeCardId)`. Forge's `isOnAdventure()` check determines that `getSpells()` on this card returns the creature SA (primary face), not the adventure SA. So the engine-side routing is correct if the card lookup resolves to the Forge card in exile.

**Expected GSM diff (exile → stack):**
- Exile loses instanceId; stack gains new instanceId
- Object: `GameObjectInfo { type: Card(0), grpId: 86968, zoneId: STACK, ... }` — type reverts to Card, grpId becomes creature grpId
- `ObjectIdChanged { orig_id: <exile iid>, new_id: <stack iid> }`
- `ZoneTransfer { zone_src: EXILE, zone_dest: STACK, category: "CastSpell" }`
- Mana payment block as normal

**Inference:** `inferCategory` in `AnnotationPipeline` has no special case for `EXILE → STACK`. It would fall through to `else -> TransferCategory.ZoneTransfer`. This is wrong — it should emit `CastSpell`. Needs a new branch.

### Phase 5 — Creature resolves to battlefield (stack → battlefield)

Standard `Resolve` path — no adventure-specific behavior here.

---

## Code gaps identified

### 1. `ActionMapper` — no `CastAdventure` action generation

`buildActions()` iterates hand cards and emits `ActionType.Cast` for all non-lands. It never calls `card.isAdventureCard()` or iterates the Secondary state SpellAbilities. Adventure cards in hand must emit **two** actions: one `Cast` (creature face) and one `CastAdventure` (adventure face), each with correct grpId and manaCost.

File: `matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt`

Forge API:
- `card.isAdventureCard()` — true if has Secondary state with subtype Adventure
- `card.getState(CardStateName.Secondary).spellAbilities` — adventure SpellAbility
- `sa.isAdventure()` — true for the secondary SA

### 2. `MatchSession` — no `CastAdventure` handler

`onPerformAction` `when` block has no arm for `ActionType.CastAdventure`. It falls to `else → PassPriority`.

Fix: add a `CastAdventure` arm that resolves the Forge card and passes the adventure-face SpellAbility index to `PlayerAction.CastSpell`. Forge's `getAllCastableAbilities()` returns both faces; the adventure SA is the one where `sa.isAdventure() == true`. The `abilityId` field in `PlayerAction.CastSpell` selects it.

File: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt`

### 3. `AnnotationPipeline.inferCategory` — exile → stack not handled

Current: `srcZone == ZONE_EXILE → when { Hand/Battlefield → Return, else → ZoneTransfer }`.
Stack is not in the `else` branch explicitly. Exile → stack should map to `CastSpell`, not `ZoneTransfer`.

File: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt` (~line 604)

Fix:
```kotlin
srcZone == ZONE_EXILE -> when (destZone) {
    ZONE_P1_HAND, ZONE_P2_HAND, ZONE_BATTLEFIELD -> TransferCategory.Return
    ZONE_STACK -> TransferCategory.CastSpell   // cast from exile (adventure, flashback, etc.)
    else -> TransferCategory.ZoneTransfer
}
```

### 4. `AnnotationPipeline.annotationsForTransfer` — needs `CastAdventure` category

If a distinct `TransferCategory.CastAdventure` is introduced (to produce the correct `category` label), a new arm is needed in `annotationsForTransfer`. If the label string is just `"CastSpell"` (same as regular cast), no new category is needed — `CastAdventure` can reuse `CastSpell` category.

**Defer until recording confirms the label string.**

### 5. `AnnotationBuilder.displayCardUnderCard` — exists but not wired

`displayCardUnderCard()` is defined at line 701 in `AnnotationBuilder.kt` but:
- Not referenced anywhere in the annotation pipeline
- `GameEvent` has no corresponding event type
- `GameEventCollector` doesn't subscribe to any Forge event that would fire it
- Rosetta marks it `MISSING`

Forge does not fire a native event for adventure exile — it's implemented via replacement effect, so `GameEventCardChangeZone` will fire with dest=Exile. We need to distinguish "exiled as adventure" from regular exile.

Detection: in `GameEventCollector`, when `GameEventCardChangeZone` fires with dest=Exile, call `card.isOnAdventure()` — if true, emit a new `GameEvent.AdventureExiled` variant. Then in the annotation pipeline, generate `DisplayCardUnderCard` from that event.

This is a tier-2 annotation (visual polish). The adventure will function without it but the exile UI may not display the card thumbnail correctly.

### 6. `ObjectMapper` — adventure object type (Adventure = 10) not emitted

`ObjectMapper.makeCard()` always sets `type = GameObjectType.Card`. When a card is on the stack or in exile as an adventure spell, the object type must be `Adventure(10)` to match the real server.

File: `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt`

Forge detection: `card.isAdventureCard() && (card.zoneOfCard == Exile || card.zoneOfCard == Stack) && sa.isAdventure()` — needs cross-referencing with the SA that put it there. Alternatively: check if the card's current state name is `CardStateName.Secondary`.

---

## Minimal implementation order

1. `ObjectMapper` — emit `type: Adventure` for adventure cards on stack/exile in secondary state
2. `ActionMapper` — add `CastAdventure` action for adventure-capable hand cards
3. `MatchSession` — handle `CastAdventure` → `PlayerAction.CastSpell(forgeCardId, abilityId=adventureIndex)`
4. `AnnotationPipeline.inferCategory` — exile → stack maps to `CastSpell`
5. `AnnotationBuilder/GameEventCollector` — `DisplayCardUnderCard` (can defer; not blocking gameplay)

Items 2-4 are blocking for the mechanic to function. Item 1 and 5 are conformance polish.

---

## Recording needed

This spec requires validation against a live recording of:
- Adventure spell cast from hand → exile
- Creature cast from exile → battlefield

Deck suggestion: any deck with Bonecrusher Giant / Stomp (Throne of Eldraine), or Brazen Borrower / Petty Theft — well-tested adventure cards. Record with `just serve-proxy` and inspect `md-frames.jsonl` for:
- The `CastAdventure` action in ActionsAvailableReq
- The ZoneTransfer category string for hand → stack (adventure cast)
- The ObjectType value for adventure objects on stack and in exile
- Whether `DisplayCardUnderCard` annotation appears and with what details
