---
summary: "How Forge handles reveal-and-choose effects (Duress, Thoughtseize): two SpellAbility paths, bridge interception points, and choice flow."
read_when:
  - "implementing or debugging hand reveal / discard choice effects"
  - "understanding Forge's DiscardEffect vs chained SubAbility paths"
  - "wiring bridge interception for player choice prompts"
---
# Forge Reveal + Choose Internals

How Forge handles "reveal opponent's hand, choose a card" effects (Duress, Revealing Eye, Thoughtseize, etc.) and how the bridge intercepts choices.

## Two Paths to the Same Mechanic

The same gameplay outcome -- reveal hand, pick a card, discard it -- is implemented via **two completely different SpellAbility chains** depending on the card script.

### Path 1: Discard with `Mode$ RevealYouChoose` (Duress)

Single `SP$ Discard` ability with mode parameter. The `DiscardEffect.resolve()` method handles everything inline.

Card script (`duress.txt`):
```
A:SP$ Discard | ValidTgts$ Opponent | Mode$ RevealYouChoose | DiscardValid$ Card.nonCreature+nonLand | NumCards$ 1
```

Execution in `DiscardEffect.resolve()` (line 225):
1. Gets target player's hand: `p.getCardsIn(ZoneType.Hand)`
2. Mode starts with `"Reveal"` -> calls `game.getAction().reveal(dPHand, p)` (reveals to ALL players except hand owner)
3. Filters valid choices: `CardLists.getValidCards(dPHand, valid, source.getController(), source, sa)` -- valid comes from `DiscardValid` param
4. **Human choice point:** `chooser.getController().chooseCardsToDiscardFrom(p, sa, validCards, min, max)` -- the chooser is `sa.getActivatingPlayer()` (the caster)
5. Reveals chosen card back to the hand owner: `p.getController().reveal(toBeDiscarded, ZoneType.Hand, p, ...)`
6. Calls `discard()` helper which calls `p.discard(card, sa, effect, params)` per card

Key: the *chooser* is the spell's activating player, not the hand's owner. `chooseCardsToDiscardFrom` takes both `playerDiscard` (whose hand) and is called on `chooser.getController()`.

### Path 2: Chained SubAbilities (Concealing Curtains / Revealing Eye)

Four separate abilities chained via `SubAbility$`:
```
TrigReveal:  DB$ RevealHand | ValidTgts$ Opponent | RememberRevealed$ True | SubAbility$ DBChoose
DBChoose:    DB$ ChooseCard | ChoiceZone$ Hand | Amount$ 1 | Choices$ Card.nonLand+IsRemembered | SubAbility$ DBDiscard
DBDiscard:   DB$ Discard | DefinedCards$ ChosenCard | Defined$ Targeted | Mode$ Defined | ...
DBDraw:      DB$ Draw | Defined$ Targeted | NumCards$ 1 | ...
DBCleanup:   DB$ Cleanup | ClearRemembered$ True | ClearChosenCard$ True
```

Execution chain:
1. **RevealHandEffect.resolve()**: Gets opponent hand, calls `game.getAction().reveal(hand, p)`, sets `host.addRemembered(hand)` (via `RememberRevealed$ True`)
2. **ChooseCardEffect.resolve()**: Builds choice pool from `ChoiceZone$ Hand`, filters by `Choices$ Card.nonLand+IsRemembered`. **Human choice point:** `p.getController().chooseCardsForEffect(pChoices, sa, title, minAmount, validAmount, !Mandatory, null)`. Sets `host.setChosenCards(allChosen)`.
3. **DiscardEffect.resolve()** (Mode=Defined): Uses `DefinedCards$ ChosenCard` to get the card. Conditional on `ConditionDefined$ ChosenCard | ConditionPresent$ Card | ConditionCompare$ EQ1` (only fires if player actually chose something).
4. **DrawEffect**: The "then draws a card" part. Same condition.
5. **CleanupEffect**: Clears remembered + chosen card state from host.

## Trigger Chain: Transform to Reveal

How Concealing Curtains fires the whole chain:

1. Player activates `AB$ SetState | Mode$ Transform` (the `{2}{B}` ability)
2. `Card.changeState("Transform")` (line ~674 in Card.java):
   - Flips `backside` flag, calls `changeToState(CardStateName.Backside)`
   - Clears old triggers, registers new ones from back face: `getTriggerHandler().clearActiveTriggers(this, null)` then `registerActiveTrigger(this, false)`
   - Fires `TriggerType.Transformed` with card as run parameter
3. `TriggerTransformed.performTest()` checks `ValidCard$ Card.Self` -- matches because the transforming card is itself
4. Trigger's `Execute$ TrigReveal` resolves the `DB$ RevealHand` chain above
5. Target (opponent) was set when trigger went on the stack

Important: the trigger re-registration at line 696-697 means the back face's triggers (including the Transformed trigger) are active when `TriggerType.Transformed` fires. This is what makes "when this creature transforms into CARDNAME" work.

## Game Events Fired

### During reveal
- `PlayerController.reveal()` called on each player (via `GameAction.reveal()` broadcast loop)
- No dedicated `GameEventRevealed` class exists in Forge
- Bridge intercepts via `WebPlayerController.reveal()` override -> `bridge.recordReveal(cardIds, ownerSeat)`
- Reveal records are queued in `InteractivePromptBridge.revealQueue` and drained at annotation-build time

### During discard
- `GameEventCardChangeZone` (Hand -> Graveyard) -- fired by `GameAction.moveToGraveyard()`
- `GameEventAddLog` with `GameLogEntryType.DISCARD` -- fired in `Player.discard()`
- `TriggerType.Discarded` -- per-card trigger (run params: Card, Cause, Player)
- `TriggerType.DiscardedAll` -- batch trigger (run params: Cards, Cause, Player, DiscardedBefore)

### No dedicated reveal event
Forge has **no `GameEventRevealed`** or similar. The reveal is purely a UI notification -- `GameAction.reveal()` iterates all players and calls `controller.reveal()` on each. The bridge captures this through the `WebPlayerController.reveal()` override.

## Valid Choice Filtering

| Card | Filter Location | Filter Expression |
|------|----------------|-------------------|
| Duress | `DiscardEffect` line 255 | `DiscardValid$ Card.nonCreature+nonLand` -> `CardLists.getValidCards()` |
| Revealing Eye | `ChooseCardEffect` line 71 | `Choices$ Card.nonLand+IsRemembered` -> `CardLists.getValidCards()` |
| Thoughtseize | `DiscardEffect` line 255 | `DiscardValid$ Card.nonLand` |

Both paths use `CardLists.getValidCards(cards, validString, controller, source, sa)` for filtering. The valid string syntax is shared: `+` joins predicates, `.` separates type from subtypes.

The `IsRemembered` predicate in Revealing Eye's filter is what connects the RevealHand step (which remembers revealed cards) to the ChooseCard step (which picks from them).

## Bridge Interception Points

### Path 1 (Discard Mode=RevealYouChoose)
```
Engine calls: chooser.getController().chooseCardsToDiscardFrom(p, sa, validCards, min, max)
Bridge:       WebPlayerController.chooseCardsToDiscardFrom() -> chooseCardsViaBridge()
              Builds PromptRequest(promptType="choose_cards", options=cardNames, min, max)
              Blocks on bridge.requestChoice() -> InteractivePromptBridge CompletableFuture
```

### Path 2 (ChooseCard SubAbility)
```
Engine calls: p.getController().chooseCardsForEffect(pChoices, sa, title, min, max, isOptional, null)
Bridge:       WebPlayerController.chooseCardsForEffect() -> chooseCardsViaBridge()
              Same PromptRequest mechanism as above
```

Both paths converge to `chooseCardsViaBridge()` which:
1. Builds card name labels from the valid cards list
2. Creates a `PromptRequest(promptType="choose_cards", ...)`
3. Calls `bridge.requestChoice(request)` which blocks the engine thread on a `CompletableFuture`
4. Returns selected card indices which get mapped back to `Card` objects

### Reveal capture
```
Engine calls: GameAction.reveal(cards, owner) [broadcast to all players]
  -> for each Player p: p.getController().reveal(cards, zone, owner, ...)
Bridge:       WebPlayerController.reveal() override
              -> bridge.recordReveal(cardIds, ownerSeat) [queued in ConcurrentLinkedQueue]
              -> super.reveal() [no-op in WebGuiGame]
```

The reveal queue is drained by the annotation pipeline during diff-build to produce RevealedCards annotations.

## Key Differences Between the Two Paths

| Aspect | Duress (Mode$ RevealYouChoose) | Revealing Eye (SubAbility chain) |
|--------|-------------------------------|----------------------------------|
| ApiType | `Discard` | `RevealHand` -> `ChooseCard` -> `Discard` |
| Choice method | `chooseCardsToDiscardFrom` | `chooseCardsForEffect` |
| Chooser | `sa.getActivatingPlayer()` | Target player (from `getDefinedPlayersOrTargeted`) |
| Optional | No (min=1) | Yes (no `Mandatory` param on ChooseCard) |
| State tracking | None needed | `Remembered` + `ChosenCard` on host card |
| Draw after discard | No | Yes (conditional SubAbility) |
| Cleanup | None | `DB$ Cleanup` clears remembered/chosen |

The bridge treats both identically at the interception layer -- both become `chooseCardsViaBridge()` calls. The semantic difference (which card the choice method is called on, optional vs mandatory) is already resolved by the engine before the bridge sees it.
