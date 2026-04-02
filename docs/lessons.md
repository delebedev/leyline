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
- When a test fails with an opaque assertion (null, false), add a diagnostic `error()` message with actual values on the FIRST rerun — don't keep rerunning blind. Gradle's `testLogging` only shows `events("failed")` — `println` output is swallowed. Use `error("got $actual")` to surface values in the stack trace.
- Puzzle tests need enough library cards and AI permanents to not auto-lose. Copy puzzle structure from working tests (e.g. KickerTest) instead of writing minimal puzzles from scratch.
- When debugging a client-facing issue (highlighting, rendering, UI), check real server logs FIRST (`scry-ts prompts --json`, `scry-ts gsm show --json`). Don't theorize about what the client needs — look at what the real server sends. "Player.log is the spec" applies to debugging too, not just implementation.
