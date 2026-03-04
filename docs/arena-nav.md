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
- **Play a land/spell:** DO NOT click cards directly (Unity treats click as drag-start). Use the debug API action system instead.

### Concede
1. `arena click 1555,72` — cog/settings icon (top-right, no text)
2. `arena click "Concede"`
3. `arena wait text="Defeat" --timeout 10`

### Result Screen
- **Dismiss:** click center three times with pauses: `arena click 800,450` → sleep 2 → `arena click 800,450` → sleep 2 → `arena click 800,450`
- **Back in lobby:** `arena wait text="Play" --timeout 15`

## Non-Text Elements (Fixed Coords)

These are window-relative logical coordinates (1920x1080).

| Element | Coords | Notes |
|---------|--------|-------|
| Play button (deck select) | 1446,871 | Bottom-right, graphical |
| Cog/Settings icon | 1555,72 | Top-right corner |
| Result dismiss (center) | 800,450 | Click 3x with 2s gaps |
| Pass turn (if no OCR) | 1750,950 | Bottom-right area |

## Bot Match Loop (Full Example)

```bash
# From Lobby:
tools/arena click 1446,871                     # Play button (bottom-right)
sleep 2
tools/arena click "Find Match" --retry 3       # opens deck list + queue picker
sleep 2
tools/arena click "Bot Match" --retry 3        # select Bot Match queue
sleep 1
tools/arena click "My Decks" --retry 3         # expand if collapsed
sleep 1
# Click a deck thumbnail (coords depend on deck position)
tools/arena click 250,825                      # first deck thumbnail
sleep 1
tools/arena click 1446,871                     # Play button
tools/arena wait text="Keep" --timeout 15
tools/arena click "Keep"
tools/arena wait phase=MAIN1 --timeout 15
tools/arena click "Pass" --retry 3
tools/arena click 1555,72                      # cog icon
tools/arena click "Concede"
tools/arena wait text="Defeat" --timeout 10
tools/arena click 800,450 && sleep 2 && tools/arena click 800,450 && sleep 2 && tools/arena click 800,450
tools/arena wait text="Play" --timeout 15      # back in lobby
```

## Tips

- **Just click, don't inspect.** Follow the scripted flow — click text/coords directly without OCR or screenshots between steps. Only capture/OCR when debugging a failure or when you don't know the current screen state.
- **Use `--exact` only for isolated button text** — works for "Done", "Concede" (standalone UI buttons). Fails for "Keep" and other words that OCR may merge with surrounding text. When in doubt, use substring match (default) + `--retry`.
- **Prefer coords for bottom-right action buttons** — Play/Edit Deck/Done are always at ~1447,868. Text matching risks card text collisions.
- **Always use `--retry` for text clicks during transitions** — animations/loading can delay text rendering
- **Never click cards** — Unity interprets single click as drag. Use debug API for card actions.
- **Use `arena wait`** instead of `sleep N` — deterministic, faster, no wasted time
- **OCR is case-insensitive** for `--find` matching
- **Coord clicks don't need retry** — they always land, but the UI might not be ready (use `arena wait` before)
- **"Waiting for Server..."** — means FD connection dropped or server not responding. Restart server, then relaunch MTGA
- **"My Decks" is often collapsed** — must click to expand before deck thumbnails are visible
- **Invalid decks** show under "Invalid Decks:" label with warning triangles — deck has fewer than 60 cards (common when some card names don't resolve in Arena's DB)
