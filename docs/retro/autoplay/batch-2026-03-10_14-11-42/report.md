# Autoplay Batch Report — 2026-03-10 14:11

## Summary Table

| Game | Turns | Cards Played | Tool Calls | Screenshots | Tokens (in/out) | Cost   | Wall Time | Result          |
|------|-------|-------------|------------|-------------|-----------------|--------|-----------|-----------------|
| 1    | 9     | 4           | 61         | 0           | 130 / 1347 (+ 6.6M cache) | n/a  | 607s      | timeout (600s)  |
| 2    | 8     | 4           | 57         | 0           | 124 / 1277 (+ 6.5M cache) | n/a  | 608s      | timeout (600s)  |
| 3    | 8     | 4           | 25         | 0           | 66 / 570 (+ 2.6M cache)   | n/a  | 402s      | stuck gsId=163  |
| 4    | 9     | 8           | 61         | 0           | 3.76M / 13987             | $1.66 | 526s      | concede (clean) |
| 5    | 9     | 5           | 48         | 0           | 100 / 1052 (+ 4.8M cache) | n/a  | 567s      | stuck gsId=200  |

Notes:
- Run 4 is the only run with full token aggregation (metrics bug still present in runs 1-2-3-5).
- Cache tokens dominate — runs 1-3 and 5 show <200 non-cache input tokens, meaning the agent's own reasoning is minimal.
- No screenshots taken in any run (good).

## Phase Split (avg across games)

Lobby sequence is identical across all 5 runs: 6 tool calls (click Home → Play → Find Match → Bot Match → deck → wait Keep + click Keep).

| Phase    | Tool Calls (avg) | % of total |
|----------|-----------------|------------|
| Lobby    | 6               | ~12%       |
| Gameplay | ~44             | ~88%       |

Lobby is fast and reliable. All variation is in gameplay.

## Stuck Events

### Run 1 — Turn 5 Phase_Main1 (gsId 108–120, 6 tool calls)
Agent had hand=[Swamp, Veteran Survivor, some spell] at turn 5 after playing land. OCR showed a "Choose One / Submit" modal. Agent tried `click 373,279` + Submit (failed, exit code 1), then `click 325,279` + Submit (failed), then `click 460,160` + Submit (succeeded gsId=114), then `click 325,278` + `click 886,489` (some secondary action). Took 6 extra tool calls to resolve an unknown modal. Root cause: agent saw a non-standard prompt and guessed coords for a "Submit" button that didn't exist at those coords.

### Run 1 — Turn 6 Phase_Combat (gsId 150–164, 4 consecutive clicks)
Active player was opponent (seat 2) during their combat step but priority remained with seat 1. Scry showed `active=2, priority=1` — i.e., we hold priority during opponent's combat. Agent fired 4 double-clicks at (887,491)+(890,510) trying to pass priority through opponent's DeclareAttackers → DeclareBlockers → combat damage. Each click advanced the gsId by ~4 steps. Eventually reached Phase_Ending. Correct behavior but wasteful — 4 tool calls to pass through one phase.

### Run 2 — Turn 6 Phase_Combat (gsId 140–155, 10 tool calls)
After playing a creature at gsId=140, scry showed `active=1, priority=1` in Phase_Combat — our own attack phase. Agent tried to navigate DeclareAttackers UI:
- `click 889,504 x2` → gsId=141 (still Phase_Combat, no attacker declared)
- OCR → `click 475,276` + `888,504` → gsId=145 (tried clicking on a creature at y=276)
- OCR → `click 473,274` + `888,504` → gsId=152 (tried again)
- `click 889,504 x2` → gsId=155 (still Phase_Combat)
- `click 889,504` → gsId=155 **STALE**
- OCR → `click 479,452` → gsId=167 (Phase_Main2, resolved)

The click at 479,452 (battlefield region, below y=300) finally passed through attack phase. 10 calls total. The agent had no explicit "I am in DeclareAttackers, I should press pass/skip" rule — it tried clicking creatures and hoping for advancement.

### Run 3 — Turn 8 Phase_Main1 (gsId=163 stale x2, stuck-killed)
Agent arrived at turn 8 with 8 cards in hand (Crusader of Odric, Plains, Dawnwing Marshal, Goblin Surprise, Plains, Release the Dogs, Dauntless Veteran, Goblin Surprise). Earlier in the game, combined commands had `scry state` bundled after arena actions, so only 25 total tool calls were used but they included inlined scry results.

At gsId=163 (our turn, Main1), agent tried drags at x=700, x=770, x=855 — all outside the normal hand range (300–620). None changed gsId. The kill hit at 90s. Root cause: agent escalated drag x-coords without doing OCR first to locate actual card positions. At turn 8, with 8 cards, the hand may fan wider or cards may shift right. Without OCR the agent is guessing coords in the wrong region.

### Run 4 — Turn 5 Phase_Main2, Fanatical Offering sacrifice prompt (gsId=104 stale x2)
Agent cast Fanatical Offering (1B draw 2, sacrifice artifact or creature). The spell went on stack and showed a sacrifice target UI. Agent tried:
- `click 480,300` (center of battlefield)
- `click 350,360` (offset)
- `click 500,420` (further offset)
All failed (gsId stayed 104). Then `click 887,456` (Cancel) → gsId=105 (Fanatical Offering returned to hand with Grim Bauble still in play as artifact). The Cancel worked. This cost 5 extra commands. Root cause: sacrifice target selection has no coord-guessing strategy in the agent prompt. The artifact Grim Bauble on the battlefield was the valid sacrifice target but agent didn't know to click on it.

### Run 4 — Turn 7 Phase_Main1, Rowan's Grim Search "Choose One" modal (gsId=134 stale x1)
Agent dragged at x=300 intending to play Swamp, but at turn 7 the leftmost hand card was Rowan's Grim Search (2B sorcery), not a land. Drag at x=300 cast the spell. Scry showed gsId=134 STALE after a pass click — the "Choose One" modal was still open. Agent OCR'd and saw "Ch" prefix text (truncated OCR of "Choose"), then clicked 353,177 (left option = normal cast), advancing to gsId=137 with 2 cards drawn. The drag-at-x=300 for lands is unreliable when hand card order shifts between turns.

### Run 5 — Turn 8 Phase_Main1 (gsId=200 stale x2, stuck-killed)
Agent played Plains at x=600 (gsId=199), OCR returned garbled `S75L` at 446,23, agent clicked 888,458 (near pass button at y=458 instead of y=504), advancing to gsId=200. Then attempted drag at x=680 → gsId=200 STALE. Agent couldn't identify cards from OCR and tried a coord at the far right of the hand. Hand still had 6 cards but agent had no path forward. Stuck-killed at 90s. Root cause: garbled OCR text prevented card identification; agent had no fallback to use `arena play` by name from scry hand list.

## Patterns

### What consistently worked
- Lobby navigation: 100% success across all 5 runs — the 6-step sequence (Home → Play → Find Match → Bot Match → deck → Keep) was identical and reliable.
- Land plays at x=300: reliable in turns 1–4 when hand order is predictable; a land is typically first in the hand at game start.
- Combat pass through opponent's turn: `click 887,491 + 890,510` pattern reliably advanced through Sparky's combat/ending phases.
- No screenshots taken: zero in all 5 runs — agent correctly used OCR instead.
- Run 4 concede: clean, immediate — cog → Concede → wait Defeat → dismiss.

### What consistently failed

**1. Blind x-coord land drags misfire on wrong card.**
Four occurrences across runs: run-4 turn-7 (dragged Rowan's Grim Search instead of Swamp), run-3 (dragged wrong cards x=700–855), run-5 (dragged at x=680 with no result). The strategy of `drag 300,500 480,300` is only valid if the leftmost hand card at y=500 is a land. As turns progress and hand composition changes, this breaks. The retro note from run-4 confirms: "Blind drag at x=300 grabbed the spell instead of the Swamp land."

**2. No strategy for DeclareAttackers / combat phase navigation.**
Runs 1, 2 both spent 4–10 extra tool calls clicking various coords to navigate through their own attack phase. The agent lacked an explicit rule: "if `phase=Phase_Combat` and `active=priority=my_seat`, press 888,504 twice to skip attack and block declarations."

**3. Sacrifice/modal prompts have no coord resolution.**
Run-4 Fanatical Offering: 5 failed clicks trying to guess sacrifice target coords. The agent prompt has no guidance for "find artifact/creature on battlefield and click it." `arena play` doesn't cover this case.

**4. Stuck-kill timeout still 90s.**
Runs 3 and 5 were stuck for 90s before harness killed them. Recommended as 30s in 3 prior batches. Each 90s kill wastes ~1.5 minutes that could be another game attempt.

**5. Token aggregation broken for 4 of 5 runs.**
Only run 4 (which produced a clean `result` line) has token data. Runs killed by SIGTERM lose the final result line. Need intermediate token checkpointing.

**6. `arena play "<name>"` not used for any card plays.**
All card plays used coord-based drags. `arena play` resolves by card name from the scry hand list, completely bypassing the coord-guessing problem. Third consecutive batch where this is the top recommendation and still not applied.

## Refinements

Specific, actionable changes ranked by impact:

1. **Use `arena play "<name>"` for ALL non-land card plays.** Scry state always returns `hand` with `name` fields. Before each card play, pick the target card name from the `hand` array and call `arena play "<name>"`. This eliminates all wrong-card drag errors (run-4 turn-7, run-3 turn-8). Only exception: plain lands where blind drag has historically worked. Even for lands, prefer `arena play "Plains"` / `arena play "Swamp"` over x=300 coord.

2. **Explicit combat phase prompt rule.** When `phase=Phase_Combat` and `active_player=priority_player=own_seat`: the correct action is always to click 888,504 twice (skip attackers, then pass). No OCR needed. No creature-clicking needed. The agent currently treats combat entry as an unexpected state requiring investigation. Add to prompt: "Phase_Combat on your turn = double-click 888,504 to pass through; no attackers for simplicity."

3. **Reduce stuck-kill timeout to 30s.** Fourth consecutive batch with this recommendation. At 90s the harness wastes 2 minutes per stuck run. 30s is enough to detect a true stuck state. Runs 3 and 5 were visibly stuck within 20s (3 consecutive same-gsId reads).

4. **Fix token aggregation for killed runs.** Write a running token total to `/tmp/run-N-tokens.json` after every tool call. Harness reads this on exit if the transcript's `result` line is missing. Without this, 80% of runs have no cost data.

5. **Add OCR-before-fallback rule for hand cards.** When a drag returns a stale gsId (no state change), the next step must be `arena ocr --fmt` to identify actual card positions, not to try higher/different x-coords blindly. Run-3 and run-5 both escalated x-coords (700, 770, 855 / 680) without OCR. Rule: "if drag fails (gsId unchanged), do OCR first, then retry by name."

6. **Add sacrifice/modal cancel as default fallback.** When a card play creates an unexpected modal (non-standard sub-prompt, not "Choose One" or DeclareAttackers), click Cancel immediately at 887,456 rather than guessing target coords. This cuts run-4's 5-click Fanatical Offering detour to 1 click. The Cancel approach worked correctly and recovered Fanatical Offering to hand.
