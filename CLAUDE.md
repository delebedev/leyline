# leyline

Local server that makes the Magic: The Gathering Arena client connect to Forge (open-source rules engine) instead of official servers. Reimplements the client's Front Door (lobby/decks/matchmaking), Match Door (game protocol), and Account Server (auth/JWT).

- **Depends on:** forge (engine submodule — game bridges, bootstrap) — never reverse the dependency
- **Server mode:** `just serve` (local, fully offline)
- **Current priority:** [v0.1 release](docs/v0.1-release.md) — first public release, FDN as core set
- **Roadmap:** [GitHub Project board](https://github.com/users/delebedev/projects/1)
- **Bugs & tasks:** `bd` (beads) for agent work; GitHub Issues for public-facing bugs/features

**Engineering stance:** correctness over speed. The protocol is opaque and the client is unforgiving — shortcuts compound.

**Player.log is the spec.** Arena logs from real server games are the source of truth for protocol conformance — not guesses, not docs. Use `scry-ts` to trace cards and compare annotation shapes between arena logs and leyline logs.

**`just wire` / `just tape` no longer exist in this repo.** Many docs, skills, and playbooks still reference them. When you encounter `just wire` or `just tape` commands in docs, try `just scry-ts` instead — it covers game state, board, card trace, GSM queries, and lobby inspection from Player.log. Run `just scry-ts --help` for available commands.

**Arena automation is `just arena-ts`.** Click, drag, hand OCR, land/cast, concede. Skills and docs referencing `arena` or `bin/arena` commands should use `just arena-ts` instead. Run `just arena-ts --help` for available commands.

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
account/        Account server (Ktor HTTPS) — auth, JWT, doorbell. Zero forge deps.
frontdoor/      Front Door protocol — lobby, decks, events, matchmaking, collections.
matchdoor/      Game engine adapter — the big one. See matchdoor/CLAUDE.md.
launcher/       Tauri v2 desktop app — player-facing launcher. See launcher/CLAUDE.md.
```

Other dirs: `bin/`, `docs/`, `forge/` (engine submodule), `gradle/`, `just/`, `proto/`.

## Build & Run

```bash
just bootstrap    # fresh clone/worktree → submodules → forge install → build → seed DB
just build        # gradle: proto-sync + compile + jar
just serve        # start server (restart after code changes — JVM holds old bytecode)
```

**Worktrees need `just bootstrap` before anything else** — they don't share submodule checkouts, but forge jars are cached globally (`~/.cache/leyline/forge-m2/`) so `mvn install` is skipped if another worktree already built the same forge commit.

## Arena Automation

**Always run `just arena-ts preflight` before any arena-ts command.** It checks MTGA is running, window is visible, and accessibility permissions are granted. If MTGA isn't running, launch it with `just arena-ts launch`.

```bash
just arena-ts preflight       # check MTGA is ready
just arena-ts launch          # launch MTGA client (1920x1080 windowed)
just arena-ts bot-match       # start a Sparky bot match
just arena-ts scene           # current Arena scene (Home, InGame, etc.)
just arena-ts --help          # full command list
```

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

## Documentation

- **Read selectively.** `docs/` has 150+ files. Don't read them all. Run `just docs` to see summaries, or `just docs <filter>` to search. Only read files whose `read_when` frontmatter matches your current task. `docs/index.md` has curated navigation.
- **Update docs in the same PR.** If your PR changes protocol behavior, mechanic support, or architecture — update the relevant doc. Don't leave it for a follow-up.
- **Changelog per PR.** Add a bullet to the `[Unreleased]` section of `CHANGELOG.md` for every user-visible change. Sections: **Gameplay** (new mechanics, card support), **Launcher** (install, UX), **Fixed** (bugs), **Developer** (protocol, engine, infra, docs).
- **Principles:** `docs/principles-documentation.md` — the full documentation strategy.

## Puzzles

Primary acceptance tool. `.pzl` files define exact board states — minimal cards, one win path, forced mechanics. See `docs/puzzle-driven-dev.md` for the full workflow.

- `just puzzle <file>` — set puzzle via debug API (hot-swaps if in match, queues for next Sparky match)
- `just puzzle-check <file>` — mandatory before commit (missing grpId = NPE)
- `POST :8090/api/puzzle?file=<name>` — runtime API (GET returns current, POST with no params clears)

## Proto

Don't edit `messages.proto` directly — edit `proto/rename-map.sed` and run `just sync-proto`. See `.claude/rules/build-infra.md` for full workflow.

## Public Repo — Content Rules

Every commit is public. **IMPORTANT: Violating these rules exposes the project legally.**

- **No captured WotC data.** Never commit server responses, recordings, card database files, or proxy captures. Golden files must be hand-written. Test fixtures must use synthetic data.
- **No private research repo references.** Don't link to, quote paths from, or reference external private repos.
- **No personal infra details.** No hardcoded IPs, hostnames, or absolute paths. Use `~/` or relative paths.
- **Tone: local playtesting tool.** "Reimplemented protocol" not "reverse-engineered". "Local server" not "private server".
- **Interop data is fine.** grpIds, set codes, CmdType numbers, loc keys — functional protocol identifiers.

<!-- repo-task-proof-loop:start -->
## Repo task proof loop

For substantial features, refactors, and bug fixes, use the repo-task-proof-loop workflow.

Required artifact path:
- Keep all task artifacts in `.agent/tasks/<TASK_ID>/` inside this repository.

Required sequence:
1. Freeze `.agent/tasks/<TASK_ID>/spec.md` before implementation.
2. Implement against explicit acceptance criteria (`AC1`, `AC2`, ...).
3. Create `evidence.md`, `evidence.json`, and raw artifacts.
4. Run a fresh verification pass against the current codebase and rerun checks.
5. If verification is not `PASS`, write `problems.md`, apply the smallest safe fix, and reverify.

Hard rules:
- Do not claim completion unless every acceptance criterion is `PASS`.
- Verifiers judge current code and current command results, not prior chat claims.
- Fixers should make the smallest defensible diff.

Installed workflow agents:
- `.claude/agents/task-spec-freezer.md`
- `.claude/agents/task-builder.md`
- `.claude/agents/task-verifier.md`
- `.claude/agents/task-fixer.md`

Claude Code note:
- If `init` just created or refreshed these files during the current Claude Code session, do not assume the refreshed workflow agents are already available.
- The main Claude session may auto-delegate to these workflow agents when the current proof-loop phase matches their descriptions. If automatic delegation is not precise enough, make the current proof-loop phase more explicit in natural language.
- TodoWrite or the visible task/todo UI is optional session-scoped progress display only. The canonical durable proof-loop state is the repo-local artifact set under `.agent/tasks/<TASK_ID>/`.
- Keep this workflow flat. These generated workflow agents are role endpoints, not recursive orchestrators.
- Keep this block in the root `CLAUDE.md`. If the workflow needs longer repo guidance, prefer `@path` imports or `.claude/rules/*.md` instead of expanding this block.
<!-- repo-task-proof-loop:end -->
