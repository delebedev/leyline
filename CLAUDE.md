# leyline

**Recording is the spec.** Real server recordings are the source of truth for protocol conformance — not guesses, not docs. Mine segments from recordings, templatize instance IDs, reproduce with matching cards in puzzles, compare hydrated templates against engine output.

Full reimplementation of the client's Front Door, Match Door, and Account Server — makes the game client connect to Forge's engine instead of the official servers.

**Engineering stance:** correctness over speed. The protocol is opaque and the client is unforgiving — shortcuts compound. Build it right, test it right, tool it right.

- **Depends on:** forge (engine submodule — game bridges, bootstrap) — never reverse the dependency
- **Server modes:** `just serve` (local, main dev — fully offline), `just serve-proxy` (passthrough for recording), `just serve-replay`, `just serve-puzzle <file>`
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
- `client-errors.jsonl` — legacy client exceptions (now use `just scry state`)
- `capture/frames/` — raw binary frames
- `analysis.json` — post-session analysis output

`recordings/latest` points to the most recent session. Recordings are the primary source of truth for understanding what the real server sends — use `just fd-*` and `just rec-*` commands to inspect them.

## Testing

All tests use **Kotest FunSpec** (JUnit Platform). See `.claude/rules/nexus-tests.md` for tags, setup tiers, conventions, and per-module commands. Key rule: **scope tests to changed modules, don't run everything.**

## Debugging

- **Server logs:** `logs/leyline.log` (rotated daily, gzipped). Read this instead of piping server output.
- **Client errors:** `just scry state` (parses Player.log for GRE state + exceptions). HTTP: `just scry serve` on :8091. See `docs/debug-api.md`.
- **Debug server** on `:8090` (auto-starts with `just serve`) — browser UI for live game state, priority log, accumulator. Full endpoint reference: `docs/debug-api.md`. Prefer reading files/using CLI tools over HTTP API for non-live inspection.

## Reference

- **Mechanic catalog:** `docs/catalog.yaml` — what works, what's wired, what's missing. Organized by gameplay mechanic (what players experience), not protocol internals. Read this first to understand current state. Update it when changing mechanic support.
- **Rosetta table:** `docs/rosetta.md` — Arena protocol ↔ Forge engine ↔ leyline code. Annotation types, zone IDs, transfer categories, action types, GRE messages, phase mapping, GameEvent wiring status. Protocol-level reference for when you need type numbers and field details.

## Architecture

See `docs/architecture.md` for diagrams. See `matchdoor/CLAUDE.md` for engine adapter architecture, mental model, cookbook, and debugging guides.

## Build Setup (from scratch)

Fresh clone needs these steps in order:

```bash
git submodule update --init --recursive   # forge + proto/upstream
just install-forge                        # mvn install forge jars to forge/.m2-local/
just build                                # gradle: proto-sync + compileKotlin + jar + writeClasspath
just dev-setup                            # gen TLS certs (needs mitmproxy CA), patch Arena, macOS defaults
just seed-db                              # create data/player.db with starter decks + player
```

**Gotchas:**
- `just build` runs `classes jar` — produces jars. But a **running server** holds old jars in memory. After code changes, restart the server (`just stop` + `just serve`) to pick up new classes. `just dev` auto-restarts on `.kt` changes.
- Forge submodule must point to a commit that exists on remote. If `git submodule update` fails with "Unable to find current revision", the pinned commit was force-pushed away. Fix: `git -C forge checkout origin/master`.
- `just gen-certs` needs hostnames from `Player.log`. If log is empty, pass them explicitly: `just gen-certs "frontdoor-mtga-production-<ver>.w2.mtgarena.com" "matchdoor-mtga-production-<ver>.w2.mtgarena.com"` (positional args, not `fd_host=`).
- `data/` dir must exist before `just seed-db` — `mkdir -p data` if missing.
- `deploy/services-proxy.conf` is gitignored — copy from mini: `scp mini:~/src/leyline/deploy/services-proxy.conf deploy/`.
- `/etc/hosts` needs FD+MD → 127.0.0.1 for proxy mode (doorbell stays commented out). Compare with `ssh mini 'cat /etc/hosts'`.

## Proto

`proto/upstream/messages.proto` → `matchdoor/src/main/proto/messages.proto` via `just sync-proto`. The upstream submodule has the raw client schema; `proto/rename-map.sed` applies field/type renames for readability. `just sync-proto` runs the sed transform + triggers protobuf codegen. Don't edit `messages.proto` directly — edit the rename map and re-sync.

## Quick Reference

- **Card lookups:** `just card <grpId>`, `just card-grp <name>`, `just ability <id>`, `just card-script <name>`. Full reference: `docs/playbooks/card-lookup-playbook.md`.
- **Arena CLI:** `bin/arena` — `click`, `ocr`, `wait`, `capture`, `state`, `issues`. Docs: `tools/arena/docs/cli.md`, `tools/arena/docs/nav.md`.
- **Recording tools:** `tape session list`, `tape session show`, `tape proto decode`, `tape annotation ranges`. Docs: `tools/tape/docs/cli.md`.
- **FD inspection:** `wire tail`, `wire search`, `wire show`, `wire flow`, `wire coverage`. Run `wire --help` for all commands.
