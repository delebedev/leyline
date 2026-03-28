# Lessons

Patterns learned from debugging sessions and user corrections. Review before starting complex work.

---

## Stale jar masking edits (2026-03-08)

`just build` ran `./gradlew classes` but `classpath.txt` pointed to module jars. The jar was hours old — fresh compiled classes were shadowed. Multiple rounds of "add logging, restart, see no output" before finding root cause.

**Fix:** `just build` now runs `classes jar`. Always restart server after code changes.

---

## candidateRefs broke prompt routing (2026-03-08)

Adding `candidateRefs` to surveil prompts caused routing to take the targeting branch instead of surveil/scry. Dispatch was by data shape, not intent.

**Fix:** Surveil/scry check runs first now. Longer-term: consider `promptIntent` field on PromptRequest.

---

## player.allCards doesn't include limbo cards (2026-03-08)

`Player.getAllCards()` skips cards removed from a zone mid-resolution. Use `Game.findById()` for lookups during engine-blocking prompts.

---

## Puzzle library ordering (2026-03-08)

First card in `humanlibrary=` is TOP of library (draw order). Forge internally stores last element = top. Opposite conventions.

---

## Always diff recordings before guessing (2026-03-08)

Spent rounds guessing why surveil animation was wrong. Root cause found in 5 minutes once we diffed against real server recording: missing `affectorId` on annotations.

**Rule:** When client behavior diverges, decode the recording FIRST. Don't speculate — diff field by field.
