# Arena UI Automation

Applies when running `arena` commands, automating MTGA, or working on arena tooling.

**Before any automation: load the `arena-nav` skill.** It has the full reference — coords, flows, recovery recipes. This rule is just core principles.

## Core principle

State machine: **detect screen → pick action → execute → confirm result.** One action at a time.

## Signal priority

1. **`arena scene`** — lobby screen from Player.log (fastest)
2. **`arena ocr`** — visual text + coords (~0 tokens). Never screenshots for state checks.
3. **`arena where`** — combined scene + OCR screen detection
4. **Debug API** (`:8090`) — engine state (phase, turn). Can be stale.

## Key coords (960-wide logical)

All coords assume 960-wide MTGA window (macOS logical on 2x Retina). `arena ocr` returns these, `arena click <x>,<y>` expects these.

- **Action button:** 888,504 (Pass / Next / End Turn / All Attack — all same coord)
- **Play button:** 866,533 (start match from deck select / event lobby)
- **Cog icon:** 940,42 (top-right, no OCR text)
- **Result dismiss:** 210,482 (click 3x with 2s gaps)

## Playing cards

1. **`arena play "Card Name"`** — best. Verified drag + zone change check.
2. **`arena drag <from> <to>`** — fallback. Manual coords.
3. **Never use raw `bin/click`** — agents use `arena` commands only.

## Server

- Ensure server running before automation. `just serve` (local) or `just serve-proxy` (recording).
- Pre-flight: `curl -s http://localhost:8090/api/state` must return JSON.
- After code changes: `just stop` + rebuild + `just serve`. JVM holds old bytecode.
- After mode switch: `just stop` + restart + `arena launch`. Clears ghost matches.

## Recovery

1. `arena errors` + `logs/leyline.log` for diagnostics
2. `arena where` to detect current screen
3. `arena navigate Home` to reset to lobby
4. Unknown modal: `arena click 480,300` or `arena click "Okay" --retry 3`
5. Unrecognizable state: stop and ask

## Subagent journaling

When dispatching arena automation to a subagent, have it journal to a file. If the session dies, the journal shows exactly where it got stuck.

```bash
echo "=== <Task Name> $(date) ===" > /tmp/<task>-playtest.log
# After every significant action:
echo "$(date +%H:%M:%S) [step N] what happened" >> /tmp/<task>-playtest.log
```

End with a structured summary:
```
=== RESULT ===
outcome: success/failure
error: (if any)
final_screen: (OCR summary)
```

## After every automation loop

`arena issues` to review failures.
