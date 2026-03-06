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

## Architecture & Cookbook

See `docs/architecture.md` for diagrams. See `matchdoor/CLAUDE.md` for engine adapter architecture, mental model, cookbook (annotations, zone categories, action handlers), and debugging guides (timeouts, conformance).

### Card & ability lookups

`just card 75515 93848` — grpId → name. `just ability 169561` — ability → owning card + text. `just card-grp "Ajani's Pridemate"` — name → grpId. `just card-script "Unholy Annex"` — name → Forge script path. `just cards-in-session latest` — all cards in a recording session. Full reference: `docs/playbooks/card-lookup-playbook.md`.

## Client UI Automation

**Arena CLI** (`bin/arena`) — high-level MTGA automation: `click`, `ocr`, `wait`, `capture`, `state`, `issues`. Full reference: `docs/arena-cli.md`, navigation guide: `docs/arena-nav.md`.
