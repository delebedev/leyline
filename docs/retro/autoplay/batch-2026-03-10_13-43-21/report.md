# Autoplay Batch Report — 2026-03-10 (batch-2026-03-10_13-43-21)

## Summary Table

| Game | Turns | Cards Played | Tool Calls | OCR Calls | Scry Calls | Blind Clicks 888,504 | Screenshots | Tokens (in/out) | Cost | Wall Time | Result |
|------|-------|-------------|------------|-----------|------------|----------------------|-------------|-----------------|------|-----------|--------|
| 1 | 11 | 7 | 27 | 2 | 10 | 2 | 0 | 0 / 0 | n/a | 266s | killed — gsId=97 stuck 90s |
| 2 | 7 | 9 | 56 | 9 | 25 | 9 | 0 | 0 / 0 | n/a | 606s | killed — absolute timeout 600s |
| 3 | 9 | 11 | 46 | 5 | 16 | 0 | 0 | 2,272,346 / 11,563 | $1.09 | 422s | exit 0 (concede) |

Token data missing for runs 1 and 2 (harness kill before final write).

## Phase Split

Token data available only for run-3. Run-3 had 47 agent turns; lobby phase consumed approximately the first 3–4 turns (cog click, Play, Find Match, deck select). Estimated split for run-3:

- Lobby: ~8% of turns, ~10% of tokens
- Gameplay: ~92% of turns, ~90% of tokens

Runs 1 and 2: no token data; phase split not computable.

## Stuck Events

### Run 1 — land drag loop, gsId=97 (lines 58–82)

At turn 5, Phase_Main1, the agent tried to drag a Plains from the hand using a sequence of coordinate guesses:

1. Line 58: `bin/arena drag 230,500 480,300` — no gsId change
2. Line 64: `bin/arena click 888,504 && bin/arena drag 320,500 480,300` — no change
3. Line 70: `bin/arena drag 200,500 480,300` — no change
4. Line 76: `bin/arena ocr --fmt` (diagnostic)
5. Line 80: `bin/arena drag 200,530 480,300` — still gsId=97

The final scry state (line 83) shows gsId=97, turn 5, Phase_Main1, active_player=1 with 3 Plains in hand and only 1 Plains on the battlefield (tapped). The agent never played the land. The harness killed it after 90s at gsId=97.

Root cause: same x=200 drag failure documented in previous batches. The agent had already learned x=300 works in prior turns of this same game (line 64 used 320 and moved on) but regressed to x=200 again on subsequent tries.

The battlefield shows opponent (seat 2) played Island + Wall of Runes + River's Favor, suggesting a non-trivial board state that the agent was missing by not advancing.

### Run 2 — high blind-click rate, 600s timeout

Run 2 had 9 blind clicks to 888,504 across 56 tool calls — one in every 6.2 calls on average. This is the highest rate in the batch. The agent reached only turn 7 despite having 10 minutes.

Key stuck sequence (lines 46, 116, 124, 184 — all in same match):

- L46: `click 888,504 × 2` — pass without checking state
- L64: `click 888,504 + ocr` — unconfirmed result
- L104–L116: agent confused "To Blockers" button with "No Attacks"; clicked 888,504 twice then immediately clicked again before verifying phase change
- L124: `click 888,504 × 2 + scry state` — finally checked
- L184: `click 888,504 × 2` — late game, no check

The agent's thinking logs show combat phase confusion: after clicking 888,504 to cancel a Main1 prompt, it advanced directly to DeclareAttackers without playing lands. OCR showed "To Blockers" but agent treated it as ambiguous and continued clicking 888,504 rather than verifying turn position.

Outcome: consumed all 600s across 7 turns, averaging 86s/turn. Never resolved the combat prompt cleanly.

### Run 3 — clean, no stuck events

0 blind clicks to 888,504. 11 drag calls. Conceded cleanly on turn 9. The retro (run-3/retro.md) documents:

- Land drag x=200 still failed every time (3 occasions) but agent immediately retried at x=300 and succeeded
- No triple-click or combat confusion
- Sparky life dropped from 20→18→16 via Hopeless Nightmare ETB drains
- Concede path worked: Cog → Concede → Defeat → 3 dismiss clicks

## Patterns

**Drag x=200 failure is a persistent multi-batch bug.** Confirmed across batches 13-13-25 and 13-43-21. The fix is `arena play "<name>"` (uses scry `hand` array for exact name → no coord guessing). Run-3 retro also confirms this: "blind-drag (x=300, x=200 failed)" logged for every land play.

**Token aggregation still broken for killed runs.** Runs 1 and 2 show 0/0 tokens, $null cost. Three consecutive batches with this problem. The fix (write intermediate token counts to a temp file) was recommended in batch 13-13-25 but not yet implemented.

**Run-2 combat confusion pattern.** The agent clicked 888,504 to "cancel" a Main1 stop, which instead advanced the game to DeclareAttackers. Then it saw "To Blockers" via OCR and clicked 888,504 again. This triple-click pattern burned 86s/turn. The recommended fix from batch 13-13-25 (scry-gated pass: 2 clicks + scry, then only fire 3rd if still active_player=2) was not applied.

**Scry call rate vs. blind click rate are inversely correlated with performance.** Run 3: 16 scry calls, 0 blind clicks, exit 0. Run 2: 25 scry calls, 9 blind clicks, timeout. Run 1: 10 scry calls, 2 blind clicks, stuck kill. High scry rate in run-2 did not compensate for blind clicks because the scry call came after the click rather than before.

**arena play not used in any run.** All 3 runs used `arena drag` with manual coords. This was recommended across 2 prior batches as the fix for coord failures. Not yet applied.

**No screenshots in any run.** Positive finding — confirmed that the 0-screenshot goal from earlier batches held.

## Refinements

Specific, actionable changes ranked by impact:

1. **Enable `arena play "<name>"`** for all card plays — resolves x=200 land drag failure that caused run-1 stuck kill. Scry `hand` gives exact name → no coord guessing. This is the third batch recommending this fix. Priority: critical.

2. **Pre-click scry before every 888,504** — never fire 888,504 without first checking `active_player` and `phase`. Pattern: `scry state && [only click if phase=expected]`. Eliminates run-2 combat phase confusion where a Main1 pass advanced to DeclareAttackers.

3. **Fix token aggregation for harness-killed runs** — write `input_tokens` and `output_tokens` to `/tmp/autoplay-tokens.json` after every tool call; aggregate from that file if the agent exits with non-zero. Two of three runs in this batch have $null cost. Third consecutive batch with this bug.

4. **Land verification by hand-count, not gsId** — gsId can lag one poll cycle. After drag, check `len(hand)` decreased by 1. Prevents the run-1 loop where agent retried drag despite the state already showing the correct result or giving false negatives.

5. **Reduce stuck-kill timeout to 30s** — gsId=97 was stable for 90s before the harness killed run-1. 30s is sufficient to detect that no action has advanced state after 3 drag retries. Recommended in two prior batches, still at 90s.
