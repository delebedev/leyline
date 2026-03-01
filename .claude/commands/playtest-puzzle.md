Playtest a puzzle end-to-end via Playwright MCP debug bridge.

$ARGUMENTS = puzzle filename (e.g. `WEB_TEST_00`, `___web_banishing`). Ask if empty.

Requires dev server on localhost:5173 (`cd forge-web && just dev`).

References:
- Puzzle format: `docs/puzzle-format.md` — read before generating any .pzl content
- Debug bridge API: `forge-web-ui/src/lib/debug/README.md`

## Procedure

1. `browser_navigate` to `http://localhost:5173`
2. `browser_evaluate`: `() => window.__actWait('loadPuzzle', '$ARGUMENTS')`
3. `browser_evaluate`: `() => window.__gameState()` — inspect initial board
4. Play through using `__act` / `__actWait` calls; check `__gameState()` after each action
5. Handle prompts when `state.prompt != null`
6. Continue until `state.outcome` shows game over
7. Report: what worked, what broke, any unexpected prompts

## Rules

- Use `__gameState()` (~200 tokens) instead of `browser_snapshot` (~10k tokens)
- Only snapshot for rendering/layout issues, never for game state
- If a step hangs, check `browser_console_messages` for errors
