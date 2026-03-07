# MTGA Navigation Guide (Agent Reference)

## Screen Flow

```
Lobby ──→ Play ──→ Find Match ──→ Bot Match ──→ Game ──→ Result ──→ Lobby
                │                                │
                │                                └── Cog ──→ Concede ──→ Result
                │
                └──→ Events ──→ Event Blade ──→ Join ──→ Deck Builder ──→ Event Blade (Play)
                                                                             │
                                                                             └──→ Game ──→ ...

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

### Events Tab
- **Navigate:** From Play menu, `arena click "Events" --retry 3`
- **Indicators:** Category filters on right sidebar: "All", "In Progress", "New", "Limited", "Constructed"
- **Filter:** `arena click "Limited" --retry 3` — narrows to sealed/draft events
- **Event tiles:** Show event artwork + title. Custom events may lack titles if the loc key isn't in the client's string table. Use baked loc keys (e.g. `Events/Event_Title_Sealed_FDN`) in EventRegistry for titles to render.
- **Finding an event:** OCR for the event name (e.g. `arena ocr --find "Sealed"`) or filter by category then click tile by position.

### Event Blade
- **Indicators:** Event description text, "Start" or "Build Your Deck" or "Resume" button
- **States:**
  - **Fresh (no course):** "Start" button → joins the event (Event_Join)
  - **DeckSelect (pool received, no deck submitted):** "Build Your Deck" → opens deck builder
  - **CreateMatch (deck submitted):** "Start" → queues for match (Event_EnterPairing)
  - **In progress course from previous session:** "Resume" → returns to current state
- **Resign:** `arena click "Resign"` → confirm dialog → click right of "Cancel" (~558,338)
- **Purchase dialog:** Events with entry fees may show a purchase confirmation on first "Start" click. Dismiss or confirm as needed.

### Sealed Deck Builder
- **Indicators:** "N/40 Cards" counter (top-right), "Sealed Deck" label, "Done" button
- **Ready signal:** `arena ocr --find "40/40 Cards"` — deck is complete
- **Card grid:** Cards displayed in rows, ~10 per row. Click a card to add it to the deck.
  - Row 1 y ≈ 250, Row 2 y ≈ 460, x spacing ≈ 72px starting at x ≈ 60
  - Cards with multiple copies get added one per click
- **Submit:** `arena click "Done" --retry 3` — sends Event_SetDeckV2, transitions to CreateMatch
- **After Done:** Client returns to event blade showing "Start" (ready to queue)
- **Scroll:** If pool has more cards than fit on screen, scroll down in the card area

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
- **Wait (normal match):** `arena wait text="Keep" --timeout 15` for mulligan screen
- **Wait (puzzle):** Puzzles skip mulligan — wait for the game board directly (e.g. `arena ocr --find "Pass"` or check debug API state)

### Mulligan
- **Indicators:** "Keep", "Mulligan" visible
- **Actions:** `arena click "Keep"` or `arena click "Mulligan"`

### Puzzle Mode
- **Start:** `just serve-puzzle <path>.pzl` — loads a .pzl file as the match
- **No mulligan:** Puzzles skip the Keep/Mulligan screen — goes straight to the game board
- **Same nav:** Still need Play → Find Match → Bot Match → deck → Play to trigger the match

### In-Game

**Engine vs real Arena differences:** Our Forge engine gives priority at every phase stop, producing many more "action button" clicks per turn than real Arena. The button at ~888,505 cycles through labels like "Pass", "Resolve", "My Turn", "Opponent's Turn", "Next" — all at the same coord. Real Arena auto-passes most of these. Expect 5-10 button clicks per turn cycle vs 1-2 in real Arena.

**Engine-specific button labels:**
- **"Opponent's Turn"** — signals transition to opponent's turn; click to continue
- **"My Turn"** — signals transition to our turn; click to continue
- **"Resolve"** — a spell/ability on the stack needs resolving
- **"Pass"** — pass priority (standard)
- **"Next"** — advance through upkeep/draw phases

**All of these appear at ~888,505.** Just click the button repeatedly to advance through phases you don't care about.

**Turn cycle pattern (our turn):** Pass upkeep → Pass draw → **MAIN1 (play lands/spells here)** → Pass to combat → declare attackers → Pass → **MAIN2 (play more)** → End turn → Opponent's turn cycle

- **Wait for priority:** `arena wait phase=MAIN1 --timeout 15`
- **Pass priority:** click 888,505 — universal action button
- **Play a card by name:** `arena play "Grizzly Bears"` — finds card in hand via debug API + detection, drags to battlefield, verifies via zone change. Retries up to 3× with jitter.
- **Play a card (manual drag):** `arena drag <x>,<y> 480,300` — drag from hand to battlefield center
- **Resolve a spell:** After a successful drag, the spell goes on the stack. Click 888,505 (shows "Resolve") to resolve it. Lands resolve instantly — no extra click needed in puzzle mode, but the engine may still prompt for priority.
- **Low-level drag with verification:** `arena drag <x1>,<y1> <x2>,<y2> --verify <instanceId>` — polls debug API to confirm card changed zones; retries with ±5px jitter on failure
- **Getting stuck on a phase:** If clicking 888,505 doesn't advance the turn, check `arena board` for pending actions (targeting prompts, mandatory choices). The game may be waiting for a specific action, not just priority pass.

### Concede
1. `arena click 1555,72` — cog/settings icon (top-right, no text)
2. `arena click "Concede"`
3. `arena wait text="Defeat" --timeout 10`

### Result Screen
- **"[Click to Continue]"** may appear at (479,552) after DEFEAT/VICTORY — click it first
- **Dismiss:** click center three times with pauses: `arena click 480,300` → sleep 2 → repeat
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

## Sealed Event Loop (Full Example)

Fresh DB to loss counter in ~60 seconds of interaction.

### Prerequisites
```bash
just stop; trash data/player.db; just seed-db
./gradlew jar -q && just serve  # (background)
bin/arena launch
```

### 1. Navigate to event
```bash
bin/arena click "Play" --retry 3          # lobby → play menu
sleep 2
bin/arena click <sealed tile image>       # click card ART, not title text (~40px above title)
                                          # ocr --find "Sealed" gives title coords; click above
```

### 2. Join
```bash
bin/arena ocr --find "Start"              # fresh course shows "Start"
bin/arena click "Start" --retry 3
```

### 3. Open packs
```bash
bin/arena wait text="Open" --timeout 10   # "Open packs to craft your sealed deck"
bin/arena click "Open" --retry 3          # opens all 6 packs
sleep 4                                   # card reveal animation
bin/arena click "Continue" --retry 3      # → deck builder
bin/arena wait text="Cards" --timeout 10  # "0/40 Cards" confirms deck builder loaded
```

### 4. Build deck (add 40 cards)

Cards in a grid. Click to add. Two rows visible at a time.

```bash
# Row 1 y≈200, Row 2 y≈400. x from 80 to 640, spacing ~120px.
for x in 80 200 350 500 640; do bin/arena click $x,200; sleep 0.3; done
for x in 80 200 350 500 640; do bin/arena click $x,400; sleep 0.3; done
# Repeat — cards shift as added. Keep clicking until 40/40.
# Check progress:
bin/arena ocr --find "40/40 Cards"        # ready signal
```
**Don't click Done with <40 cards** — triggers "Invalid Deck" modal (dismiss with `arena click "OK"`).

### 5. Submit deck
```bash
bin/arena click "Done" --retry 3          # sends Event_SetDeckV2
sleep 2                                   # returns to event blade
```
Event blade now shows: **"0 Losses"**, **"Sealed Deck"** edit button, **"Play"**.

### 6. Play match
```bash
bin/arena click "Play" --retry 3          # Event_EnterPairing → match starts
bin/arena wait text="Keep" --timeout 30   # VS screen → mulligan
bin/arena click "Keep" --retry 3
```

### 7. Concede (or play it out)
```bash
sleep 3
bin/arena click 940,55                    # cog icon
sleep 1
bin/arena click "Concede" --retry 3
bin/arena wait text="Defeat" --timeout 10
```

### 8. Return to event blade
```bash
bin/arena click 480,300 && sleep 2 && bin/arena click 480,300 && sleep 2 && bin/arena click 480,300
sleep 3
bin/arena ocr --find "Loss"              # "1 Loss" — result tracked
```
Event blade shows updated loss counter. Click "Play" for next match.

### Key signals at each stage
| Stage | OCR signal | Means |
|-------|-----------|-------|
| Events tab | `"Sealed"` + `"New"` | Fresh event, never joined |
| Event blade (fresh) | `"Start"` | Ready to join |
| Pack opening | `"Open"` | Packs generated, click to reveal |
| Card reveal | `"Continue"` | Done revealing, proceed to builder |
| Deck builder | `"N/40 Cards"` | Count of cards added |
| Event blade (ready) | `"0 Losses"` + `"Play"` | Deck submitted, can queue |
| Mulligan | `"Keep"` | Hand dealt |
| Result | `"Defeat"` or `"Victory"` | Match ended |
| Event blade (after) | `"N Loss"` | Result recorded |

---

## Quick Draft Loop (Full Example)

Smoke-tested 2026-03-07. Full flow: join → 39 picks across 3 packs → deckbuild → event blade.

### Prerequisites
```bash
just build && just serve  # (background, tmux)
bin/arena launch
# Requires 750+ gems or 5000+ gold in start-hook.json inventory
```

### 1. Navigate to Quick Draft
```bash
bin/arena click "Play" --retry 3          # lobby → play menu
sleep 2
# Quick Draft tile is in the "All" or "Limited" view
# Click tile IMAGE, not title text — ~50px above the title
bin/arena click 141,200                   # Quick Draft tile image (960-wide coords)
sleep 2
```

### 2. Join (pay entry fee)
```bash
# Event blade shows entry fees bottom-right: 750 gems (~868,485) / 5000 gold (~870,530)
bin/arena click 868,485                   # 750 gem button
sleep 2
# Confirm Purchase dialog appears: "Are you sure you want to purchase this item?"
bin/arena click "oK" --retry 3            # note: OCR reads it as "oK" not "OK"
sleep 3                                   # → draft screen loads
```

### 3. Draft picks (3 packs × 13+ picks)
```bash
# Draft screen: card grid in left area, "Confirm Pick" at (643,539)
# "Pack N / Pick M" header shows progress
# Cards rendered in grid ~x:100-600, y:130-520
# IMPORTANT: need ~1s delay between card click and Confirm Pick

for i in $(seq 1 42); do
    bin/arena click 200,200               # click card in grid area
    sleep 1                               # MUST wait — selection animation
    bin/arena click 643,539               # Confirm Pick
    sleep 2                               # wait for next pack state
    bin/arena ocr --find "Confirm Pick" >/dev/null 2>&1 || break
done

# GOTCHA: Last card in a pack renders as tiny thumbnail at (~68,140)
# with NO OCR text. If stuck on "Pick 13/14", click (68,140) then Confirm.

# After final pick: "Vault Progress" overlay + "Okay" button at (480,469)
bin/arena click "Okay" --retry 3          # → deck builder
sleep 2
```

### 4. Build deck
```bash
# Deck builder: "N/40 Cards" counter, all drafted cards auto-added
# Client may auto-build a 40-card deck from the pool
# If not, click cards in the card list to add them
bin/arena click "Done" --retry 3          # submit deck → event blade
sleep 2
```

### 5. Play / concede loop
```bash
# Event blade: "Play" button, "0 Losses" counter, "Resign" option
bin/arena click "Play" --retry 3
bin/arena wait text="Keep" --timeout 10
bin/arena click "Keep" --retry 3
# ... play or concede as needed (see In-Game section)
```

### Draft automation gotchas

- **1s delay mandatory between card click and Confirm Pick.** Faster = click doesn't register.
- **Last card in pack has no OCR text.** Renders as tiny thumbnail at upper-left (~68,140 in 960-wide). Must click by position.
- **Forge packs can have 13-14 cards.** Variable per set. Some packs need 14 picks to exhaust.
- **"Pick N" display is 1-indexed from client perspective** but 0-indexed in server logs.
- **Card grid positions shift** as cards are picked. Always click a fixed area (200,200) — there's usually a card there until pack is nearly empty.

### Key signals
| Stage | OCR signal | Means |
|-------|-----------|-------|
| Events tab | `"Quick Draft"` | Event tile visible |
| Event blade | `750` / `5000` | Entry fee buttons, click to join |
| Purchase | `"oK"` | Confirm spend (OCR reads lowercase o) |
| Draft | `"Pack N / Pick M"` | Active drafting |
| Draft | `"Confirm Pick"` | Ready to pick (card may or may not be selected) |
| Last card | No OCR in pick area | Single tiny card at ~(68,140) |
| Draft done | `"Vault Progress"` + `"Okay"` | All picks made |
| Deck builder | `"N/40 Cards"` + `"Done"` | Building limited deck |
| Event blade | `"0 Losses"` + `"Play"` | Deck submitted, can queue |

### Protocol flow (CmdTypes)
```
Event_Join(600)           → Course with CurrentModule="BotDraft", empty CardPool
BotDraft_StartDraft(1800) → first pack (13-14 grpIds as strings, draftStatus=PickNext)
BotDraft_DraftPick(1801)  × 39+ picks → remaining pack, or next pack when exhausted
BotDraft_DraftStatus(1802)→ poll current state (optional)
  (last pick returns draftStatus=Completed, CurrentModule switches to "DeckSelect")
Event_GetCoursesV2(623)   → Course with CurrentModule="DeckSelect", CardPool=[picked grpIds]
Event_SetDeckV2(622)      → submit 40-card deck
Event_EnterPairing(603)   → queue for match
```

---

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

## Detection Known Issues (`arena detect`)

The CoreML card detection model is useful but noisy. Known issues:

- **False positives at cy < 490:** Card hover previews and enlarged cards produce detections in the upper-mid screen area (~cy 410). **Always filter hand cards by `cy > 490`** — real hand cards sit at cy ~530.
- **Duplicate detections:** Overlapping bounding boxes for the same card (e.g. cx=483 and cx=560 for the same card). The model often returns more `hand-card` detections than actual cards in hand.
- **Confidence varies wildly:** First card in hand may be 0.94, last card 0.34. Don't discard low-confidence detections if the count matters.
- **Resolution doesn't help:** Tested 960px vs 1920px — same detection count, same confidence. The model is the bottleneck, not pixel count.
- **Labels:** `hand-card`, `battlefield-untapped`, `battlefield-tapped`, `opponent-untapped`, `opponent-tapped`, `stack-item`. No land-specific label — lands and creatures use the same battlefield labels.
- **Right sidebar bleeds in:** Detections with cx > 700 may be sidebar UI (Deck Details button at ~817,91). Filter by `cx < 700` during draft.
- **Card zoom blocks drag:** When a battlefield card is hovered/expanded, it covers hand cards. Click empty battlefield area (e.g. 300,200) to dismiss the zoom before dragging from hand.
- **`arena board --detect`** correlates detections with debug API cards — more reliable than raw `arena detect`. Adds `screenCX`/`screenCY` to each card dict. Falls back to estimated positions when detection count doesn't match protocol card count.

## Tips

- **OCR is ground truth.** Always use `arena ocr` / `arena ocr --find` for state checks. Never screenshot for routine state detection — screenshots cost ~800 vision tokens, OCR costs ~0.
- **OCR coords are window-relative logical** — no scaling needed regardless of capture resolution. If OCR says (480, 345), click (480, 345).
- **Never `arena wait` with timeout >10s** — poll with `for i in 1..N; do arena ocr --find "X" && break; sleep 3; done`. Keeps conversation responsive.
- **Prefer OCR over hardcoded coords** — coords break when window size changes. OCR adapts.
- **Use `--exact` only for isolated button text** — works for "Done", "Concede" (standalone UI buttons). Fails for "Keep" and other words that OCR may merge with surrounding text. When in doubt, use substring match (default) + `--retry`.
- **Always use `--retry` for text clicks during transitions** — animations/loading can delay text rendering.
- **Never click cards to play them** — Unity interprets single click as drag-start, not play. Use `arena play "<name>"` or `arena drag` instead.
- **OCR is case-insensitive** for `--find` matching.
- **"Waiting for Server..."** — means FD connection dropped or server not responding. Restart server, then relaunch MTGA.
- **"My Decks" is often collapsed** — must click to expand before deck thumbnails are visible.
- **Invalid decks** show under "Invalid Decks:" label with warning triangles — deck has fewer than 60 cards.
- **Check state, don't assume.** After relaunching MTGA, run `arena ocr` before assuming which screen you're on. The client may auto-login, show popups, or be stuck on an error.
- **Ghost courses after mode switch.** Switching from `serve-proxy` to `just serve` (or vice versa) leaves stale courses in the client. "Resume" or "Build Your Deck" appears for events that don't exist on the new server. Fix: `just stop` + trash `data/player.db` + fresh `arena launch`.
- **Sealed: click card ART to open event, not title text.** Same pattern as deck thumbnails — clickable area is the image, not the label. OCR gives title coords; click ~40px above.
- **Sealed: pack opening is Open → reveal → Continue.** Don't skip "Continue" — without it you stay on the reveal screen. `arena wait text="Continue"` after the reveal animation (~4s).
- **Sealed: Invalid Deck modal if Done with <40.** Check `arena ocr --find "40/40 Cards"` before clicking Done. Dismiss modal with `arena click "OK"`.
- **Sealed deck builder: "40/40 Cards" is the ready signal.** Use `arena ocr --find "40/40 Cards"` to confirm the deck is complete before clicking Done. Partial decks can't be submitted.
- **Event names need baked loc keys.** Custom event names (e.g. `Sealed_FDN_20260307`) won't render titles unless `titleLocKey` points to a key the client already has (e.g. `Events/Event_Title_Sealed_FDN`). Without it, the tile appears but shows no title — hard to find via OCR.
