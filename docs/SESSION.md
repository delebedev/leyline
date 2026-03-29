# Last Session

**Date:** 2026-03-29
**Branch:** `feat/mechanic-dense-decks`

## What happened

Mass analysis + spec + plan session across 6 FDN bot match recordings.

1. **Recorded 6 games**, ran `recording-inspect` on all — `cards.json`, `analysis.json`, `notes.md` per session
2. **10 card specs** written: mossborn-hydra, cackling-prowler, wildborn-preserver, bite-down, angelic-destiny, day-of-judgment, overrun, sire-of-seven-deaths, temple-garden, hare-apparent
3. **Key wire discoveries:** trample AssignDamageReq player slot = seatId (#235), bounce category = "Return" not "Bounce", OrderReq (GRE 17) for trigger stacking, 7 keyword grpIds confirmed (flying=8, first_strike=6, trample=14, vigilance=15, lifelink=12, reach=13, menace=142), shock land OptionalActionMessage, ImmediateTrigger two-step protocol, Morbid boolean AbilityWordActive
4. **Tooling shipped:** `tape proto show -s <session> <gsId>` (gsId-filtered proto decode), InspectKt C-S frame decode fix, JSONL-is-lossy documented in recordings rules + card-spec skill
5. **5 design specs** written: shock-land-etb, overrun-keyword-grant, morbid, bite-down, angelic-destiny-aura
6. **5 implementation plans** written, ready for execution
7. **SYNTHESIS.md** updated with all 35 card specs cross-cut
8. **Trample #235** commented on issue with full wire evidence

## Implementation plans — execution order

All plans are in `docs/plans/2026-03-29-*.md`. Execute in this order:

1. **Bite Down** (`bite-down.md`) — 30 min. Verification only, no prod code changes needed. Puzzle + test confirming DamageDealt affectorId is already correct.
2. **Morbid** (`morbid.md`) — 1-2 hrs. Extend `AbilityWordScanner` with per-player boolean mode. Add Morbid to CONDITIONS map. New flags: `perPlayer`, `booleanOnly` on ConditionSpec.
3. **Keyword Grant** (`keyword-grant.md`) — 3-4 hrs. Foundational. Forge fork event (`GameEventExtrinsicKeywordAdded`), `KeywordGrpIds` registry, EffectTracker keyword tracking, multi-creature `AddAbility` pAnn, `CardProtoBuilder` extrinsic uniqueAbilities. Start with Layer 0 (data) and work up.
4. **Shock Land** (`shock-land-etb.md`) — 2-3 hrs. Independent of #3. `OptionalActionMessage` round-trip in `WebPlayerController.confirmReplacementEffect()`. CompletableFuture pattern like `pendingDamageAssignment`. Accept=AllowYes, Decline=CancelNo (confirmed via C-S decode).
5. **Angelic Destiny** (`angelic-destiny.md`) — 2-3 hrs. **Depends on #3 landing first.** Packed multi-keyword AddAbility pAnn, ModifiedType tracking, SBA_UnattachedAura detection via aura attachment diffing.

## Key context for implementor

- **JSONL is lossy.** Use `just tape proto show -s <session> <gsId>` for full proto. JSONL is grep-then-read index only.
- **Bounce category bug confirmed.** `AnnotationBuilder` emits "Bounce" for BF→Hand; real server sends "Return". Fix is 1 line in `TransferCategory.kt` — do it as part of any plan touching annotations.
- **Forge fork is ours.** `forge/` submodule is our fork. Adding events to `Card.java` is expected workflow.
- **Card spec agent feedback** (in each spec's `## Agent Feedback` section) has tooling improvement ideas — read if you want to improve the pipeline further.
- **Each plan has a "Risks" section** — read before implementing. Keyword Grant plan's Risk 1 (event timing for `diffKeywords`) is the structural crux.

## Open threads

- Trample fix (#235) — not planned, just do it (add seatId as player slot in `CombatHandler.sendAssignDamageReq`)
- `docs/fdn-playtesting.md` — running findings doc, update after future sessions
- 9 new issues identified in SYNTHESIS.md but not yet filed on GitHub
- Wildborn Preserver / Temple Garden C-S disambiguation / Hare Apparent OrderReq — deferred to future sessions
