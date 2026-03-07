# Arena Playtest Session 1 — 2026-03-06

## What worked
- OCR-based polling for state detection — fast, cheap, reliable
- Nav guide coords for non-text elements (cog, deck thumbnails)
- Server log grepping to root-cause the `is_favorite` crash in one grep
- Full loop completed: lobby → queue → deck → game → concede → lobby

## What didn't
- **Coord scaling confusion**: wrongly assumed OCR coords needed 1280→1920 scaling. Wasted 3 login attempts. OCR coords are already window-relative — no scaling ever needed.
- **Window size mismatch**: nav guide assumes 1920x1080 but the window was ~960x560 (logical). Hardcoded coords from guide (1446,871 / 1555,72) all missed.
- **Screenshots for state checks**: kept capturing PNGs + reading with vision instead of running OCR. Each screenshot ~800+ tokens vs OCR ~0.
- **`arena wait --timeout 30`**: blocked conversation for 30s. Should never wait >10s; poll OCR every 3s instead.
- **Assuming login screen**: client remembered creds and auto-logged in after relaunch. Should have checked state first instead of assuming.
- **Clicking deck label text**: clicked the deck name instead of the card art above it. Unity deck labels aren't clickable — the thumbnail is.

## Bug found
- `data/player.db` missing `is_favorite` column (added on branch). Server crashed with `SQLITE_ERROR: no such column: decks.is_favorite` during StartHook → killed FD connection → "Connection Lost". Fixed by trashing stale DB. Root cause: no schema migration, tests use in-memory DBs so they pass.

## Upstreamable improvements

### Tooling
- `arena type "text"` command — activates MTGA + sends keystrokes. No more raw osascript.
- `arena click` should validate coords are within detected window bounds and warn if outside.
- OCR coord source should be documented: coords are always window-relative logical, regardless of capture resolution.

### Guides
- Nav guide hardcoded coords are wrong for non-1920x1080 windows. Either: (a) always use OCR coords, (b) detect window size and scale, or (c) verify window size at start and warn.
- Add rule: "never screenshot for state checks — always `arena ocr`"
- Add rule: "never `arena wait` with timeout >10s — poll OCR every 3s"
- Add rule: "verify window resolution at session start with `arena ocr` bounding box span"

### Automation over docs
- The coord table in arena-nav.md is fragile. Better: `arena ocr --find "Concede"` works regardless of resolution. Reserve coords only for truly non-text elements, and even those should be resolution-relative.
- DB migration in SqlitePlayerStore (ALTER TABLE ADD COLUMN IF NOT EXISTS) prevents silent runtime crashes that tests don't catch.

## Token costs
- Screenshots: ~5 captures × ~800 tokens = ~4000 tokens wasted (should have been 0)
- Server log noise: reading full Netty/logback boilerplate before grepping — ~2000 tokens wasted
- Long waits: `arena wait --timeout 30` blocked 30s of wall time with no useful output

## Timing
- Session 1 initial: ~15 min total including debugging the DB crash
- Session 1 second run (manual ocr+click loops): 2m24s
- Session 1 third run (arena click + arena wait): 42s mulligan→lobby (nav was interrupted by resolution issue)
- Target for next session: <2 min full lobby→lobby loop

## Resolution findings (added mid-session)
- **1920x1080 is the only safe resolution.** 1280x720 completely changes the UI layout — event tiles replace the Find Match tab/queue list, making the nav flow break.
- Added `arena launch` command: `arena launch --kill --width 1920 --height 1080` opens MTGA windowed at a specific resolution.
- Default is 1280x720 in `arena launch` but should be changed to 1920x1080 since that's the only layout that works.

## Key optimization: use `arena click "text"` not `ocr --find` + `click`
- `arena click "text" --retry 3` already does capture → OCR → click in one call
- Only need separate `ocr --find` when: (a) you need to offset coords (deck thumbnails), (b) you need to disambiguate multiple matches, (c) you just want to check state without clicking
- `arena wait text="X" --timeout 10` is the right transition tool — has built-in change detection, 500ms polling, skips unchanged frames. No hand-rolled sleep+ocr loops.
