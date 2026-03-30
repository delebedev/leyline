# MTGA Deck Navigation — arena-ts Reference

All coordinates in **960px reference space** (the arena-ts standard).
OCR returns 2x retina coords — divide by 2 for 960px space.

---

## Screen Flow

```
Home ──[nav-decks]──> DeckListViewer ──[Edit Deck]──> DeckBuilder
  │                        │                              │
  │                        │ [select + dbl-click]─────────┘
  │                        │
  │                        ├──[Deck Details icon]──> Deck Details (stats/avatar/sleeves)
  │                        │                              │
  │                        │ <──[< Deck Details]──────────┘
  │                        │
  │                        ├──[Export icon]──> Export Dialog ──[OK]──> DeckListViewer
  │                        │
  │                        ├──[Collection btn]──> Collection browser
  │                        │
  │                        └──[+ card]──> DeckBuilder (new empty deck)
  │
  │──[Play btn]──> Play Blade (scene stays Home)
  │                   │
  │                   ├──[Recently Played tab]──> mode cards w/ Play buttons
  │                   │
  │                   ├──[Find Match tab]──> ⚠ AUTO-QUEUES with last settings!
  │                   │
  │                   └──[Bot Match card body]──> Bot Match Deck Selector
  │                                                    │
  │                                                    ├──[select deck + Play]──> Match
  │                                                    ├──[X close]──> Home
  │                                                    └──[Edit Deck]──> DeckBuilder
  │
  └──[DeckBuilder Done on empty deck]──> Home (not DeckListViewer!)
```

---

## Navigation Bar (persistent, top)

| Element        | cx  | cy  | Notes                          |
|----------------|-----|-----|--------------------------------|
| Home           | 53  | 57  | A logo / planeswalker icon     |
| Profile        | 108 | 57  |                                |
| **Decks**      | 158 | 57  | `nav-decks` landmark           |
| Play           | 210 | 57  | (approx, sometimes hidden)     |
| Mastery        | 308 | 58  |                                |
| Achievements   | 366 | 57  |                                |
| Settings gear  | 930 | 60  | Opens global Options overlay   |

---

## DeckListViewer

### Top Bar (filters/search)

| Element        | cx  | cy  | Notes                               |
|----------------|-----|-----|-------------------------------------|
| All Decks      | 66  | 92  | Format filter dropdown              |
| Last Modified  | 194 | 92  | Sort dropdown                       |
| Search...      | 300 | 92  | Text search box                     |
| Color filters  | 370-440 | 92 | 5 mana color circle icons        |
| Deck count     | 893 | 92  | e.g. "36/100"                       |

### Deck Grid

- **"My Decks"** header: cx=135, cy=138
- **"+" New Deck** card: cx=96, cy=260 (first position, large plus icon)
- **Grid layout**: ~6 decks per row, 2 visible rows
- **Row 1 y** (card art centers): ~260-280, labels at ~330
- **Row 2 y** (card art centers): ~400-420, labels at ~465
- **Deck spacing** (cx): ~146px between deck centers
- **Row 1 deck cx values**: 240, 386, 532, 680, 825
- **Row 2 deck cx values**: 92, 240, 385, 532, 678, 824
- **Scrolling**: vertical scroll for more decks (if >12)

### Selection Behavior

- **Single click** on deck card art = select (highlighted glow)
- **Double-click** on card art = open in DeckBuilder (tooltip confirms)
- Click **card art area**, not the text label
- Selected deck shows "Double-click to Edit" tooltip

### Bottom Bar

| Element          | cx  | cy  | Notes                                    |
|------------------|-----|-----|------------------------------------------|
| Collection       | 117 | 532 | Text button, opens collection browser    |
| Deck Details     | 202 | 530 | List/text icon — opens stats/avatar view |
| Favorite         | 262 | 530 | Heart icon                               |
| **Export**        | 322 | 530 | Box w/ up arrow — copies to clipboard    |
| Import           | 385 | 530 | Box w/ down arrow                        |
| Clone/Duplicate  | 445 | 530 | Overlapping pages icon                   |
| Delete           | 520 | 530 | Trash can icon (⚠ untested confirmation) |
| Edit Deck        | 865 | 532 | Text button, opens selected deck         |

**Icon hit targets are small (~30px diameter).** Misclicks near edges may do nothing or trigger adjacent elements.

---

## DeckBuilder

### Layout

- **Left side**: Card collection grid (scrollable)
- **Center**: Card preview (enlarged card with text)
- **Right sidebar**: Deck card list

### Top Bar

| Element        | cx  | cy  | Notes                               |
|----------------|-----|-----|-------------------------------------|
| Search...      | 53  | 99  | Filter collection cards             |
| Color filters  | 100-200 | 88 | Mana color filter icons          |
| Craft          | 682 | 98  | Craft wildcards button              |
| Sideboard      | 884 | 74  | Toggle main/sideboard view          |

### Right Sidebar (Deck List)

| Element        | cx  | cy  | Notes                               |
|----------------|-----|-----|-------------------------------------|
| Deck name      | 830 | 92  | e.g. "FDN Spells"                   |
| Card count     | 829 | 104 | e.g. "60/60 Cards"                  |
| Mana curve     | 930 | 98  | Small bar chart visualization       |
| Card list      | 780-950 | 130-490 | Scrollable, qty + name + CMC |
| Apply Styles   | 875 | 498 | Card style selector                 |
| **Done**       | 867 | 533 | Save and exit to DeckListViewer     |

### Adding Cards

- Click a card in the **collection grid** (left side) to add it to the deck
- Cards show in the collection as thumbnails with card art
- Collection is filterable by search, color, and mana cost
- The deck sidebar updates in real-time

### Select Format (New Deck)

When creating a new deck, "Select Format" dropdown appears at:
- cx=852, cy=98

---

## Export

- **Trigger**: Select deck in DeckListViewer, click Export icon (cx=322, cy=530)
- **Dialog**: "Export Deck — [DeckName] has been copied to your clipboard."
- **OK button**: cx=480, cy=340
- **Format**: Standard MTGA decklist format:
  ```
  Deck
  4 An Offer You Can't Refuse (FDN) 160
  2 Unsummon (FDN) 599
  24 Island (KTK) 252
  ```
- Copies to system clipboard (readable via `pbpaste`)

---

## Bot Match Deck Selector

### How to Reach

1. From Home, click **Play** (cx=866, cy=533)
2. In "Recently Played" view, click **Bot Match card body** (cx=866, cy=350) — NOT the Play button!
3. Or: click Bot Match card from Find Match panel

### Layout

| Element             | cx  | cy  | Notes                                  |
|---------------------|-----|-----|----------------------------------------|
| "Bot Match" header  | 94  | 96  | Title                                  |
| "Best of 1"         | 50  | 116 |                                        |
| Close (X)           | 746 | 94  | Returns to Home                        |
| Format tabs: Ranked | 805 | 192 |                                        |
| Format tabs: Play   | 866 | 192 |                                        |
| Format tabs: Brawl  | 928 | 192 |                                        |
| Standard Play       | 850 | 260 | Format selector                        |
| Alchemy Play        | 850 | 291 |                                        |
| Historic Play       | 846 | 320 |                                        |
| Pioneer Play        | 846 | 350 |                                        |
| Timeless Play       | 849 | 380 |                                        |
| **Bot Match**       | 842 | 410 | Currently selected format              |
| "My Decks" header   | 110 | 208 |                                        |
| "+" New Deck        | 87  | 350 |                                        |
| Edit Deck           | 118 | 532 | Opens selected deck in DeckBuilder     |
| **Play**            | 866 | 534 | Starts match with selected deck        |

### Deck Grid

- Row 1 (y~300-350): deck cx values: 230, 376, 523, 668
- Row 2 (y~480-530): deck cx values: 230, 375, 522, 669
- Single-click to select (highlighted glow)
- Double-click to open in DeckBuilder
- Only decks matching format filter shown (e.g. 60+ cards for Bot Match)

### Deck Selection Recipe

```bash
# 1. Open play blade
just arena-ts click home-cta

# 2. Wait for play blade
sleep 2

# 3. Click Bot Match card BODY (not Play button)
just arena-ts click 866,350

# 4. Wait for deck selector
sleep 2

# 5. Click desired deck card art (e.g. first deck)
just arena-ts click 230,300

# 6. Wait for selection highlight
sleep 1

# 7. Click Play
just arena-ts click 866,534
```

---

## Deck Details View

Accessible from DeckListViewer bottom bar (leftmost icon, cx=202, cy=530).

Shows: deck name, format, creature/noncreature counts, mana curve, type breakdown, avatar selector, companion selector, card sleeves.

| Element         | cx  | cy  | Notes                     |
|-----------------|-----|-----|---------------------------|
| "< Deck Details"| 135 | 98  | Back to DeckListViewer    |
| Deck name       | 140 | 140 |                           |
| Format dropdown | 140 | 180 | e.g. "Direct Game"        |
| Avatars         | 385 | 315 |                           |
| Companions      | 584 | 316 |                           |
| Card Sleeves    | 385 | 528 |                           |

---

## Options / Concede Menu (in-game)

Opened by pressing **Escape** key during a match.

| Element        | cx  | cy  | Notes              |
|----------------|-----|-----|---------------------|
| Options header | 480 | 154 |                     |
| Gameplay       | 479 | 242 |                     |
| Graphics       | 480 | 274 |                     |
| Audio          | 480 | 304 |                     |
| **Concede**    | 480 | 344 | Red text, ends game |
| Account        | 255 | 456 |                     |
| Privacy Policy | 361 | 454 |                     |
| Report a Bug   | 470 | 454 |                     |

After conceding: "DEFEAT" screen -> "Click to Continue" -> "Waiting for Server..." (~5-8s) -> Home.

---

## Common Operation Recipes

### Open Deck Editor

```bash
just arena-ts click nav-decks          # go to deck list
sleep 3
just arena-ts click 240,280            # select first deck (card art)
sleep 0.5
just arena-ts click 865,532            # Edit Deck
sleep 3                                 # wait for DeckBuilder scene
```

### Create New Deck

```bash
just arena-ts click nav-decks
sleep 3
just arena-ts click 96,260             # "+" new deck card
sleep 3                                 # opens DeckBuilder directly
# Select format: click 852,98 ("Select Format" dropdown)
```

### Export Deck to Clipboard

```bash
just arena-ts click nav-decks
sleep 3
just arena-ts click 240,280            # select deck
sleep 0.5
just arena-ts click 322,530            # export icon
sleep 1
pbpaste > /tmp/deck.txt               # read from clipboard
just arena-ts click 480,340            # dismiss OK dialog
```

### Start Bot Match with Specific Deck

```bash
just arena-ts click home-cta           # Play button
sleep 2
just arena-ts click 866,350            # Bot Match card body
sleep 2
just arena-ts click 230,300            # select deck (1st in grid)
sleep 1
just arena-ts click 866,534            # Play
```

### Concede Current Match

```bash
osascript -e 'tell application "System Events" to tell process "MTGA" to key code 53'
sleep 2
just arena-ts click 480,344            # Concede
sleep 3
just arena-ts click center             # Click past DEFEAT screen
sleep 8                                 # Wait for server transition
```

---

## Gotchas and Timing Issues

1. **Find Match auto-queues.** Clicking "Find Match" tab (cx=867, cy=113) immediately starts a match with the last used settings. There is NO intermediate screen — it jumps straight to mulligan.

2. **Scene detection lag.** After clicking Done on an empty new deck, scene goes to Home (not DeckListViewer). The `just arena-ts scene` command reads from Player.log which may lag 1-2 seconds behind the actual UI.

3. **DeckListViewer exits easily.** Clicking outside the deck grid area (e.g. far right or bottom background) can navigate away to Home. Keep clicks within the grid bounds.

4. **Bottom bar icons are small.** Hit targets are ~30px circles. Imprecise clicks do nothing or show "Double-click to Edit" tooltip without triggering the icon action.

5. **Bot matches start from Recently Played.** The "Recently Played" section on the play blade shows quick-launch "Play" buttons. Clicking one starts a match immediately with that mode + deck.

6. **Global Options overlap.** The gear icon (cx=930, cy=60) opens the global Options overlay, not a deck-specific menu. In a match, Escape opens the same overlay but with "Concede" instead of "Exit Game".

7. **Selection state doesn't persist.** If you select a deck in DeckListViewer but wait too long before clicking an action, the selection may be lost or the scene may transition.

8. **Waiting for Server screen.** After conceding or finishing a match, there's a 5-8 second "Waiting for the Server..." loading screen before returning to Home.

---

## Recommended New Landmarks

```typescript
// DeckListViewer
"deck-new":         [96, 260],      // "+" new deck card
"deck-edit":        [865, 532],     // Edit Deck button
"deck-export":      [322, 530],     // Export icon
"deck-import":      [385, 530],     // Import icon
"deck-clone":       [445, 530],     // Clone icon
"deck-delete":      [520, 530],     // Delete/trash icon
"deck-details":     [202, 530],     // Deck details/stats icon
"deck-favorite":    [262, 530],     // Heart/favorite icon
"deck-collection":  [117, 532],     // Collection button

// DeckBuilder
"builder-done":     [867, 533],     // Done/save button
"builder-search":   [53, 99],       // Search collection
"builder-craft":    [682, 98],      // Craft button
"builder-sideboard":[884, 74],      // Sideboard toggle
"builder-format":   [852, 98],      // Select Format dropdown (new deck)
"builder-styles":   [875, 498],     // Apply Styles button

// Play Blade
"play-close":       [746, 94],      // X close play blade (from deck selector)
"play-events":      [805, 113],     // Events tab
"play-findmatch":   [867, 113],     // ⚠ Find Match (auto-queues!)
"play-recent":      [928, 113],     // Recently Played tab

// Bot Match Deck Selector
"bot-match-card":   [866, 350],     // Bot Match card body (opens selector)
"bot-play":         [866, 534],     // Play button in bot match selector
"bot-edit":         [118, 532],     // Edit Deck in bot match selector
"bot-close":        [746, 94],      // Close deck selector

// In-Game
"game-concede":     [480, 344],     // Concede in Options overlay
"game-mulligan":    [210, 482],     // Mulligan button
"game-keep":        [395, 482],     // Keep hand button

// Export Dialog
"export-ok":        [480, 340],     // OK to dismiss export dialog
```

---

## Recommended Commands to Add to arena-ts

1. **`arena-ts export-deck`** — Navigate to deck list, select first/named deck, click export, read clipboard, return decklist text.

2. **`arena-ts select-deck <name>`** — In DeckListViewer or Bot Match selector, find deck by name (OCR), click its card art.

3. **`arena-ts concede`** — Full concede sequence (Escape -> Concede -> click past DEFEAT -> wait for server).

4. **`arena-ts bot-match [deck-name]`** — Complete bot match launch: Play blade -> Bot Match card -> select deck -> Play.

5. **`arena-ts deck-list`** — Navigate to DeckListViewer, OCR all visible deck names, return as list.

6. **`arena-ts deck-details <name>`** — Open deck details view, return stats.
