# Concealing Curtains / Revealing Eye — Card Spec

## Identity

- **Name:** Concealing Curtains // Revealing Eye
- **grpId:** 78895 (front), 78896 (back)
- **Set:** VOW
- **Type:** Creature — Wall (front); Creature — Eye Horror (back)
- **Cost:** {B}
- **Activated ability grpId:** 146662 ({2}{B}: Transform)
- **Back-face trigger grpId:** 146663 (on-transform reveal/discard/draw)
- **Forge script:** `forge/forge-gui/res/cardsfolder/c/concealing_curtains_revealing_eye.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Defender | `K:Defender` | `GameEventCardStatsChanged` | wired |
| Activated transform (sorcery) | `A:AB$ SetState \| Mode$ Transform \| SorcerySpeed$ True` | `GameEventCardStatsChanged(transform=true)` | **missing** (issue #191) |
| DFC in-place grpId mutation | `AlternateMode:DoubleFaced` | `GameEventCardStatsChanged` | **missing** (issue #191) |
| Menace (back face) | `K:Menace` | `GameEventCardStatsChanged` | wired (engine), **missing** Qualification pAnn |
| On-transform trigger | `T:Mode$ Transformed` | **none** — Forge fires via SetStateEffect resolution | **missing** |
| Reveal opponent hand | `DB$ RevealHand \| ValidTgts$ Opponent` | **none** | partial (RevealedCardCreated fires, but CardRevealed/InstanceRevealedToOpponent missing) |
| Choose nonland from revealed | `DB$ ChooseCard \| Choices$ Card.nonLand+IsRemembered` | **none** | **missing** (SelectNReq shape observed) |
| Discard chosen card | `DB$ Discard \| Mode$ Defined` | `GameEventCardChangeZone` | wired |
| Draw a card (conditional) | `DB$ Draw \| NumCards$ 1` | `GameEventCardChangeZone` | wired |

## What it does

1. **Front face (Concealing Curtains):** 0/4 Wall with Defender. Cannot attack.
2. **Activated ability:** Pay {2}{B} at sorcery speed to transform into Revealing Eye. The permanent stays on the battlefield — same instanceId, new grpId.
3. **Back face (Revealing Eye):** 3/4 Eye Horror with Menace (must be blocked by 2+ creatures).
4. **Transform trigger:** When this creature transforms into Revealing Eye, target opponent reveals their hand. Controller may choose a nonland card from the revealed cards. If they do, that player discards it, then draws a card.

## Trace (session 2026-03-22_23-02-04, seat 1)

Seat 1 (human) cast three copies of Concealing Curtains. All three transformed into Revealing Eye via the {2}{B} activated ability. Revealing Eye was the primary damage source (17 damage across 4 attacks).

### Copy 1 — instanceId 287

#### Cast (gsId 47–49, T3 Main1)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 47 | 161→287 | Hand→Stack | ObjectIdChanged(161→287) + ZoneTransfer(31→27, CastSpell) |
| 49 | 287 | Stack→Battlefield | ZoneTransfer(27→28, Resolve) + ResolutionComplete |

#### Transform (gsId 156–158, T7 Main1)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 156 | 317 (ability) | Stack | Ability 146662 on stack; mana payment (3 Swamps tapped) |
| 158 | 287 | Battlefield | **grpId silently mutates 78895→78896 in Diff object.** No ZoneTransfer, no ObjectIdChanged. Ability 321 (trigger grpId 146663) pushed to stack. |

Key: the transform is a **pure grpId mutation** on the existing gameObject in the Diff. The instanceId (287) stays the same. The gameObject in gsId=158 arrives with `grpId: 78896`, `subtypes: [Eye, Horror]`, `power: 3`, `toughness: 4`, `othersideGrpId: 78895`.

#### Revealing Eye trigger resolution (gsId 158–163)

| gsId | Message | What happened |
|------|---------|---------------|
| 158 | SelectTargetsReq | Target opponent (seatId=2). Single target, min/max=1. |
| 162 | GSM + SelectNReq | Opponent's hand revealed: zone 19 (Revealed) populated with RevealedCard proxy objects (iids 322–325). Actual hand cards shown in zone 35 (Hand, ownerSeat=2, visibility=Public, viewers=2). SelectNReq: choose 1 nonland from `ids: [221]`, `unfilteredIds: [221, 225, 283, 295]` (3 lands filtered out). sourceId=287. |
| 163 | GSM + ActionsAvail | Resolution: ObjectIdChanged(221→326) + ZoneTransfer(35→37, Discard). Then ObjectIdChanged(229→327) + ZoneTransfer(36→35, Draw). RevealedCardDeleted(322). ResolutionComplete(146663). AbilityInstanceDeleted. |

### Copy 2 — instanceId 365 (gsId 314→317, transform gsId ~317)

Same pattern. Cast T10, transformed T10 Main1. Back-face trigger fired.

### Copy 3 — instanceId 391 (gsId 407→410, transform gsId ~410)

Same pattern. Cast T12, transformed T12 Main1. Back-face trigger fired.

All three transforms follow the identical wire shape — pure grpId mutation, no ZoneTransfer.

### Annotations

**Transform (gsId 158) — the novel frame:**

Annotations in gsId=158 Diff:
- `ResolutionStart` (id=363) — affectorId=317, grpid=146662 (activated ability resolving)
- `AbilityInstanceCreated` (id=365) — affectorId=287, affectedIds=[321], source_zone=28 (back-face trigger pushed to stack)
- `ResolutionComplete` (id=367) — affectorId=317, grpid=146662
- `AbilityInstanceDeleted` (id=368) — affectorId=287, affectedIds=[317] (activated ability cleaned up)
- `PlayerSelectingTargets` (id=369) — affectorId=1, affectedIds=[321] (targeting for reveal trigger)

Persistent annotations in gsId=158:
- `Qualification` (id=364) — affectorId=287, affectedIds=[287], grpid=142, QualificationType=40, QualificationSubtype=0, SourceParent=287. This is the **Menace keyword grant** on the back face.
- `TriggeringObject` (id=366) — affectorId=321, affectedIds=[287], source_zone=28. Links the trigger to its source.

**Reveal + Discard + Draw (gsId 162–163):**

gsId=162:
- `ResolutionStart` — grpid=146663 (back-face trigger)
- 4x `RevealedCardCreated` (ids 322–325) — one per hand card. Zone 19 (Revealed) populated.
- Opponent's hand (zone 35) flips to `visibility: Public, viewers: 2`.

gsId=163:
- `ObjectIdChanged` (221→326) + `ZoneTransfer` (zone_src=35, zone_dest=37, category=**Discard**) — chosen nonland discarded from opponent's hand to graveyard
- `RevealedCardDeleted` (id=383, iid=322) — only one RevealedCard deleted (the discarded one); the other 3 revealed proxies presumably cleaned up elsewhere
- `ObjectIdChanged` (229→327) + `ZoneTransfer` (zone_src=36, zone_dest=35, category=**Draw**) — opponent draws replacement card
- `ResolutionComplete` (grpid=146663) + `AbilityInstanceDeleted`
- `EnteredZoneThisTurn` persistent annotations for the drawn and discarded cards

### Key findings

- **DFC in-place transform is silent.** No annotation marks the grpId change itself. The Diff simply carries the updated gameObject. Client infers the transform from `othersideGrpId` matching the previous grpId. This is fundamentally different from saga transforms (ZoneTransfer pair with Exile/Return categories).
- **Qualification pAnn for Menace (grpId=142, QualificationType=40)** appears immediately on transform. This is how the client knows the back face has Menace — it's a persistent annotation, not inferred from card data.
- **RevealedCard proxy objects** (type=RevealedCard) live in zone 19 (Revealed) and mirror the real cards in the opponent's hand (zone 35). The hand zone itself becomes `visibility: Public, viewers: 2` during reveal.
- **SelectNReq for "choose nonland"** uses `ids` (filtered list, nonland only) vs `unfilteredIds` (all hand cards). `sourceId` points to the Revealing Eye (287), not the trigger ability.
- **Only one RevealedCardDeleted fires** in gsId=163 (for the discarded card's proxy). The remaining 3 proxies are presumably cleaned up in a subsequent gsId (not traced — low priority).
- **Opponent draw uses category "Draw"**, not a special replacement-draw category. Standard draw wire shape.

## Gaps for leyline

### Phase 1 — DFC activated transform (issue #191)

Scope: transform mechanic only. The on-transform trigger (reveal/choose/discard/draw) is autopassed — Forge resolves it internally, leyline emits no interactive wire for it.

1. **ObjectMapper: grpId mutation on DFC transform** — when Forge fires `GameEventCardStatsChanged(transform=true)`, the existing gameObject's grpId, overlayGrpId, subtypes, P/T, name, and uniqueAbilities must all update in-place in the Diff. No ZoneTransfer.
2. **Qualification persistent annotation (type 42)** — not wired at all. Must emit on transform with keyword data (grpId=142 for Menace, QualificationType=40). Also needed for other keyword-granting effects.
3. **TriggeringObject persistent annotation** — links trigger ability to its source. Format: affectorId=trigger-abilityId, affectedIds=[source-instanceId], source_zone.
4. **Update `docs/catalog.yaml`** — add `dfc-activated-transform` entry documenting the in-place grpId mutation wire shape, distinct from saga DFC.

### Phase 2 — Reveal opponent hand infrastructure (issue #256)

Scope: full interactive reveal→choose→discard→draw wire. Blocked on general reveal infrastructure that also benefits Thoughtseize, Duress, Agonizing Remorse, etc.

5. **On-transform trigger wiring** — back-face ETB-like trigger (grpId 146663) must fire when the SetState effect resolves. Forge chains this via `T:Mode$ Transformed` in the script. Need to listen for `GameEventCardStatsChanged(transform=true)` and push the back-face trigger ability to stack.
6. **RevealedCard proxy synthesis** — zone 19 (Revealed) populated with RevealedCard-type proxy objects. `RevealedCardCreated` annotation fires per card. Partial support exists (`docs/catalog.yaml` notes RevealedCardCreated fires via `drainReveals()`), but RevealedCard objects themselves aren't synthesized.
7. **Hand visibility flip** — opponent's hand zone must temporarily become `visibility: Public, viewers: 2` during reveal. Not implemented.
8. **SelectNReq for "choose from revealed"** — needs `ids` (filtered by nonland) vs `unfilteredIds` (all revealed), `sourceId` = transform source, `idType: InstanceId`. Shape matches existing SelectNReq infrastructure but filter logic is new.
9. **RevealedCard cleanup** — `RevealedCardDeleted` builder exists but never called. Real server uses `diffDeletedInstanceIds` for proxy cleanup after resolution.

## Supporting evidence needed

- [ ] Cross-reference with Brass's Tunnel-Grinder spec (`docs/card-specs/brasss-tunnel-grinder.md`) — that's a saga DFC. Compare wire shapes to confirm the two DFC paths are truly distinct.
- [ ] Check Kellan, Daring Traveler / Journey On from session 2026-03-22_13-39-53 — saga DFC transform with ZoneTransfer pair (documented in issue #191).
- [ ] Puzzle: write a Concealing Curtains transform puzzle to verify the grpId mutation Diff shape in isolation.
- [ ] Other activated-transform DFCs (e.g. Delver of Secrets, Brutal Cathar) to confirm the silent grpId mutation is universal for non-saga DFCs.
