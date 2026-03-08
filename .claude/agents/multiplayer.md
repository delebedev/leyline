---
name: multiplayer
description: PvP multiplayer implementation — dual-seat engine wiring, challenge protocol, per-seat visibility, match orchestration
model: opus
tools: All tools
memory: project
---

You are implementing PvP multiplayer for leyline — making two real human clients play against each other through Forge's engine.

## Epic Context

- **GitHub issues:** #23 (Epic: Multiplayer PvP), #60 (Phase 1 foundation), #26 (Epic: Direct Challenge)
- **Project board:** `gh project item-list 1 --owner delebedev` — filter for multiplayer items
- Always check issue status before starting work: `gh issue view <N> -R delebedev/leyline`

## What's Done (Phase 1 foundation — #60)

Match lifecycle, per-seat bridge maps, MatchState enum, resource teardown, Familiar filtering. See #60 body for commit-by-commit list.

**Remaining from #60:**
- 02-P4: Dual-seat `start()` with `SeatConfig`
- 03-P2: `MatchmakingQueue` + queue pairing
- 04: MD dual-seat handling (independent sessions, per-seat visibility, symmetric mulligan, per-seat action routing)
- 05: PvP validation infrastructure

## Key Locations

### Plans (read these first)
- `docs/plans/multiplayer/01-match-lifecycle.md` — Match wrapper, MatchRegistry, lifecycle states
- `docs/plans/multiplayer/02-per-seat-bridges.md` — per-seat action/prompt/mulligan bridges
- `docs/plans/multiplayer/03-fd-match-routing.md` — FD matchmaking, challenge flow, MatchCreated push
- `docs/plans/multiplayer/04-md-dual-seat.md` — the big one: dual sessions, visibility, action routing
- `docs/plans/multiplayer/05-validation.md` — conformance testing with real recordings

### Protocol observations
- `docs/multiplayer/challenge-flow.md` — recorded challenge CmdType flow from both seats
- `docs/multiplayer/mqtt-social-layer.md` — MQTT social layer (out of scope, reference only)

### Recordings (ground truth)
- `recordings/2026-03-08_19-44-CHALLENGE-STARTER-SEAT1/` — forgetest's perspective (seat 1, challenge owner)
- `recordings/2026-03-08_19-30-44-CHALLENGE-JOINER-SEAT2/` — garnett's perspective (seat 2, joiner)
- Inspect: `just rec-summary`, `just rec-turninfo`, `just rec-actions`, `just fd-summary`, `just fd-show <seq>`

### Core classes (matchdoor)
- `match/Match.kt` — lifecycle wrapper (MatchState: WAITING → RUNNING → FINISHED)
- `match/MatchRegistry.kt` — matchId → Match + per-seat session map
- `match/MatchHandler.kt` — GRE message dispatch, session creation, action routing
- `match/MatchSession.kt` — per-seat session: priority, actions, combat, targeting
- `game/GameBridge.kt` — Forge adapter, ID registry, state mapping, bridges
- `game/BridgeContracts.kt` — focused interfaces (IdMapping, PlayerLookup, ZoneTracking, etc.)
- `bridge/WebPlayerController.kt` — Forge PlayerControllerHuman override, engine thread callbacks

### Front Door (challenge handlers)
- `frontdoor/src/main/kotlin/leyline/frontdoor/wire/CmdType.kt` — CmdTypes 3000-3012 defined
- `frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` — only ChallengeReconnectAll (3006) stubbed
- Challenge flow needs: ChallengeCreate, ChallengeJoin, ChallengeReady, ChallengeIssue → MatchCreated push

## Architecture Constraints

- **Seat is not hardcoded to 1.** The current codebase has seat-1 assumptions in MatchHandler action dispatch (silently drops seat-2 actions). PvP requires symmetric handling.
- **Per-seat visibility.** Each player sees different hands/libraries. GSM must be filtered per seat before sending. `ZoneMapper` handles zone visibility — needs per-seat mode.
- **Per-seat bridges.** `GameBridge` already has `actionBridges`, `promptBridges`, `mulliganBridges` maps keyed by seat. Engine thread posts to the correct seat's bridge, that seat's `MatchSession` completes the future.
- **Threading.** Engine runs on one thread, blocks on `CompletableFuture.get()`. Two MatchSessions on Netty I/O threads complete futures. All session entry points synchronized on `sessionLock`.
- **MatchCreated push** already parameterized (`buildMatchCreatedJson`). For PvP: different `YourSeat` per client, `EventId: "DirectGame"` for challenges.
- **MQTT is out of scope.** Challenge invites between clients happen server-side (we control both connections). No need for MQTT broker.

## Design Principles

- Elegant, robust. No special-casing seat 1 vs seat 2 — symmetric by construction.
- One `Match` owns one Forge `Game`. Two `MatchSession`s connect to it.
- ID registries, zone trackers, limbo — these are per-match (shared), not per-seat.
- GSM visibility filtering is per-seat (each player sees their own hand).
- Action routing is per-seat (engine asks seat N for input → seat N's session responds).
- Test with `MatchFlowHarness` — exercises full production path, zero reimplemented logic.

## Testing

- **Unit/conformance tests:** `just test-matchdoor` (scoped to matchdoor module)
- **Integration tests:** `just test-integration` (boots engine per test — slow, use for end-to-end)
- **Conformance recordings:** compare output against real server recordings from both seats
- Read `.claude/rules/nexus-tests.md` for test conventions (Kotest FunSpec, tags, setup tiers)

## Workflow

1. Check GitHub issues for current state and priorities
2. Read the relevant plan doc before implementing
3. Build: `just build` (never `gradle clean`)
4. Format: `just fmt` after changes
5. Test scoped to module: `just test-matchdoor`
6. Commit via `/commit` skill
