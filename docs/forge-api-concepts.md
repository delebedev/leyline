---
summary: "Key Forge API concepts for engine work: controller callbacks, SpellAbility chains, actions, costs, events, snapshots, and prompts."
read_when:
  - "modifying engine bridge code that calls into Forge"
  - "adding or debugging a Forge PlayerController override"
  - "deciding whether to use a Forge event, snapshot diff, or prompt bridge"
  - "debugging cast, ability, cost, or targeting behaviour"
---
# Forge API Concepts

This is the stable map for how engine uses Forge APIs. It explains the concepts that show up across many classes; per-class rationale stays in KDoc and wire details stay in protocol docs.

## 1. Boundary Shape

`engine` is an adapter around Forge's synchronous rules engine. The client protocol is asynchronous, but Forge expects blocking answers from a `PlayerController`.

There are three main integration surfaces:

- `PlayerController` callbacks for priority, choices, costs, targeting, combat, and ordering.
- Forge `GameEvent`s for facts that happen during engine execution.
- Snapshot reads of `Game`, `Player`, `Card`, zones, stack, and counters when building protocol state.

Do not duplicate game rules in Kotlin. Ask Forge what is legal or what happened, then translate that result.

## 2. PlayerController Callbacks

Forge dispatches interactive work through virtual methods on the player controller. There is no registration table or composition hook that replaces this surface, so overrides live on `bridge/forge/PlayerController`.

Use this decision rule:

- Priority actions and combat declarations go through `GameActionBridge`.
- Engine-initiated choices go through `InteractivePromptBridge`.
- Mulligan decisions go through `MulliganBridge`.
- Yes/no optional-action style prompts use `OptionalActionGate` when the session must observe a pending prompt outside the normal prompt queue.
- Numeric prompts use `NumericInputGate`.

The engine thread blocks in these calls. Never block on session-owned state from an override; post a pending request and let the session complete the future.

Named `Choices` votes block in `PlayerController.vote` and retain the exact
option handles through the modal choice lifecycle. The compatibility request
carries `Prompt.parameters[choiceKind] = "vote"`, a `NonLocalizedString`, so
embedding clients can label the decision as a vote. Votes do not offer cast
cancellation or undo, and selecting a vote does not mark a casting mode. Optional votes abstain when the interaction times out.
Forge retains vote order, counting, and effect resolution. Entity votes retain
the existing entity-selection path.

Generic and villainous effect choices use `chooseSpellAbilitiesForEffect` to
retain their named `Choices` subabilities through the same modal bridge.
The selected handles return to Forge for effect resolution. These resolution
choices reject cancellation and do not offer undo.

Attacker alternatives are committed with the combat declaration. Exert and
Enlist callbacks consume their own selections on the engine thread before
Forge pays optional attack costs. Selecting a normal attack declines those
costs without opening another choice.

## 3. SpellAbility Is A Chain

A spell or ability is often an SA chain, not one `SpellAbility`. Wrapper APIs such as `Charm`, `Effect`, `Repeat`, and `RepeatEach` can put meaningful work in sub-abilities that run after choices are made.

Implication: predicates over only the outer SA are suspect:

- `sa.api`
- `sa.hasParam(...)`
- `sa.usesTargeting()`
- direct checks on only `sa.hostCard` or only the first sub-ability

Prefer Forge helpers that walk or resolve the chain:

- `PlaySpellAbility.playAbility(..., mayChooseTargets = true, ...)` for normal spell play.
- `setupTargets()` through Forge's play path for targets that are not already set.
- `PlaySpellAbility.playSpellAbilityNoStack(...)` for no-stack effects that can carry costs; it preserves payment and resolves the sub-ability chain.
- `getRootAbility()` or recursive target checks where Forge exposes them.

When the client supplied targets before the Forge play path starts, `sa.targets.isEmpty()` is the stable gate: if targets are already present, do not ask Forge to choose them again.

## 4. Castable Abilities

Card spells, alternative costs, and zone-cast options should flow through the shared cast helper, not direct `card.getSpells()` scans.

Use `getAllCastableAbilities(card, player)` when you need Forge's castable SA list. It expands additional and alternative costs with `GameActionUtil`, handles special cast states, sets the activating player, and filters by Forge legality.

Library candidates come from Forge's `PlayerZone.getCardsPlayerCanActivate(player)`. That query limits inspection to the current top card and preserves the permission's player identity. The ordinary priority catalog and zone-cast projection retain the supplied ability, including life payment, while Forge still owns land-play limits and cost affordability.

Use `chooseCastAbility(card, player)` when you only need the best current cast candidate.

Use `CastRails` when an action needs protocol fields for a named cast rail such as plot, foretell, disturb, escape, warp, or sneak. The rail table is the shared source for action emission and action submission.

Granted alternate costs retain their Forge keyword definition and granting
static ability. Resolve their protocol identity through
`CardRepository.grantedKeywordAbilityGrpId`: native metadata uses the granting
card's hidden keyword row with the same cost, while the Forge catalog uses a
stable generated keyword-definition identity. The offer, chosen-cast marker,
and sacrifice trigger use that same identity even after the hand-only grant
stops applying.

Continuous keyword grants use known keyword identities first, then the Forge
catalog's generated definition identity. The source card's
`grantedKeywordAbilityIds` supplies names and reminder text to embedding clients.
Keyword grants keep a separate persistent row for each recipient so
recipient and source removal retire the corresponding grant.

Forge catalog localization reads unbound keyword definitions, not live recipient
keywords. Recipient-dependent `ManaCost` grants keep a symbolic reminder and
keyword name, including any miracle cost reduction. They do not advertise a
concrete mana cost. Forge resolves and pays the effective cost only after the
keyword is bound to its recipient during gameplay.

Calculated `N` keyword amounts retain their catalog identity and localize as
symbolic `X` amounts. Forge applies `CalcKeywordN` to the live recipient before
constructing its effective keyword.

Use `getNonManaActivatedAbilities(card, player)` and `getPlayableManaAbilities(card, player)` for ability lookup. Both set the activating player before legality-sensitive checks.

Activated abilities come from the card's current state. An ability on another
transforming face is neither active nor inactive on the current face. Explicit
special actions such as turning a face-down card face up retain their own lookup.

Abilities copied by continuous effects retain `SpellAbility.originalAbility`.
`AbilityRegistry.identitySource` selects that definition's card catalog, while
`forSpellAbility` resolves its original definition identity. Actions and object
ability rows use the same recipient-local unique identity for each live copy.
The copying static remains the source of the grant, and the retained live
ability remains the execution handle.

Spells that can target both stack objects and permanents use Forge’s list-choice
callback. The bridge keeps both candidate kinds explicit and excludes zone
headings from selectable options; the generic choice default must not choose
a target. Cancellation returns through the ordinary spell rollback path.

## 5. Legality Versus Affordability

`SpellAbility.canPlay()` answers legality, not mana affordability.

It checks timing, zone restrictions, activator restrictions, activation limits, phase restrictions, and similar rule gates. A legal ability can still be unpayable. Opponent-turn priority stops therefore require payable mana for non-mana activations as well as casts. See the `priority-cycling` acceptance suite for paired available and unavailable response windows.

For mana affordability, call:

```kotlin
ComputerUtilMana.canPayManaCost(sa, player, 0, false)
```

Wrap it defensively. Some exotic costs can throw; an exception should mean "not currently payable" at action-emission time.

Normal action building pattern:

1. Set `activatingPlayer`.
2. Check `canPlay()`.
3. Check `ComputerUtilMana.canPayManaCost(...)`.
4. Emit active or inactive action with the right cost fields.

Shared priority visibility and player controls are described in
[Priority flow](priority-flow.md). Inactive actions remain presentation data;
they do not require a visible priority decision.

## 6. Mana And Costs

For cast actions, use the effective Forge cost, not printed card data, whenever a live `SpellAbility` exists. Effective cost applies raises and reductions through Forge's `CostAdjustment` pipeline.

For activated abilities, use `SpellAbility.payCosts.totalMana` unless the mechanic has a specific reason to use the cast-cost pipeline.

Phyrexian symbols and `PayLifeInsteadOf:B` permit a player choice for each symbol.
`ComputerUtilMana` normally prefers mana and uses life as a fallback. An explicit
payment plan removes selected life shards before automatic mana payment and
temporarily sets `AIPhyrexianPayment=Never` to preserve selected mana choices.
Life is committed only after the remaining mana succeeds. The original policy
is restored in `finally`; failed payment uses Forge's existing mana rollback.
Commander tax remains generic mana in the effective cost.

For mana color translation, use `ManaColorMapping`. Forge's color bitmasks and the client mana ordinals are not the same domain.

For land color production:

- Check `manaPart.isComboMana` first.
- Combo sources use `manaPart.getComboColors(sa)`.
- Single-color sources use `manaPart.origProduced`.
- Split produced tokens on spaces, not characters.

Manual mana activation remains a player decision when its source has non-mana
costs. Pay those costs through the active controller's
`getCostDecisionMaker(...)` and `CostPayment.payCost(...)` so sacrifice and
other choices reach the frontend. Reserve `AiCostDecision` and
`payComputerCosts(...)` for automatic AI payment paths.

## 7. Cost Payment Decisions

Forge cost payment uses visitor-style cost parts. `CostDecision` is the bridge point for interactive non-mana cost decisions.

Use the existing cost-decision path when the engine is paying a cost and asks for cards or permanents. Do not invent a parallel resolver from protocol input to game mutation. The bridge should collect a choice, return Forge objects to the cost visitor, and let Forge perform the payment.

Optional additional costs should be selected before `PlaySpellAbility.playAbility(...)` runs, then fed back into Forge through `GameActionUtil.addOptionalCosts(...)`.
When no casting-time choice is stored, decline optional additional costs. This
also applies to free casts offered while another ability resolves.

## 8. Events Versus Snapshots

Forge events are best for "what caused this?" facts:

- cast, resolve, fizzle, mana payment
- card changed zones and why
- damage source
- attachment lifecycle
- token creation
- controller change
- shuffle, scry, surveil

Snapshots are best for "what is true now?" facts:

- zones and object visibility
- live power/toughness and counters
- continuous effects
- keyword grants
- designations
- persistent annotation baselines

Continuous hand inspection follows `Card.mayPlayerLook` through
`CardSnapshot.mayLookSeatIds`. Hand zones retain their private owner-only default,
while permitted card objects list their owner and current viewers. When permission
ends, the viewer's committed full-state inventory conceals the hand identity
while preserving its slot and retires inaccessible linked faces from Diff updates. Temporary public hand reveals retain
their existing precedence.

Do not infer a cause from snapshots when a Forge event can carry it. Do not store a parallel mutable truth when a snapshot can read the current Forge state.

When an upstream Forge event lacks the payload needed for protocol translation, prefer a small fork-local event enrichment over correlating unrelated events after the fact. The event should carry the Forge object IDs needed by the bridge, not protocol instance IDs.

Imprint display is persistent state, so the snapshot reads the source's current
`imprintedCards` membership after resolution. Projection includes only battlefield
sources and cards still in exile. Rebuilding this feed retires a display when the
relationship ends, without treating every exile within an Imprint operation as
an imprinted card. Imprint rows omit the temporary-return marker.

### Atomic imported places

Puzzle-based state import has one protected pre-settle hook immediately before
its final `checkStateEffects`. Specialized importers may complete current-state
restoration there while the stack is resolving and triggers are suppressed.
Keep the default hook empty so ordinary puzzles retain their existing behavior.

Restore relationships only after object identities and visible types exist.
Restore survival abilities and current characteristics before marked damage,
then combat and phase, and allow one final settled-state check. Do not call
state-based actions or start an advisor from inside a partially imported place.
Verification must be object-aware: creature power/toughness fields do not define
planeswalker or other noncreature characteristics.

## 9. Prompt Semantics

`PromptSemantic` is the planner-facing contract between an engine callback and
a protocol prompt shape. A producer resolves it through `PromptRouteResolver`
when constructing `PromptRequest`; the request stores one immutable
`ResolvedPromptRoute` for the full pending interaction. Its `semantic`
accessor is diagnostic data derived from that route.

Use an explicit semantic when the Forge callback does not uniquely imply the wire type. Avoid relying on fallback classification by message text or by "candidate refs exist" unless the prompt is truly generic targeting.

Adding a semantic means updating the enum and the resolver's exhaustive route
catalog, then the mapping docs that describe the Forge callback to protocol
prompt relation. Classifiers, builders, re-prompts, and response handlers
consume the bound route rather than maintaining semantic lookup tables.

## 10. Identity

Forge card IDs are engine identity. Client instance IDs are protocol identity. Keep them separate.

Event-layer types should carry Forge IDs. Resolve Forge IDs to instance IDs at mapping time, when the current frame has the right allocation and zone-transfer context.

When a prompt records target or source identity while a spell is still on the stack, freeze the instance ID if later resolution would move the source and change the normal lookup result.

## 11. Tests

Use the smallest test surface that exercises the concept:

- Pure mapper or annotation tests for deterministic translation logic.
- Match harness tests when the Forge callback and session bridge both matter.
- Integration tests when phase progression, priority, or multi-step engine state matters.

For board setup, pick the Forge API that matches the test intent:

- `player.playLand(land, true, null)` for testing land play itself.
- `game.action.moveToPlay(...)` for a raw move that should not fire land-play events.
- Harness setup helpers for pre-existing board state where no event should fire.

## Ascend state

Forge owns Ascend acquisition and the lasting `Player.hasBlessing()` flag.
Seat snapshots project that flag as a player designation, independent of the
source permanent. The Ascend badge groups battlefield sources by controller,
reports the current permanent count, and retains its identity across blessing
acquisition. Source references resolve after zone identity changes. The
`ascend` acceptance suite covers threshold acquisition, retention below ten,
and conditional Saproling stats.
