# Autoplay Batch Report — 2026-03-10_08-52-59

## Summary Table

| Game | Turns | Cards Played | Tool Calls | Screenshots | OCR Calls | Scry Calls | Blind Clicks (888,504) | Drags | Cost | Wall Time | Result |
|------|-------|-------------|------------|-------------|-----------|-----------|------------------------|-------|------|-----------|--------|
| 1    | 10    | 0 (failed)  | 11         | 0           | 1         | 3          | 0                      | 2     | n/a  | 131.7s    | KILLED (gsId=23 unchanged 90s) |
| 2    | 11    | 2           | 36         | 0           | 8         | 9          | 3                      | 2     | $0.74 | 437.9s  | DEFEAT (opponent lethal T11) |
| 3    | 11    | 4 (3 landed, 1 spell→cancel) | 79 | 0 | 21 | 27 | 9 | 12 | $1.72 | 610.1s | CONCEDE (timeout @600s) |

Notes: Run 1 token data not captured by harness (metrics show 0). Run 2/3 token counts from `modelUsage` field: Run 2 = 43 in / 7,838 out, cache read 1.25M; Run 3 = 88 in / 14,938 out, cache read 3.9M.

## Phase Split

### Run 2 (36 total tool calls)
- Lobby (Play → Keep): 5 tool calls (~14%)
- Gameplay (Keep → concede): 31 tool calls (~86%)

### Run 3 (79 total tool calls)
- Lobby (Play → Keep): 20 tool calls (~25%) — inflated by Mastery Pass modal recovery
- Gameplay (Keep → concede): 59 tool calls (~75%)

Token split not measurable per phase (harness does not checkpoint tokens at phase boundary).

## Stuck Events

### Run 1 — Terminal stuck at gsId=23, turn 2 Phase_Main1
- Agent tried to play a Plains. First drag at (200,500) → gsId unchanged. Second drag at (165,500) → gsId unchanged. No further recovery attempts — harness watchdog killed after 90s.
- Only 2 drag attempts total before kill. Agent had no escalation path (no OCR re-survey, no `scry state` hand parse, no brute sweep).
- Root cause: Plains not detectable by OCR, and the two extrapolated coordinates both missed. The card was likely further left or hidden under the edge.

### Run 3 — Four consecutive missed drags at gsId=141 (turn 7 Phase_Main1)
- Agent tried: drag (200,500), (760,500), (370,500), (370,510) — all four at gsId=141.
- On the 4th attempt at y=510 (after three at y=500), gsId advanced to 142.
- This is exactly the pattern flagged in batch-08-24-51 refinement #2: sweeping fixed x-coords at wrong y burns 3–4 tool calls before accidental hit.
- The y=510 vs y=500 variation was the key fix, not the x-coord change.

### Run 3 — Multiple blind-pass 888,504 bursts between turns
- Turn 3 end: `click 889,504 && click 889,504 && sleep 3` (double tap)
- Turn 5 end: `click 888,504 && click 888,504 && sleep 2` (double tap)
- Turn 5 end extended: `for i in 1 2 3; do click 888,504 && sleep 1; done` (triple tap, 3 separate clicks)
- Total: 9 blind 888,504 clicks per metrics. These are sent even during Sparky's turns (scry not called first to gate).
- Refinement #3 from prior batch (gate on priority_player) not implemented yet.

### Run 3 — Drag hit Banishing Light and triggered targeting prompt (turn 7)
- Drag at (410,510) played Regal Caracal → went to stack → targeting prompt appeared.
- Agent correctly called `scry state` to check stack, saw "Regal Caracal", then `cancel`. gsId advanced correctly.
- Recovery worked but cost 3 extra tool calls (scry, scry, cancel). Targeting prompt detection still reactive, not proactive.

### Run 2 — Never played Plains (wrong color mana all game)
- Agent had Plains in hand every turn but consistently targeted Mountain-type coords.
- First OCR (turn 2) showed cards at x=324,424,505,595 — agent dragged 324,500 (Mountain). No attempt to identify Plains by name from scry hand data.
- This is a decision-quality failure: scry state lists card names in hand but agent did not parse them to locate Plains specifically before dragging.

## Decision Quality

### Run 1
- Land play: attempted but failed (both drags missed). No creatures played. No spells.
- No recovery escalation after second miss — agent gave up implicitly (ran out of attempts before watchdog fired).
- Mulligan: handled correctly (Keep on first try).

### Run 2
- Land play: yes (Mountain T2, Temple of Triumph T4). Correct for the available cards.
- Creatures: 0 — hand full of white spells needing Plains; never identified and played Plains.
- Attack/block: not applicable (0 creatures). Passed to Sparky correctly each turn via 888,504 triple-click.
- Discard prompts: handled correctly all 3 times (OCR found card names, clicked them).
- Scry 1 prompt (from Temple of Triumph): handled correctly (clicked TOP, Done).
- Concede: triggered but opponent got lethal before concede resolved (T11 combat).

### Run 3
- Land play: yes — Plains (T1), creature Leonin Vanguard (T1), Blossoming Sands (T3), Plains (T5). Most productive land play of the batch.
- Creature play: 1 (Leonin Vanguard). Good — agent capitalized on T1 with both land + creature.
- Spell attempts: Banishing Light (x2), Unflinching Courage (x1), Regal Caracal (x1) all triggered prompts and were cancelled. Agent correctly detected and cancelled each, but 6 drag attempts for non-land cards wasted ~12 tool calls.
- Attack: not tested (game conceded at T9 Main2, skipped T9 Main1 entirely due to fast phase advancement).
- Mastery Pass modal: agent correctly used `osascript` Escape to dismiss. Added ~8 lobby tool calls.
- Concede flow: worked perfectly (cog → Concede → Defeat text → dismiss).
- Unnecessary OCR calls: agent called `ocr --fmt` after every drag even when scry could determine success/failure alone. Each OCR + scry pair where only scry was needed = 1 wasted call.

## Agent Retro Synthesis

Run 1: No retro written (agent was killed by watchdog before writing).

Run 2 themes:
- Lobby worked first try. Scry-based turn tracking reliable.
- Never played Plains → no creatures → lost purely on mana colors.
- Discard and Scry 1 prompts both handled correctly.
- Opponent auto-kill before concede is a timing issue, not an agent issue.

Run 3 themes:
- Plains unreliable in OCR (no bold/distinctive text) — agent resorted to x-coord sweeps.
- y=510 more reliable than y=500 for hand cards (confirmed by retro and trace).
- `arena click "Cancel"` more reliable than clicking coord for cancel/targeting dismissal.
- Mastery Pass modal not in known-modals list → extra recovery tool calls.
- Fast phase advancement (turns jump 2→4→6…) because clicking "Pass" during opponent's portion advances multiple phases at once.

## Patterns

### Consistently worked
- Lobby flow (Play → Bot Match → deck select → Play): 2/3 runs on first try (run 3 needed modal recovery).
- Mulligan: Keep on first attempt in all 3 runs.
- `bin/scry state` for turn/phase/gsId tracking: reliable across all runs.
- Discard prompts: OCR-based card-name targeting worked every time.
- Scry 1 prompt (triggered by Temple of Triumph): handled correctly.
- `arena click "Cancel"` for targeting/cost dismissal: reliable.
- Concede flow (cog → Concede → Defeat → dismiss): reliable.

### Consistently failed
1. **Plains drag by coordinate extrapolation** — fails in all 3 runs. Agent guesses x-coords from visible OCR text positions; Plains is hidden under other cards or at an edge. Sweep strategy burns 2–4 tool calls before hitting or giving up.
2. **No scry-based land identification** — agent does not call `scry state` to get hand card names + instanceIds before dragging, so it cannot know which x-coord maps to which card. Blind sweeps are the inevitable fallback.
3. **Blind 888,504 clicks without priority check** — fired during Sparky's turns. Harmless but wasteful (9 clicks in run 3). Prior refinement not applied.
4. **OCR called redundantly after every drag** — scry state alone suffices to determine drag success (gsId advance + hand count change). OCR adds 1 extra tool call per drag attempt with no additional value in most cases.
5. **Targeting/cost prompts from non-land drags** — agent drags spells to battlefield even when mana is insufficient or spell is non-trivial (Banishing Light needs target, Regal Caracal needs 5 mana). Should check mana + spell type from scry data before attempting to cast.

## Refinements

Specific, actionable changes ranked by impact:

1. **Before any drag, parse hand from scry state.** Call `scry state`, extract `zones[*].objects` where zone type is Hand, get `name` + `instanceId` for each card. Identify lands vs spells. This eliminates all blind coordinate sweeps and enables targeted plays (e.g., "instanceId 164 = Plains → drag its detected position").

2. **Use `arena detect` to locate specific hand cards by name** after scry identifies what's in hand. OCR text coordinates for hand cards are unreliable (cards overlap, lands have non-bold text). `detect` returns bounding boxes; filter by `cy > 490` and `cx < 800` for hand region.

3. **Gate 888,504 clicks on `priority_player == own_seat`.** Before any pass click, check `scry state` priority_player. If it's Sparky (seat 2), sleep 1s and rescry. Do not click at all. This eliminates the 9 wasted blind clicks in run 3. (Repeated from batch-08-24-51 — not yet implemented.)

4. **Check mana available and spell cost before attempting spell drag.** From scry state, sum available mana (permanents with tap ability + untapped lands). Compare against spell CMC. Do not attempt to drag spells the agent cannot afford. Do not attempt to drag spells with targeting requirements unless agent has implemented target selection.

5. **Add Mastery Pass modal to known-modals list in prompt.** OCR signature: "Teenage Mutant Ninja Turtles Mastery" or "Mastery Pass". Action: `osascript -e 'tell application "System Events" to key code 53'`. Modal blocks all other navigation. Currently handled ad-hoc with ~8 wasted lobby tool calls.

6. **Drop redundant OCR after drag when scry suffices.** Pattern `drag && sleep 2 && scry state` is sufficient to verify a drag. Only call `ocr --fmt` when specifically needed (e.g., discard prompt card selection, modal text unknown). Eliminates ~6 OCR calls per game.

7. **Fix harness token tracking for run 1 (subagent mode).** Run 1 reports `total_input_tokens=0`, `total_cost_usd=null`, `duration_ms=0`. The outer agent that dispatches the subagent does not propagate subagent token usage back to the harness. Either switch run 1 to non-subagent mode or aggregate `modelUsage` from the subagent result into the harness metrics.
