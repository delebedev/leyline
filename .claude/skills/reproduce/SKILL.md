---
name: reproduce
description: Reproduce a gameplay bug in-game using arena automation + debug API monitoring. Captures evidence for diagnosis.
---

## What I do

Launch a game, trigger a specific bug (from recipe or exploratory play), and capture structured evidence via debug API + client errors. Feeds into the diagnose skill.

## When to use me

- "reproduce #37" / "reproduce targeting bug"
- "try to hit the blocker bug in-game"
- As phase 1 of the dev-loop

## Inputs

- **GitHub issue number** (required) — reads issue for reproduction steps and failure description
- **Deck name** (optional) — override deck selection

## Prerequisites

### Server mode selection

Pick the right mode based on the bug type:
- **Gameplay/engine bugs** (#30-#42 style) → `just serve` (local Forge engine)
- **Protocol/conformance bugs** → `just serve-proxy` (real server passthrough)

### Server pre-flight checklist

All 4 must pass before proceeding:

1. `curl -s http://localhost:8090/api/state` → returns JSON
2. `lsof -i :30010 | grep LISTEN` → our Java process listening
3. `lsof -i :30010 | grep ESTABLISHED` → MTGA connected to localhost
4. `ps aux | grep leyline | grep -o '\-\-proxy-[a-z]*'` → empty for local, `--proxy-fd`/`--proxy-md` for proxy

If server is down: `tmux new-session -d -s leyline 'cd /Users/denislebedev/src/leyline && just serve'`
If client is down: `arena launch`
If wrong mode: `just stop`, start correct mode, `arena launch`

## Process

### 1. Read the issue

```bash
gh issue view <N> --json title,body,labels
```

Extract:
- **Failure description** from title/body — what to watch for
- **`## Reproduction` section** if present — step-by-step recipe
- **Deck requirement** — specific deck or "any"

### 2. Classify reproduction strategy

Pick the **fastest tier** that covers the bug:

#### Tier 1: Puzzle test (~5s, no server/client)

Write a `.pzl` file with the exact board state and a conformance test. Best for:
- Engine/adapter logic bugs (autotap, action building, zone transitions, annotations)
- Isolating "is this our code or Forge?" — if puzzle test passes, bug is in Forge core
- Any bug where the failure is in data/proto output, not visual rendering

```
# matchdoor/src/test/resources/puzzles/my-bug.pzl
[metadata]
Name:Bug #N repro
Goal:Win
Turns:10
Difficulty:Tutorial
Description:...

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=20

humanbattlefield=Plains;Plains;Forest;Llanowar Elves
humanhand=Pacifism
humanlibrary=Plains;Plains;Plains
aibattlefield=Mountain;Runeclaw Bear
ailibrary=Mountain;Mountain;Mountain
```

Test uses `ConformanceTestBase.startPuzzleAtMain1(puzzleText)` → returns `(bridge, game, counter)` at Main1 with exact board.

#### Tier 2: Puzzle in-game (server + client, ~30s)

Start server with `--puzzle` flag, connect client, play through in MTGA. Best for:
- Visual bugs that need client rendering to confirm
- Client-side behavior (highlights, animations, prompts)
- When Tier 1 passes but you need to see what the player sees

#### Tier 3: Bot match in-game (server + client, ~2-5min)

Full bot match via arena automation. Best for:
- Bugs that depend on game flow (mulligan, turn sequence, AI decisions)
- Exploratory reproduction when no specific board state is known
- Visual bugs during normal gameplay

**Always try Tier 1 first.** It's 100x faster and produces a committed test artifact. Escalate to Tier 2/3 only when visual confirmation or game flow is needed.

---

**Has recipe:** issue has `## Reproduction` with concrete steps. Follow them literally using the appropriate tier.

**Exploratory:** no recipe. Start with Tier 1 puzzle if the bug describes a specific board state. Fall back to Tier 3 autopilot games:
- Client errors: `just scry state --no-cards` and poll `/api/logs?level=WARN&since=N` every few seconds
- Match failure pattern from issue description against errors/warnings
- Play 1-3 games max before concluding "could not reproduce"

### 3. Pre-flight snapshot

Before triggering the bug, capture baseline:

```bash
# Bookmark current message cursor for later diffing
curl -s http://localhost:8090/api/messages | python3 -c "import sys,json; print(json.load(sys.stdin).get('cursor',0))"
```

### 4. Execute reproduction

**With recipe:** translate each step to arena commands:
- "play a creature" → drag from hand to battlefield
- "attack" → click 888,504 through combat
- "block" → drag blocker to attacker
- "cast spell targeting X" → drag spell, click target coords
- Use `arena wait` between actions for transitions

**Exploratory / autopilot:** follow arena-automation rule's autopilot mode:
- Drag cards left to right, cancel if can't pay
- Click 888,504 for all buttons
- End turn when out of mana
- Monitor debug API between turns

### 5. Detect failure

After each significant action, check:

```bash
# Client errors from Player.log
just scry state --no-cards

# New warnings?
curl -s 'http://localhost:8090/api/logs?level=WARN&since=<baseline_cursor>' | python3 -m json.tool

# Visual check — does the screen match expected state?
arena ocr
```

Failure detected when:
- Client error appears matching issue description
- Server warning/error in logs
- Visual state doesn't match expected (OCR shows wrong text, stuck screen)
- Debug API state contradicts expected (wrong zone, missing card, wrong phase)

### 6. Capture evidence

On failure detection, immediately capture:

```bash
# Screenshot of the game window (clean, cropped, OCR-coord-compatible)
just arena capture --out /tmp/repro-screenshot.png --resolution 1920

# Full state snapshot
curl -s http://localhost:8090/api/state | python3 -m json.tool > /tmp/repro-state.json

# Recent state diffs
curl -s 'http://localhost:8090/api/state-diff?last=3' | python3 -m json.tool > /tmp/repro-diffs.json

# Battlefield + hand state
curl -s 'http://localhost:8090/api/id-map?active=true' | python3 -m json.tool > /tmp/repro-idmap.json

# Client errors
just scry state --no-cards > /tmp/repro-errors.json

# Recent messages
curl -s http://localhost:8090/api/messages | python3 -m json.tool > /tmp/repro-messages.json

# Priority events
curl -s http://localhost:8090/api/priority-events | python3 -m json.tool > /tmp/repro-priority.json

# OCR snapshot
arena ocr > /tmp/repro-ocr.json
```

### 7. Output

Report:

```markdown
## Reproduction: #N — <title>

### Result: REPRODUCED / NOT_REPRODUCED

### Steps taken
1. <what was done>
2. <each action>

### Failure evidence
- Screenshot: <annotated arena capture showing the bug>
- Client error: <if any>
- Server log: <if any>
- State anomaly: <if any>
- Debug API: <phase, activePlayer, relevant state confirming context>

### Session
- Recording: recordings/<session>/
- Debug snapshots: /tmp/repro-*.json
- Screenshot: /tmp/repro-screenshot.png
```

### 8. Post to GitHub issue

Upload the annotated screenshot and post the reproduction report as an issue comment:

```bash
URL=$(~/.claude/skills/screenshot-upload/upload.sh /tmp/repro-screenshot.png)
gh issue comment <N> --body "## Reproduction ...
![description]($URL)
..."
```

## Screenshots

**Capture:** `just arena capture --out /tmp/repro-screenshot.png --resolution 1920`
- Crops to MTGA window, logical coords (960x568), OCR coords map 1:1

**Annotate** with Pillow — OCR coords are pixel coords on the capture:
```python
from PIL import Image, ImageDraw, ImageFont
img = Image.open('/tmp/repro-screenshot.png')  # 960x568
draw = ImageDraw.Draw(img)
draw.rectangle([x1, y1, x2, y2], outline='red', width=3)
font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 16)
draw.text((x1, y1 - 20), "label", fill='red', font=font)
img.save('/tmp/repro-annotated.png')
```

**Upload:** `~/.claude/skills/screenshot-upload/upload.sh /tmp/repro-annotated.png` → public R2 URL for GitHub embedding.

## Key conventions

- **One action at a time.** Don't batch arena commands. Act, check, act.
- **3 games max for exploratory.** If you can't reproduce in 3 games, report NOT_REPRODUCED and move on.
- **Don't fix during reproduce.** The goal is evidence, not a fix. Stay in observer mode.
- **Visual bugs need visual proof.** Protocol evidence (messages API) is necessary but not sufficient for visual bugs. Use `arena capture` + annotate with Pillow to show what the player sees. Confirm game context (phase, activePlayer) via debug API.
- **Concede and restart** if the game gets to an unrelated stuck state. Don't waste time debugging a different bug.
- **Check catalog.yaml** before reproducing. If the mechanic is listed as "missing", reproduction will just confirm the known gap.
- **Use `just tape`** for post-hoc analysis when recordings exist (`just tape session actions`, `just tape session show`, `just tape session violations`). Structured and replayable. Debug API for live state checks only.
- **Restart server after code changes.** `just stop` + `just serve` before in-game verification. The running JVM has old bytecode — testing against stale code wastes cycles.
