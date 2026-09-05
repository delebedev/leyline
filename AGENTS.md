# leyline

Local playtesting server built around a native-client protocol head and Forge (open-source rules engine). Provides reusable domain, engine, GRE, draft, and in-process match-runtime modules for embedding.

- **Depends on:** forge (engine submodule — game bridges, bootstrap) — never reverse the dependency
- **Server mode:** `just serve` (local-only)

**Engineering stance:** playable behavior first, then protocol fidelity, then broader conformance. Preserve correctness at the shipped seam; do not gold-plate unproven edges.

**Use runtime logs and client-visible failures as primary debugging evidence.**

## Agent Policy

**IMPORTANT: These rules are non-negotiable. Follow them exactly.**

- **NEVER commit to main.** Always branch + PR. Prefer `feat/<topic>`, `fix/<topic>`, or `refactor/<topic>` unless the active tool requires another prefix.
- **Plan proportionally.** Use visible task tracking for architectural decisions, changes touching 3+ files, or work with multiple viable approaches. Obvious local fixes do not need ceremony.
- **Re-plan on repeated failure.** After two failed attempts with the same hypothesis, state the evidence, change the hypothesis or approach, and continue. Stop only for missing authority, destructive/shared-state action, or a genuine external blocker.
- **Autonomous bug fixing.** Given a bug report: just fix it. Logs → errors → failing test → resolve. Zero hand-holding.
- **Ship the feature, not just the code.** Before PRing, ask: "Does this work end-to-end from the user's perspective?" Tests green ≠ feature complete. "Pre-existing" is not an excuse when YOU created the context where it matters. "Follow-up" is not appropriate for gaps that make the feature non-functional. Trivial blockers (< 5 min) ship with the feature, not after.
- **Elegance balance.** Non-trivial changes: pause and ask "is there a more elegant way?" Skip for simple obvious fixes.
- **Learn from corrections.** Fold durable corrections into the relevant doc or local workflow note. Don't keep a public catch-all lessons log.
- **Comments describe the present.** State current behavior and intent; leave refactor history and dated context to Git.
- **Ralph PRs get labeled.** Add `--label ralph` to `gh pr create` in ralph-loop sessions.

## Modules

```
app/            Composition root — LeylineMain, local control server, management server, seed DB.
domain/         Domain model, services, repository ports.
engine/         Forge bridge + GRE match-session engine. See engine/AGENTS.md.
native/         Native-client head; packages account/frontdoor/matchdoor. Protocol leaf.
```

Other dirs: `bin/`, `docs/`, `forge/` (engine submodule), `gradle/`, `just/`, `proto/`.

## Build & Run

```bash
just bootstrap    # fresh clone/worktree → submodules → forge install → build → hooks
just hooks-install # enable repo-tracked git hooks for this clone
just build        # gradle: proto-sync + compile + jar
just serve        # start server (restart after code changes — JVM holds old bytecode)
just fmt          # apply Kotlin formatting (spotless/ktlint). Pre-push runs fmt-check only, doesn't auto-apply.
```

**End-to-end local client runs require a small amount of local setup.** See `docs/local-client-setup.md`.

**Worktrees need `just bootstrap` before anything else** — they don't share submodule checkouts, but forge jars are cached globally (`~/.cache/leyline/forge-m2/`) so `mvn install` is skipped if another worktree already built the same forge commit.

**Git hooks are repo-tracked.** `just bootstrap` installs them automatically; run `just hooks-install` manually if you cloned before the hook setup landed.

## Testing

Kotest FunSpec (JUnit Platform). Engine test guidance lives in
`engine/src/test/kotlin/leyline/AGENTS.md`.

- `just test-one <ClassName> [module]` — single class; defaults to `engine`
- `just test-many "<ClassA> <ClassB>" [module]` — several classes in one run (space-separated, one string)
- `just test-gate` — pre-commit (all modules + fmt)
- `just test-integration` — risky engine changes
- `just test-acceptance` — puzzle-backed scripted acceptance suites
- **Scope tests to changed modules, don't run everything.**

## Debugging

- **Logs:** `logs/leyline.log` (read this, don't pipe server output)
- **Local control API:** `:8090` exposes puzzle control, best-play queries, and full-state injection. Loopback-only by default; use maintained CLI/recipes rather than adding ad hoc endpoints. See `DebugServer.kt` KDoc.

## Reference

- **Architecture:** `docs/architecture.md`, `docs/forge-api-concepts.md`, `engine/AGENTS.md` (engine adapter internals).
- **Local setup notes:** `docs/local-client-setup.md`
- **Agent rules:** Read matching subtree `AGENTS.md` files. Before Forge-facing engine work, read `docs/forge-api-concepts.md` and the nearest bridge guidance. For build/bootstrap work, inspect the owning `justfile` recipe and `gradle/scripts/` helper before changing behavior.

## Documentation

- **Read selectively.** Run `just docs` from the repository root to list all docs with summaries, or `just docs <filter>` to search by path/summary. Use it before setup or debugging work; read files whose `read_when` frontmatter matches your current task. `docs/index.md` has curated navigation.
- **Update docs in the same PR.** If your PR changes public behavior, architecture, or setup, update the relevant doc in the same commit — never a follow-up.
- **Principles:** `docs/principles-design.md` (code structure), `docs/principles-documentation.md` (how to document).
- **Integrity before merge on doc-heavy PRs:** `just docs-lint` (verify cross-references) and `just docs-orphans` (find unlinked files).

## Puzzles

Primary deterministic acceptance fixture. `.pzl` files define exact board states — minimal cards, one win path, forced mechanics.
Read `docs/puzzle-harness.md` before starting fixtures from exile, face-down state, prepared/plotted/foretold/adventured state, or any other history-sensitive state.
Read `docs/ai-solved-acceptance.md` when converting a direct Forge-AI puzzle solution into scripted acceptance YAML.

Proof ownership: `MatchFlowHarness` executes YAML acceptance; simclient owns
synthetic Playthrough discovery and fixed-seed reproduction; the live client
reuses the same scripted intent; Copilot/Pilot measures autonomous robustness;
conformance compares protocol fidelity. Forge AI is an upstream rules-engine solver or
autonomous advisor, not the acceptance executor.

- `just puzzle <file>` — set puzzle via debug API (hot-swaps if in match, queues for next local AI match)
- `just puzzle-check <file>` — mandatory before commit (missing grpId = NPE)
- `./gradlew :engine:testAcceptance -PacceptanceSuites=<suite> -PacceptanceScenarios=<scenario>` — run one acceptance YAML scenario
- `POST :8090/api/puzzle?file=<name>` — runtime API (GET returns current, POST with no params clears)

For player-visible gameplay work, completion requires focused production tests, one repository-local puzzle-backed acceptance scenario, and an autonomous local-client playthrough. A lower layer does not substitute for a higher one. Ground public acceptance claims in repository-local tests, fixtures, puzzles, and docs.

## Public Repo — Content Rules

Every commit is public. **IMPORTANT: Violating these rules exposes the project legally.**

- **No third-party proprietary data.** Never commit external service responses, vendor databases, or user data. Test fixtures must use synthetic or project-generated data.
- **No private research repo references.** Don't link to, quote paths from, or reference external private repos.
- **No personal infra details.** No hardcoded IPs, hostnames, or absolute paths. Use `~/` or relative paths.
- **Tone: local playtesting tool.** Prefer "protocol implementation" or "local client-compatible server".
- **Interop data is fine.** grpIds, set codes, CmdType numbers, loc keys — functional protocol identifiers.
