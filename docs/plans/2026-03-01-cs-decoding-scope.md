# C→S Decoding Fix: Missing ClientToGREMessage Types

## Problem

`RecordingDecoder.decodeClientMessage()` parses the `ClientToGREMessage` payload
from `ClientToMatchServiceMessage` but only extracts summary fields for 4 of 12+
response types. Unhandled types decode to a bare `DecodedMessage` with just
`greType`/`gsId`/`clientType` — no structured payload summary.

## Current coverage

| Type | Handled | Summary type |
|---|---|---|
| `PerformActionResp` | Yes | `clientAction: ClientActionSummary` |
| `DeclareAttackersResp` | Yes | `clientAttackers: ClientAttackersSummary` |
| `DeclareBlockersResp` | Yes | `clientBlockers: ClientBlockersSummary` |
| `SelectTargetsResp` | Yes | `clientTargets: ClientTargetsSummary` |
| `ChooseStartingPlayerResp` | **No** | — |
| `CastingTimeOptionsResp` | **No** | — |
| `MulliganResp` | **No** | — |
| `SelectNResp` | **No** | — |
| `ConcedeReq` | **No** | — |
| `SubmitAttackersReq` | **No** | — |
| `SubmitBlockersReq` | **No** | — |
| `SubmitTargetsReq` | **No** | — |

Note: `SubmitAttackersReq`, `SubmitBlockersReq`, `SubmitTargetsReq` are
client-initiated confirmation messages (not responses to a server req).
They have no `respId`.

## Observed in recordings

583 `ClientToGremessage` payloads across 17 sessions. Inner type distribution
(partial — some payloads have escape sequences that break shell extraction):

| Inner type | Count |
|---|---|
| `SubmitAttackersReq` | 48 |
| `DeclareAttackersResp` | 23 |
| `SubmitBlockersReq` | 18 |
| `MulliganResp` | 16 |
| `SubmitTargetsReq` | 15 |
| `ChooseStartingPlayerResp` | 8 |
| `CastingTimeOptionsResp` | ~4 |
| `SelectNResp` | ~2 |
| `PerformActionResp` | ~400+ |
| `ConcedeReq` | 3 |

## Proto shapes for missing types

### ChooseStartingPlayerResp

```protobuf
message ChooseStartingPlayerResp {
  TeamType teamType = 1;      // Individual
  uint32 systemSeatId = 2;    // chosen seat (who goes first)
  uint32 teamId = 3;          // 1
}
```

Captured example (session `2026-03-01_00-18-46`, file `000000098_MD_C-S_DATA.bin`):

```
type: ChooseStartingPlayerResp_097b
gameStateId: 1
respId: 5
chooseStartingPlayerResp {
  teamType: Individual
  systemSeatId: 1
  teamId: 1
}
```

### CastingTimeOptionsResp

```protobuf
message CastingTimeOptionsResp {
  CastingTimeOptionResp castingTimeOptionResp = 1;
  repeated CastingTimeOptionResp castingTimeOptionResps = 2;  // for multi-option
}

message CastingTimeOptionResp {
  uint32 ctoId = 1;
  CastingTimeOptionType castingTimeOptionType = 2;
  oneof message {
    NumericInputResp numericInputResp = 3;
    SelectManaTypeResp selectManaTypeResp = 4;
    ChooseModalResp chooseModalResp = 5;
    SelectNResp selectNResp = 6;
  }
}
```

Captured examples:

**Modal (Goblin Surprise, file 244):**
```
type: CastingTimeOptionsResp_097b
gameStateId: 84
respId: 112
castingTimeOptionsResp {
  castingTimeOptionResp {
    ctoId: 2
    castingTimeOptionType: Modal_a7b4
    chooseModalResp {
      grpIds: 1360    ← chosen mode ability grpId
    }
  }
}
```

**Kicker skipped (Burst Lightning, file 461):**
```
type: CastingTimeOptionsResp_097b
gameStateId: 188
respId: 255
castingTimeOptionsResp {
  castingTimeOptionResp {
    castingTimeOptionType: Done   ← skip kicker
  }
}
```

### MulliganResp

```protobuf
message MulliganResp {
  MulliganOption decision = 1;  // AcceptHand / Mulligan
}
```

### SelectNResp

```protobuf
message SelectNResp {
  repeated uint32 ids = 5;       // chosen instanceIds
  OrderingType useArbitrary = 6;
}
```

### ConcedeReq

```protobuf
message ConcedeReq {
  MatchScope scope = 1;   // Game or Match
  uint32 gameNumber = 2;
}
```

## What to build

### 1. New summary data classes in `RecordingDecoder`

```kotlin
@Serializable
data class ClientChooseStartingPlayerSummary(
    val teamType: String,
    val systemSeatId: Int,
    val teamId: Int,
)

@Serializable
data class ClientCastingTimeOptionsSummary(
    val options: List<ClientCastingTimeOptionSummary>,
)

@Serializable
data class ClientCastingTimeOptionSummary(
    val ctoId: Int = 0,
    val type: String,
    val modalGrpIds: List<Int> = emptyList(),   // from chooseModalResp
    val selectNIds: List<Int> = emptyList(),     // from selectNResp
)

@Serializable
data class ClientMulliganSummary(
    val decision: String,   // AcceptHand or Mulligan
)

@Serializable
data class ClientSelectNSummary(
    val ids: List<Int>,
)

@Serializable
data class ClientConcedeSummary(
    val scope: String,
    val gameNumber: Int,
)
```

### 2. Add fields to `DecodedMessage`

```kotlin
val clientChooseStartingPlayer: ClientChooseStartingPlayerSummary? = null,
val clientCastingTimeOptions: ClientCastingTimeOptionsSummary? = null,
val clientMulligan: ClientMulliganSummary? = null,
val clientSelectN: ClientSelectNSummary? = null,
val clientConcede: ClientConcedeSummary? = null,
```

### 3. Wire in `decodeClientMessage`

Add `takeIf { it.hasXxxResp() }?.let { ... }` blocks for each new type,
matching the existing pattern for `declareAttackersResp` etc.

### 4. Tests

Add a test in `RecordingDecoderTest` (or create one) that decodes the known
C→S binary files and asserts the summary fields are populated:

| File | Expected type |
|---|---|
| `000000098_MD_C-S_DATA.bin` | `ChooseStartingPlayerResp`, systemSeatId=1 |
| `000000244_MD_C-S_DATA.bin` | `CastingTimeOptionsResp`, Modal, grpIds=[1360] |
| `000000461_MD_C-S_DATA.bin` | `CastingTimeOptionsResp`, Done |

## Key reference files

| File | Why |
|---|---|
| `RecordingDecoder.kt:379-436` | `decodeClientMessage()` — add new type handlers |
| `RecordingDecoder.kt:106-140` | `DecodedMessage` — add new summary fields |
| `messages.proto:745-786` | `ClientToGREMessage` — oneof message field numbers |
| `messages.proto:695-743` | `ClientMessageType` enum |

## Implementation notes

- Keep the pattern: `gre.takeIf { it.hasXxxResp() }?.let { ... }`
- All new summary fields should be nullable with default `null` (only set when present)
- `encodeDefaults = false` in the JSON serializer means null fields are omitted from output
- No behavioral change — this is purely for recording analysis/debugging
- `SubmitAttackersReq`/`SubmitBlockersReq`/`SubmitTargetsReq` are lower priority —
  they duplicate info from the preceding `DeclareAttackersResp`/`DeclareBlockersResp`/
  `SelectTargetsResp` and are mainly confirmation signals

## Estimated effort

Small — ~100 LOC of data classes + ~60 LOC of decoder wiring + test. Pure additive,
no behavioral changes. All proto types already generated, just need summary extraction.
