# forge-nexus

Client compat layer — stubs/proxies the client's Front Door + Match Door so the game client connects to Forge's engine.

- **Transport:** raw Netty TLS TCP (not HTTP — client uses 6-byte framing + protobuf)
- **Depends on:** forge-web (game bridges, bootstrap) — never reverse the dependency
- **Proto:** `src/main/proto/messages.proto` — client protobuf schema (from MtgaProto project)
- **Card data:** `CardDb.kt` reads the client's local SQLite for grpId, types, mana cost
- **Server modes:** `just serve` (hybrid, main dev), `just serve-stub` (offline), `just serve-proxy` (passthrough), `just serve-replay`

## Testing

See `.claude/rules/nexus-tests.md` for groups, setup tiers, and conventions. Key commands: `just test-gate` (pre-commit), `just test-one Foo` (single class).

## Debug Panel & API

Debug server on `:8090` (auto-starts with `just serve`). Full endpoint reference: `docs/debug-api.md`.

**Client error watcher:** auto-tails `Player.log` during `just serve`. Client-side exceptions (annotation parse failures, missing fields) appear inline in server output and are queryable at `/api/client-errors`. Errors persisted to `recordings/<session>/client-errors.jsonl`. Standalone: `just watch-client`.

## Reference

- **Mechanic catalog:** `docs/catalog.yaml` — what works, what's wired, what's missing. Organized by gameplay mechanic (what players experience), not protocol internals. Read this first to understand current state. Update it when changing mechanic support.
- **Rosetta table:** `docs/rosetta.md` — Arena protocol ↔ Forge engine ↔ forge-nexus code. Annotation types, zone IDs, transfer categories, action types, GRE messages, phase mapping, GameEvent wiring status. Protocol-level reference for when you need type numbers and field details.

## Mental Model

See `docs/architecture.md` for diagrams. This is the fast orientation.

**Outbound (engine → client):** Forge `Game` → `StateMapper.buildFromGame()` snapshots zones/objects/players → `GameEventCollector.drainEvents()` feeds `AnnotationBuilder.categoryFromEvents()` for transfer categories → `annotationsForTransfer()` builds per-event proto annotations → `BundleBuilder` assembles GRE messages (Diff/Full GSM + ActionsAvailableReq) → `MessageSink` → Arena client.

**Inbound (client → engine):** Arena proto (`PerformActionResp`, `DeclareAttackersResp`, etc.) → `MatchSession` handler → translates to `PlayerAction` or prompt response → submits through `GameActionBridge.submitAction()` or `InteractivePromptBridge.submitResponse()` (both `CompletableFuture.complete()`) → engine thread unblocks.

**Threading:** Engine runs on a dedicated daemon thread, blocks on `CompletableFuture.get()` at every priority stop / prompt. `MatchSession` receives client messages on Netty I/O thread, completes the future. All session entry points synchronized on `sessionLock`. Timeout = engine blocked waiting for a response MatchSession never submitted.

**Event-driven annotations:** Forge fires `GameEvent` on its Guava EventBus → `GameEventCollector` (subscribes synchronously on engine thread) translates to `NexusGameEvent` sealed variants → queued in `ConcurrentLinkedQueue` → `StateMapper` drains at diff-build time → `AnnotationBuilder.categoryFromEvents()` picks most-specific category (LandPlayed > ZoneChanged) → builder methods construct proto `AnnotationInfo` with correct Arena type numbers and detail keys.

**Three-stage diff pipeline:** (1) `detectZoneTransfers` → `TransferResult` — realloc instanceIds, return patched objects/zones + deferred retirements/zone recordings. (2) `annotationsForTransfer` — pure function, proto annotations per transfer. (3) `combatAnnotations` — damage/life/phase annotations. All numbered after assembly.

## Cookbook

### Adding a new annotation type

1. `GameEventCollector` — subscribe to Forge `GameEvent`, emit `NexusGameEvent`
2. `NexusGameEvent.kt` — add sealed variant with forge card IDs (not instanceIds)
3. `AnnotationBuilder` — add builder method matching Arena annotation type number + detail keys (reference `mtga-internals/docs/13-annotation-system.md`)
4. `StateMapper` annotation pipeline — wire event into annotation generation (either transfer-based or standalone in `buildFromGame`)
5. Test: unit test in `AnnotationBuilderTest`, category test in `CategoryFromEventsTest`

### Adding a new zone transition category

1. `TransferCategory.kt` — add variant if needed (with `.label` matching Arena's reason string)
2. `GameEventCollector` — ensure the right Forge event is captured (e.g. `GameEventCardDestroyed` → `CardDestroyed`)
3. `AnnotationBuilder.categoryFromEvents()` — add match arm; specific events take priority over generic `ZoneChanged`
4. `StateMapper.annotationsForTransfer()` — add `when` branch for the new category (ObjectIdChanged, ZoneTransfer, etc.)
5. Test: `CategoryFromEventsTest` for event→category mapping, conformance test for full proto output

### Adding a new client action handler

1. `MatchSession` — add handler method (e.g. `onDeclareAttackers`)
2. Translate Arena proto fields to Forge `PlayerAction` or prompt response (instanceId → forgeCardId via `bridge.getForgeCardId()`)
3. Submit through appropriate bridge: `GameActionBridge` for priority actions, `InteractivePromptBridge` for engine-initiated choices
4. Wire handler in `MatchHandler` message dispatch (match on `ClientMessageType`)
5. Test: `MatchFlowHarness` test exercising the full production path (zero reimplemented logic)

### Debugging a test timeout

1. Read the timeout log — `BridgeTimeoutDiagnostic` auto-captures phase, stack, priority holder, and engine thread trace on every timeout
2. If engine thread is in a bridge's `CompletableFuture.get()`: `MatchSession` handler isn't wiring through, or isn't translating the proto correctly
3. If engine thread is elsewhere (e.g. desktop `Input` class): unimplemented `WebPlayerController` override — needs bridge integration
4. Check phase in diagnostic: combat phases need combat-specific handlers (`onDeclareAttackers` etc.), not just `onPerformAction`

### Debugging a proto conformance failure

See `docs/conformance-debugging.md` — covers annotation ordering, category codes, instanceId lifecycle, gsId chain, detail key types, diff vs full, and triage flow.

## Client UI Automation

Synthetic mouse clicks work on macOS 15+ with Unity. Two requirements:

1. **Window must be foreground** — Unity ignores input on background windows
2. **CGEvent timestamp must be non-zero** — macOS Sequoia+ silently drops events with timestamp=0

### Tools

| Command | What |
|---------|------|
| `just click <x> <y>` | Left click (activates window first) |
| `just click <x> <y> move` | Move cursor only |
| `just click <x> <y> right` | Right click |
| `just click <x> <y> double` | Double click |
| `just capture-screenshot` | JPEG screenshot to `/tmp/mtga.jpg` |

Implementation: `tools/click.swift` — compiled binary at `tools/click`. Uses `CGEvent` + `mach_absolute_time()` posted at `kCGHIDEventTap`.

### Coordinate System

- Screen is 4K (3840x2160) but CGEvent uses **logical points** (1920x1080)
- Client window: origin (0,0), 1920x1108 (1080 content + 28 title bar)
- Screenshots are downscaled to 1280px wide (factor 1.5x from 1920)
- **To convert screenshot coords to click coords**: multiply by 1.5

Example: "Play" button at screenshot (1140, 710) → click at (1710, 1065).

### Coordinate mapping is fragile

The 1280→1920 scaling assumes a fixed window size and position. If the window moves or resizes, coordinates break. Future improvement: detect window bounds at click time and compute dynamically, or use relative coordinates (percentage of window).

### Iteration Loop

```
just capture-screenshot          # see current state
just click <x> <y>              # click a UI element
sleep 1
just capture-screenshot          # verify result
```

Check `/api/client-errors` after interactions for silent failures.

### What DOESN'T Work

- `cliclick` — uses CGEvent with timestamp=0, dropped by Sequoia
- AppleScript `click at` — same issue
- Clicking without activating window — Unity ignores background input
- `osascript -e 'tell app "System Events" to click'` — Accessibility API, Unity doesn't respond
