# Nexus: Current State

## What Works

- Engine wired end-to-end: auth → mulligan → keep → game loop
- Hand served to client, cards playable
- Mulligan presented (can't re-deal hand yet)
- Turn loop works — Sparky plays cards, applies instants, attacks
- Player can play lands and creatures
- AI cards visible on battlefield (fixed: pendingMessageCount gate bug)
- Phase skip / priority passing — clunky but functional

## Bugs / Short-Term Improvements

- **~~Instant targeting~~** *(fixed 2026-02-25)*: Player can now target creatures AND players (face). `WebPlayerController.selectTargetsInteractively` merges player targets alongside card targets. `StateMapper.buildSelectTargetsReq` emits player seatId as instanceId. `TargetingHandler.onSelectTargets` handles reverse lookup. Verified end-to-end: bolt-to-face on internet match path with puzzle `bolt-face.pzl`. Includes `allowCancel=Abort`, `allowUndo=true`, `highlight: Hot/Cold`, `sourceId`. See `docs/plans/player-targeting.md`.
- **~~Cancel during targeting blocks game~~** *(fixed 2026-02-26)*: `CancelActionReq` (type 5) was unhandled — fell to `else` in `MatchHandler`, engine blocked 30s on prompt timeout. Fix: dispatch `CancelActionReq_097b` → `TargetingHandler.onCancelAction` → submit empty target list → engine unwinds spell (removes from stack, returns mana). Also fixed `sourceId` in `SelectTargetsReq` — was using pre-realloc instanceId (retired hand ID), client couldn't draw targeting arrow. Fix: build diff before req in `selectTargetsBundle` so sourceId uses post-realloc stack instanceId.
- **Stack visuals**: Sparky's instants stuck on stack visually (effect applies correctly to field)
  - *Possibly improved:* if client was stuck waiting for targeting prompt that never came, the instant targeting fix should unstick it. Needs manual client test.
- **Combat targeting**: attack-all somewhat works; declaring blockers targeting doesn't
  - *Partially fixed (2026-02-21):* `defenderPlayerId` now resolved explicitly in `MatchSession.onDeclareAttackers` (was null). Combat damage resolves for unblocked attackers. Blocker declaration path not changed — may still have issues. Verified in `CombatFlowTest` (7 tests). Needs manual client test for blockers.
- **~~Double DeclareBlockersReq stalls client~~** *(fixed 2026-02-21)*: `pendingBlockersSent` flag in `CombatHandler`. Commit `15d4723786`.
- **~~Hand overflow / Cleanup stall~~** *(fixed 2026-02-21)*: Non-targeting prompt auto-resolve in `TargetingHandler.checkPendingPrompt`. Commit `15d4723786`.
- **No PhaseStopProfile → client stalls at non-essential phases**: `GameBridge.start()` creates `WebPlayerController` with no `phaseStopProfile`, so every phase grants priority. Client receives `ActionsAvailableReq` at CombatDamage/CombatEnd/EndOfTurn where real Arena would auto-pass. If client doesn't respond, bridge times out (120s), engine auto-passes silently, client never gets updated state. Confirmed via recording 2026-02-22_10-36-29. Fix: wire `PhaseStopProfile.createDefaults()`. Blocked on `PhaseOrStepModified` annotation regression — see `docs/plans/phase-stop-profile.md`.
- **~~Concede~~** *(fixed 2026-02-26)*: Game-over sequence (3x GSM + IntermissionReq + MatchCompleted room state) now matches real server recordings. Both concede and lethal damage produce the result screen. Key fixes: rich player info in gs1 (PendingLoss + lifeTotal/timers), 2 resultList entries (Game+Match) in MatchCompleted, bridge stays alive after concede (deferred cleanup).
- **~~Cancel attacks button doesn't work~~** *(fixed 2026-02-28)*: `CancelActionReq` during combat now routes to `CombatHandler.onCancelAttackers` which submits empty attacker list to engine (passes combat). `MatchSession.onCancelAction` checks `pendingLegalAttackers` to distinguish combat cancel from targeting cancel.
- **Lands show as playable on opponent's turn**: `ActionMapper.buildNaiveActions` (used during AI turn to populate hand display) puts lands in `actions` list with `canPlay=true` unconditionally. Client highlights them as playable. Fix: naive mode should put lands in `inactiveActions` instead — they're only there for hand rendering, not interaction. Same issue may affect spells appearing castable during wrong phases. *Golden coverage:* `ActionsAvailableReq` golden already documents `inactiveActions[]` fields in `expectedMissing`; fixing naive mode will populate those fields → remove from `expectedMissing` to validate the fix.
- **~~SyntheticEvent annotation crashes client~~** *(fixed 2026-02-27)*: Empty SyntheticEvent (type 72, no affectedIds) spammed every combat GSM → `ArgumentException: Annotation does not contain affected ID` in client's `SyntheticEventAnnotationParser`. Real server sends identical empty shape but different client version handles it. Fix: suppress SyntheticEvent emission (cosmetic combat marker, safe to omit).
- **~~AI's cards render as broken gray placeholders~~** *(fixed)*: Was caused by `grpId=0` on seat 2 objects. Fixed in `ObjectMapper`.
- **Keyword-granting spells don't update visual state**: Mighty Leap applies +2/+2 (P/T change visible) but Flying keyword not reflected on card — no flying icon or floating visual. Likely missing `KeywordModified` or equivalent annotation, or `ObjectMapper` not updating `abilities` field on the card object when keywords are granted temporarily.
- **No combat damage animation**: Creatures attack and block but no visual damage numbers/effects play. `DamageDealt` annotations DO fire (confirmed 2x in recording `2026-02-28_11-50-40`) — issue is annotation shape, not absence. Client's damage animation parser likely needs specific detail keys (amount, type, source) in a particular format. Compare our `DamageDealt` shape against a real server recording.
- **Phase indicators**: glitch where player and AI indicators update in sync

- **Targeting doesn't highlight all valid targets**: `buildSelectTargetsReq` target filtering is too narrow — doesn't always include own-side permanents or non-creature permanents when the engine says they're valid. Two confirmed cases: (1) Mischievous Pup ETB ("return target nonland permanent") shows targeting arrow but no permanents highlighted; (2) Banishing Light ("exile target nonland permanent") — player successfully exiled own Bird Wizard in recording `2026-02-28_11-50-40` but highlight/UX likely incomplete. Root cause: check what `WebPlayerController.selectTargetsInteractively` receives from engine vs what gets emitted in `SelectTargetsReq`.

- **Autotap prefers mana dorks over lands**: Casting Pacifism ({1}{W}) with 4 Plains + Forest + Llanowar Elves on battlefield — engine taps Llanowar Elves instead of a second Plains. Should prefer lands over creature mana sources (tapping a dork has attack/block opportunity cost). Likely a forge-core autotap priority issue (`ComputerUtilMana` / `ManaCostBeingPaid` in `forge-game`), but could also be nexus-side if we're influencing mana source selection during `payCosts()`. Needs investigation.

- **Tapping land passes priority instead of floating mana**: Client sends `PerformActionResp` with `ActivateMana` action type but no inbound handler exists in `MatchHandler` — falls through to generic handler which submits a priority pass. Real Arena flow: player taps lands manually (ActivateMana → float mana → stay at priority), then casts. Forge's flow: auto-taps lands during `SpellAbility.payCosts()` as part of cast. Two approaches: (A) intercept ActivateMana, manually activate mana ability on the Forge card, float mana, stay at priority — needs Forge mana pool integration; (B) keep auto-tap for CastSpell, make ActivateMana a no-op that returns updated state without passing priority. Option A matches real Arena but is non-trivial (Forge mana pool tightly coupled to spell payment). Option B is simpler but breaks manual mana floating.

## Front Door / Lobby

- **~~Card_GetCardSet (551) empty response crashes TitleCountManager + deck editor~~** *(fixed 2026-03-05)*: Was returning `{}` — null `cards` field caused `ArgumentNullException` in `TitleCountManager.BuildTitleCountCache()` and NPE in deck editor's `AddAllCardsFromInventoryToPool`. Fixed: return `{"cacheVersion":-1,"cards":{}}` — empty collection, not null. Deck editor loads (empty collection).

- **~~Deck_GetDeckSummariesV3 (410) deserialization logged as failure~~** *(fixed 2026-03-04)*: V3 `Attributes` field uses a flat dict (`{"Version":"1","Format":"Standard"}`) not the V2 `[{name,value}]` array format. Fixed: `buildDeckSummaryV3Obj` with flat Attributes for 410 handler; V2 array format kept for StartHook.

## Debug Panel

- **Server shutdown kills browser tab**: When the Nexus server is killed (Ctrl+C / `kill`), the debug panel's SSE connection drops. Some browsers (Safari) close the tab entirely on abrupt connection loss. Fix: add `onclose`/`onerror` handler in the SSE client JS that shows a "disconnected" banner instead of letting the browser decide. Or switch from SSE to polling with graceful reconnect.

- **~~Sparky/AI bot path starts in Combat instead of Main1~~** *(2026-02-25, fixed 2026-02-27)*: When launching via Sparky (FD stub creates both seat 1 + seat 2/Familiar), the auto-pass engine advances past Main1 into Combat before the human player's first `ActionsAvailableReq`. Root cause: `MatchSession.onPuzzleStart()` was called for both seats; seat 2's `AutoPassEngine` saw `isAiTurn=true` and consumed seat 1's pending priority action via the shared `ActionBridge`, advancing the engine through multiple turns. **Fixed by guarding `onPuzzleStart()` to only run the auto-pass loop for seat 1** — Familiar (seat 2) is a spectator and must not drive the game loop. Regression test: `PuzzleBridgeTest.seat2OnPuzzleStartDoesNotAdvancePastMain1`.
- **Stale forge-web classes not picked up by `just build`**: `just build` in forge-nexus compiles nexus classes but may skip forge-web recompilation even when forge-web sources changed. Caused a debugging detour where targeting changes in `WebPlayerController` weren't deployed. Workaround: explicit `mvn compile -pl forge-web` or ensure `just build` rebuilds both modules.

## Runtime Crashes

- **GameEventCollector NPE on null source zone** *(2026-02-24)*: `GameEventCardChangeZone.from()` returns null when a card enters a zone from nowhere (e.g. effect/token entering Command zone). `visit(ev: GameEventCardChangeZone)` calls `ev.from().zoneType` unconditionally → NPE. Fix: null-check `ev.from()`, skip or emit generic event when null. Triggered by Reckless Impulse effect entering Command zone.
- **~~Shuffle annotations missing detail keys~~** *(2026-02-24, fixed)*: `AnnotationBuilder.shuffle()` emitted `type=[Shuffle]` with no `details`. Client's `ShuffleAnnotationParser` requires `OldIds`/`NewIds` detail keys → `ArgumentException`. **Fixed by suppressing Shuffle annotations** in `AnnotationPipeline` — cosmetic only (shuffle animation). Caused the `InvalidOperationException: Sequence contains no matching element` that blocked the client at mulligan.
- **Shuffle animation not implemented** *(2026-02-24, LOW)*: Shuffle annotations are suppressed because we don't capture library card order before/after shuffle. To implement: extend `GameEventShuffle`/`LibraryShuffled` to carry pre/post library instanceId lists, then populate `OldIds`/`NewIds` detail keys in `AnnotationBuilder.shuffle()`. Purely cosmetic — client shows no shuffle animation without it, game functions fine.

## Protocol / Proto Bugs

- **~~gameOverBundle missing prevGameStateId~~** *(fixed 2026-02-26)*: `gameOverBundle()` now chains gs1.prev=last, gs2.prev=gs1, gs3.prev=gs2. Rewritten to match real server recording structure.
- **Face-down cards not implemented**: `ObjectMapper.applyCardFields()` never checks `card.isFaceDown` and never sets `isFacedown=true` or overrides `overlayGrpId`. Morph/manifest/disguise cards would leak identity to opponent. Low priority — morph/manifest rarely triggered in current integration.

## TODO

- **Wire ValidatingMessageSink in dev server** *(LOW)*: Run `ValidatingMessageSink(strict=false)` wrapping the production `MessageSink` during `just serve`. Logs invariant violations (gsId monotonicity, prevGsId validity, annotation sequentiality, instanceId consistency) without crashing. Negligible overhead — O(1) per message, no allocations on happy path. Would catch protocol bugs in real-time during playtesting instead of only in tests.
- **Replace SETTLE_MS with proper happens-before sync** *(MEDIUM)*: `GameBridge.awaitPriority()` sleeps 10ms (`SETTLE_MS`) after `PrioritySignal` fires before returning to `MatchSession` for state snapshot. The signal fires when the engine calls `awaitAction()` but in-flight side effects (Guava EventBus → `GameEventCollector` queue) may not have settled yet. Fix: engine thread should signal AFTER all side effects are committed, giving `buildFromGame` a consistent snapshot with no race window. Current 10ms is empirical — could be too short under load or too long for responsiveness.

## Test / Conformance Gaps

- **AnnotationPipelineTest missing category coverage**: Only PlayLand/CastSpell/Resolve/ZoneTransfer have pipeline annotation-shape tests. Missing: Destroy, Sacrifice, Exile, Countered, Bounce, Draw, Discard, Mill. Proto output shape for these categories is untested.
- **inferCategory fallback incomplete**: `AnnotationPipeline.inferCategory()` (proto-level fallback when events unavailable) handles only 5 zone pairs. Missing 8 zone-pair categories that `AnnotationBuilder.zoneChangedCategory` handles: Discard (Hand→GY), Draw (Lib→Hand), Mill (Lib→GY), Bounce (BF→Hand/Lib), Countered (Stack→GY), Exile (Hand→Exile, any→Exile). Low priority — event-driven path covers these correctly.
