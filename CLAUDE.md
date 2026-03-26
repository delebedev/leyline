# leyline

**Recording is the spec.** Real server recordings are the source of truth for protocol conformance — not guesses, not docs. Mine segments from recordings, templatize instance IDs, reproduce with matching cards in puzzles, compare hydrated templates against engine output.

Full reimplementation of the client's Front Door, Match Door, and Account Server — makes the game client connect to Forge's engine instead of the official servers.

**Engineering stance:** correctness over speed. The protocol is opaque and the client is unforgiving — shortcuts compound. Build it right, test it right, tool it right.

- **Depends on:** forge (engine submodule — game bridges, bootstrap) — never reverse the dependency
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
- `client-errors.jsonl` — legacy client exceptions (now use `just scry state`)
- `capture/frames/` — raw binary frames
- `analysis.json` — post-session analysis output
- `notes.md` — human observations (notable plays, conformance moments, deck context). **Always read this first when inspecting a session.**

`recordings/latest` points to the most recent session. Recordings are the primary source of truth for understanding what the real server sends — use `just wire` and `just tape` to inspect them.

## Puzzle-Driven Dev

Puzzles are the primary acceptance tool. `.pzl` files define exact board states — minimal cards, one win path, forced mechanics. The dev loop: write puzzle → run in MatchHarness (<5s cycles) → fix code → Arena playtest for visual proof.

- **Files:** `puzzles/` (acceptance targets), `matchdoor/src/test/resources/puzzles/` (test fixtures)
- **Run in Arena:** set `game.puzzle = "puzzles/bolt-face.pzl"` in `leyline.toml`, then `just serve` and launch Sparky
- **Run in tests:** `base.startPuzzleAtMain1(puzzleText)` (Kotest, ~0.09s)
- **Validate cards:** `just puzzle-check <file>` — mandatory before commit (missing grpId = NPE)
- **Docs:** `docs/puzzle-design.md` (authoring guide), `docs/puzzle-driven-dev.md` (workflow), `docs/puzzles.md` (architecture)

## Testing

All tests use **Kotest FunSpec** (JUnit Platform). See `.claude/rules/leyline-tests.md` for tags, setup tiers, conventions, and per-module commands.

- **Default loop:** run module-scoped Gradle tasks or `just test-one <ClassName>`
- **Pre-handoff / pre-push:** `just test-gate`
- **Risky matchdoor/runtime changes:** `just test-integration`

Key rule: **scope tests to changed modules, don't run everything.**

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

One command gets you from fresh clone **or fresh worktree** to runnable tests:

```bash
just bootstrap    # submodules → forge install → build → seed DB
```

This handles: git submodule init, maven install of forge engine, gradle proto-sync + compile + jars, and database seeding. Prerequisite: `mvn` in PATH (`brew install maven`). Safe to re-run — skips forge install if already up to date.

**Worktrees:** Git worktrees do NOT share submodule checkouts or build artifacts. A fresh worktree has an empty `forge/` dir and no jars. **Always run `just bootstrap` first** in a new worktree before building, testing, or serving. This is the single command that makes the worktree functional — don't try to run `just build` or `just test-gate` without it.

**For Arena client connection** (optional, not needed for tests):
```bash
just dev-setup    # patch MTGA for localhost + macOS defaults
```

**Individual steps** (when bootstrap isn't enough or you need to debug):
```bash
git submodule update --init --recursive   # forge + proto/upstream
just install-forge                        # mvn install forge jars to forge/.m2-local/
just build                                # gradle: proto-sync + compileKotlin + jar + writeClasspath
just seed-db                              # create data/player.db with starter decks + player
```

**Gotchas:**
- `just build` runs `classes jar` — produces jars. But a **running server** holds old jars in memory. After code changes, restart the server (`just stop` + `just serve`) to pick up new classes. `just dev` auto-restarts on `.kt` changes.
- Forge submodule must point to a commit that exists on remote. If `git submodule update` fails with "Unable to find current revision", the pinned commit was force-pushed away. Fix: `git -C forge checkout origin/master`.
- TLS certs are auto-generated at server boot from the mitmproxy CA (`~/.mitmproxy/`). Hostnames are discovered from `/etc/hosts`. Certs regenerate automatically when hostnames change (Arena patch).
- `deploy/services-proxy.conf` is gitignored — create it locally from `deploy/services-proxy.example.conf` and fill private proxy creds when needed. `just serve-proxy` now fails fast if the file is missing or still uses the example credential values.
- `/etc/hosts` needs FD+MD → 127.0.0.1 for proxy mode (doorbell stays commented out).

## Proto

`proto/upstream/messages.proto` → `matchdoor/src/main/proto/messages.proto` via `just sync-proto`. The upstream submodule has the raw client schema; `proto/rename-map.sed` applies field/type renames for readability. `just sync-proto` runs the sed transform + triggers protobuf codegen. Don't edit `messages.proto` directly — edit the rename map and re-sync.

## Quick Reference

All tool CLIs run via just: `just wire ...`, `just tape ...`, `just arena ...`. Always use `just` to invoke tools — never call Python scripts directly. **Discovery:** append `--help` to any tool/subcommand for full usage.

- **Card lookups:** `just card <grpId>`, `just card-grp <name>`, `just ability <id>`, `just card-script <name>`. Full reference: `docs/playbooks/card-lookup-playbook.md`.
- **Arena CLI:** `just arena` — `click`, `ocr`, `wait`, `capture`, `state`, `issues`. Docs: `tools/arena/docs/cli.md`, `tools/arena/docs/nav.md`.
- **Recording tools:** `just tape session list`, `just tape session show`, `just tape proto decode`, `just tape annotation ranges`. Docs: `tools/tape/docs/cli.md`.
- **FD inspection:** `just wire tail`, `just wire search`, `just wire show`, `just wire flow`, `just wire coverage`. Run `just wire --help` for all commands.

## Public Repo — Content Rules

This is an open-source repo. Every commit is public. Follow these rules:

- **No captured WotC data.** Never commit server responses, recordings, card database files, or proxy captures. Golden files must be hand-written. Test fixtures must use synthetic data.
- **No private research repo references.** Don't link to, quote paths from, or reference external private repos.
- **No personal infra details.** No hardcoded IPs, hostnames (`mini`, `klava`), or absolute paths (`/Users/...`). Use `~/` or relative paths.
- **Tone: local playtesting tool.** Use "reimplemented protocol" not "reverse-engineered". Use "local server" not "private server". Frame proxy mode as development tooling, not interception.
- **Interop data is fine.** grpIds, set codes, format names, CmdType numbers, loc keys — these are functional protocol identifiers required for compatibility.

## Agent Policy

- **Never commit to main.** Feature work, bug fixes, refactors — always on a branch with a PR. Branch naming: `feat/<topic>`, `fix/<topic>`, `refactor/<topic>`. Batching disparate small fixes into one PR is fine; big features get their own branch.
- **Stop and re-plan.** If something goes sideways after 2 attempts, STOP. Explain what you tried and what's blocking. Don't silently change approach — state what you're changing and why.
- **Autonomous bug fixing.** Given a bug report: just fix it. Read logs, find errors, write failing test, resolve. Zero hand-holding required from the user.
- **Elegance balance.** For non-trivial changes, pause and ask "is there a more elegant way?" If a fix feels hacky, implement the clean solution. Skip this for simple, obvious fixes — don't over-engineer.
- **Ralph PRs get labeled.** When creating PRs inside a ralph-loop session, add `--label ralph` to `gh pr create`.
