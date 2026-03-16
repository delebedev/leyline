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

**Reference:** `~/src/mtga-internals/docs/autoplay/forge-mode-notes.md` has critical Forge-mode
quirks (Stack/Resolve, combat, Main phase skipping, cards to avoid). Read it if you get stuck
on gameplay mechanics.

## Phase 0: Orient

```bash
cat PROGRESS.md 2>/dev/null || echo "First iteration — no prior progress"
git log --oneline -5
```

Check what previous iterations accomplished. Don't re-fix solved problems.

If PROGRESS.md lists a wall as "DEFERRED" or "NEEDS_HUMAN", skip it and play on — find the next wall.

### Known walls (from GitHub issues)

Before playing, scan open issues for walls you might hit. When you hit a wall during gameplay,
check if it matches a known issue — if so, the diagnosis is already done and you can skip to fix.

```bash
gh issue list -R delebedev/leyline --limit 30 --state open --json number,title,labels
```

**High-impact gameplay issues** (ordered by how early they block a game):

| Issue | Wall | Impact | Fix complexity |
|-------|------|--------|----------------|
| #92 | Extra "Resolve" click after every spell cast | Every spell needs manual resolve — real server auto-passes | MED — change `handlePostCastPrompt` to check auto-pass |
| #93 | Wrong button labels during Sparky's turn | Confusing but not blocking | Shares root cause with #92 |
| #64 | DeclareAttackersReq uses wrong attacker list | Combat attacker selection broken | LOW — conformance fix |
| #68 | Bridge timeout during combat (priority=0) | Game hangs in combat | MED — priority routing |
| #91 | Bridge timeout on card selection prompts | Commune with Beavers etc freeze game | MED — new prompt handler |
| #67 | Elenda not castable (mana false positive) | Specific card unplayable | MED — engine-level |

When you hit a wall, `gh issue view <N>` for the full diagnosis and fix direction. Don't re-diagnose
what's already been investigated.

## Phase 1: Pre-flight

The outer `bin/ralph` runner handles most of this automatically. But if you're running manually
(or the runner skipped checks with `--continue`), verify each of these. **Every check has been
learned the hard way — don't skip any.**

### 1. Console user == process owner

```bash
stat -f '%Su' /dev/console   # who owns the GUI session?
whoami                        # who are we?
```

**If they don't match: STOP.** Unity Metal requires the MTGA process owner to be the same user
as the macOS console (GUI) session. SSH as user X while console belongs to user Y = guaranteed
black screen. The server, networking, even CGEvent clicks all work fine — but the Metal render
surface produces zero frames.

**Symptoms:** MTGA window frame visible (title bar says "MTGA"), content area solid black,
`bin/arena ocr` returns only `[{"text": "MTGA", ...}]`, server logs look completely normal,
`bin/arena scene` correctly reports "Home".

**Fix:** SSH as the console user, or switch the console user via Fast User Switching.

### 2. Display awake

```bash
pmset -g assertions | grep PreventUserIdleDisplaySleep
```

If it shows `0` — display is sleeping or will sleep. macOS does NOT count SSH sessions as user
activity, so the display sleeps after `displaysleep` minutes even with active SSH.

```bash
caffeinate -u -t 10 &    # synthetic user-activity wake event
caffeinate -d -i &        # prevent display + idle sleep
sleep 5
```

**If MTGA was already running when display slept:** kill and relaunch. Unity's Metal context
doesn't recover — it initialized the swap chain while the display was on and now the compositor
can't present frames.

```bash
pkill -f MTGA; sleep 5; bin/arena launch
```

### 3. Render verification

After wake/launch, confirm MTGA is actually rendering:
```bash
bin/arena ocr --fmt
```

A healthy Home screen has 5+ OCR elements (deck names, "Play", event titles, etc.).
If only "MTGA" appears (1 element = just the title bar), the render surface is black.

Relaunch once. If still black: `NEEDS_HUMAN` — exit and note the pre-flight failure.

### 4. Server

```bash
curl -s http://localhost:8090/api/state > /dev/null && echo "Server: OK" || echo "Server: DOWN"
```

If down:
```bash
just build
tmux kill-session -t leyline 2>/dev/null; tmux new-session -d -s leyline 'just serve'
sleep 10
```

### 5. MTGA connected

```bash
bin/arena where
```

If not connected: `bin/arena launch` and wait 15-20s for full lobby load. The FD handshake
(auth → golden data → events → cards) takes 5-10s, then the Unity scene transition to Home
takes another 5-10s.

## Phase 2: Play

Start a bot match and play until something breaks.

### Lobby → Bot Match

Proven sequence from v10 autoplay prompt (21 batches of testing):

```bash
bin/arena click 40,25 && sleep 2                              # dismiss any overlay
bin/arena click "Play" --retry 3
bin/arena wait text="Find Match" --timeout 10
bin/arena click 867,112 && sleep 1                            # Find Match tab
bin/arena click "Bot Match" --retry 3 && sleep 1
bin/arena click 82,455 && sleep 1                             # first deck in list
bin/arena click 867,516                                       # Play button
bin/arena wait text="Keep" --timeout 30                       # mulligan screen
bin/arena click "Keep" --retry 3 && sleep 5                   # keep hand, wait for game
```

If `wait text="Find Match"` fails, OCR to see where you are and navigate manually.

### Game loop: ACT FAST, SCRY RARELY

**Core rule:** Scry once at start of your turn. Play everything you can. Pass. Scry at start of next turn. Do NOT scry between plays — if `arena play` prints `✓`, the card was played.

**State tool:** `bin/scry state --json` — returns turn, phase, active_player, hand, actions (legal plays) as JSON.

#### Your turn (active_player=1, Main1 or Main2)

```bash
bin/scry state --json   # read hand + actions once
```

Then burst — play everything you can:

```bash
# 1. Play a land (if ActionType_Play in actions)
bin/arena play "<land name>" && sleep 2
# Lands play directly in Forge mode — no Resolve needed.
# arena play may timeout on zone-change verification for lands — ignore, check scry.

# 2. Drain mana — cast ALL spells, cheapest first
# IMPORTANT: each spell goes on Stack in Forge mode (#92).
# After each arena play, click 888,504 TWICE (Resolve + Pass):
bin/arena play "<cheapest spell>" && sleep 2
bin/arena click 888,504 && sleep 1   # Resolve off stack
bin/arena click 888,504 && sleep 1   # Pass priority
bin/arena play "<next cheapest>" && sleep 2
bin/arena click 888,504 && sleep 1   # Resolve
bin/arena click 888,504 && sleep 1   # Pass
# ... repeat per spell until no more ActionType_Cast in actions

# 3. Pass to combat
bin/arena click 888,504 && sleep 1 && bin/arena click 888,504 && sleep 2
```

**Why the Resolve clicks?** Issue #92 — `handlePostCastPrompt()` sends `ActionsAvailableReq`
unconditionally after every cast. Real server auto-passes. Until #92 is fixed, every spell needs
`888,504 → 888,504` (Resolve → Pass) after the drag. Fixing #92 eliminates all of these.

If `arena play` prints `✗` or fails: skip that card, try the next one. Don't scry, don't OCR, just move on.

#### Combat (Phase_Combat, active_player=1)

```bash
bin/arena click 889,504 && sleep 1 && bin/arena click 889,504 && sleep 3
```

This sends "All Attack". Note: in Forge mode, "All Attack" may not respond — if so, skip combat.

#### Sparky's turn (active_player=2)

**EXACTLY two clicks, no more:**
```bash
bin/arena click 887,491 && sleep 1 && bin/arena click 890,510 && sleep 3
bin/scry state --json   # check if it's our turn now
```

If still active_player=2: `bin/arena click 888,504 && sleep 3 && bin/scry state --json`. Repeat until active_player=1.

**CRITICAL: Do NOT add extra 888,504 clicks after the two pass buttons. That passes YOUR Main1.**

#### Discard (Phase_Ending, hand > 7)

```bash
bin/arena click 400,500 && sleep 1 && bin/arena click 888,489 && sleep 2
```

### Detect a wall

A "wall" is anything that stops the game or degrades the experience:

| Signal | How to detect | Priority |
|--------|--------------|----------|
| Client error/exception | `bin/arena errors` shows new errors | HIGH |
| Stuck (gsId unchanged after 2 actions) | `bin/scry state --json` shows same gsId | HIGH |
| Server crash/exception | `logs/leyline.log` has ERROR/exception | HIGH |
| Bridge timeout | Server log: "bridge timeout" | HIGH |
| Wrong phase/stuck turn | Scry shows same turn for >30s | MEDIUM |
| Game completes but with warnings | `bin/arena errors` has warnings | LOW |

**Stuck 3 times → CONCEDE and move to diagnose.**

**If no wall is hit in 5 turns:** Concede the game, log "clean game" in PROGRESS.md, exit. This is a win — the game is more playable than before.

### Cards to avoid in test decks

These are known to stall the bridge (see forge-mode-notes.md):
- Commune with Beavers / Commune with Nature / Adventurous Impulse (top-N choose prompts)
- Analyze the Pollen (same)
- Modal choice cards (choose one/two)
- Auras (targeting prompt handling is fragile)

## Phase 3: Diagnose

You hit a wall. Now figure out why.

```bash
# Capture evidence
bin/arena errors > /tmp/ralph-errors.txt 2>/dev/null
curl -s http://localhost:8090/api/state > /tmp/ralph-state.json 2>/dev/null
tail -50 logs/leyline.log > /tmp/ralph-server-log.txt 2>/dev/null
bin/arena ocr > /tmp/ralph-ocr.json 2>/dev/null
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
bin/arena errors                    # no new client errors
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
bin/arena click 940,42 && sleep 1
bin/arena click "Concede" --retry 3
bin/arena wait text="Defeat" --timeout 10
bin/arena click 480,300 && sleep 2 && bin/arena click 480,300 && sleep 2 && bin/arena click 480,300
```

## Coords quick reference

- Action button (Pass/Next/Resolve/All Attack) = 888,504
- Sparky pass: 887,491 + 890,510 (ONLY these two!)
- Drop target / dismiss = 480,300
- Discard: click 400,500 then submit 888,489
- Cog (settings) = 940,42
- Play button = 867,516
- Find Match tab = 867,112

## Recovery

| Stuck state | Recovery |
|-------------|----------|
| Unknown screen | `bin/arena where` → `bin/arena navigate Home` |
| Modal blocking | `bin/arena click 480,300` (center dismiss) |
| Server down | `just build && just serve` in tmux |
| MTGA disconnected | `bin/arena launch`, wait 15s |
| Ghost match ("Resume") | `just stop` + restart + `bin/arena launch` |
| Can't find deck | `bin/arena ocr --fmt` to discover deck names |
| Black screen | Display sleeping — see Phase 1 display check |
| Connection Lost dialog | `bin/arena click "Reconnect" --retry 3` |
