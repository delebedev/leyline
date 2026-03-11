# Agent Retro — Run 3

## Result
- Turns played: 7+ (game was already in progress at turn 5 when agent took over)
- Cards played: (game was mid-run; had Cloudkin Seer on battlefield/graveyard, 2 Plains on battlefield)
- Approximate tool calls: ~12
- Got stuck: briefly — cog icon first click at (940,55) missed, worked at (940,42)

## What Worked
- `bin/scry state` reliable for turn/phase/priority detection
- `bin/arena ocr` quick for reading button labels and screen state without screenshots
- Discard prompt handling: click card at hand coords → click Submit (887,488)
- Cog menu at (940,42) reliably opens Options with Concede
- DEFEAT screen dismisses cleanly with 3 clicks at (480,300)

## What Didn't Work
- `bin/arena click "Concede" --retry 3` failed when cog menu wasn't yet open — need to open menu first, then click Concede by coord (479,331) not by text
- First cog click at (940,55) didn't register — (940,42) is the correct y-coord
- Action types from scry showed "?" — scry parser doesn't decode all action types yet

## What I Wish I Had
- A way to detect if I'm already in a running game at session start (vs needing to navigate to one)
- scry state decoding all action types (not "?") so I know what cards are playable
- Cleaner "discard complete" signal — the warning message persists even after discard, making it hard to confirm success
