# Plan: Bridge Timeout Diagnostics

**Status:** implemented
**Scope:** forge-web only (GameActionBridge, InteractivePromptBridge)

## Problem

Bridge timeout exception says "timeout waiting for action" — nothing else. Debugging requires guessing where the engine thread is stuck. Burns 15-30 min per incident.

## Design

### New: `BridgeTimeoutDiagnostic` (shared utility)

Location: `forge-web/src/main/kotlin/forge/web/game/BridgeTimeoutDiagnostic.kt`

Single object with one public method:

```kotlin
fun buildMessage(
    bridgeName: String,
    timeoutMs: Long,
    game: Game?,
    engineThread: Thread?,
    lastContext: String,  // bridge-specific: pending action state or prompt request
): String
```

Returns structured multi-line string:

```
Bridge timeout after 5000ms (GameActionBridge)
Phase: COMBAT_DECLARE_ATTACKERS | Active: Player1 | Priority: Player1
Stack: 1 item (Llanowar Elves)
Last posted: PendingAction(id=47, phase=COMBAT_DECLARE_ATTACKERS)
Engine thread:
  at forge.game.phase.PhaseHandler.mainLoopStep(PhaseHandler.java:312)
  at forge.game.phase.PhaseHandler.mainGameLoop(PhaseHandler.java:198)
  ...
```

### Changes to `GameLoopController`

Expose `getEngineThread(): Thread?` — returns the daemon thread ref. Already stored as `private var gameThread`.

### Changes to `GameActionBridge`

- Add `@Volatile var game: Game?` and `@Volatile var engineThread: Thread?` — set by caller at construction or via setter.
- On `TimeoutException` in `awaitAction()`: call `BridgeTimeoutDiagnostic.buildMessage()`, log the full diagnostic, include in the warn message.
- No changes to happy path. No changes to timeout values.

### Changes to `InteractivePromptBridge`

- Same: add `game` and `engineThread` volatile fields.
- On `TimeoutException` in `requestChoice()`: call diagnostic, log it.

### Wiring

`GameLoopController` already receives both bridges. After `launchGameThread`, set `bridge.game = game` and `bridge.engineThread = gameThread` on each bridge. This is the natural place — the controller owns both the thread and the game.

BridgeFuzz's `captureGameThreadTrace` uses reflection to access `gameThread`. After this change, it can use the public getter instead.

### Stack trace capture

Reuse the pattern from `BridgeFuzz.captureGameThreadTrace()`:
```kotlin
thread.stackTrace.joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
```

No reflection needed — thread reference is passed directly.

## Tests

Two unit tests in `forge-web`, group `unit`:

1. **GameActionBridge timeout diagnostic** — create bridge with 50ms timeout, set game + engineThread references, call `awaitAction()` without submitting. Assert exception log contains "Phase:", "Engine thread:", "Stack:".

2. **InteractivePromptBridge timeout diagnostic** — same pattern: 50ms timeout, never respond to `requestChoice()`. Assert diagnostic content in prompt history record.

Both tests are pure-unit (use a real Game from `constructedGameWithLoop` but only need the game object for phase info, not a full running loop).

## Not doing

- No timeout value changes
- No threading model changes
- No happy-path logging
- No forge-nexus changes (purely forge-web infrastructure)
