---
name: live-scout
description: Watch a live MTGA game — take notes on interesting interactions, inspect protocol data when the user flags something, save and summarize after.
---

## What I do

Observe a live MTGA game alongside the user. The user plays, calls out interesting moments, and I note them, look up protocol details on request, and summarize at the end.

## When to use me

- "let's play a game"
- "I'm playing, watch this"
- "live scout"
- "scout this game"

## Mode of operation

**Reactive, not proactive.** The user drives. They play and call out what they see on screen — card names, UI elements, interactions. I respond by:

1. Adding a `scry note` for anything they mention
2. Looking up protocol details when something seems novel (only if they flag it or ask)
3. Keeping responses short — they're mid-game

**The user communicates in telegraph.** Expect: "Spinner of Souls - triggered ability on stack with Decline/Take Action CTAs". Not full sentences.

## During the game

### When the user mentions something

```bash
# Note it immediately
just scry-ts note "what they said — plus brief context"

# If they want more detail, check the current state
just scry-ts board
just scry-ts gsm show <latest-gsid>
```

### What's worth noting

- New UI patterns (modals, CTAs, popups, "are you sure" dialogs)
- Triggered abilities (optional triggers, ETB effects)
- Unusual interactions (ward, copy, cost reduction, counter)
- Card names they call out
- Anything they seem surprised by

### What's NOT worth noting

- Routine combat
- Normal mana payment
- Phase transitions
- Anything that's just "the game progressing normally"

### Keep it brief during play

The user is actively playing. One-line acknowledgment + the note output is enough:

```
Noted. ShouldntPlay with Legendary reason — second copy in hand.
```

Don't launch into protocol analysis mid-game unless asked.

## After the game

When the user says the game is done (or you see the result):

```bash
# Save immediately
just scry-ts save

# Show what we captured
just scry-ts game notes <ref>

# Show our creatures/cards
just scry-ts game cards <ref>
```

Then:
1. List the notes we took
2. Suggest which cards are worth speccing (novel mechanics, new annotation patterns)
3. Ask if they want to dispatch card-spec agents

## Dispatching card-spec agents

If the user wants specs, dispatch with context:

```
Use the card-spec skill. Game data: <ref>. 
Game notes mention: <relevant notes>.
Key investigation: <what made this card interesting>.
```

Multiple agents can run in parallel for different cards.

## Commands reference

```bash
just scry-ts board                          # current board state
just scry-ts note "text"                    # note anchored to current GSM  
just scry-ts note "text" --gsid N           # note anchored to specific GSM
just scry-ts gsm show N                     # inspect a specific GSM
just scry-ts gsm list --has <type>          # find GSMs with annotation type
just scry-ts prompts                        # see all interaction prompts
just scry-ts save                           # save finished game
just scry-ts game cards <ref>               # card manifest
just scry-ts game notes <ref>               # review notes
just scry-ts trace "<card>" --game <ref>    # card lifecycle after game
```

## Rules

- **Don't run scry commands constantly.** Only when the user flags something or after the game.
- **Notes are cheap.** When in doubt, note it. Better to have a note you don't need than miss something.
- **The user knows the game.** Don't explain MTG rules. Focus on protocol/implementation implications.
- **Say "game" not "recording."**
- **Telegraph replies during play.** Save analysis for after.
