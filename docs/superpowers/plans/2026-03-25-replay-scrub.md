# Replay Scrub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add debug API endpoints to step through recorded game sessions frame-by-frame, with Arena rendering each state via the existing `serve-replay` mode.

**Architecture:** Extend existing `ReplayHandler` to pause between GRE frames and accept step commands from a new `ReplayController` interface. Wire `ReplayController` into `DebugServer` via `LeylineServer`. Support both proxy (`capture/payloads/`) and engine (`engine/`) recording formats.

**Tech Stack:** Kotlin, Netty, JDK HttpServer (DebugServer), protobuf

---

### Task 1: Extract ReplayController interface

`ReplayHandler` currently auto-advances on every client message. Extract a control interface so the debug server can drive pacing.

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/match/ReplayController.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/ReplayHandler.kt`

- [ ] **Step 1: Create ReplayController interface**

```kotlin
// matchdoor/src/main/kotlin/leyline/match/ReplayController.kt
package leyline.match

/**
 * Control interface for replay playback, exposed to the debug API.
 * ReplayHandler implements this; DebugServer calls it.
 */
interface ReplayController {
    /** Metadata for a single recorded frame. */
    data class FrameInfo(
        val index: Int,
        val fileName: String,
        val greType: String,
        /** "auth", "room", "gre", "other" */
        val category: String,
    )

    /** Current playback position (0-based index into GRE frames). */
    val currentFrame: Int

    /** Total GRE frames available. */
    val totalFrames: Int

    /** Ordered metadata for all GRE frames. */
    val frameIndex: List<FrameInfo>

    /** Advance to next GRE frame. Returns false if already at end. */
    fun next(): Boolean

    /** Current status snapshot for the API. */
    fun status(): ReplayStatus

    data class ReplayStatus(
        val currentFrame: Int,
        val totalFrames: Int,
        val currentFrameInfo: FrameInfo?,
        val atEnd: Boolean,
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/match/ReplayController.kt
git commit -m "feat(replay): add ReplayController interface for debug API scrubbing"
```

---

### Task 2: Refactor ReplayHandler to implement ReplayController

Change `ReplayHandler` from auto-advance to paused mode. On client GRE messages, wait for a `next()` call instead of immediately popping the next frame.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/ReplayHandler.kt`

- [ ] **Step 1: Add ReplayController implementation to ReplayHandler**

Add these fields and change `handleGre`:

```kotlin
// Add to ReplayHandler class body:

// -- ReplayController state --
private var greFrameIndex: List<ReplayController.FrameInfo> = emptyList()
private var grePosition = 0
@Volatile private var pendingCtx: ChannelHandlerContext? = null

init {
    // ... existing init code ...

    // Build frame index from greEvents after categorization
    greFrameIndex = greEvents.mapIndexed { i, cp ->
        val greType = cp.parsed?.greToClientEvent
            ?.greToClientMessagesList
            ?.firstOrNull()?.type?.name
            ?: "Unknown"
        ReplayController.FrameInfo(
            index = i,
            fileName = cp.fileName,
            greType = greType,
            category = "gre",
        )
    }
}
```

Replace `handleGre` — instead of auto-popping the next frame, store the context and wait:

```kotlin
private fun handleGre(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
    val greMsg = ClientToGREMessage.parseFrom(msg.payload)
    log.info("Replay: GRE from client type={} seat={}", greMsg.type, greMsg.systemSeatId)
    // Store context for next() to use — client is waiting for the next server frame
    pendingCtx = ctx
}
```

Implement `ReplayController`:

```kotlin
override val currentFrame: Int get() = grePosition
override val totalFrames: Int get() = greFrameIndex.size
override val frameIndex: List<ReplayController.FrameInfo> get() = greFrameIndex

override fun next(): Boolean {
    val ctx = pendingCtx ?: return false
    val cp = popGreForSeat() ?: return false
    sendPatchedGre(ctx, cp)
    grePosition++
    return true
}

override fun status() = ReplayController.ReplayStatus(
    currentFrame = grePosition,
    totalFrames = greFrameIndex.size,
    currentFrameInfo = greFrameIndex.getOrNull(grePosition),
    atEnd = grePosition >= greFrameIndex.size,
)
```

- [ ] **Step 2: Keep auto-advance for auth and connect**

The `handleAuth` and `handleConnect` methods stay as-is — they auto-respond during the connection handshake. Only GRE frames (the actual game) are paused.

But `handleConnect` currently also pops the first GRE frame. Change it to just send the room state, then wait:

```kotlin
private fun handleConnect(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
    // ... existing matchId/seatId parsing ...
    log.info("Replay: ConnectReq matchId={} seat={}", matchId, seatId)

    val roomState = matchRoomStates.removeFirstOrNull()
    if (roomState?.parsed != null) {
        sendProto(ctx, patchMatchId(roomState.parsed), "roomState(patched)")
    }

    // Store ctx — first GRE frame waits for next() call
    pendingCtx = ctx
}
```

- [ ] **Step 3: Add class declaration change**

```kotlin
class ReplayHandler(
    private val payloadDir: File,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>(), ReplayController {
```

- [ ] **Step 4: Build and verify compilation**

Run: `cd /Users/denis/src/leyline && ./gradlew :matchdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/match/ReplayHandler.kt
git commit -m "refactor(replay): implement ReplayController — pause GRE frames for API-driven scrubbing"
```

---

### Task 3: Wire ReplayController into LeylineServer and DebugServer

Expose the `ReplayController` from `LeylineServer` so `DebugServer` can call `next()`/`status()`.

**Files:**
- Modify: `app/main/kotlin/leyline/infra/LeylineServer.kt`
- Modify: `app/main/kotlin/leyline/LeylineMain.kt`
- Modify: `tooling/src/main/kotlin/leyline/debug/DebugServer.kt`

- [ ] **Step 1: Expose ReplayController from LeylineServer**

Add field to `LeylineServer`:

```kotlin
// After the existing debug infrastructure fields:
/** Replay controller — non-null only in replay mode. Set during startReplay(). */
var replayController: ReplayController? = null
    private set
```

In `startReplay()`, after creating the `ReplayHandler`, store it:

```kotlin
// In startReplay(), after line 315 (the ReplayHandler creation):
val replayHandler = ReplayHandler(dir)
replayController = replayHandler

// Use replayHandler in the pipeline:
ch.pipeline().addLast("handler", replayHandler)
```

Note: `ReplayHandler` is created per-connection (inside the `bindServer` lambda). But replay mode only has one connection. Store the controller from the first connection:

```kotlin
matchDoorChannel = bindServer(mdSsl, matchDoorPort, "MatchDoor-Replay") { ch ->
    ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
    ch.pipeline().addLast("headerStripper", ClientHeaderStripper())
    ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
    ch.pipeline().addLast("headerPrepender", ClientHeaderPrepender())
    val handler = ReplayHandler(dir)
    if (replayController == null) replayController = handler
    ch.pipeline().addLast("handler", handler)
}
```

- [ ] **Step 2: Pass ReplayController to DebugServer**

In `LeylineMain.kt`, update `buildDebugServer`:

```kotlin
private fun buildDebugServer(port: Int, server: LeylineServer) = DebugServer(
    port = port,
    debugCollector = server.debugCollector,
    gameStateCollector = server.gameStateCollector,
    fdCollector = server.fdCollector,
    eventBus = server.eventBus,
    recordingInspector = server.recordingInspector,
    replayController = { server.replayController },
)
```

Add constructor parameter to `DebugServer`:

```kotlin
class DebugServer(
    private val port: Int = 8090,
    private val debugCollector: DebugCollector? = null,
    private val gameStateCollector: GameStateCollector? = null,
    private val fdCollector: FdDebugCollector? = null,
    private val eventBus: DebugEventBus? = null,
    private val recordingInspector: RecordingInspector = RecordingInspector(),
    private val replayController: () -> ReplayController? = { null },
) {
```

Using a lambda because the controller is set lazily (after client connects).

- [ ] **Step 3: Build and verify compilation**

Run: `cd /Users/denis/src/leyline && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/main/kotlin/leyline/infra/LeylineServer.kt \
       app/main/kotlin/leyline/LeylineMain.kt \
       tooling/src/main/kotlin/leyline/debug/DebugServer.kt
git commit -m "feat(replay): wire ReplayController from LeylineServer to DebugServer"
```

---

### Task 4: Add debug API replay endpoints

Add `/api/replay/status`, `/api/replay/next`, `/api/replay/index` endpoints to `DebugServer`.

**Files:**
- Modify: `tooling/src/main/kotlin/leyline/debug/DebugServer.kt`

- [ ] **Step 1: Add replay endpoints to the route map**

In `DebugServer.start()`, add to the endpoint map:

```kotlin
"/api/replay/status" to ::serveReplayStatus,
"/api/replay/index" to ::serveReplayIndex,
```

And add a POST handler alongside the existing `inject-full` pattern:

```kotlin
srv.createContext("/api/replay/next") { ex ->
    safe(ex) {
        if (ex.requestMethod != "POST") {
            ex.sendResponseHeaders(405, -1)
            ex.close()
            return@safe
        }
        serveReplayNext(ex)
    }
}
```

- [ ] **Step 2: Implement the handler methods**

```kotlin
@Serializable
private data class ReplayStatusDto(
    val currentFrame: Int,
    val totalFrames: Int,
    val currentGreType: String?,
    val currentFileName: String?,
    val atEnd: Boolean,
    val active: Boolean,
)

private fun serveReplayStatus(ex: HttpExchange) {
    val ctrl = replayController()
    if (ctrl == null) {
        respondJson(ex, json.encodeToString(ReplayStatusDto.serializer(),
            ReplayStatusDto(0, 0, null, null, true, active = false)))
        return
    }
    val s = ctrl.status()
    respondJson(ex, json.encodeToString(ReplayStatusDto.serializer(), ReplayStatusDto(
        currentFrame = s.currentFrame,
        totalFrames = s.totalFrames,
        currentGreType = s.currentFrameInfo?.greType,
        currentFileName = s.currentFrameInfo?.fileName,
        atEnd = s.atEnd,
        active = true,
    )))
}

private fun serveReplayNext(ex: HttpExchange) {
    val ctrl = replayController()
    if (ctrl == null) {
        respond(ex, 404, "text/plain", "No active replay")
        return
    }
    val advanced = ctrl.next()
    val s = ctrl.status()
    respondJson(ex, json.encodeToString(ReplayStatusDto.serializer(), ReplayStatusDto(
        currentFrame = s.currentFrame,
        totalFrames = s.totalFrames,
        currentGreType = s.currentFrameInfo?.greType,
        currentFileName = s.currentFrameInfo?.fileName,
        atEnd = s.atEnd,
        active = true,
    )))
}

@Serializable
private data class ReplayFrameDto(
    val index: Int,
    val fileName: String,
    val greType: String,
    val category: String,
)

private fun serveReplayIndex(ex: HttpExchange) {
    val ctrl = replayController()
    if (ctrl == null) {
        respondJson(ex, "[]")
        return
    }
    val frames = ctrl.frameIndex.map { f ->
        ReplayFrameDto(f.index, f.fileName, f.greType, f.category)
    }
    respondJson(ex, json.encodeToString(frames))
}
```

- [ ] **Step 3: Add respondJson helper if not already present**

Check if `DebugServer` already has a `respondJson` — it likely uses `respond(ex, 200, "application/json", body)`. Use whichever pattern exists.

- [ ] **Step 4: Build and verify compilation**

Run: `cd /Users/denis/src/leyline && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add tooling/src/main/kotlin/leyline/debug/DebugServer.kt
git commit -m "feat(replay): add /api/replay/status, /next, /index debug endpoints"
```

---

### Task 5: Support engine-format recordings

The existing `ReplayHandler` only loads from proxy-format `capture/payloads/` dirs (files named `S-C_MATCH*.bin`). Engine recordings use `engine/` dir with files like `007-GameStateMessage.bin`. Add format detection and loading for both.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/ReplayHandler.kt`

- [ ] **Step 1: Add format detection in init**

Replace the file loading in `init` to support both formats:

```kotlin
init {
    val engineDir = File(payloadDir, "engine")
    val payloadsDir = File(payloadDir, "capture/payloads")

    val allFiles = when {
        // Engine format: NNN-GREType.bin files directly in engine/
        engineDir.isDirectory -> {
            log.info("Replay: detected engine format in {}", engineDir)
            engineDir.listFiles()
                ?.filter { it.extension == "bin" && !it.name.contains("AuthResp") }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
        // Proxy format: S-C_MATCH_DATA.bin files in capture/payloads/
        payloadsDir.isDirectory -> {
            log.info("Replay: detected proxy format in {}", payloadsDir)
            payloadsDir.listFiles()
                ?.filter { it.name.startsWith("S-C_MATCH") }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
        // Direct payloads dir (legacy: --replay points at payloads/ directly)
        else -> {
            log.info("Replay: loading from {}", payloadDir)
            payloadDir.listFiles()
                ?.filter { it.name.startsWith("S-C_MATCH") }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }

    // ... rest of categorization unchanged ...
}
```

- [ ] **Step 2: Handle engine format auth files separately**

Engine recordings have `NNN-AuthResp.bin` files that are `MatchServiceToClientMessage` with auth responses. Load them into `authResponses`:

```kotlin
// After format detection, if engine format:
if (engineDir.isDirectory) {
    val authFiles = engineDir.listFiles()
        ?.filter { it.name.contains("AuthResp") && it.extension == "bin" }
        ?.sortedBy { it.name }
        ?: emptyList()
    for (file in authFiles) {
        val bytes = file.readBytes()
        val parsed = try { MatchServiceToClientMessage.parseFrom(bytes) } catch (_: Exception) { null }
        if (parsed != null) auths.add(CapturedPayload(file.name, bytes, parsed))
    }
}
```

- [ ] **Step 3: Update justfile payloads default**

The `just serve-replay` target currently defaults `payloads` to `recordings/latest/capture/payloads`. Change it to point at the recording root so format detection works:

In `justfile`, change line 18:
```
payloads     := env("LEYLINE_PAYLOADS", project_dir / "recordings/latest")
```

And update the `serve-replay` recipe (line 293) — the `--replay` flag now takes a recording dir, not a payloads dir.

- [ ] **Step 4: Build and verify**

Run: `cd /Users/denis/src/leyline && ./gradlew :matchdoor:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/match/ReplayHandler.kt justfile
git commit -m "feat(replay): support engine-format recordings alongside proxy format"
```

---

### Task 6: Smoke test the full flow

Manual integration test: start replay mode, connect Arena, step through frames via curl.

**Files:**
- No code changes — verification only

- [ ] **Step 1: Build**

Run: `cd /Users/denis/src/leyline && just build`

- [ ] **Step 2: Pick a recording with frames**

```bash
# Find a recording with engine frames
ls recordings/*/engine/*.bin | head -5
# Or proxy frames
ls recordings/*/capture/payloads/S-C_MATCH* 2>/dev/null | head -5
```

- [ ] **Step 3: Start replay mode**

```bash
just serve-replay
# or with explicit dir:
# LEYLINE_PAYLOADS=recordings/2026-03-24_07-55-14 just serve-replay
```

Expected: Banner shows `(replay mode)`, logs show frame count.

- [ ] **Step 4: Check replay status before Arena connects**

```bash
curl -s http://localhost:8090/api/replay/status | python3 -m json.tool
```

Expected: `{"active": false, ...}` (no controller yet — Arena hasn't connected)

- [ ] **Step 5: Connect Arena**

Launch Arena, enter Sparky match flow. Arena should connect and sit at the duel scene loading screen (waiting for first GRE frame).

- [ ] **Step 6: Check status after connect**

```bash
curl -s http://localhost:8090/api/replay/status | python3 -m json.tool
```

Expected: `{"active": true, "currentFrame": 0, "totalFrames": N, "atEnd": false}`

- [ ] **Step 7: Step through frames**

```bash
# Step forward
curl -s -X POST http://localhost:8090/api/replay/next | python3 -m json.tool

# Repeat — watch Arena render each state
curl -s -X POST http://localhost:8090/api/replay/next | python3 -m json.tool
```

Expected: Arena renders each game state as frames are fed. Status shows advancing frame number.

- [ ] **Step 8: Check frame index**

```bash
curl -s http://localhost:8090/api/replay/index | python3 -m json.tool
```

Expected: Array of `{index, fileName, greType, category}` for all GRE frames.

- [ ] **Step 9: Step to end**

```bash
# Quick loop to end
while curl -s -X POST http://localhost:8090/api/replay/next | grep -q '"atEnd":false'; do sleep 0.5; done
```

Expected: `atEnd: true` when all frames exhausted.

---

### Task 7: Update docs

**Files:**
- Modify: `docs/debug-api.md`

- [ ] **Step 1: Add replay section to debug-api.md**

Add after the "Recording introspection" section:

```markdown
## Replay control

Available when running `just serve-replay`. Controls frame-by-frame playback of recorded sessions through the Arena client.

- `GET /api/replay/status` — current playback position, frame metadata, active state
- `POST /api/replay/next` — advance one GRE frame (returns updated status)
- `GET /api/replay/index` — ordered metadata for all GRE frames in the loaded recording

### Usage

```bash
# Start replay mode
just serve-replay

# Connect Arena (Sparky match flow)
# Then step through:
curl -s -X POST http://localhost:8090/api/replay/next | python3 -m json.tool

# Check where you are:
curl -s http://localhost:8090/api/replay/status | python3 -m json.tool
```

Supports both proxy recordings (`capture/payloads/`) and engine recordings (`engine/`). Format detected automatically from recording directory structure.
```

- [ ] **Step 2: Commit**

```bash
git add docs/debug-api.md
git commit -m "docs: add replay control API to debug-api.md"
```
