# leyline

Full reimplementation of the client's Front Door, Match Door, and Account Server — makes the game client connect to Forge's engine instead of the official servers.

**Engineering stance:** correctness over speed. The protocol is opaque and the client is unforgiving — shortcuts compound. Build it right, test it right, tool it right.

- **Depends on:** forge-web (game bridges, bootstrap) — never reverse the dependency
- **Server modes:** `just serve` (local, main dev — fully offline), `just serve-proxy` (passthrough for recording), `just serve-replay`
- **Roadmap:** [GitHub Project board](https://github.com/users/delebedev/projects/1)
- **Bugs & tasks:** GitHub Issues — no local TODO/BUGS files

## Modules

```
app/            Composition root — LeylineMain, LeylineServer, Netty pipeline, debug wiring.
                Raw TLS TCP (not HTTP — 6-byte framing + protobuf). Thin — mostly startup + glue.

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

Other dirs: `bin/` (CLI tools), `docs/`, `forge/` (engine submodule), `gradle/`, `just/` (task recipes), `proto/` (upstream proto submodule), `recordings/` (session captures — see below).

## Recordings

`just serve-proxy` captures full client↔server sessions to `recordings/<timestamp>/`. Each session contains:

- `capture/fd-frames.jsonl` — Front Door request/response frames (decoded JSON payloads)
- `md-frames.jsonl` — Match Door GRE messages (protobuf)
- `events.jsonl` — game events timeline
- `client-errors.jsonl` — client-side exceptions captured from `Player.log`
- `capture/frames/` — raw binary frames
- `analysis.json` — post-session analysis output

`recordings/latest` points to the most recent session. `just serve` (non-proxy) only captures `client-errors.jsonl`. Recordings are the primary source of truth for understanding what the real server sends — use `just fd-*` and `just rec-*` commands to inspect them.

## Testing

All tests use **Kotest FunSpec** (JUnit Platform). See `.claude/rules/nexus-tests.md` for tags, setup tiers, conventions, and per-module commands. Key rule: **scope tests to changed modules, don't run everything.**

## Debugging

- **Server logs:** `logs/leyline.log` (rotated daily, gzipped). Read this instead of piping server output.
- **Client errors:** `recordings/<session>/client-errors.jsonl` (persisted per session). Arena log: `~/Library/Logs/Wizards of the Coast/MTGA/Player.log`. Auto-tailed during `just serve`; standalone: `just watch-client`.
- **Debug server** on `:8090` (auto-starts with `just serve`) — browser UI for live game state, priority log, accumulator. Full endpoint reference: `docs/debug-api.md`. Prefer reading files/using CLI tools over HTTP API for non-live inspection.

## Reference

- **Mechanic catalog:** `docs/catalog.yaml` — what works, what's wired, what's missing. Organized by gameplay mechanic (what players experience), not protocol internals. Read this first to understand current state. Update it when changing mechanic support.
- **Rosetta table:** `docs/rosetta.md` — Arena protocol ↔ Forge engine ↔ leyline code. Annotation types, zone IDs, transfer categories, action types, GRE messages, phase mapping, GameEvent wiring status. Protocol-level reference for when you need type numbers and field details.

## Architecture & Cookbook

See `docs/architecture.md` for diagrams. See `matchdoor/CLAUDE.md` for engine adapter architecture, mental model, cookbook (annotations, zone categories, action handlers), and debugging guides (timeouts, conformance).

### Card & ability lookups

`just card 75515 93848` — grpId → name. `just ability 169561` — ability → owning card + text. `just card-grp "Ajani's Pridemate"` — name → grpId. `just card-script "Unholy Annex"` — name → Forge script path. `just cards-in-session latest` — all cards in a recording session. Full reference: `docs/playbooks/card-lookup-playbook.md`.

## Client UI Automation

**Arena CLI** (`bin/arena`) — high-level MTGA automation: `click`, `ocr`, `wait`, `capture`, `state`, `issues`. Full reference: `docs/arena-cli.md`, navigation guide: `docs/arena-nav.md`.
