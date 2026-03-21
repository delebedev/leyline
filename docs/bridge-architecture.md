# Architecture: Protocol Bridge

> **Date:** 2026-02-13
> **Status:** Design
> **Depends on:** feasibility analysis

## Goal

Let the MTGA client render games powered by Forge's rules engine. Two Arena clients connect to a Forge-backed local server — no WotC infrastructure involved.

## Key Reuse Insight

The existing web port has clean separation between **game orchestration** (bridges, player controller, IGuiGame adapter) and **transport** (WebSocket, JSON, DTOs). The Arena bridge is a second transport head plugging into the same orchestration layer.

The bridges are pure `CompletableFuture` — they don't know or care whether the thing completing them is a WebSocket JSON handler or an Arena protobuf handler.

---

## Architecture

```
forge-game (Java rules engine) ─── UNCHANGED
    │
    ├── PlayerControllerHuman ──── UNCHANGED (157 methods)
    │       │
    │       └── WebPlayerController ── REUSE (overrides chooseSpellAbility,
    │               │                         declareAttackers, etc.)
    │               │
    │               ├── WebGuiGame (IGuiGame) ── REUSE
    │               │
    │               └── Bridges ── REUSE
    │                   ├── GameActionBridge    (CompletableFuture<PlayerAction>)
    │                   ├── InteractivePromptBridge  (CompletableFuture<List<Int>>)
    │                   └── MulliganBridge      (CompletableFuture<Boolean/List<Card>>)
    │
    ├── GameRoom, GameLoopController ── REUSE
    │
    │   ┌─────────────── TWO TRANSPORT HEADS ───────────────┐
    │   │                                                    │
    │   ▼                                                    ▼
    │ [EXISTING: Web UI]                          [NEW: Arena Protocol]
    │ WebSocketHandler                            ArenaMatchHandler
    │ WsMessage (JSON)                            ClientToGREMessage (protobuf)
    │ GameStateMapper → GameStateDto              ArenaStateMapper → GameStateMessage
    │ Ktor WebSocket                              TLS TCP :30003
    │ Browser (Svelte)                            Arena Client (Unity)
    │                                             + FrontDoorService :30010
    └─────────────────────────────────────────────────────────┘
```

**Critical:** the Arena path skips our DTOs entirely. `Game → GameStateDto → GameStateMessage` is an unnecessary round-trip. Go `Game → GameStateMessage` directly via `ArenaStateMapper`.

---

## What's Reused vs. What's New

### Reused (transport-agnostic)

| Component | Role |
|---|---|
| `GameActionBridge` | Block engine at priority, unblock on player action |
| `InteractivePromptBridge` | Block engine on choices (targeting, sacrifice, scry) |
| `MulliganBridge` | Block engine on keep/mulligan/tuck |
| `WebPlayerController` | Overrides `chooseSpellAbilityToPlay`, combat, etc. |
| `WebGuiGame` | IGuiGame adapter routing to bridges |
| `GameRoom` | Per-game state container (seats, bridges, controller) |
| `GameLoopController` | Daemon thread management, shutdown |

### New (Arena-specific)

| Component | Role |
|---|---|
| `ArenaServer` | Netty TLS TCP on :30003 + :30010 |
| `FrontDoorService` | Minimal auth replay (login → session → match config) |
| `ArenaMatchHandler` | `ClientToGREMessage` → `bridge.submitAction()` |
| `ArenaStateMapper` | `Game` → `GameStateMessage` (direct, no DTO hop) |
| `ArenaActionMapper` | `ClientToGREMessage` → `PlayerAction` |
| `CardIdentityTable` | grpId ↔ card name (Scryfall-sourced) |
| Compiled proto defs | From public `messages.proto` (riQQ/MtgaProto) |

---

## Package Location

New package within `forge-web`, not a new Maven module:

```
forge-web/src/main/kotlin/forge/web/arena/
  ArenaServer.kt
  FrontDoorService.kt
  ArenaMatchHandler.kt
  ArenaStateMapper.kt
  ArenaActionMapper.kt
  CardIdentityTable.kt
```

Rationale: the Arena handler imports and uses the existing bridges directly. A separate module would need to depend on `forge-web` internals anyway.

---

## Connection Flow

```
Arena Client                    Forge Private Server
     │                                │
     ├──TLS TCP :30010───────────────►│ FrontDoorService
     │  "credentials"                 │  → "auth success"
     │  "queue for match"             │  → "match found: localhost:30003"
     │                                │
     ├──TLS TCP :30003───────────────►│ ArenaMatchHandler
     │  ConnectReq                    │  → ConnectResp
     │  (engine starts)               │  → GameStateMessage (initial state)
     │                                │
     │  ◄──ActionsAvailableReq────────│  (bridge blocks on player action)
     │  ──PerformActionResp──────────►│  (bridge completes, engine resumes)
     │  ◄──GameStateMessage───────────│  (new state broadcast)
     │                                │
     │  ... game continues ...        │
```

## Sources

- [riQQ/MtgaProto](https://github.com/riQQ/MtgaProto) — Public `.proto` definitions
- [MTGate/MTGate](https://github.com/MTGate/MTGate) — Prior art for login + match protocol flow
