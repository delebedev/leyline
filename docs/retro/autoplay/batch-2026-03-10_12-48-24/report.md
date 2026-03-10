# Autoplay Batch Report — 2026-03-10_12-48-24

## Summary Table

| Game | Turns | Cards Played | Tool Calls | Screenshots | OCR Calls | Scry Calls | Blind Clicks (888) | Drags | Wall Time | Result |
|------|-------|-------------|------------|-------------|-----------|------------|--------------------|-------|-----------|--------|
| 1    | 1     | 1 (land)    | 20         | 0           | 1         | 12         | 3                  | 6     | 234s      | Killed: gsId=12 stuck 90s |
| 2    | 0     | 0           | 23         | 0           | 1         | 7          | 0                  | 4     | 173s      | Killed: gsId=23 stuck 90s |
| 3    | 0     | 0           | 19         | 0           | 1         | 8          | 0                  | 4     | 173s      | Killed: gsId=6 stuck 90s |

**Tokens/cost:** All zeros — harness token aggregation from subagent still broken (flagged in batch-08-52-59, not fixed).

## Phase Split

Token tracking broken (all zeros). Tool call split estimated from transcript:

| Phase      | Run 1 (tool calls) | Run 2 (tool calls) | Run 3 (tool calls) |
|------------|--------------------|--------------------|---------------------|
| Lobby      | 6                  | 6                  | 6                   |
| Gameplay   | 14                 | 17                 | 13                  |

Lobby = Play → Find Match → Bot Match → deck → Keep. Consistent across all 3 runs at 6 calls.
Gameplay calls dominated by: scry (12, 7, 8) + drag (6, 4, 4) + 888,504 clicks (3, 0, 0).

## Stuck Events

### Run 1 — gsId stuck at 12 (turn 1, Phase_Main1)
**Sequence:**
1. gsId=6: agent scried hand (Swamp at x=440). Dragged (440,500)→(480,300) → **SUCCESS**: gsId advanced to 7, hand went 7→6.
2. gsId=7: agent hit 888,504 → gsId advanced to 8. Hand went back to 7 (drew Black Mage's Rod at x=480; Swamp now at x=440).
3. gsId=8: agent dragged (440,500)→(480,300) — gsId advanced to 9 but hand stayed 7. Swamp at x=440 was NOT played; the drag likely hit a non-land or triggered a stack event (life still 20, Swamp still in hand at gsId 9+).
4. gsId=9: agent hit 888,504 → gsId=10. Dragged (520,500) — x=520 is OUT OF RANGE (max hand x was 480). Drag misfired. gsId went 10→11.
5. gsId=11: agent hit 888,504 → gsId=12. Now stuck.
6. Remaining calls: drags from y=530, y=545, then grep/sed code-reading, OCR, drag from x=750 — all left gsId=12 unchanged. Killed after 90s.

**Root cause:** Agent used `scry state` x-coords correctly on turn 1 (x=440 worked), but after the turn advanced and the hand shifted, it re-used the same x without re-reading current hand state. At gsId=8, Swamp was still x=440, so the second drag *should* have worked — but gsId=9 shows hand still 7. Likely a second-land-per-turn block (already played one land) caused the drag to silently fail, advancing gsId for a different reason (triggered ability or auto-pass). Agent interpreted gsId advance as land-played, then lost track entirely.

**Recovery:** None. Agent spent 7 tool calls (grep, sed, OCR, drag variants) trying to diagnose drag failure without using `arena play` or `arena detect`.

### Run 2 — gsId stuck at 23 (turn 2, Phase_Main1, 8 cards in hand)
**Critical context:** Game started at turn 2 with gsId=23 — this session was **Sparky's turn 1 already passed** (bot goes first). Hand had 8 cards (one above 7-card limit). OCR at line 43 showed: "You will need to discard down to 7 at the end of your turn." Plains were at x=260, 300, 340 (three plains in hand).

**Sequence:**
1. Scried state: gsId=23, 8 cards, turn 2.
2. Dragged (260,500)→(480,300) — Plains at x=260 — gsId unchanged.
3. Rescried: gsId still 23. Then tried compound drag + inline python parse — python error (json.load on non-JSON output).
4. Rescried: still 23. OCR: noticed discard warning.
5. Dragged (260,530)→(480,300) — no output returned (silent error, possibly exit code swallowed by shell chain). Rescried: still 23.
6. **Agent pivoted to reading source code** (scry_lib/tracker.py, cli.py, models.py, arena.py) trying to understand drag mechanics — 8 tool calls reading/grepping code.

**Root cause:** Drag from (260,500) failed silently — gsId never moved. The discard warning visible in OCR suggests the game may have been waiting for a mandatory discard action before processing other inputs. But scry state showed priority_player=1 and Phase_Main1, so the drag should have been valid. The actual cause was likely the same drag reliability issue: agent was using hardcoded y=500 but hand y position may have been different with the discard overlay active. Agent did not try `arena play "Plains"` at any point.

### Run 3 — gsId stuck at 6 (turn 1, Phase_Main1)
**Sequence:**
1. Scried: gsId=6, 7 cards, Plains at x=400 and x=440.
2. Dragged (440,500)→(480,300) — FAILED. gsId stayed 6.
3. Dragged (400,500)→(480,300) — FAILED. gsId stayed 6.
4. OCR: showed "Ethereal Armor" at (461,494), "Optimistic Scavenger" at (556,496) — confirming cards ARE visible at y≈494.
5. Dragged (400,494)→(480,300) — FAILED. gsId stayed 6.
6. Tried `arena click "Plains"` — "Plains" not found on screen (OCR text was Ethereal Armor, not Plains).
7. Rescried — gsId still 6, 7 cards.
8. Dragged (400,530)→(480,280) — FAILED. gsId stayed 6.
9. Read tracker.py source (grep, cat), checked window bounds via osascript. Killed at line 62 with window bounds = `0, 31, 1920, 1108`.

**Root cause:** Drag never worked for any y value (494, 500, 530). This is the same failure mode as runs 2 and the prior batch. Run-1 succeeded at (440,500) on turn 1 — that is the ONLY successful drag in this batch. The specific difference for run-3: same y=500 was tried but gsId never moved even once. Possible the drag is unreliable and only worked in run-1 by chance. Agent correctly tried OCR-derived y but it still didn't work.

## Patterns

### What worked
- **Lobby navigation** is stable (all 3 runs, 6 calls, no failures). Play → Find Match → Bot Match → deck click → Play → Keep mulligan — 100% success rate.
- **Scry state** correctly provides hand x coords that match actual card positions (confirmed run-1 x=440 worked once).
- **First drag in run-1 succeeded** (440,500) → land played. Proves the drag path is not permanently broken — it works sometimes.

### What consistently failed
1. **Drag reliability after first successful land play:** Run-1 only got 1 land down; all subsequent drags for the same card type failed.
2. **`arena play "<name>"` never used** in any run despite being the recommended primary card-play method (CLAUDE.md memory: "`arena play "<name>"` — best. Verified drag + zone change check."). All agents used raw `bin/arena drag` with hardcoded coords.
3. **Same-turn second land attempt:** Run-1 agent hit 888,504 after playing a land on turn 1, advancing to a new gsId. This was likely opponent's draw step. Agent then tried to play another land (already played one this turn) — impossible, silently failed.
4. **Agent pivots to code-reading when stuck:** All 3 runs ended with the agent reading source files (tracker.py, arena.py, cli.py) instead of trying `arena play` or `arena detect`. This is the terminal failure mode: no recovery, 6-8 wasted tool calls.
5. **8-card hand not handled:** Run-2 started with 8 cards (bot's turn already passed) and discard warning was visible. Agent did not recognize the discard-required state and kept trying to play lands.
6. **gsId stuck detection too slow:** Harness kills after 90s of same gsId. By that point the agent has already burned 6-10 tool calls trying variants. A 30s stuck threshold would save ~60s per stuck event.

### Token tracking still broken
All runs report 0 tokens/0 cost. The subagent modelUsage is not propagated to harness metrics. This was flagged in batch-08-52-59 refinement #7 but not fixed.

## Refinements

Ranked by impact:

1. **Mandate `arena play "<name>"` over `bin/arena drag`** — The prompt must forbid raw `bin/arena drag` for card plays. `arena play` does the drag internally with zone-change verification and retry. All 3 runs used raw drag exclusively, which has no retry logic. The one successful drag in this batch was luck.

2. **Check land-per-turn limit before drag** — After playing a land, agent must NOT try to play another land same turn. Add explicit check: if `turn_info.turn_number` matches `land_played_this_turn` counter (derivable from zones/battlefield), skip land. Or simply: after one successful land (gsId advances + hand count drops for a basic land), mark `land_played=true` for this turn_number.

3. **Discard detection and handling** — When `scry state` shows `hand.length > 7`, agent must discard before any other action. Add explicit rule: if `len(hand) > 7`, use `arena detect` or OCR to find non-essential cards and drag them to graveyard/discard zone, then confirm. Run-2 was stuck for 173s because it never handled the discard-first requirement.

4. **Kill code-reading pivot** — When stuck (gsId unchanged after 2 drag attempts), the agent must try `arena play "<card name from scry hand[0]>"` before any file reading. The prompt should explicitly say: "Do NOT read source files to diagnose drag failures. If drag fails twice, use `arena play`."

5. **Reduce stuck timeout to 30s** — The 90s harness kill timeout wastes ~60s per stuck event. All 3 games were clearly stuck within 30s (gsId unchanged across 2+ drags with sleep 2-3 between them = ~10s actual; remaining ~80s of polling was wasted).

6. **Fix subagent token aggregation** — All metrics show 0 tokens/0 cost. The harness must extract `modelUsage` from the subagent's result and aggregate it into run metrics.

7. **Pass `arena play` card name from scry hand directly** — Instead of computing x coords, agent should do: `CARD=$(bin/scry state | python3 -c "import json,sys; h=json.load(sys.stdin)['hand']; print(next(c['name'] for c in h if 'Plains' in c['name'] or 'Swamp' in c['name'] or 'Forest' in c['name'] or 'Island' in c['name'] or 'Mountain' in c['name']))"); bin/arena play "$CARD"`. This is a one-liner that extracts the first land name and plays it directly.
