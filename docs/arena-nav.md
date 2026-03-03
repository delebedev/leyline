# MTGA Navigation Guide (Agent Reference)

## Screen Flow

```
Lobby ──→ Play ──→ Bot Match ──→ Game ──→ Result ──→ Lobby
                                  │
                                  └── Cog ──→ Concede ──→ Result
```

## Screens & Actions

### Lobby (Home)
- **Indicators:** "Play" button visible
- **Actions:** `arena click "Play"`

### Play Menu
- **Indicators:** "Find Match", "Bot Match" visible
- **Actions:**
  - `arena click "Bot Match"` — start bot match
  - `arena click "Find Match"` — ranked/unranked (needs deck selected)

### Deck List
- **Select a deck:** Click the card art image, NOT the deck name label. OCR gives the label center — click ~80px above that Y coordinate to hit the art. Example: label at cy=530 → click at `arena click 401,450`
- **Edit Deck:** only appears after a deck is selected (highlighted). Bottom-right: `arena click 1444,868`
- **Done (in editor):** `arena click "Done" --exact` — `--exact` avoids card text like "almost done"

### Deck Selection → Start (Bot Match)
- **After Bot Match:** deck list appears, "Play" button at bottom-right
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
tools/arena click "Play"
tools/arena click "Bot Match"
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

- **Use `--exact` only for isolated button text** — works for "Done", "Concede" (standalone UI buttons). Fails for "Keep" and other words that OCR may merge with surrounding text. When in doubt, use substring match (default) + `--retry`.
- **Prefer coords for bottom-right action buttons** — Play/Edit Deck/Done are always at ~1447,868. Text matching risks card text collisions.
- **Always use `--retry` for text clicks during transitions** — animations/loading can delay text rendering
- **Never click cards** — Unity interprets single click as drag. Use debug API for card actions.
- **Use `arena wait`** instead of `sleep N` — deterministic, faster, no wasted time
- **OCR is case-insensitive** for `--find` matching
- **Coord clicks don't need retry** — they always land, but the UI might not be ready (use `arena wait` before)
