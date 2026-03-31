# leyline

Local server that makes the Magic: The Gathering Arena client connect to Forge (open-source rules engine) instead of official servers. Reimplements the client's Front Door (lobby/decks/matchmaking), Match Door (game protocol), and Account Server (auth/JWT).

- **Depends on:** forge (engine submodule — game bridges, bootstrap) — never reverse the dependency
- **Server mode:** `just serve` (local, fully offline)
- **Current priority:** [v0.1 release](docs/v0.1-release.md) — first public release, FDN as core set
- **Roadmap:** [GitHub Project board](https://github.com/users/delebedev/projects/1)
- **Bugs & tasks:** `bd` (beads) for agent work; GitHub Issues for public-facing bugs/features

**Engineering stance:** correctness over speed. The protocol is opaque and the client is unforgiving — shortcuts compound.

**Recording is the spec.** Real server recordings are the source of truth for protocol conformance — not guesses, not docs. Recording tooling (`wire`, `tape`, `serve-proxy`) moved to `~/src/leyline-private`.

**`just wire` / `just tape` no longer exist in this repo.** Many docs, skills, and playbooks still reference them. When you encounter `just wire` or `just tape` commands in docs, try `just scry-ts` instead — it covers game state, board, card trace, GSM queries, and lobby inspection from Player.log. Run `just scry-ts --help` for available commands.

## Agent Policy

**IMPORTANT: These rules are non-negotiable. Follow them exactly.**

- **NEVER commit to main.** Always branch + PR. Naming: `feat/<topic>`, `fix/<topic>`, `refactor/<topic>`. No exceptions.
- **Plan before building.** YOU MUST enter plan mode for architectural decisions or changes touching 3+ files. Don't start coding complex work without alignment.
- **Stop and re-plan.** If something goes sideways after 2 attempts, STOP. Explain what you tried and what's blocking. Don't silently change approach — state what you're changing and why.
- **Autonomous bug fixing.** Given a bug report: just fix it. Logs → errors → failing test → resolve. Zero hand-holding.
- **Ship the feature, not just the code.** Before PRing, ask: "Does this work end-to-end from the user's perspective?" Tests green ≠ feature complete. "Pre-existing" is not an excuse when YOU created the context where it matters. "Follow-up" is not appropriate for gaps that make the feature non-functional. Trivial blockers (< 5 min) ship with the feature, not after.
- **Elegance balance.** Non-trivial changes: pause and ask "is there a more elegant way?" Skip for simple obvious fixes.
- **Learn from corrections.** After ANY correction from the user, update `docs/lessons.md` with the pattern — what went wrong, why, how to avoid it. Review before starting complex work.
- **Ralph PRs get labeled.** Add `--label ralph` to `gh pr create` in ralph-loop sessions.

## Task Tracking (beads)

`bd` is the issue tracker. Embedded Dolt DB in `.beads/`, git-tracked.

```bash
bd ready                    # what's unblocked and available
bd show <id>                # full details + deps
bd create --title="..." --description="..." --type=task --priority=2  # new issue
bd update <id> --claim      # claim + mark in-progress
bd close <id>               # done
bd dep add <child> <parent> # wire dependencies
bd search <query>           # full-text search
bd remember "insight"       # persistent cross-session memory
bd prime                    # session context dump (memories, workflow)
```

- **Priority:** 0–4 (0=critical, 4=backlog). Not words.
- **Don't use `bd edit`** — opens $EDITOR, blocks agents. Use `bd update <id> --description="..."` inline.
- GH issues remain for public bug reports and external contributors.

## Modules

```
app/            Composition root — LeylineMain, Netty pipeline, debug server, seed DB.
account/        Account server (Ktor HTTPS) — auth, JWT, doorbell. Zero forge deps.
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

**Worktrees need `just bootstrap` before anything else** — they don't share submodule checkouts or build artifacts.

## Testing

Kotest FunSpec (JUnit Platform). Details: `.claude/rules/leyline-tests.md`.

- `just test-one <ClassName>` — single class
- `just test-gate` — pre-commit (all modules + fmt)
- `just test-integration` — risky matchdoor changes
- **Scope tests to changed modules, don't run everything.**

## Debugging

- **Logs:** `logs/leyline.log` (read this, don't pipe server output)
- **Client errors:** `just scry state`
- **Debug server:** `:8090` — live game state, priority log. See `docs/debug-api.md`.

## Reference

- **Mechanic catalog:** `docs/catalog.yaml` — what works, what's missing. Update when changing mechanic support.
- **Rosetta table:** `docs/rosetta.md` — Arena ↔ Forge ↔ leyline type mappings.
- **Architecture:** `docs/architecture.md`, `matchdoor/CLAUDE.md` (engine adapter internals).

## Puzzles

Primary acceptance tool. `.pzl` files define exact board states — minimal cards, one win path, forced mechanics. See `docs/puzzle-driven-dev.md` for the full workflow.

- `just puzzle-check <file>` — mandatory before commit (missing grpId = NPE)

## Proto

Don't edit `messages.proto` directly — edit `proto/rename-map.sed` and run `just sync-proto`. See `.claude/rules/build-infra.md` for full workflow.

## Public Repo — Content Rules

Every commit is public. **IMPORTANT: Violating these rules exposes the project legally.**

- **No captured WotC data.** Never commit server responses, recordings, card database files, or proxy captures. Golden files must be hand-written. Test fixtures must use synthetic data.
- **No private research repo references.** Don't link to, quote paths from, or reference external private repos.
- **No personal infra details.** No hardcoded IPs, hostnames, or absolute paths. Use `~/` or relative paths.
- **Tone: local playtesting tool.** "Reimplemented protocol" not "reverse-engineered". "Local server" not "private server".
- **Interop data is fine.** grpIds, set codes, CmdType numbers, loc keys — functional protocol identifiers.
