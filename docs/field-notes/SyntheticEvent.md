## SyntheticEvent — field note

**Status:** PARTIALLY IMPLEMENTED — combat path wired, ability/drain path missing
**Instances:** 322 raw lines across 4 sessions (161 unique events — each GSM appears twice, once per seat); 330 claimed across 5 sessions
**Proto type:** AnnotationType.SyntheticEvent (type 72)
**Field:** annotations (transient only — never persistent)

### What it means in gameplay

The server fires this whenever a player's life total is about to decrease — regardless of whether the cause is combat damage, ability damage, or a drain/bleed effect. The client uses it to dispatch a `GameRulesEvent` internally that drives life-loss UI effects (screen flash, life counter animation). It consistently appears immediately before the companion `ModifiedLife` annotation on the same player, making it a "pre-signal" for life loss.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `type` | Always | `1` (only value observed) | Unknown enum — only value ever seen across all 322 instances |

### Shape

- **Type array:** always `["SyntheticEvent"]` (single type, never multi-typed)
- **affectorId:** always present — the instanceId of the source object (creature, ability, or spell on stack)
- **affectedIds:** always 1 element — the seat ID of the player losing life (1 or 2)
- **details:** always `{"type": 1}` — no other value observed across 322 instances

### Triggers

Three distinct trigger scenarios, all resulting in the same `{"type": 1}` wire shape:

**1. Combat damage to player** (most common — ~83% of instances)
- Phase: `Combat/CombatDamage` (or `Combat/FirstStrikeDamage`)
- Companion annotations: `PhaseOrStepModified` + one or more `DamageDealt` (type=1, combat flag) + `SyntheticEvent` + `ModifiedLife`
- `affectorId` = the **first** creature (in annotation order) that dealt player damage in that combat step
- When multiple creatures hit the player in one step, only one SE fires — for the lowest-annotation-ID attacker
- `affectedIds` = the defending player's seat

**2. Ability damage to player** (~10% of instances)
- Phase: varies — `Combat/DeclareAttack` (triggered abilities on attack), `Main1` (ETB abilities), `Beginning/Upkeep`
- Companion annotations: `ResolutionStart` + `DamageDealt` (type=2, **non-combat** flag) + `SyntheticEvent` + `ModifiedLife` + `ResolutionComplete` + `AbilityInstanceDeleted`
- `affectorId` = the **ability instance** ID (same ID as the `ResolutionStart`/`ResolutionComplete` affectorId)
- Cards observed: Raid Bombardment (triggers on each attacker with power ≤2), Mongoose Lizard (ETB deals 1 damage to any target)
- Note: `DamageDealt.type=2` distinguishes ability damage from combat damage (type=1)

**3. Life loss without damage (drain/bleed effects)** (~7% of instances)
- Phase: `Main1`, `Main2`, `Beginning/Upkeep`
- Companion annotations: `ResolutionStart` + *(no DamageDealt)* + `SyntheticEvent` + `ModifiedLife` (negative) + optional `ModifiedLife` (positive, for gain) + `ResolutionComplete` + `AbilityInstanceDeleted`
- `affectorId` = the **ability instance** ID
- SE fires for the losing player; the gaining player's life is tracked in a second `ModifiedLife` only
- Cards observed: Pactdoll Terror ("each opp loses 1 life and you gain 1 life"), Phyrexian Arena ("draw a card and lose 1 life"), Hopeless Nightmare (ETB "each opp discards a card and loses 2 life"), Mephitic Draught (dies → "draw a card and lose 1 life"), Rowan's Grim Search ("draw 2 cards and lose 2 life")

### Phase distribution (all 4 sessions combined)

| Phase/Step | Raw count | % | Notes |
|-----------|-----------|---|-------|
| Combat/CombatDamage | 191 | 59% | Main combat damage path |
| Combat/FirstStrikeDamage | 16 | 5% | First strike damage step |
| Combat/DeclareAttack | 30 | 9% | Raid Bombardment-style triggered abilities |
| Beginning/Upkeep | 20 | 6% | Phyrexian Arena and similar upkeep costs |
| Main1 | 12 | 4% | ETB abilities and main-phase triggers |
| Main2 | 4 | 1% | Late-game ability resolution |
| (no turnInfo) | 49 | 15% | Multi-attacker combat that also kills a creature; `turnInfo` absent from GSM |

### Ordering within GSM

Canonical annotation order when SE fires:
1. `ResolutionStart` (ability triggers only)
2. `DamageDealt` (if applicable — absent for drain effects)
3. `SyntheticEvent`
4. `ModifiedLife` (target player, negative delta)
5. `ModifiedLife` (second player, positive delta — drain effects only)
6. `ResolutionComplete` + `AbilityInstanceDeleted` (ability triggers only)

For combat damage, `PhaseOrStepModified` precedes the entire sequence.

### Cards observed

| Card | grpId | Scenario | Phase | Session |
|------|-------|----------|-------|---------|
| Charmed Stray | 75446 | Combat attacker | Combat/CombatDamage | 2026-03-10_08-23-48 |
| Optimistic Scavenger | 92081 | Combat attacker | Combat/CombatDamage | 2026-03-10_08-23-48 |
| Skeleton Archer | 75502 | Ability (ETB target?) | Main1 | 2026-03-10_08-23-48 |
| Raid Bombardment | 75523/87085 | Triggered on each attacker (power ≤2) | Combat/DeclareAttack | 2026-03-10_08-23-48, 2026-03-15_14-35-58 |
| Mongoose Lizard (ETB) | — | ETB deals 1 damage (ability 173653) | Main1 | 2026-03-10_08-23-48 |
| Pactdoll Terror | 94901 | ETB drain (ability 176471) | Main1 | 2026-03-10_08-23-48 |
| Hopeless Nightmare | 86791 | ETB drain+discard (ability 168806) | (no turnInfo) | 2026-03-10_08-23-48 |
| Phyrexian Arena | 93893 | Upkeep "draw and lose 1 life" (ability 1706) | Beginning/Upkeep | 2026-03-11_16-13-23 |
| Mephitic Draught | 87257 | Dies "draw and lose 1 life" (ability 169572) | Main2 | 2026-03-10_08-23-48 |
| Rowan's Grim Search | 86800 | Spell effect "draw 2 and lose 2 life" | Main1 | 2026-03-10_08-23-48 |

### Our code status

- Builder: exists — `AnnotationBuilder.syntheticEvent(attackerIid, targetSeatId)` at line 384. Takes instanceId + seatId, emits `{"type":1}`. Shape is correct.
- Combat path: **wired** — `AnnotationPipeline.combatAnnotations()` emits SE when `playerDamageSeat != null`. Fires for the first player-damage attacker. Handles multi-attacker case correctly (first in list).
- Ability/drain path: **NOT wired** — `mechanicAnnotations()` has no SE emission. `ModifiedLife` for non-combat life changes is also not emitted yet (both are missing together).
- The `DamageDealtToPlayer` event has a `combat: Boolean` field but the pipeline does not filter by it — all `DamageDealtToPlayer` events reach the SE/ModifiedLife logic regardless of source. This is correct behavior since both combat and ability damage flow through the same event type.

### Dependencies

- **`DamageDealt`** (type 3) — companion for damage-based SE; must share `affectorId` with SE. Combat: type=1 detail. Ability: type=2 detail.
- **`ModifiedLife`** (type 14) — always follows SE immediately, same `affectorId` and `affectedIds`. SE is the pre-signal; ModifiedLife carries the delta.
- **`ResolutionStart`/`ResolutionComplete`** (types 50/51) — bracket ability resolutions that produce SE
- **`GameEvent.DamageDealtToPlayer`** — Forge event that drives SE in the combat path. Already collected via `GameEventCollector`.
- **`GameEvent.LifeChanged`** — Forge event for non-damage life loss. Exists in `GameEvent.kt`. Not yet used for SE or ModifiedLife emission in mechanic pipeline.

### Wiring assessment

**Difficulty:** Low (combat path proves the pattern; ability/drain path needs `mechanicAnnotations` extension)

Combat path is complete and correct. The gap is purely in `mechanicAnnotations` — it needs to:
1. Collect `GameEvent.DamageDealtToPlayer` events where `combat=false` → emit `DamageDealt(type=2)` + `SyntheticEvent` + `ModifiedLife`
2. Collect `GameEvent.LifeChanged` for life-loss events not paired with DamageDealtToPlayer → emit `SyntheticEvent` + `ModifiedLife` (the drain/bleed case)

Both require knowing the `affectorId` — the ability instance ID. This is available from Forge's `GameEventAbilityResolving` / ability source context, which `GameEventCollector` would need to track alongside each damage/life event.

The two-annotation `ModifiedLife` pattern for drain effects (one negative, one positive) is a separate gap in the same function — the life gain side has no SE, only a `ModifiedLife`.

### Open questions

- Is `type=1` in `{"type":1}` the only value that exists in the Arena protocol, or are there unreachable values (e.g., for poison/infect counters or planeswalker damage)?
- For the ability-damage path, does Arena use the ability's instanceId as `affectorId` in `DamageDealt` as well, or does it use the source creature's instanceId? (Observed: ability instanceId — same ID as ResolutionStart.)
- For the no-turnInfo GSMs (multi-creature combat that also kills a blocker via SBA), does the absence of `turnInfo` cause any client rendering difference, or is it a protocol artifact of how Arena batches damage + SBA in a single frame?
