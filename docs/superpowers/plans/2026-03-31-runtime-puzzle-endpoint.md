# Runtime Puzzle Endpoint — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static `[game].puzzle` TOML config with a single runtime debug API endpoint (`POST /api/puzzle`) that both sets the puzzle for the next Sparky match AND hot-swaps mid-match.

**Architecture:** Add an `AtomicReference<String?>` puzzle holder shared between `LeylineServer` (match ID routing), `PuzzleHandler` (puzzle detection + loading), and `DebugServer` (HTTP endpoint). Remove `GameConfig.puzzle` from TOML. The existing `inject-puzzle` endpoint merges into the new unified endpoint. A `just puzzle <file>` wrapper command provides the CLI ergonomics.

**Tech Stack:** Kotlin (server), Bun/TS (just command wrapper)

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `matchdoor/src/main/kotlin/leyline/match/PuzzleHandler.kt` | Modify | Read puzzle from runtime holder instead of `matchConfig.game.puzzle` |
| `matchdoor/src/main/kotlin/leyline/config/MatchConfig.kt` | Modify | Remove `puzzle` field from `GameConfig` |
| `app/main/kotlin/leyline/infra/LeylineServer.kt` | Modify | Create `AtomicReference<String?>`, pass to PuzzleHandler and DebugServer, update `createMatchId()` |
| `app/main/kotlin/leyline/debug/DebugServer.kt` | Modify | Replace `inject-puzzle` with unified `POST /api/puzzle` (set + inject + clear) |
| `app/main/kotlin/leyline/LeylineMain.kt` | Modify | Wire puzzle holder from DebugServer to LeylineServer |
| `matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt` | Modify | Pass puzzle holder to PuzzleHandler |
| `leyline.toml` | Modify | Remove `#puzzle = ...` line |
| `matchdoor/src/test/kotlin/leyline/conformance/PuzzleHandlerTest.kt` | Modify | Use runtime holder instead of `GameConfig(puzzle=...)` |
| `just/tools.just` | Modify | Add `puzzle` command |
| `CLAUDE.md` | Modify | Update puzzle section |

---

### Task 1: Runtime Puzzle Holder

Introduce the shared state that replaces the static config field.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/PuzzleHandler.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/config/MatchConfig.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt`
- Test: `matchdoor/src/test/kotlin/leyline/conformance/PuzzleHandlerTest.kt`

- [ ] **Step 1: Update PuzzleHandler to accept a puzzle path supplier instead of reading matchConfig**

Replace the `matchConfig.game.puzzle` reads with a `puzzlePath: () -> String?` function parameter. This decouples PuzzleHandler from the config field entirely.

```kotlin
// PuzzleHandler.kt — new constructor signature
class PuzzleHandler(
    private val puzzlePath: () -> String?,
    private val cards: CardRepository?,
    private val registry: MatchRegistry,
) {
    // ...

    fun isPuzzleMatch(matchId: String): Boolean =
        puzzlePath() != null

    // In loadPuzzleForMatch, replace:
    //   val configuredPuzzle = matchConfig.game.puzzle
    // with:
    //   val configuredPuzzle = puzzlePath()
}
```

Remove the `matchConfig` constructor parameter entirely — PuzzleHandler only used it for `game.puzzle`. The `matchConfig` reference in the KDoc preamble should update to describe the runtime holder.

- [ ] **Step 2: Update MatchHandler to pass puzzle supplier to PuzzleHandler**

```kotlin
// MatchHandler.kt — add puzzlePath parameter
class MatchHandler(
    // ... existing params ...
    private val puzzlePath: () -> String? = { null },
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {

    // Change line 73:
    private val puzzleHandler = PuzzleHandler(puzzlePath, cards, registry)
```

- [ ] **Step 3: Remove `puzzle` field from GameConfig**

In `MatchConfig.kt`:
- Remove `val puzzle: String? = null` from `GameConfig`
- Remove the validation block `game.puzzle?.let { ... }` from `validate()`
- Remove `game.puzzle?.let { append(" puzzle=$it") }` from `summary()`

- [ ] **Step 4: Update PuzzleHandlerTest to use runtime holder**

```kotlin
// PuzzleHandlerTest.kt — replace all instances of:
//   PuzzleHandler(MatchConfig(game = GameConfig(puzzle = temp.absolutePath)), ...)
// with:
//   PuzzleHandler(puzzlePath = { temp.absolutePath }, ...)
```

There are 3 test sites (lines ~76, ~98, ~151). Update all three.

- [ ] **Step 5: Build and run PuzzleHandlerTest**

Run: `./gradlew :matchdoor:test --tests "leyline.conformance.PuzzleHandlerTest"`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/match/PuzzleHandler.kt \
       matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt \
       matchdoor/src/main/kotlin/leyline/config/MatchConfig.kt \
       matchdoor/src/test/kotlin/leyline/conformance/PuzzleHandlerTest.kt
git commit -m "refactor: PuzzleHandler reads puzzle path from runtime supplier, not config"
```

---

### Task 2: Wire Puzzle Holder Through LeylineServer + DebugServer

Connect the runtime holder to both the match routing and the debug API.

**Files:**
- Modify: `app/main/kotlin/leyline/infra/LeylineServer.kt`
- Modify: `app/main/kotlin/leyline/debug/DebugServer.kt`
- Modify: `app/main/kotlin/leyline/LeylineMain.kt`

- [ ] **Step 1: Add AtomicReference to LeylineServer**

```kotlin
// LeylineServer.kt — add field
import java.util.concurrent.atomic.AtomicReference

class LeylineServer(
    // ... existing params ...
) {
    // Add after debugCollector/gameStateCollector declarations:
    /** Runtime puzzle path — set via debug API, read by PuzzleHandler and createMatchId(). */
    val runtimePuzzle = AtomicReference<String?>(null)
```

- [ ] **Step 2: Update createMatchId() to use runtimePuzzle**

```kotlin
// LeylineServer.kt — replace createMatchId()
private fun createMatchId(eventName: String): String {
    val puzzle = runtimePuzzle.get()
    if (puzzle != null && eventName == "SparkyStarterDeckDuel") {
        return "puzzle-${File(puzzle).nameWithoutExtension}"
    }
    return UUID.randomUUID().toString()
}
```

- [ ] **Step 3: Pass puzzlePath to MatchHandler**

```kotlin
// LeylineServer.kt — in bindMatchDoor(), add puzzlePath to MatchHandler constructor:
MatchHandler(
    matchConfig = matchConfig,
    coordinator = coordinator,
    cards = cardRepo,
    debugSink = DebugSinkAdapter(debugCollector, gameStateCollector),
    puzzlePath = { runtimePuzzle.get() },
)
```

- [ ] **Step 4: Add runtimePuzzle to DebugServer constructor**

```kotlin
// DebugServer.kt — add constructor parameter:
class DebugServer(
    private val port: Int = 8090,
    private val debugCollector: DebugCollector? = null,
    private val gameStateCollector: GameStateCollector? = null,
    private val fdCollector: FdDebugCollector? = null,
    private val eventBus: DebugEventBus? = null,
    /** Runtime puzzle holder — set/cleared by POST /api/puzzle. */
    private val runtimePuzzle: AtomicReference<String?>? = null,
)
```

- [ ] **Step 5: Wire in LeylineMain**

```kotlin
// LeylineMain.kt — in buildDebugServer(), pass the holder:
private fun buildDebugServer(port: Int, server: LeylineServer) = DebugServer(
    port = port,
    debugCollector = server.debugCollector,
    gameStateCollector = server.gameStateCollector,
    fdCollector = server.fdCollector,
    eventBus = server.eventBus,
    runtimePuzzle = server.runtimePuzzle,
)
```

- [ ] **Step 6: Build**

Run: `just build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/main/kotlin/leyline/infra/LeylineServer.kt \
       app/main/kotlin/leyline/debug/DebugServer.kt \
       app/main/kotlin/leyline/LeylineMain.kt
git commit -m "feat: wire runtime puzzle holder through LeylineServer and DebugServer"
```

---

### Task 3: Unified `/api/puzzle` Endpoint

Replace `inject-puzzle` with a unified endpoint that sets the puzzle for next match AND hot-swaps if mid-match.

**Files:**
- Modify: `app/main/kotlin/leyline/debug/DebugServer.kt`

- [ ] **Step 1: Replace inject-puzzle with unified puzzle endpoint**

In `DebugServer.start()`, replace:
```kotlin
srv.postContext("/api/inject-puzzle", ::serveInjectPuzzle)
```
with:
```kotlin
srv.postContext("/api/puzzle", ::servePuzzle)
srv.createContext("/api/puzzle") { ex -> safe(ex) { serveGetPuzzle(ex) } }
```

- [ ] **Step 2: Implement GET /api/puzzle (query current state)**

```kotlin
private fun serveGetPuzzle(ex: HttpExchange) {
    val current = runtimePuzzle?.get()
    respondJson(ex, json.encodeToString(mapOf("puzzle" to current)))
}
```

Add the import for `kotlinx.serialization.builtins.MapSerializer` or just use a string builder — match existing patterns in the file.

- [ ] **Step 3: Implement POST /api/puzzle (set + optional hot-swap)**

The new `servePuzzle` method combines set-puzzle and inject-puzzle logic:

```kotlin
private fun servePuzzle(ex: HttpExchange) {
    val body = ex.requestBody.bufferedReader().readText().trim()
    val params = parseQuery(ex.requestURI.rawQuery)
    val fileParam = params["file"]

    // No file, no body → clear puzzle
    if (fileParam == null && body.isEmpty()) {
        runtimePuzzle?.set(null)
        respond(ex, 200, "text/plain", "Puzzle cleared")
        return
    }

    // Resolve puzzle path
    val puzzlePath = when {
        fileParam != null -> {
            // Resolve to absolute path for runtime holder
            val testResDir = File("matchdoor/src/test/resources/puzzles")
            val pzlFile = File(testResDir, "$fileParam.pzl")
            if (pzlFile.exists()) {
                pzlFile.absolutePath
            } else {
                respond(ex, 404, "text/plain", "Puzzle not found: $fileParam (checked ${pzlFile.absolutePath})")
                return
            }
        }
        else -> null // inline body — no path to store, but can still inject
    }

    // Set runtime puzzle for next match (only when file-based — inline body is inject-only)
    if (puzzlePath != null) {
        runtimePuzzle?.set(puzzlePath)
        log.info("Runtime puzzle set: {}", puzzlePath)
    }

    // If there's an active match, hot-swap immediately
    val session = debugCollector?.sessionProvider?.invoke() as? MatchSession
    val bridge = session?.gameBridge
    if (session != null && bridge != null) {
        // Load and inject puzzle (reuse existing inject logic)
        GameBootstrap.initializeLocalization()
        val puzzle = when {
            body.isNotEmpty() -> PuzzleSource.loadFromText(body, "injected")
            puzzlePath != null -> PuzzleSource.loadFromFile(puzzlePath)
            else -> {
                respond(ex, 400, "text/plain", "No puzzle content")
                return
            }
        }
        val deletedIds = bridge.resetForPuzzle(puzzle)

        // Build and send Full GSM + actions (same as old inject-puzzle)
        val counter = session.counter
        val gsId = counter.nextGsId()
        val msgId = counter.nextMsgId()

        val game = bridge.getGame()!!
        val fullGsm = StateMapper.buildFromGame(
            game, gsId, session.matchId, bridge,
            updateType = GameStateUpdate.SendAndRecord,
            viewingSeatId = session.seatId.value,
        ).gsm

        val gsmWithDeletes = if (deletedIds.isNotEmpty()) {
            fullGsm.toBuilder().addAllDiffDeletedInstanceIds(deletedIds).build()
        } else {
            fullGsm
        }

        val greGsm = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .addSystemSeatIds(session.seatId.value)
            .setGameStateMessage(gsmWithDeletes)
            .build()

        val actions = ActionMapper.buildActions(game, session.seatId.value, bridge)
        val greActions = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(counter.nextMsgId())
            .setGameStateId(gsId)
            .addSystemSeatIds(session.seatId.value)
            .setActionsAvailableReq(actions)
            .build()

        session.sendBundledGRE(listOf(greGsm, greActions))
        bridge.snapshotDiffBaseline(gsmWithDeletes)

        val meta = PuzzleSource.parseMetadata(if (body.isNotEmpty()) body else "")
        val label = fileParam ?: meta.name
        val info = "Puzzle '$label' set + injected gsId=$gsId objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}"
        log.info(info)
        respond(ex, 200, "text/plain", info)
    } else {
        // No active match — just set for next match
        val label = fileParam ?: "inline"
        respond(ex, 200, "text/plain", "Puzzle set: $label (will activate on next Sparky match)")
    }
}
```

- [ ] **Step 4: Delete the old serveInjectPuzzle method**

Remove the entire `serveInjectPuzzle` method (~lines 484-580).

- [ ] **Step 5: Build**

Run: `just build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/main/kotlin/leyline/debug/DebugServer.kt
git commit -m "feat: unified POST /api/puzzle — set for next match + hot-swap if mid-match"
```

---

### Task 4: Clean Up Config and Docs

Remove the dead TOML field and update documentation.

**Files:**
- Modify: `leyline.toml`
- Modify: `CLAUDE.md`
- Modify: `docs/puzzles.md`

- [ ] **Step 1: Remove puzzle from leyline.toml**

Delete these lines from `leyline.toml`:
```toml
# Optional puzzle file. When set, clicking the Sparky bot match tile launches
# this puzzle instead of a normal bot game.
#puzzle = "puzzles/bolt-face.pzl"
```

- [ ] **Step 2: Update CLAUDE.md puzzle section**

Replace the Puzzles section with:
```markdown
## Puzzles

Primary acceptance tool. `.pzl` files define exact board states — minimal cards, one win path, forced mechanics. See `docs/puzzle-driven-dev.md` for the full workflow.

- `just puzzle <file>` — set puzzle via debug API (hot-swaps if in match, queues for next Sparky match)
- `just puzzle-check <file>` — mandatory before commit (missing grpId = NPE)
- `POST :8090/api/puzzle?file=<name>` — runtime API (GET returns current, POST with no params clears)
```

- [ ] **Step 3: Update docs/puzzles.md**

Replace references to `leyline.toml [game].puzzle = ...` with the new `just puzzle <file>` / `POST /api/puzzle` flow.

- [ ] **Step 4: Commit**

```bash
git add leyline.toml CLAUDE.md docs/puzzles.md
git commit -m "docs: remove puzzle TOML config, document runtime API"
```

---

### Task 5: `just puzzle` CLI Wrapper

**Files:**
- Modify: `just/tools.just`

- [ ] **Step 1: Add puzzle command to tools.just**

```just
# Set/inject a puzzle via debug API. Hot-swaps if in match, queues for next Sparky match.
# Usage: just puzzle puzzles/bolt-face.pzl  OR  just puzzle bolt-face
# Clear: just puzzle --clear

[group('tools')]
puzzle file="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ "{{file}}" = "--clear" ] || [ -z "{{file}}" ]; then
        curl -s -X POST "http://localhost:8090/api/puzzle"
        exit 0
    fi
    # Support bare names: "bolt-face" → look in test resources
    f="{{file}}"
    if [ ! -f "$f" ]; then
        f="matchdoor/src/test/resources/puzzles/${f%.pzl}.pzl"
    fi
    if [ ! -f "$f" ]; then
        echo "Puzzle not found: {{file}}" >&2
        exit 1
    fi
    name=$(basename "${f%.pzl}")
    curl -s -X POST "http://localhost:8090/api/puzzle?file=$name"
```

- [ ] **Step 2: Test the command**

Run: `just puzzle bolt-face`
Expected: Either "Puzzle set + injected ..." (if in match) or "Puzzle set: bolt-face (will activate on next Sparky match)"

Run: `just puzzle --clear`
Expected: "Puzzle cleared"

- [ ] **Step 3: Commit**

```bash
git add just/tools.just
git commit -m "feat: just puzzle <file> — CLI wrapper for runtime puzzle API"
```

---

### Task 6: End-to-End Verification

- [ ] **Step 1: Build and start server**

```bash
just build && just serve
```

- [ ] **Step 2: Verify GET /api/puzzle returns null**

```bash
curl -s http://localhost:8090/api/puzzle
```
Expected: `{"puzzle":null}`

- [ ] **Step 3: Set puzzle, start bot match, verify puzzle codepath (no mulligan)**

```bash
just puzzle bolt-face
just arena-ts bot-match
just scry-ts board
```
Expected: Board shows bolt-face puzzle state (Mountain, Lightning Bolt in hand, Runeclaw Bear + Those Who Serve on opponent battlefield). No mulligan phase.

- [ ] **Step 4: Hot-swap to different puzzle**

```bash
just puzzle simple-attack
just scry-ts board
```
Expected: Board changes to simple-attack puzzle state immediately.

- [ ] **Step 5: Clear puzzle**

```bash
just puzzle --clear
curl -s http://localhost:8090/api/puzzle
```
Expected: `{"puzzle":null}`

- [ ] **Step 6: Run test gate**

```bash
./gradlew :matchdoor:testGate
```
Expected: All tests pass.
