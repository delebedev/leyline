# Vision Architecture: Fallback, Not Primary

## Principle

Debug API is the primary source for card identity and game state. Vision (CoreML card detection + OCR) is fallback for cases where the protocol layer can't help.

## Layer Hierarchy

```
Layer 1: Debug API (:8090)          — authoritative identity, zones, phase, actions
Layer 2: Player.log (scry)          — client receipt confirmation, errors, scene state
Layer 3: OCR (macOS Vision)         — screen text, button labels, prompts
Layer 4: Card Detection (CoreML)    — bounding boxes, screen coordinates
```

Each layer adds information the layer above can't provide. Lower layers never override higher layers on identity or game state.

## When to Use Each Layer

| Question | Layer | Why |
|---|---|---|
| What cards are on the battlefield? | Debug API `/api/id-map` | Authoritative — Forge engine state |
| What zone is a card in? | Debug API `/api/id-map` | Zone transitions tracked by bridge |
| What phase/turn is it? | Debug API `/api/state` | Engine state, not rendered text |
| What actions are available? | Debug API `/api/game-states` | Action list from latest GSM |
| What screen am I on? | Player.log scene + OCR | SceneChange for lobby, OCR for in-game |
| Where on screen is a card? | Card Detection + OCR | Only vision can answer this |
| What does the button say? | OCR | Text detection |
| Did the client render correctly? | OCR + Detection | Visual regression check |
| What cards are in a draft pack? | Card Detection | No debug API for draft UI |

## Card Screen Position

The primary use case for vision: mapping protocol-known cards to pixel coordinates.

```
Debug API knows:     "Grizzly Bears" (id=42) is in Hand zone
Detection knows:     hand-card bounding box at (380, 520, 80, 100)
Fused:               "Grizzly Bears" is at screen position (420, 570)
```

`arena board --detect` already does this fusion. `arena play` uses it to drag specific cards.

## When Vision Model Doesn't Need to Be Perfect

Because the debug API provides authoritative identity:
- **Missed detections** → fall back to estimated hand positions (evenly spaced)
- **False positives** → filtered by cross-referencing with protocol card count
- **Wrong labels** → ignored; zone membership comes from debug API
- **Low confidence** → accepted if count matches protocol

The model only needs to answer: "where are the card-shaped rectangles on screen?"

## When Vision IS Required (No Protocol Fallback)

1. **Draft picks** — server sends grpIds but no screen layout. Detection finds card grid positions.
2. **Deck builder** — card grid layout is client-only rendering.
3. **Screen identification** — when debug API is down or between games.
4. **Visual regression** — confirming the client rendered what we sent.

## Data Flow in `arena board`

```
                    ┌──────────────┐
                    │  Debug API   │
                    │  /api/id-map │──── card names, zones, instanceIds
                    │  /api/state  │──── phase, turn, life
                    │  /api/game-  │──── available actions
                    │   states     │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │   Fusion     │
          ┌────────│  (arena.py)  │────────┐
          │        └──────────────┘        │
          │                                │
   ┌──────▼──────┐                 ┌───────▼──────┐
   │     OCR     │                 │  Detection   │
   │  (optional) │                 │  (--detect)  │
   │             │                 │              │
   │ hand card   │                 │ bounding     │
   │ x-positions │                 │ boxes +      │
   │ button text │                 │ labels       │
   └─────────────┘                 └──────────────┘
```

## Training Implications

Since the model is fallback, not primary:
- **Coarse classes are fine** — `card`, `hand-card`, `stack-item`, `draft-card`
- **Don't need per-card recognition** — debug API provides identity
- **Opponent zone detection can be weak** — debug API knows their cards
- **Focus training data on** hand cards (drag source) and draft grids (no protocol fallback)
