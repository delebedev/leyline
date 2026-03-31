# Conformance Strategy

> Decided 2026-03-31. Replaces proto-based pipeline.

## Principle

**Player.log is the spec.** Arena logs from real server games are the source of truth. Compare per-card annotation traces between arena logs and leyline logs using `scry-ts`. Agents do the comparison — no automated diff engine.

## What drives conformance

**Cards drive everything.** Implement a card → you get its prompts, annotations, actions, game object fields. The card-by-card workflow (card → puzzle → integration test → playtest) handles the happy path.

**Cross-cutting shape conformance is the debt.** Two surfaces:

1. **Annotation shapes** — detail keys, persistence lifecycle, conditional fields. No single card forces DamageDealt's keys to be exactly right. Debt accumulates across card implementations.
2. **Prompt field completeness** — same prompt type (SelectNReq) looks different for discard vs sacrifice vs crew. Card impl makes it work, but optional fields get missed.

## Three tools

### 1. scry-ts trace (exists)

Per-card annotation journey. Agents run this during card-spec work to see what a card produces in arena vs leyline.

```bash
scry-ts trace "Lightning Bolt" --game <arena-game> --json
scry-ts trace "Lightning Bolt" --game <leyline-game> --json
```

### 2. scry-ts variance (to build)

Annotation-type-scoped profiling across saved arena games. Periodic research tool for cross-cutting debt.

```bash
scry-ts variance                          # all annotation types
scry-ts variance --type DamageDealt       # one type
```

Per annotation type: instance count, always/sometimes keys, value samples, persistence lifecycle (created/removed turns), co-type bundles. Compares against leyline's AnnotationBuilder output → MATCH/MISMATCH/NOT_IMPLEMENTED.

### 3. Trace templates (to build)

Committed YAML files in `docs/traces/`. Built from arena log traces. Agents read them as specs when implementing cards or reviewing conformance.

```yaml
# docs/traces/lightning-bolt.yaml
card: Lightning Bolt
grpId: 12345
source: arena game 2026-03-15
annotations:
  - type: ZoneTransfer
    keys: [zone_src, zone_dest, category]
    category: CastSpell
  - type: ZoneTransfer
    keys: [zone_src, zone_dest, category]
    category: Resolve
  - type: DamageDealt
    keys: [damage, type, markDamage]
```

Not machine-checked. Agent-readable reference. Built incrementally as cards are specced.

## What we don't build

- **diff-trace / trace-check** — automated per-card comparison between two logs. Different game states make raw comparison noisy (GSM chunking, persistent annotation accumulation, conditional context). Agents handle structural comparison better.
- **Sequence comparison** — cross-GSM message ordering. Mar 29 analysis confirmed annotations for one action stay in single GSM. Lower priority than originally estimated.
- **Golden baselines from recordings** — retired. Trace templates replace them as the spec artifact.

## Workflow

### Implementing a new card

1. Find arena games with the card: `scry-ts game search "Card Name"`
2. Trace it: `scry-ts trace "Card Name" --game <arena-game> --json`
3. Note annotation types, detail keys, zone transfers in the card spec
4. Implement the card (puzzle + integration test)
5. Trace on leyline: `scry-ts trace "Card Name" --game <leyline-game> --json`
6. Compare traces in card spec — note gaps
7. Optionally commit a trace template for future reference

### Periodic cross-cutting sweep

1. Run `scry-ts variance` across arena games
2. Review MISMATCH and NOT_IMPLEMENTED types
3. File issues or fix inline — small standalone PRs, not card-scoped

## Related

- `relationship-catalog.yaml` — 40 structural invariants extracted from proto tooling
- `rosetta.md` — annotation types, zone IDs, transfer categories, prompt fields
- `catalog.yaml` — mechanic implementation status
- `how-we-conform.md` — scry-ts quick reference and card-centric workflow
