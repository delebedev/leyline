# Autoplay Batch Report — 2026-03-10 (batch 04-05-40)

## Summary Table

| Game | Turns | Cards Played | Tool Calls | Screenshots | OCR Calls | Scry Calls | Blind Clicks (888,504) | Tokens (in/out) | Cost | Wall Time | Result |
|------|-------|-------------|------------|-------------|-----------|------------|------------------------|-----------------|------|-----------|--------|
| 1 | 7 (entered at T7; played T1–5 in new game) | 3 coord drags (2×Island, 1×?) | 16 | 0 | 0 | 4 | 3 | 573k / 5.4k | $0.363 | 203s | concede OK |
| 2 | 6 | 2 coord drags | 46 | 0 | 11 | 13 | 8 | N/A (timeout) | N/A | 428s | TIMEOUT |
| 3 | 8 | 2 coord drags | 47 | 0 | 8 | 12 | 7 | N/A (timeout) | N/A | 429s | TIMEOUT |

Notes:
- Game 1 token costs inflated: agent started in a mid-game state (turn 7 from previous session) and the arena-nav skill payload dominated input tokens.
- Games 2 and 3: token metrics not recorded due to SIGTERM at 420s hard limit.
- "Cards played" = confirmed drag operations; `arena play "<name>"` failed in all 3 games (basic lands + named creatures return "not found in hand").

---

## Phase Split

### Game 1
- **Lobby phase** (L1–L31, through `arena click "Keep"`): ~8 tool calls, ~50% of wall time (session started in-game → conceded prior game → new game spawned after 3× blind clicks at T7)
- **Gameplay phase** (L32–L55): ~8 tool calls, remaining wall time
- Token split: not measurable per-phase (single session token count), but arena-nav skill load (L5) accounts for bulk of input tokens

### Game 2
- **Lobby phase** (L1–L58, through `arena click "Keep"`): ~28 tool calls (~61% of total 46)
  - Includes failed Play button, Home navigation, 2 failed deck selections, OCR loops
- **Gameplay phase** (L59–L138): ~18 tool calls (~39%)
- Lobby consumed majority of budget: combat stuck events ate remaining time

### Game 3
- **Lobby phase** (L1–L64, through `arena click "Keep"`): ~26 tool calls (~55% of 47)
  - Failed Concede on in-game state, OCR loop, 2 failed deck selections
- **Gameplay phase** (L65–L129): ~21 tool calls (~45%)
- Pattern identical to Game 2: lobby bloat + repeated discard handling drained the 420s budget

**Avg across games:** ~50% of tool calls consumed in lobby/navigation. Combat handling added another ~20% in Games 2–3.

---

## Stuck Events

### Game 1

**Stuck 1 — Entered mid-game (Turn 7, Phase_Main1)**
- Phase: "Lobby" equivalent — previous session's game still active
- Agent read scry showing T7 and correctly decided to concede the old game. Three blind 888,504 clicks + scry confirmed new match spawned. Recovery: effective.
- No actual stuck — clean transition.

**Stuck 2 — `arena play "Island"` fails (T1, Phase_Main1)**
- L14–L18: `arena play "Island"` → "not found in hand". `arena play "Waterkin Shaman"` → same. Agent immediately fell back to coord drags (L20). Recovery: 2 extra tool calls wasted.

### Game 2

**Stuck 1 — Deck selection (Lobby, ~L35–L52)**
- Agent clicked deck thumbnail at (83,375) — wrong coord (y=375 = 80px above label for starter decks). Play button at (866,533) pressed but "Keep" wait timed out (20s). Second attempt: (229,375) — still wrong. Third attempt: (229,455) correct y. Total: 3 deck click attempts + 2 OCR loops + 1 extra Play click. Wasted ~5 tool calls (~60s).
- Root cause: agent memory says "click ~80px above label" but starter deck labels are at y≈455, so 80px above = 375 lands on blank space or wrong element.

**Stuck 2 — Combat stuck at Phase_Combat (Turn 5, Sparky's turn)**
- L89–L127: scry showed `Phase_Combat active:2` repeatedly across 5 separate scry checks and 2 OCR loops.
- L93: agent looped `for i in 1..5 click 888,504` (5× blind clicks). No movement (scry still shows Phase_Combat).
- L95: scry confirms still Phase_Combat. Agent tried offset variants: 887,491 / 890,510 (pass/to-blockers coords).
- L104: OCR revealed two-button state: "Pass" at (887,491) and "To Blockers" at (887,510). Agent correctly clicked "To Blockers" then "Pass" to advance.
- L110–L123: still Phase_Combat after those clicks; two more scry calls, another OCR.
- L123: Step_DeclareBlock confirmed. Agent then clicked 888,490 twice to advance.
- Total: ~12 tool calls (5 scry + 4 OCR + varied clicks) to escape one combat phase. This consumed ~120s and contributed to timeout.

**Stuck 3 — Same-gsId stall (Turn 2, Phase_Ending)**
- Run 3 L80 and L85: both scry calls return `turn: 2 phase: Phase_Ending active: 1`. Five 888,504 clicks between them (L82–L83) produced no phase advance.
- Then L87 reveals `hand_count: 8` — discard prompt is active, blocking all other actions. Blind clicks at 888,504 do nothing during discard. Agent took 3 extra tool calls to figure this out.

### Game 3

**Stuck 1 — `arena play "Plains"` fails (T2, Phase_Main1)**
- L70–L71: identical to Games 1 and 2. Immediate fallback to coord drags. Expected failure, 2 wasted calls.

**Stuck 2 — Phase_Ending stall at T2 (same-gsId)**
- L80 + L85: scry returns `turn: 2 Phase_Ending` twice with 5 blind clicks between them.
- Root cause: discard prompt (hand_count=8) blocking. Agent detected it at L87–L88 and handled correctly (L91).
- Wasted calls: 2 scry + 5 blind clicks before diagnosis.

**Stuck 3 — Phase_Ending stall at T4**
- L102: scry shows `turn: 4 Phase_Ending hand_count: 8` again. Agent correctly identified discard immediately this time (1 scry call). Improvement over T2 handling.

**Stuck 4 — Combat stuck at T5 (Phase_Combat active:2)**
- L108–L113: scry shows Phase_Combat. Agent fires 5×888,504 blind clicks (L111). Scry at L113 shows `turn: 6 Phase_Ending` — combat resolved. Recovery worked but via luck (blind clicks eventually hit the right button).
- No OCR used here (unlike Game 2). Agent got lucky that 888,504 happened to align with the active button during combat.

---

## Patterns

### What consistently worked
- **Concede flow** (`arena click 940,42` → `arena click "Concede"` → wait Defeat → 3× dismiss): 100% success across all games.
- **Mulligan Keep**: `arena click "Keep" --retry 3` worked once the game screen was visible; `arena wait text="Keep"` had mixed results (timed out in Games 2–3 if deck selection didn't stick).
- **Discard handling** (click 400,500 + 888,489): worked every time once agent identified it was needed.
- **scry state for phase/turn tracking**: reliable output, used well to confirm advancement.
- **OCR-guided combat** (Game 2 only): agent used `ocr --fmt | grep block\|pass` to identify the two-button state and navigate correctly. This was the right approach and should be the default.

### What consistently failed
1. **`arena play "<name>"` for any card** — 0/3 games succeeded. Basic lands (Island, Plains) and named creatures all returned "not found in hand". This is a fundamental tooling bug: the debug API card lookup fails for hand cards. Agent wastes 2 tool calls per game attempting this.
2. **Deck selection coord targeting** — starter deck clicks at y≈375 (80px above label) fail; correct y is 455 (label itself). Agent memory note is wrong. Games 2 and 3 both burned 2–3 extra tool calls per lobby.
3. **Blind 888,504 during combat** — works sometimes (Game 3 T5) but fails completely during multi-button combat steps (Game 2 T5). No reliable way to know which case applies without OCR. Produces stalls of 5–12 tool calls.
4. **Discard-blocking stall** — hand_count=8 at Phase_Ending blocks all priority passing. Blind clicks do nothing. Agent took 2–3 extra tool calls per occurrence before diagnosing. No proactive check for hand overflow.
5. **Games 2 and 3 hit the 420s hard timeout** — both games spent too long in lobby (failed deck selections) and combat (blind click loops). Neither game completed the intended 5-turn sequence.

### Timing pathology
- Game 1: 203s wall time for 16 tool calls (~12.7s/call avg). Completed cleanly.
- Games 2–3: ~428s wall time for 46–47 tool calls (~9.3s/call avg). Hit timeout. The issue is not per-call speed — it's call count. Both games used 3× more calls for the same task. Lobby wasted ~26–28 calls; combat/discard loops wasted another ~10–15.

---

## Refinements

Ranked by impact:

1. **Fix `arena play` for basic lands and hand cards** — the debug API lookup does not find basic lands or standard hand cards. This fails in 100% of games and forces coord-drag fallback. Either fix the API lookup to search hand objects by name (case-insensitive, partial match), or document the fallback pattern explicitly in the prompt and remove `arena play` from the recommended toolbox until fixed.

2. **Fix agent memory for deck selection coords** — memory says "click ~80px above deck label". Starter decks have labels at y≈455; 80px above = y≈375 = blank space. The correct coord is the label itself (y≈455) or slightly above it (y≈440). Update memory and the arena-nav skill. This is burning 2–3 extra lobby calls every game.

3. **Add pre-emptive hand-overflow check before phase-passing** — at Phase_Ending, agent should check hand_count as part of the standard turn-end sequence rather than discovering it via stall. Pattern: `scry state | check hand_count; if >=8: discard first, then pass`. This eliminates the "5 blind clicks then diagnose" pattern seen at T2/T4 in Games 2–3.

4. **Default to OCR before blind-click combat loops** — in Game 2, the agent correctly used OCR to detect "Pass" / "To Blockers" buttons after 5 failed blind clicks. In Game 3, it got lucky. The prompt should specify: before any multi-click blind loop at combat phase, do one `arena ocr --fmt | grep -i "block\|pass\|attack"` to detect button state. Only fall back to 888,504 if a single unambiguous action button is present.

5. **Reduce lobbby scry calls** — Games 2–3 each had 5–6 scry calls in the lobby/navigation phase, most providing no new information. Prompt should specify: no scry calls until mulligan is complete. Lobby state is managed by OCR text, not game engine state.

6. **Increase hard timeout or set per-phase budgets** — 420s is too tight when lobby navigation requires 26+ tool calls. Either increase to 600s, or add a lobby-phase call budget (e.g., "if you haven't reached Keep within 15 tool calls, abort and retry from Home"). Games 2–3 hit timeout before executing the core gameplay loop.

7. **Discard target selection** — agent always clicks (400,500) as discard target without verifying what card is there. This is non-deterministic. Prompt should say: `scry state` to identify hand objects, pick lowest-value card by name heuristic, use `arena play` (or coord) to target it specifically.
