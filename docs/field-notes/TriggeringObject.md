## TriggeringObject — field note

**Status:** NOT IMPLEMENTED (builder exists at `AnnotationBuilder.kt:662`, never called from pipeline; builder signature wrong)
**Instances:** 223 across 4 sessions (98 in `2026-03-11_16-13-23`)
**Proto type:** AnnotationType.TriggeringObject (type 32)
**Field:** persistentAnnotations (persists while trigger is on the stack)

### What it means in gameplay

When a triggered ability goes on the stack, this persistent annotation links the stack ability back to the source permanent that triggered it. The client draws a glowing arrow from the source permanent to the ability on the stack, letting the player see "this trigger came from THAT creature." Deleted when the trigger resolves or is countered.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| source_zone | Always | 28 (90%), 27 (10%) | Zone of the source permanent when the trigger was created. 28=Battlefield, 27=Stack |

### Shape

- **Type array:** always `["TriggeringObject"]` (single type, never multi-typed)
- **affectorId:** always present (100%) — the trigger ability instance on the stack (zone 27, type=Ability)
- **affectedIds:** always 1 element — the source permanent that triggered (usually zone 28/Battlefield)
- **details:** always `{ "source_zone": N }` — 28 (battlefield) in 90% of cases, 27 (stack) in 10%

### Triggers

Any triggered ability going on the stack:

- **ETB triggers** (most common) — creature enters, trigger goes on stack. Cards: Cloudkin Seer (75466), Charmed Stray (75446), Octoprophet (75470), Baloth Packhunter (75531)
- **Attack/combat triggers** — Courageous Goblin (93795), Ruby, Daring Tracker (93958)
- **Landfall triggers** — Rugged Highlands (93978) with `source_zone=28`
- **Stack-based triggers** (`source_zone=27`) — when an ability on the stack triggers another ability (e.g., cascade, storm, copy effects). ~10% of cases.

### Lifecycle

1. Trigger ability created → `AbilityInstanceCreated` transient annotation fires (same GSM)
2. `TriggeringObject` persistent annotation created with `affectorId` = ability instance, `affectedIds` = source permanent
3. Persists across GSMs while the ability is on the stack
4. Trigger resolves → `ResolutionComplete` fires → `TriggeringObject` annotation ID appears in `diffDeletedPersistentAnnotationIds`

### Cards observed

| Card | grpId | Trigger type | Count | Session |
|------|-------|-------------|-------|---------|
| Cloudkin Seer | 75466 | ETB — draw a card | 3 | 2026-03-11_16-13-23 |
| Courageous Goblin | 93795 | Attack trigger | 2 | 2026-03-11_16-13-23 |
| Baloth Packhunter | 75531 | ETB — create token | 2 | 2026-03-11_16-13-23 |
| Octoprophet | 75470 | ETB — scry 2 | 2 | 2026-03-11_16-13-23 |
| Charmed Stray | 75446 | ETB — +1/+1 counter | 1 | 2026-03-11_16-13-23 |
| Ruby, Daring Tracker | 93958 | Attack/combat trigger | 1 | 2026-03-11_16-13-23 |
| Rugged Highlands | 93978 | ETB — gain 1 life | 1 | 2026-03-11_16-13-23 |

### Our code status

- Builder: exists — `AnnotationBuilder.triggeringObject(instanceId, sourceZone)` at line 662. **Signature is wrong:** takes `(instanceId, sourceZone)` and puts `instanceId` in `affectedIds`. Real server uses `affectorId` = ability on stack, `affectedIds` = source permanent. Builder needs `(affectorId, sourceInstanceId, sourceZone)`.
- GameEvent: partially exists — `GameEvent.AbilityTriggered` captures trigger creation but doesn't carry the source permanent ID
- Emitted in pipeline: no — builder is never called

### Dependencies

- **`AbilityInstanceCreated`** (transient) — fires in the same GSM, links the ability to its source. `TriggeringObject` is the persistent companion.
- **`ResolutionComplete`** (transient) — fires when the trigger resolves, at which point `TriggeringObject` is deleted.

### Wiring assessment

**Difficulty:** Medium

Requirements:
1. Fix builder signature: `triggeringObject(abilityInstanceId, sourceInstanceId, sourceZone)`
2. In `AnnotationPipeline`, when an `AbilityTriggered` event fires, emit `TriggeringObject` as a persistent annotation with `affectorId` = the ability's instanceId (zone 27), `affectedIds` = source permanent instanceId
3. Track in `PersistentAnnotationStore` — delete when `ResolutionComplete` fires for the same ability

The main challenge is getting the source permanent's instanceId at trigger time. `GameEvent.AbilityTriggered` currently has the ability's forge card ID but may not carry the source permanent's card ID. Need to check if `AbilityTriggered` (or the underlying Forge event) exposes the source.

### Open questions

- `source_zone=27` (10% of cases): which triggers fire from the stack? Likely copy/cascade mechanics where an ability on the stack triggers another ability. Need more samples to confirm.
- When multiple triggers from the same permanent go on the stack simultaneously (e.g., two ETB triggers), do they share the same `affectedIds` but have different `affectorId`s? Likely yes — confirmed by gsId=122 in `2026-03-10_08-23-48` where two abilities (304, 305) both point to source 302.
