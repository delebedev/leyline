## CastingTimeOption — field note

**Status:** NOT IMPLEMENTED
**Instances:** 5 across 1 session (`2026-03-06_22-37-41`)
**Proto type:** `AnnotationType.CastingTimeOption` (= 64)
**Field:** `persistentAnnotations`

### What it means in gameplay

Marks a spell on the stack as having been cast using an alternate or optional casting mechanism. The client uses this to display a badge or indicator on the spell (e.g. "cast via Warp", "cast via Impending", "cast with Offspring"). The annotation persists while the spell is on the stack.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `type` | Always | 2, 5, 13 | `CastingTimeOptionType` enum: 2=`ChooseX_a7b4`, 5=`AdditionalCost`, 13=`CastThroughAbility` |
| `value` | Sometimes | 1, 8 | The chosen X value (when `type`=2, i.e. `ChooseX`) |
| `alternateCostGrpId` | Sometimes | 190919, 174489 | The ability grpId of the alternate cost used (`CastThroughAbility`) |
| `additionalCostGrpId` | Sometimes | 173838 | The ability grpId of the additional cost paid (`AdditionalCost`) |

### Cards observed

| Card name | grpId | Scenario | type value | Session |
|-----------|-------|----------|-----------|---------|
| Mockingbird | 91597 (iid=388) | Cast via Warp (ability 190919 = "Warp {1U}") | 13 (CastThroughAbility) | `2026-03-06_22-37-41` gsId=216 |
| Mockingbird | 91597 (iid=388) | In hand, Warp choice pending (ChooseX for Warp cost) | 2 (ChooseX), value=1 | `2026-03-06_22-37-41` gsId=207 |
| Nature's Rhythm | 95672 (iid=409) | In hand, Warp choice pending | 2 (ChooseX), value=8 | `2026-03-06_22-37-41` gsId=280 |
| Overlord of the Hauntwoods | 92286 (iid=289) | Cast via Impending (ability 174489 = "Impending 4—{1GG}") | 13 (CastThroughAbility) | `2026-03-06_22-37-41` gsId=93 |
| Badgermole Cub | 97444 (iid=307) | Cast with Offspring (ability 173838 = "Offspring {2}") | 5 (AdditionalCost) | `2026-03-06_22-37-41` gsId=114 |

### Lifecycle

Persistent annotation. Created in the GSM when the spell moves to the stack (ZoneTransfer CastSpell). Deleted (via `diffDeletedPersistentAnnotationIds`) when the spell resolves or leaves the stack.

Two distinct trigger points:
- `type=2` (ChooseX / Warp in hand): appears before casting, on the card in hand, while the server is building the `ActionsAvailableReq`. Represents the chosen X value already selected. This is a QueuedGameStateMessage (`updateType=Send`), so it's the opponent's-perspective echo of the pending action.
- `type=13` (CastThroughAbility / Impending): appears when the spell is on the stack. Marks which alternate cast ability was used.
- `type=5` (AdditionalCost / Offspring): appears when spell is on stack with an additional cost paid.

### Related annotations

- `AbilityWordActive` with `AbilityWordName: "Impending"` appears alongside `CastingTimeOption` type=13 for Impending cards (same gsId=93).
- `CastingTimeOptionsReq` (separate GRE message type) is the interactive prompt to the player before cast — distinct from this annotation which is the post-decision record.

### Our code status

- Builder: missing — no `castingTimeOption()` or similar in `AnnotationBuilder.kt`
- GameEvent: missing — no Forge GameEvent for "cast via alternate cost"
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Medium

Three sub-cases:

1. **`type=13` (CastThroughAbility):** When a spell resolves its cast via a Warp/Impending/Flash-granting ability, emit this annotation with `alternateCostGrpId` = the ability that granted the alternate cast timing. Hook point: the `UserActionTaken` annotation already records `alternativeGrpId` on the `actionType=1` (Cast) action — the same grpId flows into `alternateCostGrpId`. This is the most tractable sub-case.

2. **`type=5` (AdditionalCost / Offspring):** When a spell is cast with an additional cost keyword paid. Forge has `SpellAbility.costAdjustments` for additional costs. The annotation needs the grpId of the optional additional cost ability.

3. **`type=2` (ChooseX for Warp):** This appears in QueuedGameStateMessages for the opponent perspective. Low priority — the interactive Warp cost selection is handled by `CastingTimeOptionsReq` already. The annotation here is cosmetic (showing the X value on the card badge).

The primary missing piece is detecting "was this spell cast via an alternate cost?" in the post-cast GSM. The `UserActionTaken.alternativeGrpId` field already encodes this for the cast action.

### Open questions

- Does `type=2` (ChooseX) ever appear in the casting player's GSM or only in opponent-echo QueuedGameStateMessages?
- Is the annotation deleted when the `CastingTimeOptionsReq` is dismissed (i.e. player chose "Done"), or only when the spell resolves?
- Are there other `CastingTimeOptionType` values (Kicker=3, Bargain=17, etc.) not yet observed? Almost certainly yes.
