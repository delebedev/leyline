# Navigation Retro — 2026-03-08

Notes from debugging arena automation coords and the state machine.

## Findings

### CGEvent move doesn't move the cursor

`CGEvent(.mouseMoved)` posts an event but leaves the cursor visually in place. Clicks work because the event carries its own coordinates — the cursor position is irrelevant. To visually reposition the cursor, use `CGWarpMouseCursorPosition(point)` then post the mouseMoved event so apps track the new position. Fixed in `click.swift`.

### Compiled binary staleness + TCC invalidation

`bin/click` is a compiled Mach-O binary. Editing `bin/click.swift` does nothing until recompiled:
```bash
swiftc -O -o bin/click bin/click.swift -framework CoreGraphics -framework Foundation
```
This bit us — the move action existed in source but the binary was from a day earlier.

**Recompiling invalidates macOS TCC (accessibility) permissions.** The binary gets a new code signature hash, so macOS revokes its accessibility grant. Symptoms: `CGWarpMouseCursorPosition` still works (no permission needed), but `CGEventPost` for clicks silently fails. Fix: System Settings → Privacy & Security → Accessibility → toggle `click` off and on.

**Permanent fix (not yet done):** create a self-signed `leyline-dev` certificate in login keychain, then sign with it after every recompile:
```bash
# One-time: create cert via Keychain Access → Certificate Assistant → Create a Certificate
#   Name: leyline-dev, Type: Code Signing, self-signed
# After every recompile:
swiftc -O -o bin/click bin/click.swift -framework CoreGraphics -framework Foundation
codesign -f -s "leyline-dev" bin/click
```
TCC keys on signing identity — same cert = permission persists across recompiles.

### Cog icon is at (940, 42), not (940, 55)

The `.claude/rules/arena-automation.md` documents the cog at `940,55`. Actual position at 960×568 window: **(940, 42)**. The 13px difference matters — at 55 the click lands below the icon and gets swallowed by the game canvas.

### Coord verification workflow

`arena move <x>,<y>` is the calibration tool. Move cursor to where you think something is, user confirms visually. Much faster than screenshot → guess → click → wonder why nothing happened.

For self-verification without user:
```python
from Quartz import CGEventCreate, CGEventGetLocation
loc = CGEventGetLocation(CGEventCreate(None))
# loc.x, loc.y = screen-absolute
# subtract window origin for window-relative
```

### Screenshot coord mapping

`arena capture --resolution 1280` produces a 1280px-wide image of a 960-wide window. Scale factor: 1280/960 = 1.333×. To convert image pixel coords to window coords: **divide by 1.333** (multiply by 0.75). Easy to forget and estimate wrong.

OCR coords are always in window-relative space at the logical window size — they don't need scaling.

### State machine: classic flow matters

Our custom PlayBlade config puts "Bot Match" on the Home screen sidebar. Stock MTGA doesn't. The state machine must reflect the real multi-step flow:

```
Home → Play → Find Match → Bot Match → Deck Select → Play → Mulligan → InGame
```

Not the shortcut our config enables. The state machine is for navigating *any* MTGA session, not just our dev build.

### InGame detection: don't check activePlayer

`activePlayer` can be empty string during combat phases even with an active match. The correct check: `matchId != null && !gameOver`. The empty-activePlayer guard was incorrectly rejecting real in-game state.

## Changes made

| File | Change |
|---|---|
| `bin/click.swift` | `CGWarpMouseCursorPosition` for move action |
| `bin/arena.py` | Added `cmd_move` — `arena move <x>,<y>` |
| `bin/arena.py` | Removed `activePlayer:""` guard in InGame detection |
| `bin/arena_screens.py` | Classic flow: Home → Play → FindMatch → DeckSelected → Mulligan |
| `bin/arena_screens.py` | Screen detection: Home=`"Play"`, Play=`"Find Match"`, FindMatch=`"Bot Match"` |
| `bin/arena_screens.py` | Removed duplicate Play→Home transition, added back-nav edges |
