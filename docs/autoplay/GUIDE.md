# Autoplay Harness — Design Guide

Read this verbatim after context compaction. It is the complete spec.

## Objective

Build and run an automated gameplay harness that:
1. Spawns a Claude agent (sonnet, via `claude -p`) to play bot matches in MTGA
2. Evaluates the agent's performance across two phases: lobby navigation and in-game play
3. Collects transcripts, metrics, agent retros, and analyst reports
4. Iterates on the agent prompt based on findings

## Success Criteria (per game)

- **Phase 1 (lobby → mulligan):** Agent navigates from home screen to in-game. Metric: token count, tool calls, time, stuck events. Should be cheap and fast.
- **Phase 2 (gameplay, 5 turns):** Agent plays cards, attacks with all, doesn't block, survives 5 turns. Metric: tokens per turn, cards played, screenshots taken, total tool calls.

## Architecture

```
bin/record-game (Python orchestrator — NO LLM)
  ├── pre-flight checks:
  │     server running (just serve), client connected, dock auto-hidden
  │     scry responding (bin/scry state)
  ├── for each game (1..N):
  │     pick random deck index
  │     spawn: claude -p --model sonnet --system-prompt <play-game prompt>
  │            --output-format stream-json --verbose
  │            --dangerously-skip-permissions
  │            > transcript.jsonl
  │     watchdog loop (every 10s):
  │       - agent process alive?
  │       - scry state → gsId advancing? turn advancing?
  │       - same state for 90s → stuck, kill agent
  │       - turn >= 5 → success, kill agent
  │       - budget: token count from partial stream-json, cap at ~200k output tokens
  │     on agent exit:
  │       - dismiss result screen (arena clicks, no LLM)
  │       - navigate back to home (arena clicks)
  │       - extract metrics from stream-json final result line
  │       - copy /tmp/agent-retro.md into run dir
  │     after game 3: evaluate — if 0/3 reached turn 5, stop batch
  ├── after all games:
  │     spawn analyst: claude -p --model sonnet <analyze prompt>
  │     reads all transcripts + retros
  │     writes report.md + refinements.md
  └── done
```

## Data Sources for the Agent

- **Game state:** `bin/scry state` — works in ALL server modes (local, proxy, replay). Accumulated state with zones, objects (card names, p/t, tapped), turn info, life, actions. Independent of debug API.
- **Screen state:** `bin/arena ocr` — button text, card names, coordinates
- **Input:** `bin/arena click`, `bin/arena drag` — play cards, click buttons
- **Screenshots:** `bin/arena capture` — only when confused, expensive in tokens
- **Wait:** `bin/arena wait text="X"` — transition detection

Key: the agent does NOT use the debug API (localhost:8090). It uses scry (Player.log parsing) which works regardless of server mode.

## Agent Prompt (docs/autoplay/play-game.md)

The player agent prompt must cover:
- Use `bin/scry state` for game state, not debug API
- Use `bin/arena ocr` for screen text and button detection  
- Use `bin/arena click`/`drag` for input
- Universal action button at 888,504 (Next, Pass, End Turn, etc.)
- Play cards by dragging from hand (~y530) to battlefield (~480,350)
- All-attack in combat (click 888,504 twice)
- No blocking (click 888,504 to pass)
- Handle mulligan: always Keep
- Handle triggers/modals: pick first option, click Done
- Navigate lobby: Play → Find Match → Bot Match → pick deck → Play
- Before finishing: write retro to /tmp/agent-retro.md
- Goal: play 5 turns with cards played each turn if possible
- MINIMIZE token usage: don't screenshot unless stuck, don't OCR when you can blind-click 888,504

## Analyst Prompt (docs/autoplay/analyze-gameplay.md)

Reads stream-json transcripts + agent retros. Produces:
- **Metrics table:** per-game tokens in/out, tool calls, screenshots, turns reached, cards played, wall time, cost
- **Phase breakdown:** lobby vs gameplay token split
- **Stuck events:** where agent stalled, why, how long
- **Retro synthesis:** common themes from agent self-reports
- **Refinements list:** specific actionable changes (prompt edits, new tools, scry fixes)

## Scry Gap: Actions

`bin/scry state` parses actions from Player.log but `tracker.py:to_dict()` does NOT include them in output. Fix needed: add actions to the JSON output so the agent knows which cards are playable. Without this, agent must guess (drag and see what happens).

## Output Structure

```
docs/retro/autoplay/
  batch-NNN/
    run-1/
      transcript.jsonl   (stream-json from player agent)
      metrics.json        (extracted by orchestrator)
      retro.md            (written by player agent)
    run-2/
    run-3/
    ...
    report.md             (analyst output)
    refinements.md        (actionable changes, accumulates across batches)
```

## Run Plan

- Batch 1: 3 games. After game 3, orchestrator evaluates:
  - If 0/3 reached turn 5 → stop, report failures, fix prompt/tools
  - If 1+/3 reached turn 5 → continue to 10 games total
- Budget cap per game: ~200k output tokens (or ~$3 cost if available in stream-json)
- Watchdog kills agent after 90s of no state change
- Total batch target: 10 games (if gate passes after 3)

## Server Mode

Use `just serve` (local engine) for this exercise. Reasons:
- Debug data is richer (recordings with engine/ dumps)
- No dependency on real Arena servers being up
- Faster match start
- We're testing the AGENT, not recording proxy data yet
- Once the agent is reliable, switch to `just serve-proxy` for real recordings

## Key Files to Build

1. `docs/autoplay/play-game.md` — player agent system prompt
2. `docs/autoplay/analyze-gameplay.md` — analyst system prompt  
3. `bin/record-game` — Python orchestrator with watchdog
4. Fix `bin/scry_lib/tracker.py` — expose actions in to_dict()

## What NOT to Build

- No changes to the debug server
- No new arena commands
- No test infrastructure
- No changes to the recording pipeline
