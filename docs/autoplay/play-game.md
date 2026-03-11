# MTGA Autoplay Agent (v10 — max density)

Play a bot match against Sparky. Play 10 of your own turns. Maximize cards played and damage dealt. Concede. Write retro.

## TOOLS

- `bin/scry state` — game state: turn, phase, hand, actions (legal plays)
- `bin/arena play "<card name>"` — play a card from hand by name. Prints `✓` on success.
- `bin/arena play "<card name>" --to <x>,<y>` — play targeting a position (auras, targeted spells)
- `bin/arena click <target>` — click text or x,y
- `bin/arena ocr --fmt` — screen text with coords (only when stuck)
- `sleep N`

## FORBIDDEN

- `curl`, `localhost:8090`, `localhost:8091`
- Reading source code, loading skills, grepping files
- Taking screenshots
- Scrying after successful plays (trust `✓`)
- Thinking more than 1 sentence between actions

## LOBBY

```
bin/arena click 40,25 && sleep 2
bin/arena click "Play" --retry 3
bin/arena wait text="Find Match" --timeout 10
bin/arena click 867,112 && sleep 1 && bin/arena click "Bot Match" --retry 3 && sleep 1
bin/arena click 82,455 && sleep 1 && bin/arena click 867,516
bin/arena wait text="Keep" --timeout 30 && bin/arena click "Keep" --retry 3 && sleep 5
```

If `wait text="Find Match"` fails, OCR to see where you are and navigate manually.

## STATE VARIABLES

- `own_turns` = 0  (increment when active_player=1 on a new turn_number)
- `last_turn` = 0

## GAME LOOP

### Core rule: ACT FAST, SCRY RARELY

Scry once at start of your turn. Play everything you can. Pass. Scry at start of next turn.

**Do NOT scry between plays.** If `arena play` prints `✓`, the card was played. Move to the next one.

### Your turn (active_player=1, Main1 or Main2)

Scry → read actions → execute this burst:

```bash
# 1. Play a land (if ActionType_Play exists)
bin/arena play "<land name>" && sleep 2

# 2. Drain mana — play ALL castable spells, cheapest first
bin/arena play "<cheapest spell>" && sleep 2
bin/arena play "<next cheapest>" && sleep 2
# ... keep going until no more ActionType_Cast in actions list

# 3. Pass to combat
bin/arena click 888,504 && sleep 1 && bin/arena click 888,504 && sleep 2
```

If `arena play` prints `✗` or fails: skip that card, try the next one. Don't scry, don't OCR, just move on.

After the burst, scry ONCE to see the new state.

### Combat (Phase_Combat, active_player=1)

Clicking 888,504 during combat = "All Attack". Your creatures attack. This deals damage. You WANT this.

```
bin/arena click 889,504 && sleep 1 && bin/arena click 889,504 && sleep 3
```

### Sparky's turn (active_player=2)

Blind chain. No thinking needed:

```
bin/arena click 887,491 && sleep 1 && bin/arena click 890,510 && sleep 3 && bin/scry state
```

If still active_player=2 → `bin/arena click 888,504 && sleep 3 && bin/scry state`. Repeat until active_player=1.

**CRITICAL: Do NOT add extra 888,504 clicks after the two pass buttons. That passes YOUR Main1.**

### Discard (Phase_Ending, hand > 7)

```
bin/arena click 400,500 && sleep 1 && bin/arena click 888,489 && sleep 2
```

### Stuck (gsId unchanged after 2 actions)

OCR to see what's on screen. Click any visible button. If stuck 3 times → CONCEDE.

## CONCEDE

After own_turns >= 10, or stuck 3 times:

```
bin/arena click 940,42 && sleep 1 && bin/arena click "Concede" --retry 3
bin/arena wait text="Defeat" --timeout 10
bin/arena click 480,300 && sleep 2 && bin/arena click 480,300 && sleep 2 && bin/arena click 480,300
```

## COORDS

- Action button = 888,504 (Pass / Next / All Attack — all same coord)
- Drop target = 480,300
- Discard: click 400,500 then submit 888,489
- Cog = 940,42
- Sparky pass: 887,491 + 890,510 (only these two!)

## RETRO

Write `/tmp/agent-retro.md`:
```
# Agent Retro
- Own turns played: X
- Cards played: (list name + turn)
- Cards failed: (name + error)
- Damage dealt: (if known from life total changes)
- Stuck moments: where/why/how resolved
- Concede: clean or killed?
```
