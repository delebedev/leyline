# Front Door Stub: Refined Plan

## Session TLDR (2026-02-24)

We tried to make FD stub mode work offline with the Arena client. Six iterations:

1. **Auth** — `{"SessionId":"...","Attached":true}` works. Adding `"PlayerConditions":{}` broke deserialization (custom JSON converters reject unknown shapes). Reverted to omitting them.
2. **Graph definitions** — client sends `GraphGetGraphDefinitions` (CmdType 1700), not `GraphGetGraphState`. We were responding with graph *states* → `"Graph Definitions are null!"` → black screen. Fixed: send `{"GraphDefinitions":[...]}`.
3. **StartHook** — client sends empty `{}` JSON (CmdType 1). Our catch-all returned `{}` → `PAPA+<loadDesignerMetadata>` NRE. Fixed: send minimal `StartHookResponse` with `CardMetadataInfo`, `DeckSummariesV2`, etc.
4. **StartHookResponse deserialization failure** — `StartHookResponseJsonConverter` is a custom converter that reads by CmdType. Our stub sends all fields as one `Response` envelope but the client uses CmdType to pick the deserializer. Our whole JSON gets fed to `DTO_CardMetadataInfoJsonConverter` → `"Additional text found after deserializing"`.
5. **MatchCreated field casing** — `ClientMatchInfoJsonConverter` expects PascalCase (`MatchEndpointHost`), we sent camelCase → all fields silently skipped → `"Incoming Invalid"`. Fixed: PascalCase.
6. **Root cause identified** — FD protocol is CmdType-dispatched. Each C→S message has a CmdType in the protobuf `Cmd` envelope. Each S→C response must match. We're ignoring CmdType on inbound and sending all responses as generic `Response` envelopes. The client routes our response to the wrong deserializer.

**Conclusion:** Hand-crafting JSON per message type hits diminishing returns. We need the CmdType from the envelope to dispatch correctly, and we need real response shapes. Capture-and-replay is the right approach.

## Reference Docs

All derived from Arena client decompilation: minimum viable responses, field validation, dispatch table, exact proto field numbers, CmdType enum (120+ values).

## Envelope Schema (from fd-envelope-proto.md)

```
Cmd (C→S / S→C push): type=1, raw_trans_id=2, {protobuf_payload=3, json_payload=4}, compressed=5
Request (C→S newer):   type=1, raw_trans_id=2, key=3, {protobuf_payload=4, json_payload=5}, session_info=6, compressed=7
Response (S→C reply):  raw_trans_id=1, {protobuf_payload=2, json_payload=3}, error=4, compressed=5
```

Key CmdTypes for stub mode: Authenticate=0, StartHook=1, GetFormats=6, Event_AiBotMatch=612, Graph_GetGraphDefinitions=1700, Graph_GetGraphState=1701, GetDesignerMetadata=2400.

Push notifications: MatchCreated=600 (sent as `Cmd` with type=600).

## Tooling Plan (build order)

### Tool 1: FD Envelope Decoder

**What:** Kotlin utility to parse FD protobuf envelopes — extract CmdType, transactionId, JSON payload from raw bytes. Both C→S (`Cmd`/`Request`) and S→C (`Response`/`Cmd` push).

**Why:** Everything downstream needs CmdType. Currently FrontDoorService does regex JSON extraction and ignores the envelope entirely.

**Where:** `leyline.protocol.FdEnvelope.kt`

**Shape:**
```kotlin
data class FdMessage(
    val cmdType: Int?,        // from Cmd.type (null for Response)
    val transactionId: String,
    val jsonPayload: String?,
    val direction: Direction, // C2S or S2C
    val envelopeType: EnvelopeType, // CMD, REQUEST, RESPONSE
)

object FdEnvelope {
    fun decode(bytes: ByteArray): FdMessage
    fun encodeResponse(transactionId: String, json: String): ByteArray
    fun encodeCmd(cmdType: Int, transactionId: String, json: String): ByteArray
}
```

### Tool 2: FD Message Collector + Debug API

**What:** Ring buffer of FD messages (like `NexusDebugCollector` for Match Door). Records every C→S/S→C message with decoded CmdType, transactionId, JSON payload. Exposed at `GET /api/fd-messages?since=N`.

**Why:** Real-time visibility into FD conversation while iterating. Today we had to `curl /api/logs` and grep — slow, truncated, no structure.

**Where:** `leyline.debug.FdDebugCollector.kt`, new endpoint in `DebugServer.kt`

### Tool 3: FD Frame Recorder

**What:** Extend proxy mode to dump decoded FD frames to `recordings/<session>/fd-frames.jsonl`. Each line: `{"seq":N,"dir":"C2S","cmdType":1700,"txId":"...","json":"..."}`.

**Why:** One proxy session → golden captures for every CmdType the client sends and every response the real server returns. Eliminates guessing.

**Where:** `CaptureSink` integration — after frame reassembly, decode via `FdEnvelope`, write JSONL. Also write raw payloads to `capture/fd-payloads/` for binary replay.

### Tool 4: FD Replay Stub

**What:** `FrontDoorReplayStub` replaces `FrontDoorService`. Loads golden frames from disk. Uses `FdEnvelope.decode()` on inbound to get CmdType. Looks up `{cmdType → recorded Response bytes}` map. Patches dynamic fields (sessionId, matchId, host:port) in the JSON before re-encoding.

**Why:** Stub mode "just works" offline. Re-capture after each Arena patch. No more hand-crafted JSON.

**Where:** `leyline.server.FrontDoorReplayStub.kt`

**Flow:**
1. Load `fd-frames.jsonl` (or raw `.bin` payloads) from `resources/fd-golden/` or config path
2. Build `Map<Int, ByteArray>` — cmdType → recorded response bytes
3. On inbound: decode CmdType → lookup → patch dynamic fields → send
4. MatchCreated push: send recorded Cmd(type=600) with patched matchId/host/port

### Tool 5: Smoke Test Automation

**What:** `just smoke-stub` — starts stub server, launches MTGA, polls `/api/client-errors` and `/api/fd-messages`, waits for either "Match Door: client connected" (success) or client error (fail). Reports pass/fail in <90s.

**Why:** Manual loop was 5-10 min per iteration. This makes it one command.

**Where:** Extend existing `SmokeTest.kt` or new `FdSmokeTest.kt`. New justfile target.

## Build Order

1. **FD Envelope Decoder** — unlocks all other tools
2. **FD Message Collector + Debug API** — immediate dev quality-of-life
3. **FD Frame Recorder** — capture golden session
4. **FD Replay Stub** — the endgame
5. **Smoke Test** — fast feedback loop

## What This Changes Architecturally

**Before:** FrontDoorService is a hand-rolled state machine with regex JSON extraction, ignoring CmdType, brittle across patches.

**After:** FrontDoorService becomes a thin CmdType router with recorded responses. The FD protocol is treated as opaque — we don't need to understand every JSON shape, just replay what the real server sent and patch the 3-4 dynamic fields. The state machine simplifies to: auth → replay recorded startup sequence → push MatchCreated.

The existing `FrontDoorService.kt` stays for reference but the replay stub becomes the default for `just serve-stub`. Proxy/hybrid modes are unchanged.
