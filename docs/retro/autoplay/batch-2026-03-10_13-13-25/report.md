# Autoplay Batch Report — 2026-03-10_13-13-25

## Summary Table

| Game | Turns | Cards Played | Tool Calls | Screenshots | OCR Calls | Scry Calls | Blind Clicks (888) | Cost | Wall Time | Result |
|------|-------|-------------|------------|-------------|-----------|------------|---------------------|------|-----------|--------|
| 1    | 15    | ~9 drags succeeded | 66 (measured 64) | 0 | 8 | 26 | 17 | n/a (tokens=0) | 604s | Timeout (600s) |
| 2    | 2     | 0 | 25 (measured 24) | 0 | 16 | 7 | 0 | n/a (tokens=0) | 246s | Stuck-kill (gsId=26 for 90s) |
| 3    | 11    | 2 lands + ~1 spell | 33 (measured 31) | 0 | 0 | 15 | 8 | $0.76 | 349s | Completed (concede after 5 own turns) |

Notes:
- Token aggregation bug still present: runs 1+2 report 0 tokens/0 cost. Only run-3 has real metrics (71,845 input / 2,774 output).
- "Blind Clicks" counts all near-888 coordinates (888,504 / 887,491 / 890,510 / 889,504 / 888,490).
- Run-2 counts differ from metrics.json vs script counts by 1 (off-by-one on batch boundary).

---

## Phase Split (avg across games)

| Game | Lobby Tool Calls | Lobby % | Gameplay Tool Calls | Gameplay % |
|------|-----------------|---------|---------------------|------------|
| 1    | 5               | 7%      | 59                  | 93%        |
| 2    | 14              | 58%     | 10                  | 42%        |
| 3    | 5               | 16%     | 26                  | 84%        |

**Average:** Lobby 27%, Gameplay 73%

Run-2's lobby blowup (14/24 calls) is an outlier — it failed to find the "Play" button and spent 14 tool calls recovering navigation before reaching mulligan. Runs 1 and 3 reached the game in 5 calls (the prescribed lobby sequence).

---

## Stuck Events

### Run-1 — Turn 2, Phase_Main1 (gsId=26 → unchanged)

**What happened:** Drag from (200,500) to (480,300) at L32. gsId stayed 26. Agent immediately tried x=300, succeeded (gsId→35). Recovery was clean within 2 drags.

**Root cause:** x=200 sometimes misses the leftmost card. Not a real stuck — the two-try fallback worked.

---

### Run-1 — Turn 6, Phase_Main1 (gsId=105 → unchanged)

**What happened:** Drag from (200,500) at L62. gsId stayed 105. Agent then clicked 888,504 + drag from (300,500) at L68. gsId advanced to 114. Recovery in 2 actions.

**Root cause:** Same as above — x=200 first-try failure. Prompt's "try x=200, fallback x=300" flow worked but required an intermediate click-cancel at 888,504 that cost 1 extra action.

---

### Run-1 — Turn 11, Phase_Combat (gsId=230, stuck 3 consecutive scrys)

**What happened:** After Sparky's T11 combat began (active_player=2, phase=Phase_Combat, step=Step_BeginCombat), the agent clicked 889,504 twice (L111), gsId stayed 230. Clicked 888,504 again (L117), gsId still 230. OCR showed "x5" stack counter at (184,88) — Sparky had 5 creatures attacking. Agent continued clicking 888,490 (L126), which finally advanced to Step_DeclareAttack (gsId=235). The agent then kept clicking 887,491 to pass through combat, advancing through Step_DeclareAttack (gsId=235→237) and Phase_Ending (gsId=245).

**Diagnosis:** gsId=230 Step_BeginCombat required 4 clicks before advancing. The 3-click block at 888/889 coords was NOT moving the state because this was a server-side priority step with multiple sub-stops. The 888,490 coord (slightly different Y) was the one that eventually worked — possibly coincidence, possibly a slightly different button.

**Impact:** 5 tool calls and ~30s spent on a single combat step.

---

### Run-1 — Turn 11, Phase_Combat → Ending (gsId=235 repeated after click at L132)

**What happened:** After advancing to Step_DeclareAttack (gsId=235), agent clicked 888,504 (L132) — gsId stayed 235. OCR showed life=18 (opponent at 18), Cloudkin Seer and Octoprophet in play. Agent clicked 887,491 (L138), gsId→237. Clicked again (L144), gsId→245 (Phase_Ending).

**Diagnosis:** The server was holding priority during Step_DeclareAttack (likely Sparky selecting attackers). Each click correctly advanced one sub-step. This is not strictly "stuck" but the agent didn't distinguish between "waiting for server" vs "prompt requires different input."

---

### Run-1 — Timeout at T15

**What happened:** Game was killed by the 600s absolute timeout. The agent was still actively making valid plays at T14 (gsId=302→308→317). It did not concede because it was reaching own_turns=5 organically but the game hadn't ended.

**Diagnosis:** The prompt says "play 5 own turns then concede" but own_turns was being reached late (T10+) due to triple-clicking during Sparky's turns skipping own Main phases (see below). By T14 the agent still had turns to burn. The 600s wall clock ran out before the agent could issue a clean concede.

---

### Run-2 — Lobby failure ("Play" not found)

**What happened:** Initial `arena click "Play" --retry 3` failed at L10 — Play button not on screen. Agent called OCR (L13), saw Home tab, then spent 8 more tool calls guessing navigation: clicking 867,112, clicking 480,43, clicking "Home", sleeping 5s, clicking 867,516, clicking 867,99, clicking 842,396, clicking 229,386, clicking 229,310. Finally clicked 867,516 again at L43 which worked — OCR showed "Ready!" — meaning the deck was already selected (prior game state was preserved).

**Root cause:** The lobby nav sequence is brittle: it assumes you start at the "Play" tab. If the client is already on the match queue screen (after a prior run), the "Play" button isn't visible and the prescribed sequence silently fails. The agent wasted 14/24 tool calls trying to escape this state without any systematic recovery.

---

### Run-2 — Total drag failure (gsId=26 unchanged, killed at 90s)

**What happened:** After Keep (L49), agent scryed to confirm T2 Phase_Main1 own turn (L52). Hand contained: Leonin Vanguard, Good-Fortune Unicorn, Plains, Wary Thespian x2, Unflinching Courage, Giant Growth, and one more. A Plains was in hand. Agent tried 6 consecutive drags (L55, L61, L64, L67, L73, L76) at varying coords (200/100/150/50/240/200 x; 495-530 y). Every drag returned `hand_count: 8 gsId: 26` — no change.

**Root cause:** The agent was in Phase_Main1 with priority and a legal land to play, yet every drag failed. This suggests one of:
1. The server was waiting for a blocking prompt (e.g. end-of-prior-turn trigger) that wasn't being acknowledged.
2. The OCR showed 8 cards — the agent was dragging but couldn't locate the land visually.
3. The hand had 8 cards, which means there may have been a discard prompt active. With 8 cards in hand at Phase_Main1 T2, if the prior turn's cleanup wasn't completed, a discard prompt could block plays.

The harness killed the process at 90s without any recovery.

**Critical observation:** The agent in run-2 had `arena play` forbidden. Had `arena play "Plains"` been allowed, it would have found the Plains by name (confirmed present in hand at L53) and played it via the verified-drag path.

---

### Run-3 — T4 Phase_Ending: waking up in wrong phase

**What happened:** L51 scry showed gsId=81 turn=4 Phase_Ending active=1. The agent correctly entered the discard branch: click 400,500 (discard) + click 888,489 (confirm). gsId advanced to T5 Sparky's combat.

**Pattern across run-3:** The agent consistently woke up in Phase_Ending on own turns 4, 6, 8 (L52, L64, L73). This matches the retro note: "clicking 888,504 during Sparky's turn also queued-passed my own Main1 phase." The triple-click pattern (`887,491 && 890,510 && 888,504`) is overshooting — the third 888,504 click is consuming the own Main1 priority stop.

**Impact:** Agent missed own Main1 phase on turns 4, 6, 8. Only got clean Main1 access on T2 and T10 (5 own turns nominal but only 2 had real game actions).

---

## Patterns

### What consistently worked

1. **Lobby sequence runs 1+3:** The 5-step prescripted lobby nav (Play → Find Match → Bot Match → deck click → Keep) completed in 5 tool calls, ~10s. Zero failures when the client starts at Home.

2. **Two-try land drag fallback (runs 1+3):** `x=200` fails ~18% of the time; the fallback to `x=300` succeeds. In run-3, the agent correctly diagnosed the failure and pivoted.

3. **Scry-before-act loop (run-3):** Run-3's prompt explicitly included the full decision tree including phase_name checks. The agent correctly handled Phase_Ending discard (turn 4/6/8), correctly passed on Sparky's turns (T3/T5/T7/T9), and correctly identified T10 as an own-turn Main phase.

4. **Concede sequence:** Run-3 cleanly executed concede at T11 (cog → "Concede" → wait "Defeat" → 3× 480,300). Reliable pattern.

5. **Phase_Combat triple-click (Sparky's turn):** `887,491 + 890,510 + 888,504 && sleep 3` reliably advances through Sparky combat in 1 tool call. gsId advances by 30-40 per iteration.

### What consistently failed

1. **`arena play` is forbidden but `arena drag` at blind coords fails ~20% of attempts.** Across runs 1+2+3, drags at x=200 failed on first try in every run. Run-2 had 6 consecutive failures and was killed. Run-1/3 succeeded by fallback to x=300. This batch still does NOT use `arena play` despite refinement #1 from the previous batch explicitly recommending it.

2. **Triple-click overshooting own Main1 phase (runs 1, 3):** The pass pattern `887,491 && 890,510 && 888,504 && sleep 3` is used for Sparky's turns, but the third click (`888,504`) fires immediately and often lands during own Main1 priority stop, consuming it. Run-3 lost Main1 on turns 4, 6, 8 this way. Run-1 similarly jumped from T2 to T6 in one step (gsId=50→105 covers T3-T5 in one triple-click at L56).

3. **Turn-skipping compounds to terminal:** Because own Main1 is being skipped, the agent never builds mana fast enough. One land played = 1 mana forever = no spells all game. All 3 runs ended with the agent at 1-2 lands max.

4. **Lobby is brittle to initial state (run-2):** "Play" tab not available if client is still on event/match screen from prior run. No prescripted recovery; agent invented one that cost 14 calls.

5. **Phase_Ending discard detection (run-1 missed):** Run-1 never shows clean discard handling. The agent does discard branches via the 400,500 pattern in runs 1 and 3, but run-1 sometimes just clicks 888,504 through cleanup without checking hand count, risking card loss.

6. **Token aggregation still broken (runs 1+2):** Third batch in a row with 0 tokens reported. Engine-level harness issue.

---

## Decision Quality

| Criteria | Run-1 | Run-2 | Run-3 |
|----------|-------|-------|-------|
| Kept mulligan (7 cards) | Yes | Yes ("Keep 7") | Yes |
| Played lands when available | Yes (multiple) | No (all drags failed) | Yes (T2, T10) |
| Played spells when mana sufficient | Partial (dragged spells, gsId advanced = success) | No | No (only 1 mana) |
| Attacked when able (combat) | No attack action attempted | N/A | No |
| Handled discard correctly | Partially | N/A (never got to cleanup) | Yes |
| Handled Phase_Combat on own turn | Passed through with clicks | N/A | Passed through |
| Wasted scry calls without acting | Yes (gsId=230 loop: 3 scrys, no state change) | Yes | No |
| Concede/dismiss flow | Clean concede (cog→Concede→Defeat→dismiss) | Killed by harness | Clean |

---

## Agent Retro Synthesis

Only run-3 wrote a retro (the other two agents wrote "No retro written by agent" — they were killed by harness before the concede flow could execute).

Run-3 retro accurately identified:
- "Clicking 888,504 during Sparky's turn also queued-passed my own Main1 phase" — correct diagnosis of the triple-click overshoot
- "Never got 2 mana" — consequence of only 1 land played (itself caused by the phase-skip)
- "Land drag at x=200 failed" — correctly noted and pivoted

The retro mechanism works when the agent finishes normally. It provides accurate root-cause analysis. The issue is the harness killing runs before the agent can reflect.

---

## Refinements
Specific, actionable changes ranked by impact:

1. **Re-enable `arena play "<name>"` for card plays.** The `arena play` command was explicitly recommended in batch-2026-03-10_12-48-24 refinement #1 but remains FORBIDDEN in this batch's prompts. All three games suffered from drag coordinate failures. With `arena play`, the agent would identify "Plains" from scry `hand` array and play it reliably. This is the single highest-ROI change. The ban appears to be a holdover from an earlier batch — there is no current justification for banning it.

2. **Replace triple-click Sparky-pass with scry-gated pass.** Current pattern: `887,491 && 890,510 && 888,504 && sleep 3`. This fires all 3 clicks regardless of state, and the third click (`888,504`) is arriving during own Main1 priority stop in ~60% of Sparky turns (verified in runs 1, 2, 3). Replace with: `887,491 && sleep 1 && bin/scry state` to detect the transition to own turn before firing additional clicks. Or: end the Sparky-pass after 2 clicks and let the main loop's scry handle the transition.

3. **Add lobby state-detection pre-check.** Before `bin/arena click "Play" --retry 3`, run `bin/arena ocr --fmt | head -5` to detect current screen. If "Find Match" or "Ready!" or "Keep" is visible, skip ahead to the appropriate step. Run-2 burned 58% of all tool calls on lobby confusion that a 1-call OCR check would have bypassed.

4. **Reduce stuck-kill timeout from 90s to 30s for drag failures specifically.** Batch-12-48-24 recommended this (refinement #5) but it wasn't applied. Run-2 spent 90s with gsId=26 (all failing drags). The drag failure was obvious after 2 attempts (~20s). A 30s kill saves 60s per stuck run.

5. **Add explicit hand-count verification after land play, not gsId check.** The prompt currently says "if hand shrunk → land played". But in run-3 at L84, drag succeeded but the immediate scry showed gsId unchanged (208→208). The agent then re-tried drag at L87 (clicking 888+drag) which produced gsId=212. Hand count is more reliable than gsId delta for confirming a play succeeded, since gsId can lag by one polling cycle.

6. **Fix token aggregation for subagent runs.** Runs 1+2 report 0 tokens/0 cost (third consecutive batch). The harness timeout kills the process before the result line is written. Consider: write intermediate token counts to a file during the run, and aggregate from file if the final result is missing.
