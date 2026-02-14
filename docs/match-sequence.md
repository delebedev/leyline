# Match Door GRE Sequence

> **Goal:** Determine what `ArenaMatchHandler.kt` must implement for the smoke test — get Arena to render a board state.

## Complete Game Lifecycle

The observed Sparky game lasted ~40 seconds of active play (08:12:54 → 08:13:34), with 91 meaningful messages before the post-game heartbeat phase.

### Phase 1: Connect + Auth (Messages 1-9, ~08:12:54)

The Arena client opens **two parallel TLS connections** to the Match Door:

| Connection | PersonaID | Role |
|---|---|---|
| 1 | `OZ2H676CTZGPTDV7JWQFQ5TTPY` | Human player |
| 2 | `OZ2H676CTZGPTDV7JWQFQ5TTPY_Familiar` | AI opponent (Sparky) |

**Auth handshake per connection:**

```
C→S: [6-byte header] + AuthRequest
     - Contains: transactionId (UUID), PersonaID, JWT token
     - Player JWT is same as Front Door token
     - Familiar JWT appears to be a derived/secondary token

S→C: [6-byte header ack] (echo)
S→C: [6-byte header] + AuthResponse
     - Contains: transactionId (echo), SessionID (UUID), PersonaID, ScreenName
     - Player response: screenName="garnett#01186"
     - Familiar response: PersonaID has "_Familiar" suffix
```

**Key difference from Front Door:** Match Door uses protobuf for auth payloads, not JSON. The `ClientToMatchServiceMessage` envelope wraps an `AuthenticateRequest`:

```protobuf
// Client sends:
ClientToMatchServiceMessage {
  requestId = <int>
  clientToMatchServiceMessageType = AuthenticateRequest_f487
  payload = AuthenticateRequest {
    clientId = "<PersonaID>"
    clientAuthToken = <JWT bytes>
    playerName = "<screenName>"
  }
}

// Server replies:
MatchServiceToClientMessage {
  requestId = <echo>
  authenticateResponse = AuthenticateResponse {
    clientId = "<id>"
    sessionId = "<uuid>"
    screenName = "<name>"
  }
}
```

### Phase 2: Match Door Connect (Messages 8-10, ~08:12:55)

After auth, each client sends a `ClientToMatchDoorConnectRequest`:

```
C→S: [6-byte header] + ClientToMatchServiceMessage {
  type = ClientToMatchDoorConnectRequest_f487
  payload = ClientToMatchDoorConnectRequest {
    matchId = "127d41e3-23c6-4137-b3db-813645a2a295"
    mcFabricUri = "wzmc://5CfhRVzx.../127d41e3-..."
    clientToGreMessageBytes = <optional embedded ConnectReq>
  }
}
```

The `clientToGreMessageBytes` field may contain an initial `ClientToGREMessage` with type `ConnectReq`.

### Phase 3: Initial Game State (Messages 11-15, ~08:12:55.3)

Server sends 5 messages totaling ~3.5 KB of initial state:

1. **Match room state** (668 bytes): Player info, cosmetics, team assignments
2. **Player 1 config** (421 bytes): Avatar, sleeve, cosmetics for human
3. **Player 2 config** (421 bytes): Avatar for Sparky, "AIBotMatch" event, "Familiar" type
4. **Deck/match config** (860 bytes): Deck references, match parameters
5. **Full game state** (1441 bytes): Initial zones, game info, turn info

The initial `GameStateMessage` likely contains:
- `gameInfo`: matchID, gameNumber=1, stage=Start, mulliganType=London
- `zones`: Library, Hand, Graveyard, Battlefield, Stack, Exile, Command for each player
- `players`: life totals, mana pools
- `teams`: team assignments

### Phase 4: Mulligan (Messages 16-20, ~08:12:55.8 - 08:12:56.9)

**Server sends `MulliganReq`:**
```
S→C: GREToClientMessage {
  type = MulliganReq_aa0d
  mulliganReq = MulliganReq {
    mulliganType = London
    freeMulliganCount = 0
    mulliganCount = 0
  }
}
```

Along with a `GameStateMessage` showing the opening hand (zone=Hand, 7 cards).

**Client responds with `MulliganResp`:**
```
C→S: ClientToGREMessage {
  type = MulliganResp_097b
  mulliganResp = MulliganResp {
    decision = AcceptHand  // or Mulligan
  }
}
```

The exchange takes ~1 second. Server responds with updated game state.

### Phase 5: Priority / Action Loop (Messages 21-63, ~08:12:57 - 08:13:26)

The main game loop. Each turn cycle:

**Server prompts for action:**
```
S→C: MatchServiceToClientMessage {
  greToClientEvent = GreToClientEvent {
    greToClientMessages = [
      GREToClientMessage {
        type = GameStateMessage_695e
        gameStateMessage = { ... current state diff ... }
      },
      GREToClientMessage {
        type = ActionsAvailableReq_695e
        actionsAvailableReq = {
          actions = [ ... legal actions ... ]
          inactiveActions = [ ... greyed-out actions ... ]
        }
      }
    ]
  }
}
```

**Client responds with chosen action:**
```
C→S: ClientToMatchServiceMessage {
  type = ClientToGremessage
  payload = ClientToGREMessage {
    type = PerformActionResp_097b
    systemSeatId = 1
    gameStateId = <current>
    performActionResp = { ... chosen action ... }
  }
}
```

**Observed patterns:**
- Client requests are small (67-87 bytes) — just the action choice
- Server responses are medium (70-940 bytes) — state diff + next action prompt
- A single client action can trigger 2-3 server messages (state update + new prompt)
- Phase transitions generate larger state diffs
- Pass actions are ~73 bytes
- Each message has a unique UUID `transactionId`

### Phase 6: Spell Casting (Messages 64-66, ~08:13:28)

Casting a spell generates large server responses (5-6 KB) with rich annotations:

```
S→C: GameStateMessage {
  type = Diff
  annotations = [
    { type = ZoneTransfer, details: [
        { key = "zone_src", value = "ZoneType_Hand" },
        { key = "zone_dest", value = "ZoneType_Stack" },
        { key = "orig_id", value = "<instanceId>" },
        { key = "new_id", value = "<instanceId>" },
        { key = "category", value = "CastSpell" }
    ]}
  ]
  gameObjects = [ ... updated objects ... ]
}
```

**Visible annotation keys from capture:**
- `zone_src` / `zone_dest` — zone transfer tracking
- `orig_id` / `new_id` — object ID changes during zone transitions
- `category` — action category (CastSpell, etc.)

### Phase 7: Concede / Game End (Messages 91-93, ~08:13:34-39)

**Client sends concede:**
```
C→S: ClientToGREMessage {
  type = ConcedeReq_097b
  concedeReq = ConcedeReq {
    scope = Match  // or Game
  }
}
```

**Server sends final state + result:**
```
S→C: GameStateMessage {
  gameInfo = { stage = GameOver, matchState = MatchComplete }
  results = [ ... win/loss per seat ... ]
}
```

### Phase 8: Post-Game Heartbeat (Messages 92-159, ~08:13:39-54)

After the game ends, both sides exchange 6-byte header-only messages (no payload) for ~15 seconds. These appear to be keepalive/heartbeat/phase-stop messages. The pattern is strict alternation: S→C, C→S, S→C, C→S...

---

## What `ArenaMatchHandler.kt` Must Implement for Smoke Test

### Minimum Viable Sequence

```
1. Accept two TLS connections (player + familiar)
2. For each: receive AuthRequest → send AuthResponse
3. Receive ClientToMatchDoorConnectRequest → ACK
4. Send initial MatchGameRoomStateChangedEvent (player info, cosmetics)
5. Send ConnectResp (status=Success)
6. Send initial GameStateMessage (full state, stage=Start)
7. Send MulliganReq (London mulligan)
8. Receive MulliganResp → send GameStateMessage (stage=Play, turnInfo)
9. Send ActionsAvailableReq (legal actions)
10. Receive PerformActionResp → update state → repeat from 9
```

### Critical Proto Message Types

**Server sends (via `MatchServiceToClientMessage.greToClientEvent`):**
- `ConnectResp` — confirm GRE connection
- `GameStateMessage` — full or diff game state
- `MulliganReq` — mulligan prompt
- `ActionsAvailableReq` — legal actions for priority player
- `ChooseStartingPlayerReq` — coin flip (may be skipped for Sparky)

**Client sends (via `ClientToMatchServiceMessage.payload` → `ClientToGREMessage`):**
- `ConnectReq` — initial GRE connect
- `MulliganResp` — keep/mull decision
- `PerformActionResp` — chosen action (cast, activate, pass)
- `ConcedeReq` — end game

### Message Sizes to Expect

| Message Type | Typical Size |
|---|---|
| Auth request | 1100-1150 bytes |
| Auth response | 140-150 bytes |
| MatchDoorConnect | 400 bytes |
| Initial game state (full) | 1400-1500 bytes |
| Mulligan prompt | 400-500 bytes |
| Action prompt | 70-450 bytes |
| Player action | 67-87 bytes |
| Spell cast state diff | 4000-6000 bytes |
| Concede | 73 bytes |

### Two-Connection Architecture

The Arena client always opens **two connections** to the Match Door. For the smoke test with Sparky:

1. **Connection 1** (human player): Receives game prompts, sends player decisions
2. **Connection 2** (Familiar/AI): Receives the same game state, sends AI decisions

Both connections authenticate separately with different session IDs. The current `ArenaMatchHandler.kt` handles this correctly — it just needs to track which connection is the player vs. the AI.

For the initial smoke test, the Familiar connection can be handled minimally (just auth + state pushes, no prompts needed since Forge AI makes its own decisions).
