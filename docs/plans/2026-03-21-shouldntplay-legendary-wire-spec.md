# ShouldntPlay — Legendary Reason Wire Spec

Session: `recordings/2026-03-21_22-05-00`
Date: 2026-03-21

---

## Summary

`ShouldntPlay` with `Reason=Legendary` fires when a player holds a second copy of a legendary permanent they already control on the battlefield. The annotation is sent **only** to the **opponent's seat** (not the hand-owner's seat). This is the opposite of the `EntersTapped` case, where the annotation goes to the hand-owner.

4 instances in this session. All share: `affectorId=351` (Leonardo, Cutting Edge, grpId=100472), `affectedIds=[164]` (private card in seat-1's hand, masked grpId from seat-2's view).

---

## Card Context

| Role | iid | grpId | Card | Zone |
|------|-----|-------|------|------|
| Affector (Legendary on BF) | 351 | 100472 | Leonardo, Cutting Edge (Legendary Creature — Mutant Ninja Turtle) | 28 (Battlefield) |
| Affected (second copy, private) | 164 | masked (93784 per seat-1 stream, but see note) | Unknown to seat-2 | 31 (Hand, owner=1) |

**Note on affectedId=164 grpId:** From seat-1's own opening hand declaration (gsId=2, systemSeatIds=[1]), iid=164 is declared as grpId=93784 (Stab, Instant). However, the ShouldntPlay annotation is sent only in seat-2's stream, where iid=164 is a **private/opaque** card — seat-2 has no grpId for it. The Reason=Legendary implies the server knows iid=164 is a second Leonardo. There are two possible interpretations:

1. **grpId 93784 = second Leonardo (alt art or our DB is wrong).** The card DB returns "Stab" for 93784, but Arena has multiple printings. Our DB may lack an entry mapping 93784 to `Leonardo, Cutting Edge`. The cardTypes=`Instant` in the declaration could also be a red herring from the dual-stream recording structure.
2. **Dual-stream instanceId aliasing.** Private cards in the opening hand use different instanceIds per seat (placeholder IDs for the opponent's stream vs real IDs in the owner's stream). The recording interleaves both streams. The seat-1 "iid=164=Stab" declaration and the seat-2 "iid=164=second Leonardo" may be two different cards aliased onto the same ID in the recording JSON. Confirmed evidence: at gsId=2, seat-1 sees hand=[168,167,166,165,164,163,162] while seat-2 sees zone31=[165,164,163,162,161,160,159] — overlapping but not identical sets, consistent with placeholder aliasing.

For wiring purposes, treat iid=164 as "the second copy of Leonardo held by the hand-owner". The ShouldntPlay annotation itself is the ground truth: the server fires it because a second legendary matches 351.

---

## All 4 Instances — Full JSON

### Instance 1 — gsId=82, Turn 4 Main1

Context: Leonardo (351) just resolved its ETB triggered ability chain (two abilities stacked). Stack is now empty. Player 1 has priority in Main1 for the first time after the stack cleared.

```json
{"id": 292, "types": ["ShouldntPlay"], "affectorId": 351, "affectedIds": [164], "details": {"Reason": "Legendary"}}
```

Surrounding annotations in same GSM:
- ResolutionStart(grpid=92970) — second ability resolves
- CounterAdded(affectedIds=[351], counter_type=1, transaction_amount=1) — +1/+1 counter placed on Leonardo
- PowerToughnessModCreated(power=1, toughness=1)
- LayeredEffectCreated
- ResolutionComplete(grpid=92970)
- AbilityInstanceDeleted(affectedIds=[355])
- **ShouldntPlay** (fires in same GSM as stack clearing)

`diffDeletedPersistentAnnotationIds: [284, 292]` — the ShouldntPlay annotation id (292) itself is in the delete list, confirming transient behavior (added and removed in same diff).

---

### Instance 2 — gsId=91, Turn 4 Main2

Context: Phase transition from Combat → Main2. Player 1 gets priority at start of Main2.

```json
{"id": 297, "types": ["ShouldntPlay"], "affectorId": 351, "affectedIds": [164], "details": {"Reason": "Legendary"}}
```

Co-occurring annotation: `PhaseOrStepModified(phase=4, step=0)` — Main2 transition.

`diffDeletedPersistentAnnotationIds: [297]` — same transient pattern.

Preceding record: `Uimessage` (no GSM). No ShouldntPlay during any Combat-step priority points between gsId=82 and gsId=91.

---

### Instance 3 — gsId=116, Turn 6 Main1

Context: Phase transition → Main1 for player 1's turn 6. Game had an opponent's turn 5 with no SP firing. First Main1 priority of turn 6.

```json
{"id": 327, "types": ["ShouldntPlay"], "affectorId": 351, "affectedIds": [164], "details": {"Reason": "Legendary"}}
```

Co-occurring annotation: `PhaseOrStepModified(phase=2, step=0)` — Main1 transition.

`diffDeletedPersistentAnnotationIds: [327]`.

---

### Instance 4 — gsId=117, Turn 6 (during land play)

Context: Player 1 plays a Plains (iid=358, played from hand as iid=357, ObjectIdChanged 357→358). This is a state-change GSM mid-main phase, not a phase-transition point. `turnInfo` is absent (the GSM has no turnInfo field in this diff).

```json
{"id": 331, "types": ["ShouldntPlay"], "affectorId": 351, "affectedIds": [164], "details": {"Reason": "Legendary"}}
```

Co-occurring annotations:
- ObjectIdChanged(357→358)
- ZoneTransfer(zone_src=31, zone_dest=28, category=PlayLand) — land enters BF
- UserActionTaken(affectorId=1, actionType=3, abilityGrpId=0) — player played land

`diffDeletedPersistentAnnotationIds: [331, 9]`.

Zone 31 at this point still contains 164 — Stab/second-Leo is still in hand after the land play.

---

## 3-Message Windows

### Window 1 (gsId=82)

| # | gsId | File | Phase/Step | Key Content |
|---|------|------|-----------|-------------|
| prev | 81 | 000000152 | Main1 / prio=2 | (empty diff, opponent passes priority) |
| **SP** | **82** | **000000152** | **Main1 / prio=1** | **ShouldntPlay + ability stack resolves** |
| next | 83 | 000000152 | Main1 / prio=2 | (empty diff) |

### Window 2 (gsId=91)

| # | gsId | File | Phase/Step | Key Content |
|---|------|------|-----------|-------------|
| prev | — | — | Uimessage | (no GSM) |
| **SP** | **91** | **000000161** | **Main2 / prio=1** | **ShouldntPlay + PhaseOrStepModified** |
| next | 92 | 000000161 | Main2 / prio=2 | (empty diff, opponent passes) |

### Window 3 (gsId=116)

| # | gsId | File | Phase/Step | Key Content |
|---|------|------|-----------|-------------|
| prev | — | — | Uimessage | (no GSM) |
| **SP** | **116** | **000000191** | **Main1 / prio=1** | **ShouldntPlay + PhaseOrStepModified** |
| next | 117 | 000000196 | (no turnInfo) | ShouldntPlay again + land play |

### Window 4 (gsId=117)

| # | gsId | File | Phase/Step | Key Content |
|---|------|------|-----------|-------------|
| prev | 97 | 000000210 | seat-1 stream (no SP) | — |
| **SP** | **117** | **000000196** | **(no turnInfo)** | **ShouldntPlay + Plains played to BF** |
| next | 98 | 000000210 | seat-1 stream | — |

---

## Leonardo Lifecycle

| Event | gsId | File | Zone | Detail |
|-------|------|------|------|--------|
| Game start | 1 | — | 32 (Library) | iid=161 (pre-rename placeholder in library) |
| Opening hand drawn | 2 | — | 31 (Hand) | iid=161 in hand (private to seat-1) |
| Cast to Stack | 76 | 000000152 | 27 (Stack) | ObjectIdChanged 161→351; ZoneTransfer zone_src=31, zone_dest=27, category=CastSpell |
| Resolves to Battlefield | 78 | 000000152 | 28 (BF) | ZoneTransfer zone_src=27, zone_dest=28, category=Resolve; hasSummoningSickness=true; AbilityInstanceCreated iid=354 |
| First triggered ability (iid=354) resolves | 80 | 000000152 | — | ResolutionStart/Complete grpId=122104; ModifiedLife+2 for player 1; AbilityInstanceCreated iid=355 |
| Second triggered ability (iid=355) resolves | 82 | 000000152 | — | ResolutionStart/Complete grpId=92970; CounterAdded(+1/+1 on 351); **ShouldntPlay fires here** |
| Loses summoning sickness | 112 | 000000191 | 28 (BF) | hasSummoningSickness no longer set |
| End of game | 117+ | — | 28 (BF) | Still on battlefield at game end (opponent conceded) |

Leonardo was cast from hand on **turn 4 Main1** (the 000000152 stream shows Main1 throughout; the 000000142 stream shows Combat at the same gsIds due to dual-stream recording — the 000000152 stream is authoritative for seat-2's Main1 perspective).

---

## Second Copy Lifecycle (iid=164)

| Event | gsId | Zone | Detail |
|-------|------|------|--------|
| Opening library | 1 | 32 (Library) | iid=164 in library |
| Opening hand deal | 2 | 31 (Hand, owner=1) | iid=164 drawn to hand; grpId=93784 declared in seat-1 stream (see identity note above) |
| Stays in hand | 2–117 | 31 (Hand) | Present in every zone snapshot through gsId=117 |
| First ShouldntPlay | 82 | 31 (Hand) | After Leonardo resolves ETB chain |
| Last ShouldntPlay | 117 | 31 (Hand) | During opponent's Plains play (game about to end) |
| Game end | 117 | 31 (Hand) | Never played; opponent conceded |

**No ObjectIdChanged for iid=164** — it was never cast or played. The second copy stayed in hand the entire game.

---

## Firing Pattern Analysis

ShouldntPlay(Legendary) does **NOT** fire every priority point. Observed pattern:

| gsId | Phase | Active | Priority | SP fires? | Reason |
|------|-------|--------|----------|-----------|--------|
| 78 | Main1 | 1 | 1 | No | Stack not empty (ability 354 pending) |
| 80 | Main1 | 1 | 1 | No | Stack not empty (ability 355 pending) |
| 82 | Main1 | 1 | 1 | **Yes** | Stack just cleared after all ETB abilities |
| 83 | Main1 | 1 | 2 | No | Opponent has priority |
| 84–90 | Combat/Ending | — | — | No | Not main phase |
| 91 | Main2 | 1 | 1 | **Yes** | Phase transition to Main2 (first prio) |
| 92 | Main2 | 1 | 2 | No | Opponent has priority |
| 93–115 | Various | — | — | No | Not player-1-active main phase OR opponent's turn |
| 116 | Main1 | 1 | 1 | **Yes** | Turn 6 Main1 phase transition |
| 117 | — | — | — | **Yes** | During land play state change |
| 120 | Main1 | 1 | 1 | No | Game ended (concede at 122 — no more SP opportunity) |

**Key pattern:** fires when player 1 (hand-owner) **first gains priority in a main phase**. Specifically:
- After stack clears at the first open-priority point in Main1 (not mid-stack)
- At the Main2 phase transition (first priority)
- At each turn's Main1 phase entry (first priority of that phase)
- Also fires within a **state-change GSM** (land play) mid-main phase

Does **not** fire on every priority point like `EntersTapped` — fires only on **main-phase entries and stack-clear events**. This is because the legend-rule check is re-evaluated when the player's options change (phase start or stack clearing opens new cast opportunities).

---

## Comparison with EntersTapped

| Dimension | EntersTapped | Legendary |
|-----------|-------------|-----------|
| Sent to seat | Hand-owner's stream | Opponent's stream |
| Fires every priority? | Yes (all main-phase prios) | No (phase entry + stack clear) |
| Affected card in hand? | Yes (card with ETB tapped) | Yes (second legendary) |
| Affector | The card itself | The legendary already on BF |
| Transient? | Yes | Yes |

---

## Relevant diffDeletedPersistentAnnotationIds Pattern

All 4 instances include the ShouldntPlay annotation's own id in `diffDeletedPersistentAnnotationIds`:

```
gsId=82: diffDeletedPersistentAnnotationIds=[284, 292]   ← 292 is the ShouldntPlay
gsId=91: diffDeletedPersistentAnnotationIds=[297]         ← 297 is the ShouldntPlay
gsId=116: diffDeletedPersistentAnnotationIds=[327]        ← 327 is the ShouldntPlay
gsId=117: diffDeletedPersistentAnnotationIds=[331, 9]     ← 331 is the ShouldntPlay
```

The annotation id appears in both `annotations` (added) and `diffDeletedPersistentAnnotationIds` (removed) in the **same GSM**. This is the canonical "transient annotation" pattern — added and immediately expired in the same state snapshot. Identical to `EntersTapped`.

---

## Code Status

```
grep -r "shouldnt\|ShouldntPlay" matchdoor/  → no matches
```

- No builder exists for `ShouldntPlay`
- No `AnnotationType.ShouldntPlay` emission anywhere in the pipeline

---

## Wiring Assessment — Legendary Case

**Difficulty:** Medium (harder than EntersTapped)

### Trigger condition

At each main-phase priority point for the **active player**, scan all cards in the active player's hand. For each card, check:

1. Does the card share a name with a legendary permanent the active player controls on the battlefield?
2. If yes, emit `ShouldntPlay(Reason=Legendary)` for that card.

### Annotation target and direction

- `affectorId` = the **battlefield legendary** instanceId (iid=351, Leonardo on BF)
- `affectedIds` = [the hand card's instanceId] (iid=164, second copy)
- Sent to the **opponent's seat** (systemSeatIds=[2]), NOT the hand-owner's seat

This is the inverse of `EntersTapped`. The opponent receives the hint so their client can potentially display that the opponent "has a card they can't usefully play" (though in practice the opponent can't see the hand).

### Forge API

Forge's `Card.getName()` + `CardCollectionView.filterByName(name)` can identify same-name cards. `GameState.getCardsIn(ZoneType.Hand, player)` gives hand contents. `GameState.getCardsIn(ZoneType.Battlefield, player).filter(isLegendary)` gives battlefield legendaries.

```kotlin
// pseudocode
for legendary in battlefield.filter { it.superTypes.contains("Legendary") } {
    for handCard in hand.filter { it.name == legendary.name } {
        emit ShouldntPlay(
            affectorId = legendary.instanceId,
            affectedIds = listOf(handCard.instanceId),
            reason = "Legendary"
        )
    }
}
```

### Firing hook

Same GSM builder hook as `EntersTapped`:

1. Phase entry (Main1 / Main2) — fire when player gets first priority
2. Stack-clear events — fire when stack becomes empty and player regains priority in a main phase

Also fires within action-result GSMs (the land-play case, gsId=117). This suggests the check runs on **any GSM where the active player gets priority in a main phase**, regardless of what triggered the GSM.

### Seat routing

Route to `opponentSeatId` (the seat that is NOT the hand-owner). The hand-owner does not receive this annotation.

### Open questions

1. Does the opponent's client actually use this annotation visually? (Can they see hand contents?) Possibly used for "opponent has X cards" count with a dim indicator, or just unused noise on the opponent side.
2. Does `ShouldntPlay(Legendary)` also fire for the hand-owner's own seat in some other recording? Not observed here — only seat-2 (opponent) received it.
3. If player controls MULTIPLE legendaries and has second copies of multiple ones in hand, does it fire once per affected card? Presumably yes given the array structure of `affectedIds`.

---

## Priority for Implementation

Low. Advisory-only, cosmetic, opponent-side hint for a card they can't see. Does not affect gameplay. Implement after `EntersTapped` is wired.

Implement together with `EntersTapped` since they share the same hook point and builder pattern. The only differences are:
- Target seat: `EntersTapped` → hand-owner, `Legendary` → opponent
- Condition check: ETB tapped vs name-match legendary
- Firing frequency: every priority vs phase-entry/stack-clear

---

## Files to Touch

- `matchdoor/src/main/kotlin/…/game/AnnotationBuilder.kt` (or wherever annotations are built — no builder exists yet)
- Hook in `GameStateMessageBuilder` at the main-phase priority serialization point
- Proto field: `annotations[]` (transient, same as all other `ShouldntPlay` instances)
