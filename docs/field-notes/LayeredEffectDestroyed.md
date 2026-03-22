## LayeredEffectDestroyed — field note

**Status:** NOT IMPLEMENTED (builder exists at `AnnotationBuilder.kt:721`, never called from pipeline)
**Instances:** 135 across 5 sessions (119 in `2026-03-11_16-13-23` alone)
**Proto type:** AnnotationType.LayeredEffectDestroyed (type 19)
**Field:** annotations (transient only — never persistent)

### What it means in gameplay

The server fires this when a continuous/layered effect expires — an aura leaving the battlefield, a pump spell wearing off at end of turn, or a creature with a granted keyword dying. The client uses it to clean up the effect's synthetic ID from its layer tracking system, removing stale P/T modifications and keyword displays.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| (none) | Always | — | No detail keys — all metadata was on the companion `LayeredEffect` persistent annotation |

### Shape

- **Type array:** always `["LayeredEffectDestroyed"]` (single type, never multi-typed)
- **affectedIds:** always 1 element — the synthetic effect ID (7000+ range, same ID used in `LayeredEffect` persistent annotation)
- **affectorId:** present ~50% of the time (59/119 in richest session)
  - **Present:** points to the card whose departure caused the effect to end (e.g., an aura moving to zone 30/Limbo after being destroyed)
  - **Absent:** for effects expiring by duration (end-of-turn pumps, phase-bound effects) or game-system effects
- **details:** empty — always `{}`

### Triggers

- **Aura leaves battlefield** → affectorId = aura card (zone 30/Limbo). Cards: Angelic Destiny (93993), Waterknot (75481)
- **Creature with granted ability dies** → affectorId = dead creature. Cards: Windstorm Drake (75483)
- **End-of-turn pump expires** → no affectorId. Fires at Beginning phase (upkeep of next turn) or Combat phase
- **Effect replaced by new one** → `LayeredEffectDestroyed` (old effect) + `LayeredEffectCreated` (new effect) in same GSM. Seen when +1/+1 counter count changes recalculate the layered effect.

### Phase distribution (no-affectorId cases)

| Phase | Count | Interpretation |
|-------|-------|----------------|
| Main1 | 30 | Until-end-of-turn effects cleaning up at start of next turn |
| Combat/BeginCombat | 10 | Phase-bound effects |
| Combat/DeclareAttack | 6 | Mid-combat effect expiry |
| Combat/CombatDamage | 4 | Post-damage cleanup |

### Lifecycle

1. `LayeredEffectCreated` fires as transient annotation → a companion `LayeredEffect` persistent annotation is created (same GSM)
2. `LayeredEffect` persists across GSMs while the effect is active
3. `LayeredEffectDestroyed` fires as transient annotation → the `LayeredEffect` persistent annotation ID appears in `diffDeletedPersistentAnnotationIds` (same GSM)

The synthetic effect ID starts at 7002 and increments globally per match (range observed: 7002–7030 in one session).

### Cards observed

| Card | grpId | Role | Scenario | Session |
|------|-------|------|----------|---------|
| Angelic Destiny | 93993 | affector | Aura leaves battlefield → effects cleaned up | 2026-03-11_16-13-23 |
| Stab | 93784 | affector | Instant removal → creature's effects end | 2026-03-11_16-13-23 |
| Windstorm Drake | 75483 | affector | Drake dies → its lord effect ends | 2026-03-11_16-13-23 |
| Waterknot | 75481 | affector | Aura destroyed → untap-prevention effect ends | 2026-03-10_08-23-48 |

### Our code status

- Builder: exists — `AnnotationBuilder.layeredEffectDestroyed(effectId)` at line 721. Takes only `effectId`, no `affectorId` param (needs adding for the 50% case).
- GameEvent: missing — no event for effect lifecycle destruction
- Emitted in pipeline: no — builder is never called

### Dependencies

- **`LayeredEffect`** (persistent, type 51) — the companion that tracks the active effect. Must be deleted in same GSM.
- **`LayeredEffectCreated`** (transient, type 18) — the creation counterpart. All three form a unit.
- **`EffectTracker.DiffResult`** — our existing effect diff system already detects created/removed effects. The `removed` set in `DiffResult` maps directly to `LayeredEffectDestroyed` annotations.

### Wiring assessment

**Difficulty:** Medium (not Hard — most infrastructure exists)

The `EffectTracker` already computes `DiffResult.removed` which contains the effect IDs that disappeared. Wiring:

1. In `AnnotationPipeline.effectAnnotations()`, iterate `diff.removed` → emit `LayeredEffectDestroyed` per removed effect ID
2. Add optional `affectorId` param to builder (the card that caused the removal — available from the diff context when a card leaves battlefield)
3. Emit alongside the `diffDeletedPersistentAnnotationIds` for the corresponding `LayeredEffect` persistent annotation

The hard part is determining `affectorId` — requires correlating the effect removal with what left the battlefield in the same GSM. The no-affectorId case (duration expiry) is the easy path.

### Open questions

- When multiple effects are destroyed simultaneously (e.g., creature with 3 auras dies), are they ordered? Or arbitrary? (Likely annotation ID order, but not confirmed.)
- The `affectorId` for duration-based expiry is always absent — is there ever a case where the game system generates one?
