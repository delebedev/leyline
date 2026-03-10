# Autoplay Batch Report — 2026-03-10_04-29-09

5 games. All completed (exit_code=0). All reached ≥5 turns before concede. Zero screenshots across all runs.

---

## Summary Table

| Game | Turns (game#) | Cards Played (drag attempts) | BF Growth (scry) | Tool Calls | Screenshots | OCR Calls | Scry Calls | Blind Clicks 888,504 | Tokens (in/out) | Cost USD | Wall Time |
|------|--------------|------------------------------|-------------------|------------|-------------|-----------|------------|----------------------|-----------------|----------|-----------|
| 1 | 8 (T1→T5 observed) | 4 drags, ~3 landed | 0→? (only 1 scry) | 21 | 0 | 5 | 3 | 4 | 634k / 8.4k | $0.405 | 388s |
| 2 | 8 (T2,4,6,8) | 8 drags, ~9 BF net | 1→10 | 23 | 0 | 0 | 5 | 8 | 726k / 4.0k | $0.379 | 235s |
| 3 | 8 (T1,4,7) | 4 drags, ~9 BF net | 0→9 | 17 | 0 | 0 | 3 | 5 | 480k / 3.3k | $0.269 | 174s |
| 4 | 7 (T2,4,5) | 4 drags, ~6 BF net | 1→7 | 17 | 0 | 0 | 3 | 6 | 480k / 2.9k | $0.264 | 184s |
| 5 | 9 (T2,4,6,8,9) | 4 drags, ~8 BF net | 1→9 | 22 | 0 | 0 | 5 | 6 | 685k / 4.4k | $0.372 | 215s |

**Totals:** 100 tool calls, $1.689 total, avg 240s/game, avg 19.6 tool calls/game.

Notes on "BF Growth":
- BF objects include both players' lands + creatures. The agent plays a single-player deck vs Sparky.
- Run-1 only had 1 scry before gameplay, so no diff available.
- Run-2 BF starts at 1 because scry was called after first play (game_turn 2 = one mana source already dropped).

---

## Phase Split (per run)

Lobby = tool calls before first `scry state`. Game = from first scry state onwards (including scry call itself).

| Game | Lobby calls | Game calls | Lobby % (calls) | Lobby issues |
|------|-------------|------------|-----------------|--------------|
| 1 | 11 | 9 | 55% | "Play" OCR failure x3; needed 5 extra recovery calls |
| 2 | 5 | 17 | 22% | None |
| 3 | 5 | 11 | 31% | None |
| 4 | 5 | 11 | 31% | None |
| 5 | 5 | 16 | 24% | None |

Token split is not directly measurable per phase (tokens are per API call, not per tool call), but run-1's lobby overhead is visible in its high output token count (8.4k vs avg 3.6k for runs 2-5) — the extra OCR calls each generate new reasoning.

**Normal lobby is 5 tool calls:** `click "Play"` → `wait "Find Match"` → `click 867,112 + "Bot Match"` → `click 82,455 + 867,516` → `wait "Keep" + click "Keep"`. Runs 2-5 hit this exactly.

---

## Stuck Events

### Run-1 — Lobby: "Play" tab not found (9 extra calls)

Phase: Lobby. Before any game state.

Sequence:
1. `click "Play" --retry 3` → FAIL: "Play" not found
2. `wait "Find Match" || ocr` → OCR shows nav bar without "Play" (Home/Profile/Decks/Store/Mastery/Achievements)
3. `click 867,112 + "Bot Match" --retry 3` → FAIL: Bot Match not found, wrong screen
4. `ocr` → still on Home/Store screen
5. `click 207,43 + "Bot Match" --retry 3` → FAIL
6. `ocr` → still no match content
7. `click "Home" + 867,112 + ocr` → "Play" appears at (867,516) in OCR output — agent misidentifies this as play area rather than lobby trigger
8. `click "Bot Match" --retry 3` → FAIL: still not found
9. `click 82,455 + 867,516 + wait "Keep"` → wait times out
10. `click "Play" --retry 3` → finds "Play" at (258,154) in Events area — succeeds, reveals Find Match nav
11. `click 866,472 (Bot Match) + 82,455 + 867,516 + wait "Keep"` → succeeds

Root cause: Arena was on the Events/Store tab at startup. The `click "Play" --retry 3` instruction at the start of the prompt assumes Play is in the nav bar, but the client was in a tab that doesn't have it. The agent iterated correctly but wastefully (5 OCR calls, 3 failed Bot Match lookups).

Recovery: agent eventually found "Play" via text-OCR at (258,154) and succeeded. No permanent failure.

### Run-2 — Turn 6 Phase_Ending: discard required (1 extra scry + 1 discard click)

Phase: Gameplay, turn 6.

Scry returned `game_state_id=120, turn=6, Phase_Ending, Step_Cleanup, hand=8`. Agent correctly identified discard requirement, clicked `400,500` (hand card area) + `888,489` (submit), then advanced. The NEXT scry showed `gsId=159, turn=8` — so the discard resolved and two bot turns passed. No stuck behavior, handled correctly.

This is worth flagging as a **success pattern**: agent read `hand > 7` from scry and acted before passing. Not a stuck event, but the prompt correctly handles this case.

### Run-3 — Turn 4: scry returned opponent's turn (active=2)

Phase: Gameplay.

Scry at gsId=80 returned `turn=4, Phase_Main2, active_player=2` (Sparky's turn). Agent reasoning: "Turn 4, Phase_Main2, active_player=2 (Sparky's turn). I need to pass through." Agent clicked `887,491 + 890,510 + 888,504` (three blind passes) + the standard 3x loop. This advanced to turn 7 for the agent's next scry. No stuck behavior — correctly identified it was opponent's turn.

However: agent called the exact same 3-pass sequence (`887,491 + 890,510 + 888,504`) as a "two-button stack" fallback. This was because scry showed the `for i in 1 2 3` loop hadn't fully advanced the game. Worked, but the use of `887,491`/`890,510` is improvised coords not in the standard nav.

### Run-4 — Turn 5: scry returned opponent's combat (active=2, Phase_Combat, Step_DeclareBlock)

Phase: Gameplay.

Scry at gsId=101 returned `turn=5, Phase_Combat, Step_DeclareBlock, active_player=2`. Agent clicked `887,491 + 890,510 + 888,504` to pass through Sparky's block step, then immediately conceded. Total turns logged: 2, 4, 5 (3 observed). The agent treated "turn 5 reached" as condition met even though it was Sparky's turn 5. Decided to concede there. This is acceptable but borderline — turn count target is ambiguous.

### Runs 3 and 4 — "Two-button stack" pattern at non-standard coords

Both runs used `bin/arena click 887,491 && sleep 1 && bin/arena click 890,510` as an alternate pass when the 3x 888,504 loop appeared insufficient. These coords are not documented — they're adjacent pixels, possibly a "double-stacked" action button when two options appear. This works but is fragile.

---

## Decision Quality

| | G1 | G2 | G3 | G4 | G5 |
|---|---|---|---|---|---|
| Lands played | Yes (scry shows BF growing) | Yes | Yes | Yes | Yes |
| Creatures/spells when mana available | Attempted (blind drags) | Yes — verified by BF growth | Attempted | Attempted | Attempted |
| Attack when able | No — no attack logic at all | No | No | No | No |
| Mulligan handled | Yes (text-click "Keep") | Yes | Yes | Yes | Yes |
| Discard (hand > 7) handled | N/A | Yes (turn 6, x1) | N/A | N/A | Yes (turns 4+6, x2) |
| Modals/triggers | None triggered | None | None | None | None |
| Unnecessary state checks | 3 extra scry in run-1 lobby | None | None | None | None |

**Attack is never executed.** The agent's loop is: drag + 3x blind click to advance. There is no "check if combat is active, tap to attack" step. Creatures go to battlefield but never attack.

**Drag coords are fixed.** Every run used `370,500 → 480,300` and `450,500 → 480,300` as card source positions, regardless of hand layout or card count. No `arena detect` call was ever made. Drags succeed often (BF grows) but this is probabilistic — not guaranteed to hit the right card.

**No `arena play "Card Name"` usage.** All drags are raw coords. The prompt provides `arena play` as the primary card-play mechanism but the agent consistently used raw drags instead.

---

## Agent Retro Synthesis

Themes from self-reports across 5 runs:

**What worked (consistent):**
- Scry state parsing with python3 one-liner was reliable and fast
- 888,504 blind clicks advanced phases as expected
- Concede flow (cog `940,42` → text "Concede") worked first try every run
- Defeat screen dismiss (3x `480,300`) always succeeded
- Phase_Ending discard detection from `hand > 7` worked correctly in runs 2 and 5
- Mulligan "Keep" text-click always found the button (8-9s matchmaking)

**What didn't (consistent):**
- Drag coords (370/450,500 → 480,300) are uncertain — agent repeatedly noted "cards may not have landed" and "uncertain drag"
- No zone-change verification after drag — BF growth is only confirmed at next scry, not immediately
- Run-1: lobby was non-standard (Arena in Events tab), cost 6 extra calls
- Deck select at `82,455` may pick wrong deck — run-5 agent noted "Swamp on bf suggests wrong deck (Sparky's deck?)" but run-2's BF showed `owner=1` for all objects so this may be a false worry
- Turn counting is confusing because Arena uses 2x game-turn numbering (player turn N = game turn 2N)

---

## Patterns

**Consistent successes:**
- All 5 games completed with zero crashes, zero screenshots
- Lobby (runs 2-5): exactly 5 calls — clean, fast, repeatable
- Concede + defeat dismiss: 100% success rate
- Discard detection: correct when triggered

**Consistent failures:**
- No attacks attempted in any game — creatures always stay untapped
- Drag coords are probabilistic, not verified — sometimes wrong card, sometimes misses
- `arena play "Card Name"` never used despite being the recommended tool
- The "two-button stack" click at `887,491 + 890,510` is improvised and undocumented
- Token cost is high ($0.26-$0.40/game) primarily due to cache misses on first scry per turn — scry JSON is large (full game state)

**Single-run failure:**
- Run-1 lobby navigation failure (Arena not on standard screen at start)

---

## Refinements

Specific, actionable changes ranked by impact:

1. **Replace blind drag with `arena play "Card Name"`** — the agent ignores the recommended high-level tool and uses raw coords. The prompt should either (a) mandate `arena play` explicitly and forbid raw drags for card plays, or (b) include a step that uses `arena detect` to find hand cards first. Blind coords produce uncertain outcomes and the agent knows it ("cards may not have landed").

2. **Add combat step to the game loop** — after Main1, the agent should check if active_player=1 and phase reaches Phase_Combat, then use `arena click "Attack All"` or the combat button. Currently zero attacks happen across 5 games. The turn loop in the prompt needs an explicit combat sub-step: "if Phase_Combat and active=1: try to attack."

3. **Add zone-change verification after each drag** — after `arena drag`, call `bin/scry state` and compare BF counts. Current loop defers the next scry 3-6 blind clicks later, so failed drags are silent. Even a lightweight check (`gsId` change + `bf_count > prev`) would catch misses.

4. **Inject pre-flight scry into lobby to confirm game started** — the first scry (currently called after `sleep 5`) sometimes returns turn 1 (run-1 gsId=6) while other runs start at turn 2 (gsId=23). The agent needs to know it's in game AND it's its turn before issuing drags. Add: "after Keep, call scry and wait until active_player=1 and phase=Phase_Main1 before any plays."

5. **Disambiguate "5 turns" in the prompt** — run-4 conceded at turn 5 which was Sparky's combat, having only seen 2 full own turns (turns 2 and 4). Run-5 played through 4 of its own turns before hitting turn 9. The prompt says "5 turns" without specifying player turns vs game turns vs scry-observed turns. Define explicitly: "complete 5 of your own Main Phase 1 steps."

6. **Add startup screen detection before lobby flow** — run-1 spent 6 extra calls because Arena was on the Events tab. Add a pre-lobby step: `arena where` or `bin/scry state` to confirm we're in lobby (no active match), then `arena click "Play"`. If screen shows unexpected content, call `arena navigate Home` first.

7. **Document `887,491`/`890,510` coords or replace** — these "double-stack" pass coords appeared in runs 3 and 4 as improvised workarounds when 888,504 wasn't enough. Either document them in arena-nav as "Phase_Ending two-button stack" with their meaning, or provide an explicit discard/confirm flow in the prompt so the agent doesn't improvise.

8. **Reduce per-turn scry frequency for token cost** — each scry call adds ~120-150k input tokens (large game state JSON in context). Runs 2 and 5 made 5 scry calls. If the loop is `drag → 3x click → scry` every turn, that's the minimal pattern. Consider batching: scry once at start of each own turn, skip scry if active_player returned 2 last time (bot turns auto-pass).
