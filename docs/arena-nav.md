# MTGA Navigation Guide (Agent Reference)

## Screen Flow

```
Lobby ──→ Play ──→ Bot Match ──→ Game ──→ Result ──→ Lobby
                                  │
                                  └── Cog ──→ Concede ──→ Result

First Login (after connect):
  Banned Cards popups ──→ dismiss "Okay" ×N ──→ Lobby

Waiting for Server:
  Client reconnecting or FD connection dropped ──→ restart server + relaunch MTGA
```

## Screens & Actions

### Banned Cards Popups (First Login)
- **Indicators:** "Banned Standard Cards" or "Banned Historic Cards" text, "Okay" button
- **Actions:** `arena click "Okay" --retry 3` — repeat until lobby appears
- **Note:** Multiple popups may appear in sequence (Standard, Historic, etc.)

### Lobby (Home)
- **Indicators:** "Play" button visible, top nav bar (Home, Profile, Decks, etc.)
- **Actions:** `arena click "Play"` — clicks left-nav Play, not the right-side Play button

### Play Menu
- **Indicators:** "Find Match", "Events", "Recently Played" tabs in right panel
- **Actions:**
  - `arena click "Find Match"` — opens deck list + queue picker
  - After Find Match: `arena click "Bot Match"` — select bot match queue
- **Note:** "Bot Match" only appears after clicking "Find Match" tab. It's in the queue list on the right side, NOT a top-level button.

### Deck List (Find Match view)
- **Layout:** Left side shows "My Decks" header (may be collapsed), right side shows queue options (Ranked, Play, Brawl, Standard Play, Bot Match)
- **My Decks collapsed:** Shows "▶ My Decks" — click `arena click "My Decks"` to expand
- **My Decks expanded:** Shows "▼ My Decks", "+" button, deck thumbnails. Invalid decks show under "Invalid Decks:" label with warning triangles
- **Select a deck:** Click the deck thumbnail card art at the bottom of the list. Thumbnails are ~150px wide, arranged horizontally
- **Edit Deck:** only appears after a deck is selected (highlighted). Bottom-right: `arena click 1444,868`
- **Done (in editor):** `arena click "Done" --exact` — `--exact` avoids card text like "almost done"

### Deck Selection → Start (Bot Match)
- **After selecting Bot Match + deck:** "Play" button at bottom-right
- **Play button coords:** `arena click 1446,871` (no OCR text — it's an icon/graphic)
- **Wait:** `arena wait text="Keep" --timeout 15` for mulligan screen

### Mulligan
- **Indicators:** "Keep", "Mulligan" visible
- **Actions:** `arena click "Keep"` or `arena click "Mulligan"`

### In-Game
- **Wait for priority:** `arena wait phase=MAIN1 --timeout 15`
- **Pass priority:** `arena click "Pass" --retry 3`
- **Play a land/spell:** `arena drag <x>,530 480,350` — drag from hand to battlefield. Lands play instantly; spells show "Pay" prompt (auto-pays or Cancel at 888,504).

### Concede
1. `arena click 1555,72` — cog/settings icon (top-right, no text)
2. `arena click "Concede"`
3. `arena wait text="Defeat" --timeout 10`

### Result Screen
- **Dismiss:** click center three times with pauses: `arena click 800,450` → sleep 2 → `arena click 800,450` → sleep 2 → `arena click 800,450`
- **Back in lobby:** `arena wait text="Play" --timeout 15`

## Non-Text Elements

Most "non-text" elements actually have nearby OCR-detectable text. **Prefer OCR-based discovery over hardcoded coords.** Coords break when the window isn't exactly 1920x1080.

| Element | OCR discovery | Fallback coords (1920x1080) |
|---------|--------------|----------------------------|
| Play button (deck select) | `ocr --find "Play"` — pick bottom-right hit | 1446,871 |
| Cog/Settings icon | `ocr --find "Sparky"` — cog is top-right of game screen | 1555,72 |
| Result dismiss (center) | Click screen center 3x with 2s gaps | 800,450 |
| Pass turn | `ocr --find "Pass"` | 1750,950 |
| Deck thumbnail | `ocr --find "<deck name>"` — click ~80px above the label | varies |

**Window resolution check:** At session start, run `arena ocr` and check the rightmost cx value. If max cx < 1000, the window is NOT 1920px — all hardcoded coords will miss. Use OCR coords only.

## Bot Match Loop (Full Example)

All steps use OCR — no hardcoded coords except deck thumbnail offset.

```bash
# From Lobby:
bin/arena click "Play" --retry 3
sleep 2
bin/arena click "Find Match" --retry 3       # opens deck list + queue picker
sleep 2
# Bot Match is in the right-side queue list — use ocr to find it
bin/arena ocr --find "Bot Match"             # get coords
bin/arena click <cx>,<cy from ocr>           # click it
sleep 1
bin/arena click "My Decks" --retry 3         # expand if collapsed
sleep 1
# Deck thumbnail: find deck name, click ~80px above label center
bin/arena ocr --find "<deck name>"           # get label coords
bin/arena click <cx>,<cy - 80>              # click card art above label
sleep 1
# Confirm deck selected:
bin/arena ocr --find "Edit Deck"             # appears when deck is highlighted
# Play button — use OCR, pick the bottom-right "Play" hit
bin/arena click "Play" --retry 3
# Poll for mulligan (never wait >10s):
for i in 1 2 3 4 5; do bin/arena ocr --find "Keep" && break; sleep 3; done
bin/arena click "Keep" --retry 3
sleep 5
bin/arena click "Pass" --retry 3
sleep 2
# Concede: find cog via OCR context (top-right area)
# Cog is ~940,55 in a 960px-wide window — use rightmost OCR x + offset
bin/arena ocr                                # find max-x text to estimate width
bin/arena click <width - 20>,55              # top-right cog area
bin/arena click "Concede" --retry 3
# Poll for defeat:
for i in 1 2 3; do bin/arena ocr --find "DEFEAT" && break; sleep 3; done
# Dismiss result (click center 3x):
bin/arena click 480,300 && sleep 2 && bin/arena click 480,300 && sleep 2 && bin/arena click 480,300
# Poll for lobby:
for i in 1 2 3 4 5; do bin/arena ocr --find "Home" && break; sleep 3; done
```

## Tips

- **OCR is ground truth.** Always use `arena ocr` / `arena ocr --find` for state checks. Never screenshot for routine state detection — screenshots cost ~800 vision tokens, OCR costs ~0.
- **OCR coords are window-relative logical** — no scaling needed regardless of capture resolution. If OCR says (480, 345), click (480, 345).
- **Never `arena wait` with timeout >10s** — poll with `for i in 1..N; do arena ocr --find "X" && break; sleep 3; done`. Keeps conversation responsive.
- **Prefer OCR over hardcoded coords** — coords break when window size changes. OCR adapts.
- **Use `--exact` only for isolated button text** — works for "Done", "Concede" (standalone UI buttons). Fails for "Keep" and other words that OCR may merge with surrounding text. When in doubt, use substring match (default) + `--retry`.
- **Always use `--retry` for text clicks during transitions** — animations/loading can delay text rendering.
- **Never click cards** — Unity interprets single click as drag. Use debug API for card actions.
- **OCR is case-insensitive** for `--find` matching.
- **"Waiting for Server..."** — means FD connection dropped or server not responding. Restart server, then relaunch MTGA.
- **"My Decks" is often collapsed** — must click to expand before deck thumbnails are visible.
- **Invalid decks** show under "Invalid Decks:" label with warning triangles — deck has fewer than 60 cards.
- **Check state, don't assume.** After relaunching MTGA, run `arena ocr` before assuming which screen you're on. The client may auto-login, show popups, or be stuck on an error.
