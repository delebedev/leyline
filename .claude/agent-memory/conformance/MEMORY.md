# Conformance Agent Memory

## Recording file locations
- `md-frames.jsonl` is at recording root, NOT under `capture/`
- `capture/fd-frames.jsonl` is under capture/

## Combat Echo-Back GSM Pattern (confirmed across 5 proxy recordings)

After DeclareAttackersResp or DeclareBlockersResp, real server sends:
1. Two echo GSMs (same gsId, same msgId) — one per seat ([1] and [2])
2. DeclareAttackersReq or DeclareBlockersReq re-prompt to decision seat

Echo GSM properties:
- `updateType`: always `SendAndRecord`
- `turnInfo`: **absent** (never present in the direct echo)
- `attackState`/`blockState`: **never present** on creature objects
- `pendingMessageCount`: **never present**
- `actions`: cumulative turn-level log (Cast, Play, ActivateMana, Activate) — no combat-specific types
- `objects`: the toggled attacker/blocker with base fields only (instanceId, grpId, zoneId, power, toughness, cardTypes, subtypes, uniqueAbilityCount — NO tapped/attackState flags)

The two seat copies differ only in `systemSeatIds` and the `actions` list (each seat gets their own perspective action history).

Re-prompt DeclareBlockersReq clears the committed blocker's `attackerInstanceIds` to `[]`.

## DeclareAttackersReq: initial vs re-prompt distinction

**Initial DeclareAttackersReq** (before any toggle):
- `declareAttackers.attackers` = all eligible creatures (available pool for attack)
- `declareAttackers.qualifiedAttackers` = same as attackers
- `promptId` = 6 (constant)
- `systemSeatIds` = [attacking player's seat]

**Re-prompt DeclareAttackersReq** (after each DeclareAttackersResp toggle):
- `declareAttackers.attackers` = **cumulative committed attackers so far** (grows with each toggle)
- `declareAttackers.qualifiedAttackers` = same as attackers (same list, not the full eligible pool)
- `promptId` = 6 (constant)
- `systemSeatIds` = [attacking player's seat] (= decisionPlayer's seat)

The cumulative list in re-prompts does NOT reset between toggles — each re-prompt shows the full running total. The client uses this to render the attacker-selected state.

**autoDeclare=True** path: server still sends echo GSM + re-prompt DeclareAttackersReq with full attacker list; client immediately follows with SubmitAttackersReq.

Confirmed recordings: `2026-02-28_11-50-40`, `2026-03-01_00-11-05`, `2026-03-01_00-18-46`, `2026-03-06_22-37-41`

## Annotation search in md-frames.jsonl

`md-frames.jsonl` uses human-readable type strings (e.g. `"types":["CastingTimeOption"]`), NOT numeric IDs. Use grep directly on md-frames.jsonl, not on proto-decode-recording output, to find annotation instances by type name.

## Annotation affectorId: synthetic zone-change IDs (9000+ range)

`ReplacementEffect` and some ETB annotations use `affectorId` in the `9000+` range (e.g. 9002, 9010, 9013). These are synthetic zone-change event IDs, NOT object instanceIds. Unclear if they correspond to Forge zone-change counters or are Arena-internal.

## DelayedTriggerAffectees + TriggerHolder pattern

Arena creates a synthetic TriggerHolder object (grp=5) in Limbo when a delayed trigger registers. The `DelayedTriggerAffectees` annotation points to this TriggerHolder while pending, then its `affectorId` updates to the live Ability instanceId when the trigger fires. This two-phase lifecycle has no Forge equivalent.

## CastingTimeOption annotation vs CastingTimeOptionsReq message

These are DIFFERENT things:
- `CastingTimeOptionsReq` = interactive GRE message asking the player to choose (Kicker, Warp cost, etc.)
- `CastingTimeOption` (type 64) = persistent annotation on the spell on-stack recording WHICH option was chosen

`type=13` (CastThroughAbility) appears for alternate-cast mechanics (Warp, Impending, Flash-granted casts). The `alternateCostGrpId` matches the `alternativeGrpId` already in `UserActionTaken`.

## Kicker wire protocol (fully documented)

Full spec: `docs/plans/2026-03-14-kicker-wire-spec.md`

Key facts:
- `CastingTimeOptionsReq` with `type="Kicker"` is sent in the **same GSM file** (same TCP flush) as the stack-placement Diff GSM, at the same `gsId`.
- `promptId` = 23 (fixed constant for all CastingTimeOptionsReq).
- `grpId` in the kicker option = the **kicker ability grpId** (not the card grpId). Look up with `just ability <id>`.
- `Done` option always has `ctoId=0`, `isRequired=true`.
- Kicker option: no nested `modal`/`SelectNReq` block — flat structure.
- **Declined response is bare** (no body, no selectedOptions).
- **Kicker accepted**: not yet recorded — CastingTimeOptionsResp body unknown, CastingTimeOption annotation type value unknown.
- Targeting (SelectTargetsReq) is gated behind CastingTimeOptionsResp — never fires before option resolution.
- 4 kicker instances observed, all declined: 2× Burst Lightning (22-37-41), 1× Burst Lightning (00-18-46), 1× Sun-Blessed Healer (00-11-05).

## Field notes directory

Individual per-annotation-type field notes: `docs/field-notes/<TypeName>.md`
Older bulk notes: `docs/annotation-field-notes.md`
