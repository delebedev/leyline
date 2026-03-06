# leyline

Client compat layer — stubs/proxies the client's Front Door + Match Door + Account Server so the game client connects to Forge's engine.

- **Transport:** raw Netty TLS TCP (not HTTP — client uses 6-byte framing + protobuf)
- **Depends on:** forge-web (game bridges, bootstrap) — never reverse the dependency
- **Proto:** `matchdoor/src/main/proto/messages.proto` — client protobuf schema (from MtgaProto project)
- **Card data:** `ExposedCardRepository` reads the client's local SQLite for grpId, types, mana cost
- **Server modes:** `just serve` (local, main dev — fully offline), `just serve-proxy` (passthrough for recording), `just serve-replay`
- **Roadmap:** [GitHub Project board](https://github.com/users/delebedev/projects/1) — epics for Multiplayer, Sealed, Draft, Direct Challenge, Match History, Social, Brawl/Commander
- **Bugs & tasks:** GitHub Issues — no local TODO/BUGS files

## Modules

```
app/            Composition root — LeylineMain, LeylineServer, Netty pipeline, debug wiring
                Depends on all other modules. Thin — mostly startup + glue.

account/        Account server (Ktor HTTPS) — auth, registration, JWT, doorbell.
                Independent. Zero forge/netty/protobuf deps.

frontdoor/      Front Door protocol — lobby, decks, events, matchmaking, collections.
                Wire format (FdEnvelope, CmdType), domain model, persistence.
                Zero coupling to game engine.

matchdoor/      Game engine adapter — the big one.
                bridge/ (Forge adapter), game/ (state mapping, annotations, proto builders),
                match/ (orchestration, combat, targeting, mulligan handlers).
                Owns proto generation. Structural Forge coupling lives here.

tooling/        Dev-only — debug server, session recording, analysis, conformance,
                arena CLI automation. Not on prod classpath.
```

Other dirs: `bin/` (CLI tools), `docs/`, `forge/` (engine submodule), `gradle/`, `just/` (task recipes), `proto/` (upstream proto submodule).

## Testing

All tests use **Kotest FunSpec** (JUnit Platform). See `.claude/rules/nexus-tests.md` for tags, setup tiers, and conventions. Key commands: `just test-gate` (pre-commit), `just test-one Foo` (single class).

## Debug Panel & API

Debug server on `:8090` (auto-starts with `just serve`). Full endpoint reference: `docs/debug-api.md`.

**Client error watcher:** auto-tails `Player.log` during `just serve`. Client-side exceptions (annotation parse failures, missing fields) appear inline in server output and are queryable at `/api/client-errors`. Errors persisted to `recordings/<session>/client-errors.jsonl`. Standalone: `just watch-client`.

## Reference

- **Mechanic catalog:** `docs/catalog.yaml` — what works, what's wired, what's missing. Organized by gameplay mechanic (what players experience), not protocol internals. Read this first to understand current state. Update it when changing mechanic support.
- **Rosetta table:** `docs/rosetta.md` — Arena protocol ↔ Forge engine ↔ leyline code. Annotation types, zone IDs, transfer categories, action types, GRE messages, phase mapping, GameEvent wiring status. Protocol-level reference for when you need type numbers and field details.

## Mental Model

See `docs/architecture.md` for diagrams. This is the fast orientation.

**Outbound (engine → client):** Forge `Game` → `StateMapper.buildFromGame()` snapshots zones/objects/players → `GameEventCollector.drainEvents()` feeds `AnnotationBuilder.categoryFromEvents()` for transfer categories → `annotationsForTransfer()` builds per-event proto annotations → `BundleBuilder` assembles GRE messages (Diff/Full GSM + ActionsAvailableReq) → `MessageSink` → Arena client.

**Inbound (client → engine):** Arena proto (`PerformActionResp`, `DeclareAttackersResp`, etc.) → `MatchSession` handler → translates to `PlayerAction` or prompt response → submits through `GameActionBridge.submitAction()` or `InteractivePromptBridge.submitResponse()` (both `CompletableFuture.complete()`) → engine thread unblocks.

**Threading:** Engine runs on a dedicated daemon thread, blocks on `CompletableFuture.get()` at every priority stop / prompt. `MatchSession` receives client messages on Netty I/O thread, completes the future. All session entry points synchronized on `sessionLock`. Timeout = engine blocked waiting for a response MatchSession never submitted.

**Event-driven annotations:** Forge fires `GameEvent` on its Guava EventBus → `GameEventCollector` (subscribes synchronously on engine thread) translates to `GameEvent` sealed variants → queued in `ConcurrentLinkedQueue` → `StateMapper` drains at diff-build time → `AnnotationBuilder.categoryFromEvents()` picks most-specific category (LandPlayed > ZoneChanged) → builder methods construct proto `AnnotationInfo` with correct Arena type numbers and detail keys.

**Three-stage diff pipeline:** (1) `detectZoneTransfers` → `TransferResult` — realloc instanceIds, return patched objects/zones + deferred retirements/zone recordings. (2) `annotationsForTransfer` — pure function, proto annotations per transfer. (3) `combatAnnotations` — damage/life/phase annotations. All numbered after assembly.

## Cookbook

### Adding a new annotation type (matchdoor)

1. `game/GameEventCollector` — subscribe to Forge `GameEvent`, emit `GameEvent`
2. `game/GameEvent.kt` — add sealed variant with forge card IDs (not instanceIds)
3. `game/AnnotationBuilder` — add builder method matching Arena annotation type number + detail keys (reference `mtga-internals/docs/13-annotation-system.md`)
4. `game/StateMapper` annotation pipeline — wire event into annotation generation (either transfer-based or standalone in `buildFromGame`)
5. Test: unit test in `AnnotationBuilderTest`, category test in `CategoryFromEventsTest`

### Adding a new zone transition category (matchdoor)

1. `game/TransferCategory.kt` — add variant if needed (with `.label` matching Arena's reason string)
2. `game/GameEventCollector` — ensure the right Forge event is captured (e.g. `GameEventCardDestroyed` → `CardDestroyed`)
3. `game/AnnotationBuilder.categoryFromEvents()` — add match arm; specific events take priority over generic `ZoneChanged`
4. `game/StateMapper.annotationsForTransfer()` — add `when` branch for the new category (ObjectIdChanged, ZoneTransfer, etc.)
5. Test: `CategoryFromEventsTest` for event→category mapping, conformance test for full proto output

### Adding a new client action handler (matchdoor)

1. `match/MatchSession` — add handler method (e.g. `onDeclareAttackers`)
2. Translate Arena proto fields to Forge `PlayerAction` or prompt response (instanceId → forgeCardId via `bridge.getForgeCardId()`)
3. Submit through appropriate bridge: `GameActionBridge` for priority actions, `InteractivePromptBridge` for engine-initiated choices
4. Wire handler in `match/MatchHandler` message dispatch (match on `ClientMessageType`)
5. Test: `MatchFlowHarness` test exercising the full production path (zero reimplemented logic)

### Card & ability lookups

`just card 75515 93848` — grpId → name. `just ability 169561` — ability → owning card + text. `just card-grp "Ajani's Pridemate"` — name → grpId. `just card-script "Unholy Annex"` — name → Forge script path. `just cards-in-session latest` — all cards in a recording session. Full reference: `docs/playbooks/card-lookup-playbook.md`.

### Debugging a test timeout

1. Read the timeout log — `BridgeTimeoutDiagnostic` auto-captures phase, stack, priority holder, and engine thread trace on every timeout
2. If engine thread is in a bridge's `CompletableFuture.get()`: `MatchSession` handler isn't wiring through, or isn't translating the proto correctly
3. If engine thread is elsewhere (e.g. desktop `Input` class): unimplemented `WebPlayerController` override — needs bridge integration
4. Check phase in diagnostic: combat phases need combat-specific handlers (`onDeclareAttackers` etc.), not just `onPerformAction`

### Debugging a proto conformance failure

See `docs/conformance-debugging.md` — covers annotation ordering, category codes, instanceId lifecycle, gsId chain, detail key types, diff vs full, and triage flow.

## Client UI Automation

**Arena CLI** (`bin/arena`) — high-level MTGA automation: `click`, `ocr`, `wait`, `capture`, `state`, `issues`. Full reference: `docs/arena-cli.md`, navigation guide: `docs/arena-nav.md`.
