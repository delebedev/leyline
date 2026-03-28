# Forge DFC Transform Internals

Reference documentation for how Forge handles double-faced card (DFC) activated transforms internally. Covers state management, the resolution chain, triggered ability flow, and leyline's detection layer.

---

## Card State Management

- `Card.backside` (boolean, `Card.java:244`) — primary toggle; flipped by `changeCardState("Transform")`
- `Card.currentStateName` / `Card.currentState` — swapped by `setState()`; `currentStateName` is the authoritative enum
- `CardStateName.Original` / `CardStateName.Backside` — the two DFC states used throughout the engine
- `Card.isDoubleFaced()` / `Card.isTransformable()` / `Card.isTransformed()` — query methods; `isTransformed()` returns `backside == true`
- `Card.transformedTimestamp` — receives a new timestamp on each transform per MTG rules (timestamp ordering)

---

## Transform Resolution Chain

```
SetStateEffect.resolve() (SetStateEffect.java:179)
  → Card.changeCardState("Transform", null, sa) (Card.java:665-705)
      → c.backside = !c.backside                              (Card.java:681)
      → c.changeToState() → c.setState(Backside, true)       (Card.java:684)
          → currentStateName = state                         (Card.java:592)
          → currentState = getState(state)                   (Card.java:593)
          → updateStateForView()
              → view.updateState(this)                       (Card.java:597)
                  → set(TrackableProperty.CurrentState, currentStateView)
                    -- view NOW reflects back face
          → game.fireEvent(GameEventCardStatsChanged(this))  (Card.java:614)
            -- FIRST event fire (from setState)
      → returns true
  → game.fireEvent(GameEventCardStatsChanged(gameCard))      (SetStateEffect.java:205)
    -- SECOND event fire (from SetStateEffect — redundant)
```

Both fires happen **after** the state change is committed. The view is already updated when either event is observed.

---

## Key Facts

**Double event fire.** `GameEventCardStatsChanged` fires twice per transform — once from `Card.setState()` and once from `SetStateEffect.resolve()`. Both are safe to receive; the state is stable by the time of the first fire.

**CardView wrapping.** The event carries `CardView` objects (via `CardView.getCollection()`), not `Card` references directly.

**`CardStateView.state` is final.** `CardView.getCurrentState()` returns `TrackableProperty.CurrentState`, a `CardStateView` whose `state` field is set at construction and never mutated. After transform, `getCurrentState().state == CardStateName.Backside`.

**`transform` flag is hardcoded false.** The `transform` field on `GameEventCardStatsChanged` is always `false` — hardcoded disabled at construction (line 22). Do not use it for detection; check `CardStateView.state` instead.

**Sorcery-speed gate.** Activated transforms check `ability.canCastTiming(player)` → `player.canCastSorcery()`, which requires `isPlayerTurn && isMain && stackEmpty`.

---

## Trigger Chain After Transform

When `SetStateEffect` resolves with `hasTransformed = true`, Forge scans the card's triggered abilities for `T:Mode$ Transformed`. For Concealing Curtains → Revealing Eye (grpId 146663), this pushes the back-face trigger onto the stack with the following resolution sequence:

1. **SelectTargetsReq** — target opponent
2. **RevealHand** — calls `PlayerController.reveal()`
3. **ChooseCard from revealed** — calls `PlayerController.chooseCardsForEffect()`; blocks on `InteractivePromptBridge` if unhandled
4. **Discard chosen card**
5. **Draw a card**

If the choose-card prompt is not handled by the bridge, the engine thread blocks indefinitely and the game appears hung.

---

## Leyline Detection

`GameEventCollector.visit(GameEventCardStatsChanged)` iterates the event's `CardView` collection and checks `card.currentState.state == CardStateName.Backside` on each entry. Last-seen state is tracked via `lastBackSide: ConcurrentHashMap<ForgeCardId, Boolean>`. `GameEvent.CardTransformed` is emitted only when the state actually flips — not on first observation — preventing spurious events during initial hydration.
