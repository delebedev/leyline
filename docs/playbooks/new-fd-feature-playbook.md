---
summary: "End-to-end playbook for adding a new Front Door feature: record real flow, extract protocol, build sequence table, staged implementation."
read_when:
  - "adding a new Front Door feature (event, queue, store flow)"
  - "recording and reverse-engineering a new FD protocol sequence"
  - "planning staged FD implementation work"
---
# New FD Feature Playbook

How to add a new Front Door feature end-to-end. Covers protocol capture, domain modelling, staged implementation, and verification.

## 1. Record the real flow

Start `just serve-proxy`, play through the **full lifecycle** in Arena.

"Full lifecycle" means entry to completion — not just the first screen. For sealed: join → open packs → build deck → play matches → lose out → rewards. For a queue event: select deck → queue → match → result → re-queue.

Missing the tail end means you'll discover the post-match protocol by crashing the client later.

```bash
# Start proxy
tmux new-session -d -s leyline 'just serve-proxy'
just arena launch

# Monitor capture growth
wc -l recordings/*/capture/fd-frames.jsonl
```

## 2. Extract the protocol

Find the new traffic and build the **sequence table** — the authoritative reference for implementation.

```bash
# What message types appeared?
just wire summary

# Find feature-specific frames
just wire search sealed

# Delta view if you already know a checkpoint
just wire since 144

# Structure at a glance (don't dump 40KB of JSON)
just wire keys 227

# Resolve grpId arrays to card names
just wire cards 227

# Pair requests with responses
just wire pairs | grep Event_

# Extract a specific response payload
just wire response Event_Join | jq .
```

Build a table like this — it becomes the implementation spec:

| Step | Seq | CmdType | Direction | Key Data |
|------|-----|---------|-----------|----------|
| Join | 225→227 | Event_Join | C2S→S2C | entry fee, CourseId, CardPool |
| Submit deck | 243→244 | Event_SetDeckV2 | C2S→S2C | deck contents, module transition |
| ... | | | | |

Save notable payloads for reference: `just wire raw 227 | jq . > /tmp/sealed-join-response.json`

## 3. Check client internals

Consult client decompilation artifacts (IL2CPP dump, protocol analysis) for the client-side model — wire types, enums, state machines, null-safety landmines.

Key things to extract:
- **Wire enums** and their integer values (e.g. `EModule`: Join=0, Sealed=1, DeckSelect=3, CreateMatch=4, Complete=7)
- **Required vs optional fields** — which nulls cause NRE
- **State machine transitions** — valid module progressions
- **Ordering constraints** — e.g. ActiveEventsV2 must resolve before GetCoursesV2

## 4. Check Forge engine for reuse

Forge's engine (in the `forge/` submodule) may already have the feature working. Check for:

- **Pack generation**: `UnOpenedProduct`, booster templates, `FModel.getMagicDb().getBoosters()`
- **Deck building**: `SealedDeckBuilder`, `LimitedDeckEvaluator`, `DeckFormat.Limited`
- **Session management**: existing session/lifecycle patterns in `matchdoor/bridge/`

```bash
# Find relevant bridge/engine code
grep -rl 'Sealed\|Booster\|UnOpenedProduct' matchdoor/src/
```

Reuse Forge's game logic (pack gen, deck validation, AI deck building). Don't reimplement card pools or rarity distribution — that's engine authority (principle 7).

## 5. Gap analysis

Compare what exists against what's needed:

```bash
# Which CmdTypes are handled vs observed?
just wire coverage

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

### Phase 2.5: Golden replay smoke test

- Extract key response payloads from the recording as golden JSON files
- Wire handlers to return golden data for the new feature flow
- `just serve` → client navigates to the feature → exercises the full UI flow
- **Gate**: client renders the feature UI without crashes (no domain logic needed yet)

This catches wire format issues (null fields, wrong types, missing keys) before you write real logic. Cheap to do, expensive to debug later.

#### Arena UI navigation for events

Events with `bladeBehavior = null` (sealed, draft) appear on the **Events tab**, not Find Match. To navigate:

1. Click "Play" from home screen
2. The Events tab shows event tiles — but **event names may not render** if the loc key (`Events/Event_Title_<name>`) isn't in the client's string table. The tile still appears (with artwork from the set) but shows no title text.
3. Use category filters ("Limited", "Constructed") on the right sidebar to narrow the list.
4. Click the event tile — the event blade opens with a "Start" / "Enter" button.
5. After joining, the client transitions based on `CurrentModule` in the response.

**Common issues during golden playtest:**
- **Missing event title**: client doesn't have our custom loc keys. The tile renders but is unnamed. Navigate by position or filter category.
- **Ghost "Resume" on Jump In**: default courses include `Jump_In_2024` at `CreateMatch`, client shows "Resume" even though no real session exists. Harmless but confusing during navigation.
- **Pack opening animation**: the sealed join response includes `CardPool` — the client auto-plays a pack reveal animation showing rares. This works if the grpIds in the golden are real cards.

### Phase 3: Real logic

- Replace golden stubs with service calls
- Forge engine integration (pack gen, deck validation)
- Full lifecycle (join → play → complete)
- **Gate**: play through the feature end-to-end with `just serve`

### Phase 4: Conformance

- Compare our wire output against the recording
- Fix field ordering, missing fields, null vs empty
- **Gate**: diff our response shapes against `just wire raw` captures

## 8. Verify

```bash
# Unit tests for the changed module
./gradlew :frontdoor:test

# Start local server, connect client, play through
just serve

# Compare against recording
just wire response Event_Join | jq 'keys' > /tmp/real-keys.json
# ... compare with our response
```

Final check: read the wire with a human (principle 8). Decode a session from `just serve` and eyeball the responses against the proxy recording. Functional correctness doesn't catch missing fields that the client silently ignores now but will break on later.
