# Autoplay Batch Report — 2026-03-10_08-24-51

## Summary Table

| Game | Turns | Cards Played | Tool Calls | Screenshots | OCR Calls | Scry Calls | Blind 888,504 | Wall Time | Result |
|------|-------|-------------|------------|-------------|-----------|------------|---------------|-----------|--------|
| 1    | 1     | 0           | 12         | 0           | 2         | 4          | 0             | 131s      | killed: gsId=6 unchanged 90s |
| 2    | 5     | ~3 effective| 57         | 0           | 20        | 30         | 9             | 546s      | killed: gsId=101 unchanged 90s |
| 3    | 10    | ~3 effective| 91         | 0           | 25        | 26         | 9             | 610s      | killed: absolute timeout 600s |

Token counts: all zero (metrics.json populated by harness but token tracking not wired yet — `total_input_tokens=0` across all runs).

## Phase Split

Token tracking is not operational (all zeros), so phase split is estimated from tool call ratios.

**Lobby phase** (start → Keep click):
- Run 1: 5 tool calls out of 12 (42%)
- Run 2: 6 tool calls out of 57 (11%)
- Run 3: 5 tool calls out of 91 (5%)

Lobby is cheap once working. The disproportion in run 1 is because the session ended early — gameplay never got going.

**Gameplay phase** (Keep → death/timeout):
- Run 1: 7 tool calls — 3 failed drags, 2 scry checks, 1 OCR, 1 more drag attempt
- Run 2: 51 tool calls — targeting confusion, combat pass loops, stuck on last land play
- Run 3: 86 tool calls — modal interrupts (6× "View Battlefield" prompt), targeting, opaque pass-through combat

## Stuck Events

### Run 1 — Turn 1, Phase_Main1: all drags failed, gsId never moved

Agent had a 7-card hand: `['Swamp', "Black Mage's Rod" ×3, 'Cornered by Black Mages', 'Swamp', 'Fanatical Offering']`. It tried to drag cards at x=285, 200, 289 (all y=500) to (480,300). All three drags returned success ("dragged …") but gsId remained at 6 and hand_size stayed at 7.

Root cause: agent did not verify gsId change after first drag; immediately tried different x coords assuming a hit-detection miss. The drags likely missed all cards — y=500 may be too low for this hand/deck layout, or cards were not at those positions. No OCR-based card detection was used.

The agent was killed after 90s with gsId=6 unchanged.

### Run 2 — Turn 1, 4 drags to play 1 land

After Keep, agent tried (350,500), (750,500), (400,500) with no effect (hand=7, gsId=6), then ran a loop over x=300,330,360,700,730,760 — x=300 finally worked (hand dropped to 6, gsId=7). Total: 6 drag attempts for 1 land play.

### Run 2 — Turn 3, Phase_Main2: Ethereal Armor target confusion

After dragging Ethereal Armor (or similar aura) onto the field, a "Target a creature." prompt appeared. Agent clicked (283,205), (283,300), (283,350) — each time OCR still showed "Target a creature." and "Cancel". Agent then pressed (888,456) — this cancelled the cast (hand went back to 5). Spell was wasted without attaching. The agent did not use `arena detect` to find creature positions; it guessed Y coordinates by hand.

### Run 2 — Turn 4, Phase_Combat: 3-click blind-pass loop repeated twice

Agent was on Sparky's turn (ActiveP=2, priority_player not checked). Ran the "pass trio" (click 887,491 + 890,510 + 888,504) twice in a row with no state change check between repetitions, then a 5×click 888,504 loop. OCR confirmed Sparky's combat was happening but agent couldn't distinguish its own priority stop from Sparky's auto-play.

### Run 2 — Turn 5, Phase_Main1: stuck on gsId=101 (terminal)

Agent played a Plains (gsId dropped from 100 to 101, hand 6→5). Then tried to play Ethereal Armor: ran a sweep of x=570,590,610,630,650,670 — all dragged to (480,300), none changed gsId (stayed 101, hand stayed 5). Agent then noticed via OCR that "Feather of Flight" was at (460,493) — tried clicking (283,334) and (283,280) as targeting attempts, then ran coordinate sweeps for x at y=205. Killed after 90s stuck at gsId=101.

Analysis: Ethereal Armor is an aura — it needs a target click after the drag. The drag likely triggered the targeting prompt, but the agent was still trying to drag the card rather than submitting a target. No use of `arena detect` for creature positions.

### Run 3 — Repeated "View Battlefield" modal (6 occurrences)

A "View Battlefield" modal appeared with a "Yes" button at (784,661) six times throughout run 3 (lines 18, 42, 78, 90, 136, 246 in the event log). Each time the agent correctly clicked "Yes" to dismiss it. However, each modal appearance cost 2 tool calls (OCR + click) and introduced latency. Total overhead: ~12 extra tool calls just for modal handling.

This modal is not described in the prompt or arena-nav skill. The agent handled it ad-hoc by luck (OCR found "Yes" text). There is no documented recovery for this modal.

### Run 3 — Turn 8, Phase_Combat: gsId=219 stuck twice in a row

Agent was in Sparky's combat (ActiveP=2). Ran 3× blind click 888,504, got gsId=221 (life1 dropped 20→9, so combat did resolve), then another 3× 888,504 returned gsId=221 unchanged. Agent did OCR, then clicked (887,491), which advanced to gsId=229. The stuck was Sparky attacking — agent needed to pass blockers prompt. Two consecutive 3-click blind sweeps were required to get past it.

### Run 3 — Turn 9 Seam Rip cast: no valid targets visible

Agent cast Seam Rip (targeting prompt appeared), tried clicking (403,209) and (452,215) as creature targets, then called (887,488) to cancel. Tried (452,215) + (887,488) again. Then "View Battlefield" modal appeared. Eventually a "Yes" click resolved the hanging state. Seam Rip appears to have been cancelled/bounced rather than resolving with a target.

## Patterns

### Consistently worked
- **Lobby navigation**: Play → Find Match → Bot Match → deck select → Play → Keep. Reliable across all 3 runs with minimal tool calls (5–6).
- **`bin/arena wait text="Keep"`**: landed correctly every time.
- **"Yes" modal dismissal** (run 3): agent handled an undocumented modal correctly via OCR text matching.
- **Combat pass for own turn**: click 889,504 twice + scry to verify turn advanced.
- **Scry state for hand/phase info**: worked reliably; hand contents and phase were accurate.

### Consistently failed
- **Blind-coordinate card drags**: y=500 misses cards in many hand configurations. Agent uses fixed y=500 without OCR-based card detection. Across runs 1–3, ~50% of drag attempts returned "dragged" but no gsId change.
- **Aura/targeted spell resolution**: agent drags the aura to the battlefield but doesn't know to then click a creature for target. When the target prompt appears, the agent treats it as a navigation problem (guessing y coords) rather than a targeting problem (use detect to find creatures). Affected: Ethereal Armor in runs 2 and 3, Feather of Flight in run 2.
- **Sparky's priority stop vs own stop**: agent cannot tell when it has priority vs when Sparky has priority. During Sparky's main phase and combat, agent fires blind 888,504 clicks hoping to pass through. Occasionally presses "Pass" via OCR-click which accidentally advances to wrong phase. The 3-click "pass trio" pattern fires even when Sparky is resolving spells.
- **No land-played gate**: in run 2 turn 5, agent played a land then immediately tried to play another land (Ethereal Armor was in the way but a second Plains was also in hand). No check for `land_plays_remaining` before attempting.
- **Stuck kills always at gsId unchanged**: all three runs ended at a gsId unchanged condition caused by targeting prompt remaining open and agent not detecting it.

## Refinements

Specific, actionable changes ranked by impact:

1. **Teach targeting prompt detection before any spell drag.** After every drag that doesn't reduce hand count within 2s, call `arena ocr` and check for "Target a creature." or "Choose a target". If found: use `arena detect` to get creature coords, click the best candidate, then call (888,489) to submit. This single change would fix the terminal stall in all three runs.

2. **Replace fixed y=500 card drags with OCR-based detection.** Before dragging, call `arena ocr --fmt` and find the card name text coords. Real hand cards are at cy~530; anything at cy~410 is a hover preview. Use the detected cx as the drag source x. This eliminates the multi-sweep "try x=300,330,360…" pattern entirely.

3. **Add `priority_player` check before blind pass clicks.** Scry state includes `priority_player`. The agent should only click 888,504 when `priority_player == own_seat`. When it's Sparky's priority, just wait (sleep 2 + re-scry). This prevents firing 5× blind clicks into Sparky's spell resolution.

4. **Document and pre-handle "View Battlefield" modal.** This modal appeared 6 times in run 3. Add it to the agent prompt's known modals section: "If OCR shows 'View Battlefield', click 'Yes' immediately to dismiss." This turns a 2-tool ad-hoc response into a 0-extra-tool pattern.

5. **Verify gsId advance after each individual drag**, not after a sweep. Current pattern: run 5 drags, check gsId once at end. Better: drag → sleep 1.5 → scry → if hand changed, done; else try next position. Reduces wasted scry calls and exits the loop as soon as the first successful play is confirmed.

6. **Fix token tracking in harness.** All three `metrics.json` files report `total_input_tokens=0`. Without token data, phase split analysis is impossible and cost tracking is broken. The harness should accumulate token counts from the Claude API response metadata.
