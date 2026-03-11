## Batch 2026-03-10_18-57-54 — 3 runs, all timeout

1. **Fix seed-db deck format** — `SeedDb.kt` seeds decks with `format='Standard'`; these are rejected by Arena because seeded cards (Grizzly Bears etc.) aren't in Standard. Change to `format='Timeless'`. Root cause of ~70% of lobby time wasted in 2/3 runs.

2. **Add session pre-flight to prompt** — check `bin/arena ocr --fmt` for Connection Lost / Unable to Login before navigating to Play. If found: dismiss modal or restart server. Run 1 spent entire 600s stuck on Connection Lost.

3. **Add opponent-turn wait to prompt** — when `scry state` shows `active_player != self` or `actions=[]`, instruct `sleep 3 && bin/scry state` (up to ~5 times) before taking any action. Clicking 888,504 on opponent's turn is a no-op; run 3 burned ~6 tool calls doing exactly this during Phase_Combat.

4. **Inject DB schema into prompt** — `decks(deck_id, name, format, cards, ...)`. All 3 runs queried `SELECT id FROM decks` and got an error (column is `deck_id`). Two wasted tool calls per run.

5. **Add deck format pre-check to prompt** — before clicking Play, run `sqlite3 data/player.db "SELECT name, format FROM decks"`. If any deck is `format='Standard'`, run `UPDATE decks SET format='Timeless'` before proceeding.

6. **Replace screenshot diagnosis with OCR** — runs 2 and 3 used 5–6 `arena capture` + `Read` calls to diagnose deck select UI. `bin/arena ocr --fmt` is faster, cheaper, token-lighter. Screenshots should be last resort.

7. **Fix token capture in harness** — `total_input_tokens` is 0 in all metrics.json files. Harness is not extracting usage from the transcript `system/result` event.

8. **Use `arena play "<name>"` for card plays** — agent used fixed coords (200,500) which failed on turn 1 (hand unchanged). `arena play "<name>"` does internal detection and verifies zone change. `scry state` provides card names; use them.
