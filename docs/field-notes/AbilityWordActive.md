## AbilityWordActive — field note

**Status:** NOT IMPLEMENTED
**Instances:** 47 across 2 sessions
**Proto type:** AnnotationType.AbilityWordActive (not yet in our proto enum)
**Field:** persistentAnnotations (updated in-place across GSMs)

### What it means in gameplay

Tracks a named condition on a card — whether an "ability word" threshold is currently met.
The client uses `AbilityWordName` to show the correct glow/badge (e.g., Raid, Impending, creature-count-based bonuses). When `value >= threshold`, the enhanced mode is active.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| AbilityWordName | Always | `NumberOfLessonCardsInYourGraveyard`, `NumberOfCreaturesYouControl`, `Impending`, `Raid` | Named condition string; client maps to UI badge |
| value | Sometimes (27%) | 1, 2, 3, 4, 9 | Current count/value of the tracked metric |
| threshold | Sometimes (17%) | 3 | The count required to activate the enhanced mode |
| AbilityGrpId | Sometimes (27%) | 192590, 99713, 188773 | The ability being tracked; absent for keyword ability words (Raid, Impending) |

Note on "sometimes": keyword-only ability words (Raid, Impending) appear with only `AbilityWordName` — no value/threshold/AbilityGrpId. Quantitative conditions carry all four keys.

### Cards observed

| Card name | grpId | AbilityWordName | Scenario | Session |
|-----------|-------|-----------------|----------|---------|
| Accumulate Wisdom | 97318 | NumberOfLessonCardsInYourGraveyard | Tracks Lesson count in GY; threshold=3; value updates 1→4 as more Lessons enter GY | 09-33-05 |
| Craterhoof Behemoth | 72447 | NumberOfCreaturesYouControl | On-stack CRB ability (grp:99713); value=9 (9 creatures controlled) | 2026-03-06_22-37-41 |
| Craterhoof Behemoth ability target | 99713 | NumberOfCreaturesYouControl | Same GSM, annotation on both source ability and CRB card | 2026-03-06_22-37-41 |
| Overlord of the Hauntwoods | 92286 | Impending | Keyword-only; no value/threshold; just marks active Impending state | 2026-03-06_22-37-41 |
| Dawnwing Marshal | 93998 | Raid | Keyword-only; no value/threshold; active during T7 Combat | 2026-03-06_22-37-41 |
| Searslicer Goblin | 93806 | Raid | Same session; Raid badge persists through Main2 until annotation deleted | 2026-03-06_22-37-41 |

Note: variance tool reported grp:75557 (Swamp) for the Impending card — this is a tool resolution artifact. The actual grpId from the raw frame at gsId=93 is 92286 (Overlord of the Hauntwoods). The tool resolves the last known grpId for an instanceId, which can be stale after zone transfers. Ditto grp:99713 on the Avenger of the Fallen ability — the annotation appears on the Craterhoof Behemoth ability instanceId which maps to ability 99713 (same number used as both ability grpId and card reference).

### Lifecycle

**Quantitative type (Accumulate Wisdom):**
- Annotation ID stays constant for same instanceId; updated in-place as value changes.
- When card changes zones (new instanceId), old annotation is deleted and a new one is created for the new instanceId.
- Multiple copies in hand each get their own annotation (same value — shared game state).
- No deletion observed at turn boundary (condition persists as long as the tracked resource count is nonzero).

**On-stack ability type (Craterhoof Behemoth):**
- Annotation appears when the ability is on the stack; attached to both the source ability object and the source card.
- Expected to be cleaned up when the ability resolves.

**Keyword type (Raid, Impending):**
- Annotation appears as a persistent marker; no value tracking.
- For Raid: active during Combat phase and Main2 while the attack condition was met; deleted when the turn advances past relevance.
- For Impending: marks the Impending keyword active state; lifecycle tied to the permanent.

### Related annotations

- `AbilityExhausted` — tracks abilities that have been used up; AbilityWordActive tracks the opposite (whether an ability's condition is currently met)
- `LayeredEffectCreated` — can co-occur when a condition-dependent buff becomes active

### Our code status

- Builder: missing (type not in our proto enum)
- GameEvent: missing
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Hard

Two distinct sub-patterns to handle:

1. **Quantitative conditions** (Lesson count, creature count): requires per-GSM evaluation of each card's condition SVars, tracking the current value, and emitting/updating the annotation when the value changes. Need a mapping from Forge's `Count$` SVar expressions to `AbilityWordName` strings + threshold values.

2. **Keyword ability words** (Raid, Impending): simpler — just need to detect when a Raid/Impending condition becomes active (attacked this turn, Impending keyword on BF) and emit the annotation with only `AbilityWordName`.

The core gap is the Forge-to-Arena ability word identity mapping. The `AbilityWordName` strings are Arena-specific identifiers not documented in the proto — they require a static mapping table. There is no GameEvent for "condition became true/false."

Low priority — purely cosmetic (ability word glow/badge). The mechanics resolve correctly without this.

### Open questions

- What is the full set of `AbilityWordName` string values? Only 4 seen so far. May be a large but finite enum on the Arena client side.
- Does the annotation appear on permanents in non-hand zones (graveyard, exile)? Not yet observed.
- For Impending: does the annotation get deleted when the Impending countdown reaches zero and the permanent "enters"? Not yet observed.
- Is `AbilityGrpId` absent for all keyword ability words, or just for the ones seen? Avenger of the Fallen's `Mobilize` (ability 188773) was in the variant report samples but no example was visible in the 15 collected samples — may have been from a session not in the recordings.
