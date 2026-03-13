---
name: ralph
description: Play-fix-play loop iteration. Play MTGA bot match, hit a wall, fix it, verify, commit, update PROGRESS.md, exit.
---

# Ralph: Play-Fix-Play

You are one iteration of an autonomous loop. Your job:

1. Play a bot match against Sparky
2. Hit whatever wall comes first (client error, stuck priority, crash, wrong visual)
3. Fix that ONE wall
4. Verify the fix
5. Commit, update PROGRESS.md, exit

State from previous iterations is in PROGRESS.md. Read it first.

## Phase 0: Orient

```bash
cat PROGRESS.md 2>/dev/null || echo "First iteration — no prior progress"
git log --oneline -5
```

Check what previous iterations accomplished. Don't re-fix solved problems.

If PROGRESS.md lists a wall as "DEFERRED" or "NEEDS_HUMAN", skip it and play on — find the next wall.

## Phase 1: Pre-flight

Verify the server and client are up:

```bash
curl -s http://localhost:8090/api/state > /dev/null && echo "Server: OK" || echo "Server: DOWN"
```

If server is down:
```bash
just build && tmux new-session -d -s leyline 'just serve'
sleep 8
```

Check MTGA:
```bash
arena where
```

If not connected, `arena launch` and wait.

## Phase 2: Play

Start a bot match and play until something breaks.

### Start the match

```bash
arena where   # detect current screen
```

Navigate to bot match:
```bash
arena click "Play" --retry 3
sleep 2
arena click "Find Match" --retry 3
sleep 2
arena click "Bot Match" --retry 3
sleep 2
```

Select a deck (use OCR to find deck names):
```bash
arena ocr --fmt   # find deck label and coords
# Click card art ~80px above the deck name
arena click <cx>,<cy-80>
sleep 1
arena click 866,533   # Play button
sleep 5                # match load
```

Handle mulligan:
```bash
arena click "Keep" --retry 3 2>/dev/null; true
sleep 2
```

### Play the game

Simple strategy — play what you can, pass when you can't:

1. **Check hand via debug API:**
   ```bash
   arena board --no-ocr   # shows hand, battlefield, phase
   ```

2. **Play cards from hand** (left to right, lands first):
   ```bash
   arena play "Card Name"   # verified drag, zone change check
   ```
   If `arena play` fails (card not found, can't cast), move to next card.

3. **Pass priority / advance phase:**
   ```bash
   arena click 888,504   # universal button (Pass/Next/End Turn/All Attack)
   sleep 1
   ```

4. **During Sparky's turn:** sleep 5, then resume clicking 888,504.

5. **After each turn, check for problems:**
   ```bash
   arena errors            # client errors from Player.log
   curl -s http://localhost:8090/api/state | python3 -c "
   import sys, json
   s = json.load(sys.stdin)
   print(f'Turn: {s.get(\"turn\",\"?\")}, Phase: {s.get(\"phase\",\"?\")}, Active: {s.get(\"activePlayer\",\"?\")}')
   " 2>/dev/null || echo "No game state"
   ```

6. **Play 3-5 turns** or until something breaks. If the game completes without issues — great! Concede, log success, and exit.

### Detect a wall

A "wall" is anything that stops the game or degrades the experience:

| Signal | How to detect | Priority |
|--------|--------------|----------|
| Client error/exception | `arena errors` shows new errors | HIGH |
| Stuck priority (button doesn't advance) | 3x clicks on 888,504 with no phase change | HIGH |
| Server crash/exception | `logs/leyline.log` has ERROR/exception | HIGH |
| Bridge timeout | Server log: "bridge timeout" | HIGH |
| Wrong phase/stuck turn | Debug API shows same turn for >30s | MEDIUM |
| Missing visual (no animation, wrong card) | OCR doesn't match expected state | LOW |
| Game completes but with warnings | `arena errors` has warnings | LOW |

**If no wall is hit in 5 turns:** Concede the game, log "clean game" in PROGRESS.md, exit. This is a win — the game is more playable than before.

## Phase 3: Diagnose

You hit a wall. Now figure out why.

```bash
# Capture evidence
arena errors > /tmp/ralph-errors.txt 2>/dev/null
curl -s http://localhost:8090/api/state > /tmp/ralph-state.json 2>/dev/null
tail -50 logs/leyline.log > /tmp/ralph-server-log.txt 2>/dev/null
arena ocr > /tmp/ralph-ocr.json 2>/dev/null
```

Read the error/log. Trace it to source code. Common patterns:

| Error pattern | Likely cause | Where to look |
|---------------|-------------|----------------|
| `NullPointerException` in game/ | Missing null check in state mapping | `matchdoor/src/main/kotlin/leyline/game/` |
| "bridge timeout" | Engine waiting for response we didn't send | `matchdoor/src/main/kotlin/leyline/match/` |
| `IndexOutOfBounds` in annotation | Wrong instanceId or missing zone entry | `AnnotationBuilder.kt`, `AnnotationPipeline.kt` |
| Client error: "index out of range" | Proto field missing or wrong zone ID | `StateMapper.kt`, `GsmBuilder.kt` |
| `ActionBuilder` exception | Missing action type handling | `ActionBuilder.kt` |
| "unknown CmdType" in FD | Unhandled front door command | `frontdoor/src/main/kotlin/leyline/fd/` |

### Triage: fix or defer?

**FIX NOW** (you can handle these):
- Missing null check / defensive guard
- Wrong enum variant or missing case in when()
- Missing annotation field or detail key
- Off-by-one in zone ID mapping
- Missing FD handler for a known CmdType (use `just wire response <CmdType>` to get shape)
- Test failure from a code change

**DEFER** (log in PROGRESS.md, exit):
- Forge engine bug (wrong game logic, card rules)
- Architectural change needed (new subsystem, redesign)
- Protocol mystery (need recording analysis, unclear what real server sends)
- Multiple interacting bugs (fix one, new one appears, fix that, loop)

## Phase 4: Fix

**ONE fix only.** Don't refactor, don't clean up, don't improve.

1. **Write a regression test first** (if the fix is in matchdoor):
   ```bash
   # Use the fastest test setup that covers the bug
   # startWithBoard{} for most things, startPuzzleAtMain1() for board states
   ```

2. **Make the code change.** Minimal. Targeted.

3. **Format:**
   ```bash
   just fmt
   ```

4. **Run scoped tests:**
   ```bash
   # Pick based on what you changed:
   ./gradlew :matchdoor:testGate          # matchdoor changes
   ./gradlew :frontdoor:test              # frontdoor changes
   ./gradlew :account:test                # account changes
   ```

5. **Build:**
   ```bash
   just build
   ```

If tests fail, fix them. If the fix causes new test failures, reconsider — you might be on the wrong track. Defer if it's getting complicated.

## Phase 5: Verify

**Restart the server** (JVM has old bytecode):
```bash
just stop
sleep 2
tmux new-session -d -s leyline 'just serve'
sleep 8
```

Then replay the scenario that hit the wall:
- Start a new bot match
- Get to the same game state
- Confirm the wall is gone

If the wall is still there, your fix was wrong. Revert and defer.

Quick checks:
```bash
arena errors                    # no new client errors
curl -s http://localhost:8090/api/state > /dev/null   # server alive
```

## Phase 6: Commit + Progress

### Commit

```bash
git add -A
git commit -m "fix(module): description of what was fixed

Wall: <what broke>
Evidence: <error message or symptom>
Ralph iteration: N"
```

### Update PROGRESS.md

Append to PROGRESS.md (create if needed). Format:

```markdown
## Iteration N — <date> <time>

**Wall:** <what broke>
**Fix:** <what you changed, with file:line references>
**Status:** FIXED / DEFERRED / NEEDS_HUMAN
**Reason (if deferred):** <why you can't fix it>
**Commit:** <short hash>
**Game result:** <completed N turns / conceded / crashed at turn N>

### Deferred walls (for human review)
- <wall description> — <why it needs human>
```

## Phase 7: Exit

You're done. The outer loop will respawn a fresh agent for the next iteration.

Before exiting, make sure:
- [ ] Code compiles (`just build` passed)
- [ ] Tests pass (scoped to changed module)
- [ ] Changes are committed
- [ ] PROGRESS.md is updated
- [ ] Server is running (for next iteration)

## Rules

1. **ONE wall per iteration.** Fix the first thing that breaks. Don't hunt for more.
2. **Don't thrash.** If the same command fails 3 times, it's a defer.
3. **Restart server after code changes.** Always. JVM holds old bytecode.
4. **No architecture changes.** If the fix needs a new subsystem or redesign, defer.
5. **Test what you change.** Scoped to the module, not the whole project.
6. **PROGRESS.md is sacred.** It's how the next iteration (and the human) knows what happened.
7. **Clean game = success.** If you play 5 turns with no wall, that's a win. Log it and exit.
8. **Concede to exit.** Don't let games run forever. Concede after fixing/verifying or after 5 clean turns.
9. **File issues for deferred walls.** If something needs human attention, `gh issue create` with the diagnosis.
10. **Never force-push. Never rewrite history.** You're on a shared branch.

## Concede recipe

```bash
arena click 940,42        # cog icon
sleep 1
arena click "Concede" --retry 3
arena wait text="DEFEAT" --timeout 10
arena click 210,482 && sleep 2 && arena click 210,482 && sleep 2 && arena click 210,482
sleep 3
```

## Recovery

| Stuck state | Recovery |
|-------------|----------|
| Unknown screen | `arena where` → `arena navigate Home` |
| Modal blocking | `arena click 480,300` (center dismiss) |
| Server down | `just build && just serve` in tmux |
| MTGA disconnected | `arena launch`, wait 10s |
| Ghost match ("Resume") | `just stop` + restart + `arena launch` |
| Can't find deck | `arena ocr --fmt` to discover deck names |
