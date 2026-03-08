## LayeredEffectCreated — field note

**Status:** NOT IMPLEMENTED
**Instances:** 131 across 5 sessions
**Proto type:** AnnotationType.LayeredEffectCreated
**Field:** annotations (transient only — never persistent)

### What it means in gameplay

The server fires this when a continuous/layered effect begins — a permanent gaining a keyword, a creature getting a P/T buff until end of turn, or a land having its type changed. The client uses it to start tracking the effect's synthetic ID so it knows what to clean up when `LayeredEffectDestroyed` fires.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| (none) | Always | — | No detail keys on LayeredEffectCreated itself |

All effect metadata lives on the companion `LayeredEffect` persistent annotation (same GSM), not on `LayeredEffectCreated`.

### Cards observed

| Card / Ability | grpId | Scenario | Session |
|----------------|-------|----------|---------|
| Multiversal Passage (land, plays as Forest) | 97998 | Land played — type change effect | 2026-03-06_22-37-41 gsId=24 |
| Badgermole Cub ETB (earthbend ability) | ability 192752 | Creature ETB, triggers earthbend — changes land types | 2026-03-06_22-37-41 gsId=72,195,215,227 |
| A Most Helpful Weaver (Spider hero ability) | ability 192918 | Resolves — target creature becomes legendary Spider Hero | 2026-03-06_22-37-41 gsId=92,160 |
| Veteran Survivor | 92102 | Resolves — grants effect to creatures | 2026-03-06_22-37-41 gsId=167 |
| Wonderweave Aerialist | 97964 | Resolves — buff effect | 2026-03-06_22-37-41 gsId=170 |
| A Most Helpful Weaver (double strike grant) | ability 172172 | Grants double strike until end of turn | 2026-03-06_22-37-41 gsId=249 |
| Craterhoof Behemoth ETB | ability 99713 | ETB — all creatures gain trample and +X/+X | 2026-03-06_22-37-41 gsId=287 |
| Game-initialization effects | — | gsId=1 across multiple sessions: 3 effects created at game start | all sessions |

### Lifecycle

1. `LayeredEffectCreated` fires as a **transient annotation** (in `annotations`, never `persistentAnnotations`).
2. In the same GSM, a companion `LayeredEffect` **persistent annotation** is created (or updated), referencing the same synthetic effect ID via `effect_id`.
3. Later, `LayeredEffectDestroyed` fires when the effect expires (transient), and the `LayeredEffect` persistent annotation is deleted via `diffDeletedPersistentAnnotationIds`.

The synthetic effect ID (`affectedIds=[7002]`, `[7003]`, etc.) starts at 7002 and increments globally across the match. A counter that resets per-match, not per-GSM.

**affectorId patterns:**
- Present: the ability instance or card on stack causing the effect (e.g. an ETB ability instance in zone 27 or 25)
- Absent (no affectorId): for game-system effects, land-type effects, or effects where the cause is implicit (observed for ~35 of 98 instances in this session)
- `-3`: special sentinel value observed once (gsId=211) — meaning unclear, possibly a game-system or static-ability-generated effect

**Grouping:** when one trigger creates multiple effects (e.g. Badgermole Cub earthbend creates 4 land-type effects), there are 4 separate `LayeredEffectCreated` annotations with 4 different effect IDs, all in the same GSM.

### Related annotations

- `LayeredEffect` (persistent) — carries the actual effect metadata (types, `sourceAbilityGRPID`, `effect_id`). Always co-created in the same GSM as `LayeredEffectCreated`.
- `LayeredEffectDestroyed` — the teardown counterpart. See existing field notes in `docs/annotation-field-notes.md`.
- `ModifiedType` — a multi-type annotation that combines with `LayeredEffect` when a type modification is part of the effect.
- `RemoveAbility` — also combines with `LayeredEffect` (e.g. Multiversal Passage removing a basic land's normal abilities on type change).

### Our code status

- Builder: missing — no `LayeredEffectCreated` annotation is ever emitted
- GameEvent: missing — no event hooking into effect lifecycle creation
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Hard

`LayeredEffectCreated` is the creation side of the `LayeredEffectDestroyed` pair (already documented as Hard). The core challenges are:

1. **Synthetic effect ID allocation** — a match-global counter starting at 7002. No equivalent in Forge.
2. **Effect source tracking** — when an ETB ability fires, the ability instance is on the stack. The annotation's `affectorId` points to that ability instance's zone-27 object. Forge doesn't expose this.
3. **Multi-effect grouping** — a single trigger can create 4+ effects in one GSM. Requires knowing all effects created by a single resolution.
4. **Missing-affectorId cases** — some effects (land type changes from static abilities, game-init effects) have no affectorId. The rule for when to omit it is not fully clear.

This annotation pairs with `LayeredEffect` and `LayeredEffectDestroyed` — all three must be implemented together as a unit.

### Open questions

- What is `affectorId=-3`? Possibly a sentinel for "game rule" or "static ability from a permanent" — seen once at gsId=211 where no obvious stack object exists.
- For static continuous effects (e.g. a permanent always granting a keyword), do `LayeredEffectCreated`/`Destroyed` fire each turn, or only once when the permanent enters? Not fully confirmed from this session (would need turn-boundary tracking).
- Game-init effects at gsId=1 (effects 7002-7004 with affectorIds 55, 56, 57): what are those objects? They appear before any cards are drawn.
