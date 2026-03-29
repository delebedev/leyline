# Morbid AbilityWordActive — Design Spec

## Problem

Morbid cards (Cackling Prowler, Needletooth Pack, ~55 in Forge) don't display the morbid indicator in the Arena client. The `AbilityWordActive` pAnn with `AbilityWordName="Morbid"` is never emitted.

The trigger itself works — Forge evaluates `Count$Morbid` at end step and fires the +1/+1 counter ability correctly. What's missing is the **cosmetic annotation** that tells the client "morbid is active."

## Wire shape (from cackling-prowler.md, session `16-18-08`)

```
persistentAnnotations {
  types: AbilityWordActive
  affectorId: 1                    // player seat object
  affectedIds: [323]               // morbid permanent iids
  details {
    key: "AbilityWordName"
    type: String
    valueString: "Morbid"
  }
}
```

- **Boolean-only** — no `value`, `threshold`, or `AbilityGrpId` fields
- `affectorId = 1` (player seat), not the dying creature or the morbid permanent
- `affectedIds` = all morbid permanents on the battlefield (and the ability iid while on stack)
- Fires reactively — appears in the same diff as the creature death `ZoneTransfer(SBA_Damage)`
- Persists until end of turn (cleared when no longer relevant)

## Design

### Approach: extend AbilityWordScanner

`AbilityWordScanner.scan()` already runs every GSM build and checks battlefield permanents for ability word conditions. Morbid fits the same model — it's a boolean condition ("did a creature die this turn?") checked at scan time.

**Why snapshot is fine:** The pAnn appears "reactively" in the recording because the real server rebuilds state after each event. Our `StateMapper.buildFromGame()` does the same — it runs `AbilityWordScanner.scan()` after every diff. If a creature just died (new entries in `CardUtil.getThisTurnEntered()`), the next scan will see morbid = true and emit the pAnn. No special event-driven hook needed.

### Changes

**1. Add Morbid to AbilityWordScanner CONDITIONS map**

```kotlin
"Morbid" to ConditionSpec(
    // Boolean — no value/threshold. Just check if any creature died BF→GY this turn.
    value = { player ->
        val died = CardUtil.getThisTurnEntered(
            ZoneType.Graveyard, ZoneType.Battlefield, "Creature",
            null, null, player
        )
        if (died.isNotEmpty()) 1 else 0
    },
    threshold = 1,  // value >= 1 means active
),
```

Wait — the wire shows no `value` or `threshold` fields for Morbid. The scanner currently emits these for Threshold/Metalcraft. Need to handle boolean-only ability words differently.

**Option:** Add a `booleanOnly: Boolean = false` flag to `ConditionSpec`. When true, `abilityWordActive()` is called without value/threshold args. The scanner evaluates the lambda — if result > 0, emit the pAnn; otherwise don't.

**2. Fix affectorId**

Currently the scanner uses the permanent's instanceId as affectorId. Morbid wire shows `affectorId = 1` (player seat). Add a `affectorId` override to `ConditionSpec`:
- Default: permanent's instanceId (existing behavior for Threshold etc.)
- Morbid: player seatId (value `1` for seat 1)

**3. Fix affectedIds**

Wire shows `affectedIds` contains ALL morbid permanents on the battlefield, not just the one being scanned. The scanner currently emits one pAnn per permanent. For Morbid, emit one pAnn per player with all morbid permanent iids collected.

This is a structural difference from Threshold (per-permanent) — Morbid is per-player.

### Revised approach

Two emission modes in AbilityWordScanner:

**Per-permanent mode** (existing — Threshold, Metalcraft, Delirium):
- One pAnn per permanent with the condition
- affectorId = permanent iid
- affectedIds = [permanent iid]
- value/threshold in details

**Per-player mode** (new — Morbid, Raid):
- One pAnn per player (seat)
- affectorId = seatId
- affectedIds = all permanents with this ability word on that player's battlefield
- Boolean-only (no value/threshold)
- Only emitted when condition is true

```kotlin
data class ConditionSpec(
    val threshold: Int? = null,
    val value: ((Player) -> Int)? = null,
    val perPlayer: Boolean = false,      // NEW: emit one pAnn per player, not per permanent
    val booleanOnly: Boolean = false,    // NEW: omit value/threshold from details
)
```

### Files touched

| File | Change |
|------|--------|
| `game/AbilityWordScanner.kt` | Add Morbid to CONDITIONS, per-player emission mode |
| `game/AnnotationBuilder.kt` | No change needed (already supports boolean-only) |
| Puzzle file | `morbid-cackling-prowler.pzl` |

### Test plan

**Puzzle:** Cackling Prowler on BF, opponent creature, combat where opponent creature dies.
- Verify `AbilityWordActive` pAnn with `Morbid` appears in the combat damage diff
- Verify end-step trigger fires and puts +1/+1 counter
- Verify pAnn clears next turn if no creature dies

**Harness test:**
- Kill a creature → assert AbilityWordActive Morbid pAnn present
- Assert affectorId = seatId, affectedIds contains all morbid permanents
- Next turn, no deaths → assert pAnn absent

### Scope

Just the pAnn emission. The morbid trigger itself (end-step +1/+1 counter) already works via Forge. This is cosmetic — makes the client show the morbid indicator badge.

### Leverage

55 cards use Morbid in Forge. Per-player boolean mode also applies to Raid (same wire shape per cackling-prowler spec). Adding `perPlayer: true, booleanOnly: true` covers both.
