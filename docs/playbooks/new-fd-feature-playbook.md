# New FD Feature Playbook

How to add a new Front Door feature end-to-end. Covers protocol capture, domain modelling, staged implementation, and verification.

## 1. Record the real flow

Start `just serve-proxy`, play through the **full lifecycle** in Arena.

"Full lifecycle" means entry to completion — not just the first screen. For sealed: join → open packs → build deck → play matches → lose out → rewards. For a queue event: select deck → queue → match → result → re-queue.

Missing the tail end means you'll discover the post-match protocol by crashing the client later.

```bash
# Start proxy
tmux new-session -d -s leyline 'just serve-proxy'
bin/arena launch

# Monitor capture growth
wc -l recordings/*/capture/fd-frames.jsonl
```

## 2. Extract the protocol

Find the new traffic and build the **sequence table** — the authoritative reference for implementation.

```bash
# What message types appeared?
just fd-summary

# Find feature-specific frames
just fd-search sealed

# Delta view if you already know a checkpoint
just fd-since 144

# Structure at a glance (don't dump 40KB of JSON)
just fd-keys 227

# Resolve grpId arrays to card names
just fd-cards 227

# Pair requests with responses
just fd-pairs | grep Event_

# Extract a specific response payload
just fd-response Event_Join | jq .
```

Build a table like this — it becomes the implementation spec:

| Step | Seq | CmdType | Direction | Key Data |
|------|-----|---------|-----------|----------|
| Join | 225→227 | Event_Join | C2S→S2C | entry fee, CourseId, CardPool |
| Submit deck | 243→244 | Event_SetDeckV2 | C2S→S2C | deck contents, module transition |
| ... | | | | |

Save notable payloads for reference: `just fd-raw 227 | jq . > /tmp/sealed-join-response.json`

## 3. Check client internals

Consult `~/src/mtga-internals` for the client-side model — wire types, enums, state machines, null-safety landmines.

- `il2cpp-dump/dump.cs` — search for class definitions, enum values, field layouts
- `docs/` — existing analysis (front-door-stub.md, post-game-protocol.md, etc.)
- `docs/front-door-stub.md` § "Hard-Won Implementation Lessons" — null fields that crash the client

Key things to extract:
- **Wire enums** and their integer values (e.g. `EModule`: Join=0, Sealed=1, DeckSelect=3, CreateMatch=4, Complete=7)
- **Required vs optional fields** — which nulls cause NRE
- **State machine transitions** — valid module progressions
- **Ordering constraints** — e.g. ActiveEventsV2 must resolve before GetCoursesV2

```bash
# Find a class definition
grep -n 'class ClientPlayerCourseV2' ~/src/mtga-internals/il2cpp-dump/dump.cs

# Find an enum
grep -n 'enum EModule' ~/src/mtga-internals/il2cpp-dump/dump.cs
```

## 4. Check forge-web for engine reuse

`~/src/forge-web` may already have the feature working against Forge's engine. Check for:

- **Pack generation**: `UnOpenedProduct`, booster templates, `FModel.getMagicDb().getBoosters()`
- **Deck building**: `SealedDeckBuilder`, `LimitedDeckEvaluator`, `DeckFormat.Limited`
- **Session management**: existing session/lifecycle patterns

```bash
# Find relevant forge-web code
grep -rl 'Sealed\|Booster\|UnOpenedProduct' ~/src/forge-web/forge-web/src/
```

Reuse Forge's game logic (pack gen, deck validation, AI deck building). Don't reimplement card pools or rarity distribution — that's engine authority (principle 7).

## 5. Gap analysis

Compare what exists against what's needed:

```bash
# Which CmdTypes are handled vs observed?
just fd-coverage

# What domain types exist?
ls frontdoor/src/main/kotlin/leyline/frontdoor/domain/

# What handlers exist?
grep 'CmdType\.' frontdoor/src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt
```

Categorize each gap:

| Gap | Type | Effort |
|-----|------|--------|
| Course domain model | New entity + persistence | Medium |
| Event_Join handler | Replace golden stub with real logic | Medium |
| Pack generation | Forge reuse | Small |

## 6. Design the domain

Follow `docs/principles-design.md`:

- **Value objects**: `CourseId`, `CourseModule` enum — not raw strings
- **Domain entity**: lives in `frontdoor/domain/`, depends on nothing external
- **Repository interface**: in the domain package, implemented by `SqlitePlayerStore`
- **Service layer**: `CourseService` — business logic, called by handler
- **Wire builder**: extends `EventWireBuilder` — translates domain → Arena JSON shapes
- **Constructor injection**: wired in `LeylineServer`, no singletons

Domain objects don't know about Arena wire format. The handler parses wire input, calls the service with domain types, the wire builder serializes the response.

## 7. Stage the implementation

Each phase has a **verification gate** — don't proceed until it passes.

### Phase 1: Domain + persistence

- Domain model (entity, value objects, enums)
- Repository interface + SQLite implementation
- Service layer with business logic
- **Gate**: unit tests for service logic (create, state transitions, validation)

### Phase 2: Handlers + wire

- New/updated CmdType handlers in `FrontDoorHandler`
- Wire builder methods for new response shapes
- Golden data extraction for any static responses
- **Gate**: `just serve` + client connects, navigates to the feature, no crashes

### Phase 3: Real logic

- Replace golden stubs with service calls
- Forge engine integration (pack gen, deck validation)
- Full lifecycle (join → play → complete)
- **Gate**: play through the feature end-to-end with `just serve`

### Phase 4: Conformance

- Compare our wire output against the recording
- Fix field ordering, missing fields, null vs empty
- **Gate**: diff our response shapes against `just fd-raw` captures

## 8. Verify

```bash
# Unit tests for the changed module
just test-frontdoor

# Start local server, connect client, play through
just serve

# Compare against recording
just fd-response Event_Join | jq 'keys' > /tmp/real-keys.json
# ... compare with our response
```

Final check: read the wire with a human (principle 8). Decode a session from `just serve` and eyeball the responses against the proxy recording. Functional correctness doesn't catch missing fields that the client silently ignores now but will break on later.
