# scry sequences addons

Five additions to `scry sequences`. Each is independent — implement in any order.

---

## 1. `--diff` — side-by-side source comparison

Compare leyline vs real server output in one command.

```bash
scry sequences --diff leyline real
scry sequences --diff leyline real --type COMBAT_DAMAGE
```

Output: one table per interaction type showing both sources side by side.

```
COMBAT_DAMAGE
                    real (125 instances)              leyline (21 instances)
  slots             2                                 1
  [1] role          COMBAT_DAMAGE                     COMBAT_DAMAGE
      updateType    SendHiFi                          SendHiFi
      annotations   [DD POS? SE? ML?]                 [DD EnteredZoneThisTurn ColorProduction POS?]
      order         POS → DD → SE → ML (36%)         POS → DD → SE → ML (14%)
  [2] role          ECHO                              —
      updateType    SendHiFi                          —

  Gaps:
    - missing bare echo
    - extra always-present: EnteredZoneThisTurn, ColorProduction
    - annotation order consistency: 14% vs 36%
```

**Gaps section:** auto-detected differences:
- Slot count mismatch (missing/extra GSMs)
- Annotation always-set differences (extra or missing always-present types)
- updateType mismatches
- Order consistency delta > 20%

**Implementation:** Run the existing aggregation pipeline twice with different source filters, then zip slots by index and diff. The `Gaps` section is mechanical: compare slot counts, iterate matched slots comparing `updateType`, `annotations.always`, and `orderConsistency`.

**JSON `--json --diff`:** Same structure but with `left`/`right`/`gaps` per interaction type.

---

## 2. `--field-presence` — GSM field presence per slot

Which top-level GSM fields are populated in each slot.

```bash
scry sequences --field-presence
scry sequences --field-presence --type TARGETED_SPELL
```

Fields to track (from `gsm.raw`):

| Field | Key in raw JSON |
|-------|----------------|
| gameInfo | `gameInfo` |
| turnInfo | `turnInfo` |
| players | `players` |
| zones | `zones` |
| gameObjects | `gameObjects` |
| timers | `timers` |
| annotations | `annotations` (count) |
| persistentAnnotations | `persistentAnnotations` (count) |
| diffDeletedInstanceIds | `diffDeletedInstanceIds` |
| diffDeletedPersistentAnnotationIds | `diffDeletedPersistentAnnotationIds` |
| actions | `actions` |
| prevGameStateId | `prevGameStateId` |

Output per slot:

```
TARGETED_SPELL slot 1 (CAST_TARGETED):
  gameInfo:        0%    turnInfo:      100%   players:       100%
  zones:         100%    gameObjects:   100%   timers:         85%
  annotations:   100%    persistentAnns: 92%   actions:        97%
  diffDeleted:     0%    diffDelPAnns:    0%   prevGsId:      100%
```

**Implementation:** In `classifySlot`, read each field from `gsm.raw` and record presence (non-null, non-empty-array, non-zero). Aggregate as percentage across instances. Add to slot detail in both human and JSON output.

---

## 3. `--persistent` — persistent annotation lifecycle

Track when persistent annotations appear and disappear across interaction slots.

```bash
scry sequences --persistent
scry sequences --persistent --type TARGETED_SPELL
```

Output:

```
TARGETED_SPELL persistent annotation lifecycle:
  TargetSpec:
    appears:  slot 3 (TARGETS_CONFIRMED)  92%
    removed:  slot 5 (RESOLVE)            92%
    lifespan: 2 slots

  EnteredZoneThisTurn:
    appears:  slot 1 (CAST_TARGETED)      100%
    removed:  never (persists)
    lifespan: ∞

  ColorProduction:
    appears:  slot 1 (CAST_TARGETED)      45%
    removed:  never (persists)
    lifespan: ∞
```

**How:** For each interaction instance, walk the slots and track `persistentAnnotations` type set. For each pAnn type, record first-seen slot and last-seen slot (or "persists" if still present at final slot). Also check `diffDeletedPersistentAnnotationIds` on each GSM to detect explicit deletions.

**Implementation:** After interaction detection, iterate each instance's GSM sequence. Build a map of `pAnnType → {firstSlot, lastSlot, deletedAtSlot?}`. Aggregate across instances — report majority first/last slot with percentage.

---

## 4. `scry annotations order` — per-type neighbor analysis

New top-level command (not a sequences flag). Given an annotation type, show what always comes before and after it across ALL GSMs — not scoped to an interaction type.

```bash
scry annotations order --type DamageDealt
scry annotations order --type ZoneTransfer --source real
```

Output:

```
DamageDealt  (183 instances across 24 games)

  Always before (100%):
    PhaseOrStepModified     (in combat GSMs)
    ResolutionStart         (in resolve GSMs)

  Usually before (>70%):
    (none beyond always)

  Always after (100%):
    ObjectIdChanged         (when creature dies)
    ZoneTransfer            (when creature dies)

  Usually after (>70%):
    SyntheticEvent          (82%)
    ModifiedLife            (78%)
    ResolutionComplete      (71%)

  Never colocated with:
    PlayerSelectingTargets
    PlayerSubmittedTargets
    ManaPaid
```

**How:** For each GSM containing the target annotation type, record the ordered annotation type list. Extract the position of the target type. Everything before it → "before" set, everything after → "after" set. Aggregate across all GSMs.

**Implementation:** New file `src/commands/annotations.ts`. Reuses `resolveGame` and game loading. Iterates all GSMs in matched games, filters to those containing the target type, extracts position-relative neighbor sets.

**CLI registration:** `scry annotations order --type TYPE [--source SRC] [--json]`

---

## 5. `--gsid-gaps` — inter-GSM gap analysis

What fills the gaps between interaction GSMs. Are they bare echoes? Priority passes? Other-seat actions?

```bash
scry sequences --gsid-gaps
scry sequences --gsid-gaps --type TARGETED_SPELL
```

Output per slot transition:

```
TARGETED_SPELL gap analysis:
  slot 1→2 (CAST_TARGETED → ECHO):
    gsId delta: 1 (100%)  — always consecutive

  slot 2→3 (ECHO → TARGETS_CONFIRMED):
    gsId delta: 1 (45%), 2 (38%), 3 (12%), 4+ (5%)
    gap contents (when delta > 1):
      PHASE (62%)  — priority pass for other seat
      ECHO (28%)   — bare echo for prior action
      UNKNOWN (10%)

  slot 4→5 (ECHO → RESOLVE):
    gsId delta: 1 (72%), 2 (23%), 3+ (5%)
    gap contents:
      PHASE (80%)  — priority pass
      ECHO (20%)
```

**How:** For each interaction instance, compute `gsId[slot N+1] - gsId[slot N]` for consecutive slots. When delta > 1, look up the GSMs in between (from the game's `greMessages` by gsId range) and classify them by role.

**Implementation:** After interaction detection, iterate each instance's slot pairs. Compute deltas, look up intervening GSMs in the game's full GSM list, classify with the existing classifier. Aggregate delta distributions and gap role distributions per slot transition.

---

## Testing

All five addons should produce correct output against game `2026-03-30_20-06` (the Shock/Bite Down game we traced manually). Specific checks:

- `--diff`: TARGETED_SPELL shows 6 slots real vs 0 leyline instances
- `--field-presence`: COMBAT_DAMAGE slot 1 should show zones at <100% (no zone changes in damage-only GSMs)
- `--persistent`: TARGETED_SPELL TargetSpec appears slot 3, removed slot 5
- `annotations order --type DamageDealt`: ObjectIdChanged and ZoneTransfer always after
- `--gsid-gaps`: TARGETED_SPELL slot 2→3 gap should show delta > 1 (client targeting interaction happens between echo and confirmed)
