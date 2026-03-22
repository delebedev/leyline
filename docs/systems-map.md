# Systems Map — Protocol Coverage & Gaps

Bird's-eye view of leyline's protocol coverage, organized by player-facing interaction systems. Complements `catalog.yaml` (mechanic-level) and `rosetta.md` (type-level).

**Catalog** = what mechanics work. **Rosetta** = type mappings. **Systems map** = coverage, gaps, priorities.

---

## Systems Taxonomy

Each system is a contract between server and client — a player-facing interaction surface.

| System | What it governs | Status |
|--------|----------------|--------|
| **Turn/Priority** | Phase transitions, priority passing, auto-pass, stops, yields, shouldStop | Functional — fidelity improves as other systems get wired |
| **Casting** | Action generation, cost payment, targets, stack push | Core works; alternative cast paths missing (MDFC, Adventure, Split, Prototype, Room) |
| **Combat** | Attackers, blockers, damage assignment, first-strike step, evasion | Core works; manual damage assignment missing |
| **Stack/Resolution** | LIFO, fizzle, counterspell, triggers, replacement effects | Core works; replacement effect selection missing |
| **Zones/Transfers** | 17 zones, 21 transfer categories, zone change annotations | 14 of 21 categories wired; missing ones are cosmetic (fall back to generic animation) |
| **Continuous Effects** | LayeredEffect, AddAbility, RemoveAbility, ModifiedType/Color/P+T | Partial — P/T tracking works; ability grant tracking missing (no lord effects, no keyword badges) |
| **Targeting/Interaction** | SelectTargets, SelectN, Group, Search, Order, Distribution | Strong — two-phase targeting, search, surveil/scry, modals, optional costs all wired. TargetSpec persistent annotation missing ([#201]) |
| **Mana** | Auto-tap, manual tap, treasure, convoke/improvise | Auto-tap works; manual payment UI (PayCostsReq) not sent |
| **Information** | Reveal, scry, surveil, face-down, hidden information | Partial — scry/surveil work; face-down/morph not wired |
| **Counters/Markers** | +1/+1, loyalty, poison, stun, designations | State tracking works; some annotation polish missing |
| **Settings** | Stops, yields, auto-answers, cosmetics, full-control | Functional; downstream consumer of other systems' correctness |
| **Game Lifecycle** | Connection, mulligan, timer, game-over, post-game, intermission | Complete |

---

## GRE Message Coverage

27 server→client request types documented in the protocol. Coverage status:

### Implemented (19 of 27)

| GRE Type | Value | Notes |
|----------|------:|-------|
| GameStateMessage | 1 | Full + Diff |
| ActionsAvailableReq | 2 | With shouldStop, highlight |
| ChooseStartingPlayerReq | 6 | |
| ConnectResp | 7 | |
| GetSettingsResp / SetSettingsResp | 9/10 | |
| GroupReq | 11 | Surveil, scry |
| MulliganReq | 15 | London mulligan |
| SelectNReq | 22 | Legend rule, discard, sacrifice |
| DeclareAttackersReq | 26 | Two-phase (update + submit) |
| DeclareBlockersReq | 28 | Two-phase |
| SelectTargetsReq | 34 | Two-phase with echo-back re-prompt |
| IntermissionReq | 37 | Post-game |
| CastingTimeOptionsReq | 46 | Modal + kicker/buyback/entwine |
| SearchReq | 44 | Library search with reveal |
| TimerStateMessage | 56 | |
| QueuedGameStateMessage | 51 | |
| PromptReq | 18 | |
| DieRollResultsResp | 38 | |
| UIMessage | 52 | No-op sink |

### Not Implemented (8 of 27)

| GRE Type | Value | What it does | Protocol examples? | Impact |
|----------|------:|-------------|:------------------:|--------|
| **PayCostsReq** | 36 | Composite cost payment UI (mana + convoke + delve). Wraps ActionsAvailableReq + SelectNReq + EffectCostReq + AutoTapActionsReq | Yes — observed frequently | UX — auto-tap handles it engine-side today |
| **AssignDamageReq** | 30 | Manual combat damage distribution across multiple blockers ([#197]) | Yes — observed | Functional — blocks asymmetric damage |
| **OptionalActionMessage** | 45 | "You may..." triggered ability prompts | Yes — few examples | Functional — some triggers |
| **SelectReplacementReq** | 39 | Choose between replacement effects (shocklands, ETB choices) | Not yet observed | Functional — ETB choices |
| **DistributionReq** | 42 | Distribute N among targets (damage, counters) | Not yet observed | Functional — Walking Ballista, etc. |
| **NumericInputReq** | 43 | Choose X value for X-cost spells | Not yet observed | Functional — X spells |
| **OrderReq** | 17 | Order simultaneous triggered abilities on stack | Not yet observed | Low — multiple triggers edge case |
| **PayCostsReq (composite)** | 36 | Complex cost payment with sub-requests | See above | See above |

Others not observed and likely niche: GatherReq (50), SelectNGroupReq (40), SelectFromGroupsReq (48), SearchFromGroupsReq (49), SubmitDeckReq (53, Bo3 sideboarding), StringInputReq (58, "name a card"), SelectCountersReq (59).

---

## Gap Tiers

### Tier 1 — Blocks real gameplay

1. **Alternative cast paths** — Adventure ([#173]), DFC/Saga flip ([#191]), MDFC, Split, Prototype, Room. ActionMapper never emits these action types → player can't cast these cards. Blocks 10+ sets.
2. **Continuous effects / LayeredEffect infra** ([#200]) — EffectTracker only does P/T, not abilities. No AbilityGrantTracker → no keyword badges, no lord effects, no aura grants. Needs design, not just bug fixes.

### Tier 2 — Degrades gameplay noticeably

3. **AssignDamageReq** ([#197]) — manual combat damage distribution. Multi-blocker trample scenarios.
4. **SelectReplacementReq** — choose between replacement effects. Shocklands, ETB choices.
5. **DistributionReq** — distribute damage/counters among targets.
6. **NumericInputReq** — X-cost spells.
7. **ControllerChanged annotation** (type 15) — steal effects don't move permanents visually.
8. **Scry annotation detail keys** — wrong format (topCount vs topIds).

### Tier 3 — Polish

9. Face-down (Morph/Manifest/Disguise) — identity leaks.
10. Phasing visuals.
11. PayCostsReq — manual mana payment UI (auto-tap covers most cases).
12. Missing zone transfer categories — cosmetic animation fallback.
13. Targeting arrow annotations (PlayerSelectingTargets/SubmittedTargets) — cosmetic.

---

## Priority/Yielding Assessment

The priority system is functional (2-layer auto-pass, full-control toggle, phase stops). What's imperfect is fidelity: shouldStop marking on actions, HighlightType values, stack_auto_pass_option interaction.

**Not a standalone work item.** Priority is a downstream consumer — it improves organically as producing systems get wired correctly. When AddAbility works, shouldStop gets set correctly on instant-speed abilities. When alternative casts work, action generation covers more shouldStop cases.

---

## Updating This Document

- Close a gap → update status + move between tiers
- New recording reveals unobserved message type → update "Protocol examples?" column
- New system identified → add to taxonomy table
- Periodic audit: every ~5 PRs, check if tier assignments still hold

[#173]: https://github.com/delebedev/leyline/issues/173
[#191]: https://github.com/delebedev/leyline/issues/191
[#197]: https://github.com/delebedev/leyline/issues/197
[#201]: https://github.com/delebedev/leyline/issues/201
[#200]: https://github.com/delebedev/leyline/issues/200
