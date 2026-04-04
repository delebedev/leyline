# Hickey Review — Open Findings

## matchdoor

### Open

**R2-3: TargetingHandler inline message construction** (2026-03-22, medium)
Six methods build GRE messages inline — proto builders, ID resolution, GSM diffs. This is BundleBuilder/PromptBuilder work. TargetingHandler should be purely state machine + protocol dispatch.

Extraction targets: `sendCastingTimeOptionsReq`, `sendSelectTargetsReq`, `sendSelectNReq`, `sendGroupReqForSurveilScry` (~70 LOC, worst), `sendSearchReq`, plus one echo-back pattern possibly belonging in CombatHandler.

Compounds with BundleBuilder already being ~1200 LOC — consider splitting diff bundles (stay in BundleBuilder) from prompt bundles (new PromptBuilder or existing RequestBuilder) as part of the same pass.

Bead: leyline-bsq

**R2-6a: Event flag consumption in collector** (2026-03-22, low)
`GameEventCollector.isSearchedToHand()` and `isLegendRuleVictim()` consume one-shot bridge flags during event collection. Correlation is essential; consuming flags in the event visitor complects event translation with bridge-state management. Could move to annotation pipeline (GATHER phase).

Risk: flag consumed but event dropped (or vice versa), non-reproducible due to one-shot nature. Hasn't materialized. Revisit if a flag-dependent mechanic bug surfaces.

**R2-1: setActivatingPlayer mutation in match/** (2026-03-22, low)
`TargetingHandler.checkOptionalCosts` calls `sa.setActivatingPlayer()` through `getGame()` — a mutation in match/ code. Narrow, but breaks the "reads only" contract. 10 call sites across 4 files in Forge make isolated fix inconsistent. Defer until broader bridge method for "evaluate cost with player context."

### Deferred (not current)

**R1-5: onPerformAction decompose** (2026-03-21, low)
Stable, well-commented. Refactor when it next needs modification.

**R2-2: drainEvents → cursor model** (2026-03-22, low)
Correct for 1vAI. PvP prerequisite. Design cursor model when PvP work starts.

### Resolved

- R1-1 buildFromGame gather/compute/apply phases — done (2026-03-21)
- R1-2 GameBridge split — won't do, correctly deferred (2026-03-21)
- R1-3 Pipeline pure overloads — done (2026-03-21)
- R1-4 MessageCounter explicit construction — done, PR #147 (2026-03-21)
- R1-6 GsmFrame value type — done (2026-03-21)
- R2-4 BundleBuilder object→class — done (2026-03-22)
- R2-5 Purity audit — confirmed holding (2026-03-22)
- R2-6b revealLibraryForSeat temporal coupling — done, field removed (2026-03-22)
