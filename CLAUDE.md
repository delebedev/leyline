# leyline

Local playtesting server built around a client protocol bridge and Forge (open-source rules engine). Reimplements Front Door (lobby/decks/matchmaking), Match Door (game protocol), and local account/bootstrap flows.

- **Depends on:** forge (engine submodule — game bridges, bootstrap) — never reverse the dependency
- **Server mode:** `just serve` (local-only)
- **Bugs & tasks:** `bd` (beads) for agent work; GitHub Issues for public-facing bugs/features

**Engineering stance:** correctness over speed. The protocol is opaque and the client is unforgiving — shortcuts compound.

**Use runtime logs and client-visible failures as primary debugging evidence.**

## Agent Policy

**IMPORTANT: These rules are non-negotiable. Follow them exactly.**

- **NEVER commit to main.** Always branch + PR. Naming: `feat/<topic>`, `fix/<topic>`, `refactor/<topic>`. No exceptions.
- **Plan before building.** YOU MUST enter plan mode for architectural decisions or changes touching 3+ files. Don't start coding complex work without alignment.
- **Stop and re-plan.** If something goes sideways after 2 attempts, STOP. Explain what you tried and what's blocking. Don't silently change approach — state what you're changing and why.
- **Autonomous bug fixing.** Given a bug report: just fix it. Logs → errors → failing test → resolve. Zero hand-holding.
- **Ship the feature, not just the code.** Before PRing, ask: "Does this work end-to-end from the user's perspective?" Tests green ≠ feature complete. "Pre-existing" is not an excuse when YOU created the context where it matters. "Follow-up" is not appropriate for gaps that make the feature non-functional. Trivial blockers (< 5 min) ship with the feature, not after.
- **Elegance balance.** Non-trivial changes: pause and ask "is there a more elegant way?" Skip for simple obvious fixes.
- **Learn from corrections.** Fold durable corrections into the relevant doc or local workflow note. Don't keep a public catch-all lessons log.
- **Ralph PRs get labeled.** Add `--label ralph` to `gh pr create` in ralph-loop sessions.

## Task Tracking (beads)

`bd` is the issue tracker. Dolt DB in `.beads/`, synced via `bd dolt push/pull`.

```bash
bd ready                    # what's unblocked and available
bd show <id>                # full details + deps
bd create --title="..." --description="..." --type=task --priority=2  # new issue
bd update <id> --claim      # claim + mark in-progress
bd close <id>               # done
bd dep add <child> <parent> # wire dependencies
bd search <query>           # title search
bd query 'description=...'  # full-text search across all fields
bd remember "insight"       # persistent cross-session memory
bd prime                    # session context dump (memories, workflow)
```

- **Priority:** 0–4 (0=critical, 4=backlog). Not words.
- **Don't use `bd edit`** — opens $EDITOR, blocks agents. Use `bd update <id> --description="..."` inline.
- **Worktrees:** `bd` doesn't work from worktrees (server discovery bug). Run `bd` from the main repo only.
- GH issues remain for public bug reports and external contributors.

## Modules

```
app/            Composition root — LeylineMain, Netty pipeline, debug server, seed DB.
account/        Account/bootstrap server (Ktor HTTPS) — local login, profile, doorbell. Zero forge deps.
frontdoor/      Front Door protocol — lobby, decks, events, matchmaking, collections.
matchdoor/      Game engine adapter — the big one. See matchdoor/CLAUDE.md.
```

Other dirs: `bin/`, `docs/`, `forge/` (engine submodule), `gradle/`, `just/`, `proto/`.

## Build & Run

```bash
just bootstrap    # fresh clone/worktree → submodules → forge install → build → seed DB
just build        # gradle: proto-sync + compile + jar
just serve        # start server (restart after code changes — JVM holds old bytecode)
```

**End-to-end local client runs require a small amount of local setup.** See `docs/local-client-setup.md`.

**Worktrees need `just bootstrap` before anything else** — they don't share submodule checkouts, but forge jars are cached globally (`~/.cache/leyline/forge-m2/`) so `mvn install` is skipped if another worktree already built the same forge commit.

## Testing

Kotest FunSpec (JUnit Platform). Details: `.claude/rules/leyline-tests.md`.

- `just test-one <ClassName>` — single class
- `just test-gate` — pre-commit (all modules + fmt)
- `just test-integration` — risky matchdoor changes
- **Scope tests to changed modules, don't run everything.**

## Debugging

- **Logs:** `logs/leyline.log` (read this, don't pipe server output)
- **Debug endpoints:** `:8090` — local inspection and puzzle control. See `DebugServer.kt` KDoc.

## Reference

- **Architecture:** `docs/architecture.md`, `matchdoor/CLAUDE.md` (engine adapter internals).
- **Local setup notes:** `docs/local-client-setup.md`

## Documentation

- **Read selectively.** `docs/index.md` is the public entry point.
- **Update docs in the same PR.** If your PR changes public behavior or setup, update the relevant public doc.

## Puzzles

Primary acceptance tool. `.pzl` files define exact board states — minimal cards, one win path, forced mechanics.

- `just puzzle <file>` — set puzzle via debug API (hot-swaps if in match, queues for next local AI match)
- `just puzzle-check <file>` — mandatory before commit (missing grpId = NPE)
- `POST :8090/api/puzzle?file=<name>` — runtime API (GET returns current, POST with no params clears)

## Proto

Don't edit `messages.proto` directly — edit `proto/rename-map.sed` and run `just sync-proto`. See `.claude/rules/build-infra.md` for full workflow.

## Public Repo — Content Rules

Every commit is public. **IMPORTANT: Violating these rules exposes the project legally.**

- **No third-party proprietary data.** Never commit external service responses, vendor databases, or user data. Test fixtures must use synthetic or project-generated data.
- **No private research repo references.** Don't link to, quote paths from, or reference external private repos.
- **No personal infra details.** No hardcoded IPs, hostnames, or absolute paths. Use `~/` or relative paths.
- **Tone: local playtesting tool.** Prefer "protocol implementation" or "local client-compatible server".
- **Interop data is fine.** grpIds, set codes, CmdType numbers, loc keys — functional protocol identifiers.
