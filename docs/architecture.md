# Nexus — Architecture

Mermaid diagrams: package wiring, server layout, wire protocol, bridge threading, match lifecycle.

---

## 1. Package Wiring

How `forge-nexus` relates to the core engine and the web port.

```mermaid
graph TB
    subgraph "Client (Unity)"
        ARENA["Game Client<br/><small>Unity · C# · protobuf</small>"]
    end

    subgraph "forge-nexus (Kotlin)"
        SRV["server/<br/><small>Netty TLS TCP · MatchHandler<br/>FrontDoorStub · MatchRegistry</small>"]
        GAME_PKG["game/<br/><small>GameBridge · BundleBuilder<br/>AnnotationBuilder · StateMapper<br/>CardDb · ZoneIds · PromptIds</small>"]
        PROTO["protocol/<br/><small>FrameCodec · HandshakeMessages<br/>DecodeCapture</small>"]
        DEBUG["debug/<br/><small>DebugServer · DebugCollector<br/>GameStateCollector · Inspect</small>"]
        CONF["conformance/<br/><small>RecordingDecoder · StructuralDiff<br/>StructuralFingerprint</small>"]
    end

    subgraph "forge-web (Kotlin)"
        WEB["Bridges + Controllers<br/><small>GameActionBridge<br/>InteractivePromptBridge<br/>WebPlayerController · GameRoom</small>"]
    end

    subgraph "Core Engine (Java)"
        GUI["forge-gui<br/><small>IGuiGame · PlayerController</small>"]
        GAME_ENG["forge-game<br/><small>Rules engine · game state<br/>turns · phases · stack</small>"]
        CORE["forge-core<br/><small>Card definitions · mana</small>"]
    end

    ARENA -- "6-byte frame + protobuf<br/>TLS TCP :30003" --> SRV
    SRV --> GAME_PKG
    GAME_PKG -- "completes futures<br/>reads game state" --> WEB
    WEB -- "extends PlayerControllerHuman<br/>implements IGuiGame" --> GUI
    GUI --> GAME_ENG
    GAME_ENG --> CORE

    style ARENA fill:#4a9eff,color:#fff
    style SRV fill:#7c4dff,color:#fff
    style GAME_PKG fill:#7c4dff,color:#fff
    style PROTO fill:#7c4dff,color:#fff
    style DEBUG fill:#607d8b,color:#fff
    style CONF fill:#607d8b,color:#fff
    style WEB fill:#e91e63,color:#fff
    style GUI fill:#ff9800,color:#fff
    style GAME_ENG fill:#ff9800,color:#fff
    style CORE fill:#ff9800,color:#fff
```

**Key point:** `forge-nexus` never touches the rules engine directly. It goes through `forge-web`'s bridges (`GameActionBridge`, `InteractivePromptBridge`) — the same `CompletableFuture` pattern the web UI uses. The bridge doesn't know or care whether the thing completing it is a WebSocket JSON handler or a protobuf handler.

---

## 2. Server Layout

Two Netty TCP servers, one debug HTTP server.

```mermaid
graph TB
    subgraph "Front Door (:30010)"
        FD["FrontDoorStub<br/><small>Auth handshake replay<br/>Login → Session → MatchConfig</small>"]
    end

    subgraph "Match Door (:30003)"
        MH["MatchHandler<br/><small>ConnectReq/Resp<br/>GRE message exchange</small>"]
        FC["FrameCodec<br/><small>6-byte length-delimited<br/>protobuf framing</small>"]
        MS["MatchSession<br/><small>Per-seat session state<br/>message routing</small>"]
    end

    subgraph "Debug (:8090)"
        DS["DebugServer<br/><small>Ktor · REST + SSE<br/>nexus-debug.html panel</small>"]
    end

    subgraph "Shared State"
        MR["MatchRegistry<br/><small>Active matches · sessions<br/>seat assignment</small>"]
        GB["GameBridge<br/><small>State mapping · bundle building<br/>annotation pipeline</small>"]
        DC["DebugCollector<br/><small>Message ring buffer<br/>state snapshots · logs</small>"]
    end

    FD --> MR
    MH --> FC
    MH --> MS
    MS --> MR
    MS --> GB
    DS --> DC
    GB --> DC

    style FD fill:#4caf50,color:#fff
    style MH fill:#2196f3,color:#fff
    style FC fill:#2196f3,color:#fff
    style MS fill:#2196f3,color:#fff
    style DS fill:#607d8b,color:#fff
    style MR fill:#e91e63,color:#fff
    style GB fill:#e91e63,color:#fff
    style DC fill:#e91e63,color:#fff
```

**Front Door** replays a canned auth handshake — just enough to make the client believe it authenticated and got assigned a match. **Match Door** handles actual gameplay via protobuf. **Debug server** exposes introspection APIs and an SSE event stream.

---

## 3. Wire Protocol

Client ↔ server message framing and protobuf schema.

```
┌─────────────────────────────────────────┐
│  Arena Wire Frame (6 bytes + payload)   │
├──────┬──────┬───────────────────────────┤
│ 0x04 │ 0x11 │ payload_length (4 LE)     │
│ type │ flag │                           │
├──────┴──────┴───────────────────────────┤
│  protobuf payload (variable length)     │
│  MatchServiceToClientMessage   (S→C)    │
│  MatchClientToServerMessage    (C→S)    │
└─────────────────────────────────────────┘
```

**Inbound (C→S):** `ClientToGREMessage` containing `PerformActionResp`, `ConnectReq`, `SetSettingsReq`, etc. Decoded by `FrameCodec`, dispatched by `MatchHandler`.

**Outbound (S→C):** `GREToClientMessage` wrapped in `MatchServiceToClientMessage`. Built by `BundleBuilder` (game state, annotations, actions) and `MatchHandler` (connect/timer responses).

---

## 4. Bridge Threading

The blocking-bridge pattern connecting the protobuf handler to the synchronous Java engine thread.

```mermaid
sequenceDiagram
    box rgb(66, 133, 244) Netty I/O (async)
        participant MH as MatchHandler
    end
    box rgb(219, 68, 55) Daemon Thread (blocking)
        participant GL as Game Loop Thread
    end
    participant GB as GameBridge
    participant AB as GameActionBridge
    participant ENG as Rules Engine

    Note over GL,ENG: Engine starts game loop

    GL->>ENG: next priority point
    ENG->>GL: chooseSpellAbilityToPlay()
    GL->>AB: awaitAction(state)
    Note over AB: CompletableFuture.get()<br/>⏳ BLOCKS engine thread

    GB->>MH: send ActionsAvailableReq
    Note over MH: Client sees board +<br/>available actions

    MH->>GB: PerformActionResp (action choice)
    GB->>AB: submitAction(PlayerAction)
    Note over AB: future.complete() →<br/>unblocks engine

    AB->>GL: returns PlayerAction
    GL->>ENG: resolve action

    Note over GL,ENG: Loop repeats at<br/>next priority point
```

**Same bridges as the web port.** `GameActionBridge` blocks the engine thread until a player responds. `InteractivePromptBridge` handles engine-initiated choices (targeting, sacrifice, scry). `MulliganBridge` handles keep/mulligan. All three use `CompletableFuture` with timeouts that return safe defaults.

---

## 5. Match Lifecycle

```mermaid
sequenceDiagram
    participant C as Client
    participant FD as FrontDoorStub
    participant MH as MatchHandler
    participant GB as GameBridge
    participant ENG as Engine

    C->>FD: TLS connect :30010
    FD-->>C: Auth handshake (canned)
    FD-->>C: MatchConfig (connect to :30003)

    C->>MH: TLS connect :30003
    MH-->>C: ConnectResp

    Note over GB,ENG: GameRoom created,<br/>engine thread starts

    MH-->>C: GameStateMessage (initial hand)
    MH-->>C: MulliganReq

    C->>MH: MulliganResp (keep)
    GB->>ENG: mulliganBridge.complete()

    loop Priority Loop
        ENG->>GB: chooseSpellAbilityToPlay()
        GB->>MH: ActionsAvailableReq + GameStateMessage
        C->>MH: PerformActionResp
        MH->>GB: submitAction()
        GB->>ENG: future.complete()
    end

    Note over ENG: Game ends
    MH-->>C: GameStateMessage (result)
    MH-->>C: IntermissionReq
```

---

## 6. State Mapping Pipeline

How Forge engine state becomes a protobuf `GameStateMessage`:

```
Game (forge-game)
  │
  ├── StateMapper.mapGameObjects()     → GameObjectMsg[]  (cards, permanents, abilities)
  ├── StateMapper.mapZones()           → ZoneMsg[]        (hand, library, battlefield, etc.)
  ├── StateMapper.mapPlayers()         → PlayerMsg[]      (life, mana, counters)
  ├── AnnotationBuilder.build()        → AnnotationMsg[]  (zone transfers, combat, abilities)
  │     ├── detectAndApplyZoneTransfers()
  │     ├── annotationsForTransfer()
  │     └── combatAnnotations()
  └── BundleBuilder.bundle()           → GREToClientMessage
        ├── per-seat visibility filtering
        ├── diff vs. full state selection
        └── gsId chain management
```

**Per-seat filtering:** Each seat gets its own `GameStateMessage`. Private zones (opponent's hand, face-down library) are filtered. The same engine state produces different protobuf payloads per seat.

**gsId chain:** Each game state message gets a monotonic `gameStateId`. The client expects sequential delivery — gaps cause resync requests.
