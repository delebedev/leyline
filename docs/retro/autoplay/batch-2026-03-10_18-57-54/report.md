# Autoplay Batch Report — 2026-03-10_18-57-54

## Summary Table

| Game | Turns | Cards Played | Tool Calls | Screenshots | OCR Calls | Scry Calls | Blind Clicks (888) | Drags | Wall Time | Result |
|------|-------|-------------|------------|-------------|-----------|------------|---------------------|-------|-----------|--------|
| 1    | 8     | 0           | 40         | 1           | 18        | 2          | 0                   | 0     | 609s      | Timeout — stuck on Connection Lost modal, never got into game |
| 2    | 0     | 0           | 60         | 6           | 19        | 0          | 0                   | 1     | 606s      | Timeout — entire session spent debugging deck validity / lobby |
| 3    | 4     | 2           | 79         | 5           | 17        | 14         | 6                   | 4     | 607s      | Timeout — reached gameplay but stuck during opponent's Phase_Combat |

Token/cost data: not captured (zeros in all metrics.json — harness bug, `total_input_tokens` always 0).

---

## Phase Split

Token counts are unavailable (all zero). Tool call split estimated from transcript event counts:

| Game | Lobby Tool Calls (est.) | Gameplay Tool Calls (est.) | Lobby % |
|------|------------------------|---------------------------|---------|
| 1    | ~40                    | 0                         | 100%    |
| 2    | ~60                    | 0                         | 100%    |
| 3    | ~55                    | ~24                       | ~70%    |

All three games failed to complete lobby efficiently. Run 3 was the only one to reach actual gameplay.

---

## Stuck Events

### Run 1 — Connection Lost (entire session)

**Phase: Lobby**

Client started with "Connection Lost" modal from a prior session. The agent:
1. Clicked `480,325` (Reconnect equivalent) repeatedly — 3 times — with no state change after each.
2. Called `scry state` and found turn 8 in Phase_Main1 (game was alive server-side, client disconnected).
3. Read leyline.log — saw FD proxy dropping after 5s.
4. Attempted `636,325` ("Exit Game" coord) — but MTGA window was at `0,31,1920,1108` while Zed was at `8,31,1904,955` — the click landed on Zed, not MTGA. Agent spent 15+ tool calls diagnosing window layering (osascript `visible`, `position`, hiding other apps).
5. Eventually discovered OCR was reading coordinates from a non-foreground MTGA window. Never recovered.
6. Took 1 screenshot at the very end to see state.

Root cause: agent had no pre-flight check for Connection Lost modal before attempting Play. When it appeared, the coord `636,323` for "Exit Game" was hitting Zed (which overlapped at the same screen position). Agent had no procedure for recovering window focus reliably.

### Run 2 — Deck Invalid (entire session)

**Phase: Lobby**

Decks had `format='Standard'` in the DB but contained classic-only cards. Arena showed them as "Invalid Decks". Agent:
1. Clicked the invalid deck at `82,455` and `82,490` etc. — Play button stayed grayed out. 4+ attempts.
2. Took 4 screenshots to diagnose deck state.
3. Queried DB with wrong column name (`id` instead of `deck_id`) — wasted 2 tool calls.
4. Tried `arena capture /tmp/botmatch-screen.png` (wrong syntax) — wasted 1 call.
5. Read source code in CollectionService.kt and EventWireBuilder.kt (5+ Read calls) trying to understand why cards are invalid — this is a harness-level diagnosis that should never happen mid-game.
6. Diagnosed format mismatch, ran `UPDATE decks SET format='Timeless'` correctly.
7. After fix, re-navigated lobby, applied "Timeless Play" filter, selected deck — but ran out of time before clicking Play.

Root cause: seeded decks have `format='Standard'` which is invalid for cards in the DB. Pre-game validation is absent. The agent correctly diagnosed and fixed this but wasted the entire 600s session doing it.

### Run 3 — Deck Select Loop (turns 1–2 lobby) + Combat Phase Block (turns 3–4 gameplay)

**Phase 1 — Lobby (events 4–186, ~55 tool calls)**

Agent clicked `82,455` (deck selection) then `867,516` (Play) but got no game start. `bin/arena wait text="Keep" --timeout 30` timed out. Agent:
1. Took 2 screenshots (events 36, 45) trying to read deck selection screen.
2. Queried DB with wrong column (`id` instead of `deck_id`) — same mistake as run 2.
3. Clicked format filter at `849,366` multiple times (events 75, 96, 108) — trying different coords for the same filter.
4. Did 8+ grep/Read calls into frontdoor source to understand deck validation — should not happen.
5. Eventually found working flow: `849,366` (format filter) → `228,306` (Timeless option) → `867,516` (Play) → game launched.

**Phase 2 — Gameplay (events 186–241, ~24 tool calls)**

Game reached turn 1. Agent:
1. Correctly kept hand (`bin/arena click "Keep"` at event 189).
2. Turn 1: drag `200,500 → 480,300` resulted in `hand: 7` (no change). Drag didn't play a card — coords don't correspond to actual hand cards. Then passed with `888,504`.
3. Turn 2: drag `300,500 → 480,300` — `hand: 6`, 1 card played. Clicked `888,504` twice.
4. Turn 3 (active=1, Phase_Main1): `scry state` showed hand has Forest, Plains, Giant Growth. Drag `200,500 → 480,300` — `hand: 7` (no change, wrong coord again). Then tried `888,504` + drag `300,500 → 480,300` — `hand: 6`. Clicked `888,504` twice.
5. Turn 4 (active=2, Phase_Combat): `scry state` shows opponent's turn, combat phase. Agent clicked `888,504` twice — state stayed `turn: 4 phase: Phase_Combat active: 2 actions: []`. Then tried coord variants `887,491` and `890,510`. No progress. Repeated pattern 3 more times (events 228, 231, 237, 240). Ran out of time stuck here.

Root cause for drag failures: agent used fixed coords `200,500` and `300,500` for hand cards. These are not reliable — hand card positions vary. First drag consistently failed (hand count unchanged), second at `300,500` sometimes worked.

Root cause for Phase_Combat block: when `active=2` (opponent's turn) and `actions=[]`, there is nothing for the player to do — the client just needs to wait for the opponent to finish combat. The agent had no timeout/wait strategy for "opponent's turn, no actions available". Kept clicking `888,504` which does nothing when it's not your priority.

---

## Patterns

### What worked
- `bin/arena click "Keep" --retry 3` for mulligan — reliable when game launches.
- `bin/scry state` is functional and returns accurate data (turn, phase, hand count, card names, active player).
- Format filter at `849,366` followed by specific format option works for making decks valid.
- `bin/arena wait text="Keep" --timeout 30` works correctly as a game-start detector when game actually launches.
- `UPDATE decks SET format='Timeless'` fix is correct and was independently discovered twice (runs 2 and 3).

### What consistently failed

**1. No pre-flight checks at session start.**
All 3 runs started without checking for Connection Lost modal or previous match state. Runs 1 and 2 hit stale state from the prior session immediately.

**2. Deck seeded with wrong format.**
All 3 runs hit deck-is-invalid (Standard format, classic cards). Runs 2 and 3 spent majority of time diagnosing. This is a harness issue — `just seed-db` produces decks with `format='Standard'` which Arena rejects.

**3. Hand drag coords are fixed and unreliable.**
`200,500` and `300,500` are not reliable hand card positions. The first drag attempt failed in both turn 1 and turn 3. `300,500` happened to work on those turns but is not principled.

**4. No wait strategy for opponent's turn.**
When `active_player != self` and `actions=[]`, the agent has no procedure. It kept clicking `888,504` which has no effect. Should detect "not my turn, wait 3–5s, re-check scry".

**5. Screenshot overuse.**
Run 2: 6 screenshots. Run 3: 5 screenshots. These are expensive and slow. Mostly used to diagnose deck selection UI — should be replaced with `arena ocr` and `scry state` pattern.

**6. DB schema not known to agent.**
All 3 runs queried `SELECT id FROM decks` (wrong — column is `deck_id`). Schema is stable and should be in the prompt or system context.

**7. Source code archaeology mid-game.**
Runs 2 and 3 grepped frontdoor source, read CollectionService.kt, EventWireBuilder.kt, etc. to diagnose deck validity. This wastes 10+ tool calls and accomplishes nothing. Agent should treat deck validity as a known environmental issue with a fixed remediation (`UPDATE decks SET format='Timeless'`).

**8. Token data missing.**
`total_input_tokens` is 0 in all metrics. Harness is not capturing token usage.

---

## Refinements
Specific, actionable changes ranked by impact:

1. **Fix seed-db deck format.** Change `just seed-db` to seed decks with `format='Timeless'` (or whatever the valid format is for the seeded cards). This is the root cause of ~70% of wasted time across 2/3 runs. One-line SQL fix in `SeedDb.kt`.

2. **Add session pre-flight to prompt.** At the start of every run, the prompt must instruct: (a) check `bin/arena ocr --fmt` for Connection Lost / Unable to Login modals; (b) if found, dismiss with `arena click "Dismiss"` or restart server; (c) only then navigate to Play. This would have saved run 1 entirely.

3. **Add opponent-turn wait to prompt.** When `scry state` returns `active_player != self_seat` or `actions=[]`, instruct agent to `sleep 3 && bin/scry state` (up to N times) instead of clicking `888,504`. Clicking 888,504 during opponent's turn is a no-op and burns time.

4. **Inject DB schema into prompt.** Add a `db_schema` section to the prompt: `decks(deck_id, name, format, cards, ...)` — eliminates 2 wasted tool calls per run minimum.

5. **Add deck selection pre-check to prompt.** Before clicking Play, instruct: run `sqlite3 data/player.db "SELECT name, format FROM decks"` and if any deck has `format='Standard'`, run the fix (`UPDATE decks SET format='Timeless'`). This is a known recurring issue.

6. **Replace screenshot diagnosis with OCR.** When deck select UI is unclear, use `bin/arena ocr --fmt` not `bin/arena capture` + `Read`. Screenshots are 5x slower and consume image tokens. Only take a screenshot as a last resort after 2 OCR attempts fail.

7. **Fix token capture in harness.** `total_input_tokens` is 0 in all runs. Harness is not extracting usage from transcript. Fix `metrics.json` population so we can track cost and context growth.

8. **Add reliable hand card detection.** The drag-to-play pattern uses fixed coords (`200,500`, `300,500`). These are wrong for turn 1 (hand count didn't change). Should use `bin/arena detect` or `bin/arena play "Card Name"` which does internal detection. At minimum, `scry state` card names should be used to drive `arena play "<name>"` calls.
