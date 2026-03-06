# Plan: UI Automation Next Steps

## Status: Draft

## Context

Synthetic mouse clicks work on macOS 15+ with Unity via `bin/click` (CGEvent +
`mach_absolute_time()` + foreground window activation). Current coordinate mapping
is manual: screenshot at 1280px → multiply by 1.5 → screen coords at 1920px.

## Opportunities

### 1. Deck Selection Automation

The deck list comes from StartHook's `DeckSummariesV2` (115 decks). Each has a
`DeckId` GUID. When the user clicks a deck tile, the client sends CmdType 612
with `{"deckId":"<guid>"}`. The golden StartHook has real decks.

The deck grid is 5 columns × N rows (scrollable). First tile position is roughly
(310, 440) in 1280px screenshot coords. Tiles are ~190px apart horizontally.

To pick deck by index: `col = index % 5`, `row = index / 5`, compute (x, y) from
grid layout. May need to scroll for rows beyond the visible 2.

### 2. Automated Full Bot Match Cycle

The main dev iteration loop:

```
lobby → Play button → Find Match tab → Play queue tab → Bot Match →
pick deck tile → Play button → [game runs to completion] →
post-game dismiss → back to lobby → repeat
```

Each step is a `just click` at a known position with a `just capture-screenshot`
to verify state. Approximate coordinates (1920px screen):

| Step | Target | ~X | ~Y | Verify |
|------|--------|----|----|--------|
| 1 | Play button (home) | 1780 | 1045 | Play blade opens |
| 2 | Find Match tab | 1695 | 145 | Deck grid visible |
| 3 | Play queue tab | 1695 | 230 | Bot Match in list |
| 4 | Bot Match radio | 1620 | 395 | Selected |
| 5 | Deck tile (1st) | 465 | 660 | Deck highlighted |
| 6 | Play button (blade) | 1700 | 1045 | Loading screen |
| 7 | Wait for match end | — | — | Post-game screen |
| 8 | Dismiss post-game | 960 | 540 | Back to lobby |

Coordinates are approximate — need validation pass with screenshots.

### 3. Multi-Queue/Format Testing

Switch between Ranked/Play/Brawl by clicking the queue tabs:

| Queue | Tab position (~1920px) |
|-------|----------------------|
| Ranked | ~1560, 230 |
| Play (Unranked) | ~1695, 230 |
| Brawl | ~1830, 230 |

Each queue shows different event entries. Bot Match is only under Play tab.
Ranked/Brawl would need actual matchmaking (not implemented in stub — would
need different CmdTypes and match setup).

## Coordinate Mapping Improvements Needed

Current approach (hardcoded pixel positions) is fragile. Better options:

1. **Window-relative coordinates**: Query window bounds at click time via
   CoreGraphics, compute click position as offset from window origin. Handles
   window moves.

2. **Named UI targets**: Map logical names ("play-button", "find-match-tab",
   "deck-tile-0") to coordinate functions that compute from window bounds.
   Could be a JSON config or a Kotlin/Swift helper.

3. **Template matching on screenshots**: Use `sips` or ImageMagick to find
   known UI elements in screenshots. Heavy but robust against layout changes.
   Probably overkill for dev tooling.

Option 1 is low effort and covers the most common failure mode (window not at
origin). Option 2 is the right medium-term solution.

## Implementation Priority

1. **Window-relative click** — update `just click` / `bin/click` to accept
   window-relative coords (query MTGA window bounds, add offset)
2. **`just bot-match [deck-index]`** — recipe that runs the full cycle
3. **`just repeat-bot-match N`** — run N matches back-to-back for soak testing
4. **Queue switching** — lower priority, Bot Match is the main use case
