# MTGA Autoplay Agent

Play a bot match against Sparky. Play 5 of your own turns. Concede. Write retro.

## TOOLS

- `bin/scry state` — game state: turn, phase, hand, **actions** (legal plays with mana costs), zones
- `bin/arena click <target>` — click text or x,y
- `bin/arena drag <from> <to>` — drag between coords
- `bin/arena wait text="X" --timeout N` — wait for text
- `bin/arena ocr --fmt` — screen text with (x,y) coords
- `sleep N`

## FORBIDDEN

- `arena play`, `arena board`, `arena state`, `arena detect`
- `curl`, `localhost:8090`
- Reading source code, loading skills

## LOBBY

```
bin/arena click "Play" --retry 3
bin/arena wait text="Find Match" --timeout 10
bin/arena click 867,112 && sleep 1 && bin/arena click "Bot Match" --retry 3 && sleep 1
bin/arena click 82,455 && sleep 1 && bin/arena click 867,516
bin/arena wait text="Keep" --timeout 30 && bin/arena click "Keep" --retry 3 && sleep 5
```

## GAME LOOP

### Decision engine: ACTIONS FIRST

`bin/scry state` returns an `actions` array — the game engine's list of **legal plays right now**. This is your brain. Example:

```json
"actions": [
  {"actionType": "ActionType_Play", "instanceId": 163, "name": "Island", "seatId": 1},
  {"actionType": "ActionType_Cast", "instanceId": 160, "name": "Waterkin Shaman", "seatId": 1, "manaCost": [{"color": ["ManaColor_Generic"], "count": 1}, {"color": ["ManaColor_Blue"], "count": 1}]}
]
```

**Priority order:**
1. `ActionType_Play` — lands. Always play one if available.
2. `ActionType_Cast` with NO `manaCost` or cheapest cost — free/cheap spells.
3. `ActionType_Cast` — sort by total mana, play cheapest you can afford.
4. **Skip** anything with "Aura" or "Enchant" in the card name — these need targets, cancel is wasteful.

### Each of your turns (active_player=1, Main phase):

**Step 1 — Scry + OCR together:**
```
bin/scry state
```
Read actions. Pick what to play (land first, then cheapest spell).
```
bin/arena ocr --fmt
```
Find that card's name in OCR output → get its x coordinate.

**Step 2 — Play the card:**
```
bin/arena drag <card_x>,500 480,300 && sleep 2
```

**Step 3 — Verify via scry:**
```
bin/scry state
```
- Hand shrunk → card played. Pick next action if available, repeat from step 2.
- Hand same, "Cancel" or target prompt → click cancel: `bin/arena click 888,504 && sleep 1`
- Hand same, no prompt → drag missed. Move on.

**Step 4 — Advance:**
```
bin/arena click 888,504 && sleep 1 && bin/arena click 888,504 && sleep 2
```

### Combat (active_player=1, Phase_Combat):
```
bin/arena click 889,504 && sleep 1 && bin/arena click 889,504 && sleep 3
```

### Opponent's turn (active_player=2):
```
bin/arena click 887,491 && sleep 1 && bin/arena click 890,510 && sleep 1 && bin/arena click 888,504 && sleep 3
```

### Phase_Ending:
Check hand count from scry zones (owner=1 Hand).
- Hand > 7: `bin/arena click 400,500 && sleep 1 && bin/arena click 888,489 && sleep 2`
- Hand <= 7: `bin/arena click 888,504 && sleep 2`

### Advance between turns:
```
for i in 1 2 3; do bin/arena click 888,504 && sleep 1; done && sleep 3
```

### Modals:
If OCR shows "View Battlefield" / "Yes" → click "Yes".
Any unexpected popup with a button → click it to dismiss.

### Stuck (same gsId twice):
OCR → click whatever button you see. If stuck 3 times, concede.

## CONCEDE

```
bin/arena click 940,42 && sleep 1 && bin/arena click "Concede" --retry 3
bin/arena wait text="Defeat" --timeout 10
bin/arena click 480,300 && sleep 2 && bin/arena click 480,300 && sleep 2 && bin/arena click 480,300
```

## COORDS

- Hand Y = 500
- Drop = 480,300
- Action button = 888,504
- Discard submit = 888,489
- Cog = 940,42
- Opponent stacked: 887,491 + 890,510

## RETRO

Write `/tmp/agent-retro.md`:
```
# Agent Retro
- Own turns: X
- Cards played: (list name + turn)
- Cards skipped: (why — aura, too expensive, missed)
- Deck type: (what colors/cards did I see in hand?)
- Stuck: where/why
- What worked / didn't
```
