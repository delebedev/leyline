# Lessons

Patterns learned from debugging sessions and user corrections. Review before starting complex work.

- Worktree requests: when user says "off main", default to new branch created from `main`, not a second checkout that locks `main`.
- Read `just --list` and justfile recipes before debugging infra. `just dev-setup`/`dev-teardown` toggle Arena↔leyline.
- `castSpellByName()` puts spell on stack — call `passPriority()` after to resolve.
- Check key types when wiring maps: forge card ID vs instanceId vs grpId. `snapshotKeywords()` keys by instanceId, not card.id.
- Suppress detekt on feature branches; refactor separately.
- Forge events must fire AFTER state mutation. `fireEvent()` before `changedCardKeywords.put()` = subscribers see stale state.
- Don't parse Forge internal strings (`origProduced`) directly — check if Forge has a structured API first (`isComboMana()`, `getComboColors()`, `mana()`). The raw DSL field shape varies by keyword (`"G"` vs `"Combo B G"` vs `"Combo Chosen"`). Unit tests on the mapping function won't catch this; integration tests with real card definitions will.
- When fixing a data-mapping bug, always include an integration test with the most complex variant (dual lands, not just basics). A unit test that exercises the mapping in isolation can pass while the end-to-end pipeline produces garbage.
- Before fixing one consumer of a Forge field, grep for ALL consumers (`grep origProduced matchdoor/`). Fix them together. Shipping a partial fix means discovering the rest through playtesting.
- Write the reproduction test first, using the exact scenario from the bug report. "Dual lands can't cast" → test with dual lands, not basic lands. TDD forces this naturally; skipping it means the first test validates the wrong thing.
- **Test diagnostics:** `just test-debug ClassName` runs a single test with `println` output visible (`-Pverbose` enables `showStandardStreams`). Logback root stays WARN so engine noise is suppressed — only your `println` and any temporarily-bumped loggers show through. For multi-value diagnostics, `println` to dump state then let assertions run. For one-shot values, `error("got $actual")` still works (crashes with value in stack trace). Don't use `/tmp/` files or logback config changes — `-Pverbose` is the standard path.
- Puzzle tests need enough library cards and AI permanents to not auto-lose. Copy puzzle structure from working tests (e.g. KickerTest) instead of writing minimal puzzles from scratch.
- When debugging a client-facing issue (highlighting, rendering, UI), check real server logs FIRST (`scry-ts prompts --json`, `scry-ts gsm show --json`). Don't theorize about what the client needs — look at what the real server sends. "Player.log is the spec" applies to debugging too, not just implementation.
- **Worktree discipline:** When operating from a worktree (`~/.claude/worktrees/<name>/`), ALL commands — git, just, file writes — must run from that worktree path, never from `~/src/leyline` (the main repo). Switching branches or stashing in the main repo while a worktree session is active creates cross-contamination: files written to the worktree become invisible from the main repo, `git checkout` in the main repo disrupts other agents' work, and stash/pop across repos loses changes. Rule: check `pwd` before any git operation. If you're in a worktree session, stay there.
