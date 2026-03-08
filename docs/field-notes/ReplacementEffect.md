## ReplacementEffect — field note

**Status:** NOT IMPLEMENTED (annotation; the Forge mechanic does work)
**Instances:** 4 across 1 session (`2026-03-06_22-37-41`)
**Proto type:** `AnnotationType.ReplacementEffect_803b` (= 62; renamed from `ReplacementEffect` in upstream proto)
**Field:** `persistentAnnotations`

### What it means in gameplay

Marks a permanent that has a "replacement effect" triggered on entry — specifically an "as this enters" choice that has been resolved. The client uses this to show which replacement effect was applied to a permanent (e.g. which basic land type a dual land was configured as).

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `grpid` | Always | 193130, 173905, 90846 | The ability grpId of the replacement effect that was applied |
| `ReplacementSourceZcid` | Always | 160, 279, 339, 388 | The zone-change id (zcid) of the card that provided the replacement effect; often the same permanent or the spell on the stack |

### Cards observed

| Card name | grpId | grpId of replacement effect ability | Scenario | Session |
|-----------|-------|------|----------|---------|
| Multiversal Passage | 97998 (iid=282) | 193130 ("As CARDNAME enters, choose a basic land type...") | Enters BF, player chose a land type; enters tapped or pays 2 life | `2026-03-06_22-37-41` gsId=24 |
| Mockingbird | 91597 (iid=388) | 173905 ("You may have CARDNAME enter as a copy of any creature...") | Enters BF; copy-on-entry replacement resolves | `2026-03-06_22-37-41` gsId=210 |
| Temple Garden / similar shockland | 98590 (iid=280, 340) | 90846 ("As CARDNAME enters, you may pay 2 life. If you don't, it enters tapped.") | Shockland enters; pay-2-life replacement resolves | `2026-03-06_22-37-41` gsId=23, 240 |

Note: grp:75554 (Island) in the variance report's card list is misleading — the iid=282 is Multiversal Passage (grp:97998), which has an Island-choosing replacement effect. The annotation is on Multiversal Passage, not on a real Island.

### Lifecycle

Persistent annotation. Created in the GSM when the permanent enters the battlefield (same GSM as the ZoneTransfer with `category=Resolve`). For lands entering in the opponent's QueuedGameStateMessage (from a different seat), it appears before the SendHiFi resolution GSM.

`affectorId` = a synthetic zone-change id in the `9000+` range (e.g. `9002`, `9010`, `9013`) — this is the zone-change event id, not an object instanceId. This is unusual: most annotations use `affectorId` = the object or ability instanceId.

`affectedIds` = the permanent instanceId that was affected.

The annotation persists for the lifetime of the permanent on the battlefield. Deleted if the permanent leaves (not observed in this session since the permanents stayed).

### Related annotations

- `LayeredEffectCreated` (type 7002+) often appears in the same GSM for lands with type-choice replacement effects (Multiversal Passage creates a layered effect for the land type modification).
- `RemoveAbility`/`ModifiedType`/`LayeredEffect` combo on the same permanent tracks the ongoing land type change.

### Our code status

- Builder: missing — no `replacementEffect()` builder in `AnnotationBuilder.kt`
- GameEvent: `forge.game.replacement.ReplacementEffect` exists in Forge and is referenced in `WebPlayerController.kt` (`confirmReplacementEffect`), but this is the interactive prompt, not the annotation emitter
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Medium

The `confirmReplacementEffect` hook in `WebPlayerController` fires when a replacement effect choice is made by the player (e.g. choosing which land type for Multiversal Passage, choosing whether to pay 2 life for a shockland). That's exactly the right hook point for emitting this annotation.

What's needed:
1. In `confirmReplacementEffect`, after the choice is made, emit a `ReplacementEffect` persistent annotation with:
   - `affectorId` = a synthetic zone-change id (the `9000+` range — needs to track the current "as-enters" context)
   - `affectedIds` = the permanent that just entered
   - `grpid` = the ability grpId of the replacement effect
   - `ReplacementSourceZcid` = the source card/spell's instanceId or zcid
2. The synthetic `9000+` affectorId scheme needs to be understood — it may be the Forge zone-change ID for the ETB event.

The Forge `ReplacementEffect` object has `getHostCard()` and can be queried for the ability grpId. The main unknown is the `9000+` affectorId scheme.

### Open questions

- What is the `9000+` affectorId scheme? Is it a Forge zone-change counter, or a synthetic counter we need to maintain in leyline?
- Does the annotation appear for ALL replacement effects on entry, or only "choose a type" / "pay life or enter tapped" kinds?
- Is `ReplacementEffectApplied` (type 77) a related annotation? Not observed in these recordings — may fire for triggered replacements rather than ETB-choice ones.
