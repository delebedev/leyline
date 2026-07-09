---
summary: "ADR: define displayed cost as a value, and make non-interactive controller contexts explicit."
read_when:
  - "computing or emitting the mana cost of an action offer"
  - "adding a mechanic that reduces a cost during payment (Convoke, Improvise, Delve, Waterbend, Offering, Emerge, Assist)"
  - "working in ActionMapper, ActionManaCosts, ActivatedActionEmitter, or PlayerController cost callbacks"
  - "a Forge callback needs an answer somewhere the player cannot be asked"
---
# ADR 0007: Displayed Cost, and Controller Contexts

## Status

Proposed.

## Context

Two problems share one seam.

### Displayed cost has no definition

Every action offer carries a mana cost. That cost is computed independently in
roughly fourteen places across `ActionMapper` and `ActivatedActionEmitter`, and
no two of them agree on what it means. `usesPaymentSourceReducer` special-cases
Convoke and Improvise. `usesAlternateAdditionalCost` recognises its case by
substring-matching an ability description. The adventure, omen, alt-cost and
face-cast builders guard nothing at all.

The result is not one rule imperfectly applied. It is several different rules,
each true of the builder that hosts it, drifting apart every time a mechanic is
added. Delve, Waterbend, Offering, Emerge and Assist currently fall between
them.

### The controller is asked questions where no one can answer

`PlayerController` extends Forge's human controller, so every choice callback it
inherits raises an interactive prompt through `InteractivePromptBridge`. That is
correct for the case it was designed for, and it is the only case it handles.

Forge calls the controller from three contexts:

1. **Decision.** The player holds priority, on the game loop thread, and a
   choice genuinely belongs to them.
2. **Cost calculation.** `CostAdjustment` runs while an action list is being
   built. Nobody is being asked anything; a number is being computed.
3. **Hypothetical evaluation.** The AI advisor asks "could I cast this?" from a
   worker thread, to decide what to try.

Only the first may prompt. `InteractivePromptBridge.requestChoice` already knows
this and has three escape hatches: off the game loop thread it logs a warning and
returns the request's default index; with a zero timeout it returns the default
index outright; when a prompt is already pending it returns the default index
again.

Each escape hatch converts *"you asked me in a context where I cannot answer"*
into *"here is an answer"* — one indistinguishable from a real choice, and
plausible enough to travel. A default index selects permanents the player never
tapped. Those phantom selections reduce a cost that is then displayed, or
corrupt the plan an advisor commits to. The failure is silent, and it surfaces
far from its origin.

These are the same defect. `CostAdjustment.adjustCostByWaterbend` delegates to
`adjustCostByConvokeOrImprovise`, which calls
`getController().chooseCardsForConvokeOrImprovise`. `PlayerController.payManaCost`
reaches the same coordinator method directly from its `CostWaterbend` branch. One
callback, entered from cost calculation and from payment, answered by a default
index in both.

## Decision

### 1. Displayed cost is a defined value

> **Displayed cost is the printed cost after every cost modification that follows
> from game state alone, and before every reduction that requires the player to
> choose which permanents or cards pay.**

The distinction is a rules distinction, not an implementation convenience. A
continuous effect that reduces a spell's cost — Affinity, "this spell costs {1}
less to cast for each instant and sorcery card in your graveyard" — changes what
the spell *costs* (CR 601.2f). Convoke, Improvise, Delve, Waterbend, Offering,
Emerge and Assist change how that cost may be *paid*: the player chooses which
permanents to tap, or which cards to exile or sacrifice, while paying
(CR 601.2h). The spell's cost is unchanged.

So the dividing line is **whether applying the reduction requires a choice**,
not whether the reduction reads game state. Delve counts cards in a graveyard;
so does a graveyard-count cost reducer. One is excluded and the other included,
because only one asks the player which cards.

A displayed cost that already subtracted a payment choice would be showing a
discount the player has not yet agreed to, on an offer they have not yet taken.
Payment-time reductions belong to the payment prompt, where the choice is
actually made.

One entry point serves every action builder:

```text
CastDisplayCost.requirements(sa, player, printedCost) -> List<ManaRequirement>
```

Affordability is a different question and must not share this path. "Can I pay
for this?" legitimately wants the best case the board allows, including every
payment-time reduction. Displayed cost wants none of them. Conflating the two is
what produced the current guards.

### 2. Controller contexts are explicit

Introduce **non-interactive controller scopes**: a thread-scoped answer policy,
entered for the duration of a computation, consulted by the bridge controller's
payment callbacks before any prompt machinery. Each scope names a policy that
answers every payment callback without prompting:

- **Quiet** — every payment choice answers "nothing chosen": no cards to delve,
  no permanents tapped, nothing sacrificed. Consumer: displayed cost. Under this
  policy Forge's own cost adjustment returns exactly the value the rule above
  defines, with no keyword list to maintain.
- **BestEffort** — every payment choice answers with the maximum legal reduction,
  deterministically. Consumers: affordability, and hypothetical evaluation by the
  advisor.

The policy is chosen by the caller, because only the caller knows why it is
asking. Neither policy consults the player, and both work on any thread — the
scope travels with the thread running the computation.

A `Player.addController(Long.MAX_VALUE)` layer was considered and rejected:
Forge's controller stack is keyed by timestamp, so a same-slot layer silently
evicts whatever else occupies it (the simclient harness already layers its
advisor controller there), and a policy controller that extends an AI
controller would answer *every* callback — turning "refuse, not guess" into
silent guessing for callbacks no policy enumerates. With a scoped policy, an
unenumerated callback falls through to the prompt bridge, which refuses.

Then the load-bearing rule:

> **A controller callback reached outside a real prompt window must refuse, not
> guess.**

Under `DevCheck.strict`, `requestChoice` throws when it is called off the game
loop thread, inside a non-interactive scope, or with a prompt already pending.
Production keeps a fallback so a live game degrades rather than dies — but the
fallback stops being invisible, and no new caller can quietly acquire a phantom
answer.

## Responsibilities

`CastDisplayCost` owns:

- The single definition of displayed cost, and its proto materialization.
- The choice of scope policy for display.

It must not own:

- Affordability, auto-tap solutions, or legality.
- Which actions are offered.

Non-interactive controller scopes own:

- Answering payment callbacks deterministically, without prompting, on any thread.
- Scope entry and exit, including nesting and restoration on exception.

They must not own:

- Cost arithmetic. Forge computes the cost; the scope only decides the answers.
- Prompt emission, or any lifecycle in `InteractivePromptBridge`.

## Target-Shape Tests

The seam is paying for itself when these statements stay true:

- Adding a mechanic that reduces a cost during payment requires no change to any
  display code, and no entry in any keyword list.
- Naive and snapshot action builders produce identical mana costs for every card
  in hand, over a fixture board carrying a continuous reducer, a Convoke card, a
  Delve card, and a card with both.
- A card with both a continuous reducer and a payment-time reducer displays the
  continuous reduction and only that.
- No cost computation can raise a prompt. Strict mode proves this rather than
  documenting it.
- Changing how affordability is computed never changes a displayed cost.

Wrong-shape signals:

- A keyword list inside display code.
- A guard that recognises a mechanic by matching text in an ability description.
- Two action builders that compute the same card's cost by different routes.
- A callback that returns a default answer because of the thread it is on.

## Consequences

`usesPaymentSourceReducer` and `usesAlternateAdditionalCost` are deleted from the
display path. The first survives, renamed, only where affordability needs it.

Some Forge behaviour is not ours to change and should be documented rather than
fought: `CostAdjustment.adjust` records a waterbend maximum on the ability it is
handed, and turns a face-down host face-down again for the duration of the
calculation. Both are Forge-owned and restored by Forge.

Migration is incremental. The scopes land first, because the advisor depends on
them and its failures are the loud ones. Display sites collapse onto
`CastDisplayCost` one family at a time, preserving emitted costs at each step
except where the current value is a phantom. Strict-mode refusal lands last, once
no legitimate caller trips it.

The work stops if a scope policy starts accumulating per-mechanic branches. That
would mean the callback boundary is in the wrong place, and the keyword lists have
simply moved.
