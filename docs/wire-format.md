# Wire Format Spec

> **Status:** Confirmed (2026-02-13)

## Transport Layer

Both doors use **TLS over raw TCP** — not HTTP, not WebSocket.

```
Arena Client ←→ TLS 1.2/1.3 ←→ TCP ←→ Server
                (raw bytes, no HTTP framing)
```

## Message Framing

Every message consists of:

```
┌────────────────────┬────────────────────────────┐
│  6-byte header     │  Payload (variable length)  │
│  (always present)  │  (0 to ~20 KB typical)      │
└────────────────────┴────────────────────────────┘
```

### 6-Byte Header (confirmed)

```
Byte  0:   Version        = 0x04 (constant across all observed messages)
Byte  1:   Frame type     (see table below)
Bytes 2-5: Payload length (uint32 little-endian)
```

| Frame Type | Hex    | Meaning | Typical Payload |
|------------|--------|---------|-----------------|
| CTRL_INIT  | `0x12` | Sender announces a message | 4-byte nonce |
| CTRL_ACK   | `0x13` | Receiver acknowledges | 4-byte nonce (echo) |
| DATA_FD    | `0x21` | Front Door data + Match Door C→S | Protobuf envelope |
| DATA_MATCH | `0x11` | Match Door S→C data | Protobuf envelope |

#### Validation

Every single-chunk message satisfies:

```
total_length == 6 + uint32_LE(bytes[2:6])
```

Multi-chunk messages (TCP segmentation of large payloads) have the length in the first chunk's header; subsequent chunks are raw continuation bytes.

### Control Frames (CTRL_INIT / CTRL_ACK)

Control frames are 10 bytes total: 6-byte header + 4-byte nonce payload.

**Sequence:**
1. Sender sends CTRL_INIT (`0x12`) with a 4-byte nonce
2. Receiver replies CTRL_ACK (`0x13`) echoing the same nonce
3. Only byte 1 differs between the pair (`0x12` → `0x13`)

Observed at connection establishment and periodically during the session.

### Payload

#### Front Door Payloads

The Front Door uses a **protobuf envelope with JSON string fields** — NOT `ClientToMatchServiceMessage`.

**C→S envelope (confirmed):**

| Proto Field | Wire Type | Content |
|-------------|-----------|---------|
| 1 | varint | `requestId` (optional — absent in auth request) |
| 2 | string | `transactionId` (UUID, 36 chars) |
| 4 | string | JSON payload (the actual message) |

**S→C envelope (confirmed):**

| Proto Field | Wire Type | Content |
|-------------|-----------|---------|
| 1 | string | `transactionId` (UUID, echoed from request) |
| 3 | string/bytes | JSON response (sometimes gzip-compressed) |
| 5 | varint | Status flag (optional) |

**Observed JSON payloads:**
- Auth: `{"ClientVersion":"2026.56.6","Token":"<JWT>","PersonaId":null,"PlatformId":"Mac"}`
- Session: `{"SessionId":"<uuid>","Attached":true}`
- State request: `{"RequestedType":"None"}`
- Graph request: `{"GraphId":"NPE_Tutorial"}`
- Match config: `{"Type":"MatchCreated","MatchInfoV3":{...}}`

#### Match Door Payloads

The Match Door uses **pure protobuf** with `ClientToMatchServiceMessage` / `MatchServiceToClientMessage`:

```
ClientToMatchServiceMessage {
  requestId: int32          // field 1
  type: enum                // field 2
  timestamp: int64          // field 3
  transactionId: string     // field 4 (UUID)
  payload: bytes            // field 100 (inner protobuf)
}
```

C→S data frames use type `0x21` (same as Front Door). S→C data frames use type `0x11`.

### Differences Between Front Door and Match Door

| Aspect | Front Door (:30010) | Match Door (:30003) |
|--------|---------------------|---------------------|
| Header | 6-byte, same format | 6-byte, same format |
| S→C frame type | `0x21` | `0x11` |
| Envelope | Custom protobuf + JSON | `ClientToMatchServiceMessage` protobuf |
| Payload encoding | JSON strings | Nested protobuf |
| Connections | Single | Two (player + familiar) |
| Typical payload size | 50 – 20 KB | 67 – 6 KB |

### Multi-Chunk Messages

Large payloads are split across multiple TCP segments. The header's length field covers the entire payload; subsequent chunks are raw continuation bytes.

**Observed in Front Door:**
- State dump: 4 chunks totaling ~20 KB (single protocol message)

**Observed in Match Door:**
- Game state diffs: 2–3 chunks totaling ~10 KB

## Implementation

### `ArenaFrameCodec.kt`

```kotlin
const val HEADER_SIZE = 6
const val LENGTH_OFFSET = 2   // uint32 LE at bytes 2-5
const val VERSION: Byte = 0x04
```

Netty decoder uses `getIntLE()` (little-endian) at offset 2 to read the payload length.

### `FrontDoorService.kt`

```kotlin
// C→S fields
const val FIELD_REQUEST_ID_CS = 1
const val FIELD_TRANSACTION_ID_CS = 2
const val FIELD_PAYLOAD_CS = 4

// S→C fields
const val FIELD_TRANSACTION_ID_SC = 1
const val FIELD_PAYLOAD_SC = 3
```

---

## What Changed from Initial Analysis

The `-v` capture replaced non-printable bytes with `.` (0x2E), which made the header appear to be 10 opaque bytes. The `-x` hex capture revealed:

| Property | Old (guess) | New (confirmed) |
|----------|-------------|-----------------|
| Header size | 10 bytes | **6 bytes** |
| Length field offset | byte 4 | **byte 2** |
| Byte order | big-endian | **little-endian** |
| "10-byte header-only" messages | Header ack | **6-header + 4-byte nonce** (control frame) |
| Ack pattern | Full header echo | **Same bytes, byte 1 flipped 0x12→0x13** |
| FD C→S transactionId field | 4 | **2** |
| FD C→S payload field | 5 | **4** |
| FD S→C transactionId field | 4 | **1** |
| FD S→C payload field | 5 | **3** |
