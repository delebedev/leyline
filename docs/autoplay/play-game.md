# MTGA Autoplay Agent (v9 — arena play)

Play a bot match against Sparky. Play 5 of your own turns. Concede. Write retro.

## TOOLS

- `bin/scry state` — game state: turn, phase, hand (card names + ids), actions (legal plays + mana cost)
- `bin/arena play "<card name>"` — play a card from hand by name (finds card, drags, verifies zone change). Works in all server modes.
- `bin/arena play "<card name>" --to <x>,<y>` — play targeting a specific position (for auras, targeted spells)
- `bin/arena click <target>` — click text or x,y
- `bin/arena drag <from> <to>` — drag between coords (low-level fallback)
- `bin/arena wait text="X" --timeout N` — wait for text
- `bin/arena ocr --fmt` — screen text with (x,y) coords
- `sleep N`

## FORBIDDEN

- `curl`, `localhost:8090`, `localhost:8091`
- Reading source code, loading skills, grepping files
- Taking screenshots (use `arena ocr` instead)

## LOBBY

If you're not sure you're on the Home screen, click "Home" first: `bin/arena click 40,25 && sleep 2`

Then the lobby sequence:
```
bin/arena click "Play" --retry 3
bin/arena wait text="Find Match" --timeout 10
bin/arena click 867,112 && sleep 1 && bin/arena click "Bot Match" --retry 3 && sleep 1
bin/arena click 82,455 && sleep 1 && bin/arena click 867,516
bin/arena wait text="Keep" --timeout 30 && bin/arena click "Keep" --retry 3 && sleep 5
```

If `wait text="Find Match"` fails, OCR to see where you are and navigate manually.

## STATE VARIABLES

Track these yourself:
- `own_turns` = 0  (increment when active_player=1 on a new turn_number)
- `last_turn` = 0
- `land_played_this_turn` = false

## GAME LOOP

**Every iteration: scry first, then act based on what scry says.**

### Reading scry output

Key fields:
- `turn_info.active_player` — 1=you, 2=Sparky
- `turn_info.phase` — Phase_Main1, Phase_Combat, Phase_Main2, Phase_Ending
- `hand` — array of {name, id}. Card count = length.
- `actions` — legal plays: ActionType_Play (lands), ActionType_Cast (spells with manaCost)

### How to play a card

Use `arena play` for everything — lands, creatures, spells:

```bash
bin/arena play "<card name>"
```

1. Check `actions` from scry. Pick what to play (land first, then cheapest spell).
2. Run `bin/arena play "<exact card name from scry>"`. Sleep 2.
3. Run `bin/scry state` to confirm hand count decreased.
4. If hand count decreased → success! Continue with next play.
5. If hand count unchanged → play failed. Cancel any prompt: `bin/arena click 888,504 && sleep 1`. Move to next card.

**Auras and targeted spells:** use `--to` with a target position:
- Target opponent's face: `bin/arena play "Lightning Strike" --to 480,100`
- Target a creature: use OCR to find creature name, then `--to cx,cy`

**If `arena play` fails** (card not found in hand): fall back to OCR-guided drag:
1. `bin/arena ocr --fmt` to find card name → cx coordinate
2. `bin/arena drag cx,500 480,300 && sleep 2`
3. Scry to verify.

### Decision tree (after scry):

**If own_turns >= 5 → CONCEDE immediately.**

**If active_player=1, Phase_Main1 or Phase_Main2:**
Increment own_turns if turn_number > last_turn. Set last_turn. Reset land_played_this_turn.

1. If `actions` has `ActionType_Play` and !land_played_this_turn:
   - Find the land name in actions → `bin/arena play "<land name>"`. Sleep 2. Scry.
   - If success, set land_played_this_turn=true.
2. If `actions` has `ActionType_Cast`:
   - Pick the cheapest non-aura spell from actions.
   - `bin/arena play "<spell name>"`. Sleep 2. Scry.
   - If success and more mana available, try next spell.
3. When done playing: `bin/arena click 888,504 && sleep 1 && bin/arena click 888,504 && sleep 2`

**If active_player=1, Phase_Combat:**
```
bin/arena click 889,504 && sleep 1 && bin/arena click 889,504 && sleep 3
```

**If active_player=2 (Sparky's turn):**
Click ONLY the two stacked buttons, then scry to check:
```
bin/arena click 887,491 && sleep 1 && bin/arena click 890,510 && sleep 3
bin/scry state
```
If still active_player=2 → click `888,504` once, sleep 3, scry again. Repeat.
If active_player=1 → it's your turn! Handle your Main phase.

**CRITICAL: Do NOT add a third 888,504 click after the two pass buttons. That third click lands during YOUR Main1 and passes your turn.**

**If Phase_Ending or Step_Cleanup:**
If hand count > 7: `bin/arena click 400,500 && sleep 1 && bin/arena click 888,489 && sleep 2`
If hand count <= 7: `bin/arena click 888,504 && sleep 2`

**If gsId unchanged after 2 actions:** You're stuck. OCR to see what's on screen. Click any visible button. If stuck 3 times → CONCEDE.

### Complete turn example:

```bash
# 1. Scry: own turn, Main1, 7 cards
bin/scry state
# hand: 7 cards, actions: ActionType_Play "Plains", ActionType_Cast "Searslicer Goblin" (1R)

# 2. Play land
bin/arena play "Plains" && sleep 2
bin/scry state
# hand now 6 → land played!

# 3. Play spell
bin/arena play "Searslicer Goblin" && sleep 2
bin/scry state
# hand 6→5 → cast!

# 4. Advance to combat
bin/arena click 888,504 && sleep 1 && bin/arena click 888,504 && sleep 2
```

## CONCEDE

After own_turns >= 5, or stuck 3 times:

```
bin/arena click 940,42 && sleep 1 && bin/arena click "Concede" --retry 3
bin/arena wait text="Defeat" --timeout 10
bin/arena click 480,300 && sleep 2 && bin/arena click 480,300 && sleep 2 && bin/arena click 480,300
```

## COORDS

- Drop target = 480,300
- Action button = 888,504
- Discard: click 400,500 then submit 888,489
- Cog = 940,42
- Opponent stacked buttons: 887,491 + 890,510 (only these two! no extra 888,504)

## RETRO

Write `/tmp/agent-retro.md`:
```
# Agent Retro
- Own turns played: X
- Cards played: (list name + turn + method: arena-play/ocr-drag/fallback)
- Cards skipped: (why — aura, too expensive, failed play)
- Deck type: (what colors/cards seen)
- Stuck moments: where/why/how resolved
- Concede: clean or killed?
```
