Debug a forge-web game mechanics issue.

$ARGUMENTS = symptom description (e.g. "card disappears after casting", "prompt hangs"). Ask if empty.

Requires dev server running (`cd forge-web && just dev`).

## Before anything

Read `forge-web/docs/debugging.md` — triage flow, WS tap format, common symptom→check patterns.

## Procedure

1. Read the WS tap: `tmux capture-pane -t forge-dev-8080:0 -p -S -80`
2. Match symptom against the common patterns table in the doc
3. If live repro needed, use debug bridge: `window.__gameState()` / `window.__act()`
4. Check `browser_console_messages` for frontend errors
5. Trace the gap: what message was sent vs what state came back
6. Report: root cause, which layer (frontend/bridge/engine), fix suggestion
