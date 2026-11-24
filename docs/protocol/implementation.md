---
summary: "Implementation map: Leyline owner, status and known exceptions for each protocol entity, plus the shapes Leyline defines itself."
read_when:
  - "checking whether Leyline meets a protocol page's Contract today"
  - "reviewing emission that differs from a protocol page"
  - "adding or changing a Contract statement or a Leyline protocol owner"
---
# Implementation map

This page records how Leyline meets the [protocol reference](README.md). It is
the only place protocol pages' Leyline status appears. One row per entity names
its owner and status. Exceptions list only the statements or branches where
Leyline does something other than the page describes. Everything not listed as
an exception is intended to hold.

Proof coverage is not stored here. `just docs-lint` computes it from
`protocol:` citations in tests and conformance contracts and lists statements
no proof cites yet.

## Status vocabulary

Entity status:

| Status | Meaning |
|---|---|
| `implemented` | Leyline emits or accepts every branch with Contract statements, with no exceptions. |
| `partial` | Leyline implements the entity; at least one exception applies. |
| `compat-only` | Leyline uses the entity only through Leyline-defined shapes or deliberate alternatives. |
| `absent` | Leyline never emits or accepts the entity. |

Exception kinds:

| Kind | Meaning |
|---|---|
| `diverges` | Leyline emits or accepts something the Contract does not allow. A defect. |
| `compat` | A deliberate alternative: Leyline takes a different shape to keep play working where the contract is unknown or cannot be met. Not a defect; reviewers do not report it. |
| `absent` | Leyline never produces the branch or statement. A gap, not a wrong shape. |

## Entities

Rows are grouped by family and sorted by entity name.

### Model and stream

| Entity | Owner | Status |
|---|---|---|
| [AnnotationOrdering](stream/annotation-ordering.md) | `OrderRules`, `ObjectIdChangedFirstRule`, `SameCardIncrementalRule`, `ResolutionLifecycleRule`, `ResolveTransferOrderingRule`, `PhaseOrStepFirstRule`, `AnnotationPipeline` | `partial` |
| [Enums](model/enums.md) | `PlayerMapper.mapPhase`, `PlayerMapper.mapStep`, `AnnotationBuilder.userActionTaken` | `implemented` |
| [IdentityAndZones](model/identity-and-zones.md) | `ZoneIds`, `ZoneMapper`, `StateZoneProjection`, `ZoneHandoffProjection`, `StateMapper` | `partial` |
| [Messages](stream/messages.md) | `BundleBuilder`, `GsmBuilder`, `StateMapper`, `LifecycleMessageMaterializer`, `SettledPromptOwner`, `MatchConnection` | `partial` |
| [Priority](stream/priority.md) | `PriorityPolicyRuntime`, `MatchCutCoordinator`, `MatchActionWindowRuntime` | `partial` |
| [Timers](stream/timers.md) | `PlayerMapper.buildTimers`, `BundleBuilder.timerStart`, `BundleBuilder.timerStop` | `partial` |

### Objects

| Entity | Owner | Status |
|---|---|---|
| [Ability](object_types/Ability.md) | `ObjectMapper.buildAbilityObject`, `ZoneMapper.addStackAbilitiesFromSnapshot` | `partial` |
| [Adventure](object_types/Adventure.md) | `LinkedFaceCompanionProjector`, `ObjectMapper.buildLinkedFaceObject` | `implemented` |
| [Card](object_types/Card.md) | `ObjectMapper.buildFromSnapshot`, `LinkedFaceCompanionProjector`, `CardProtoBuilder` | `implemented` |
| [RevealedCard](object_types/RevealedCard.md) | `ObjectMapper.buildRevealedCardProxy`, `RevealStateContributor`, `StateMapper` | `partial` |
| [Token](object_types/Token.md) | `ObjectMapper.buildFromSnapshot`, `AnnotationBuilder.tokenCreated`, `AnnotationBuilder.tokenDeleted` | `implemented` |
| [TriggerHolder](object_types/TriggerHolder.md) | `ObjectMapper.buildTriggerHolderObject`, `StateMapper` | `implemented` |

### Actions

| Entity | Owner | Status |
|---|---|---|
| [Activate_Mana](actions/Activate_Mana.md) | `ActivatedActionEmitter`, `ManaSourcePaymentMaterializer`, `ActionPerformer` | `partial` |
| [Cast](actions/Cast.md) | `ActionMapper`, `CastDisplayCost`, `CastRails`, `CastingTimeOptionsBuilder` | `implemented` |
| [CastAdventure](actions/CastAdventure.md) | `ActionMapper.buildAdventureAction`, `TransferAnnotations.castSpellEventAnnotations` | `partial` |
| [Special](actions/Special.md) | `ActionMapper`, `CardStateDesignations`, `ActionPerformer` | `partial` |

### Prompts

| Entity | Owner | Status |
|---|---|---|
| [SelectNReq](prompts/SelectNReq.md) | `CardSelectWindowMaterializer`, `StaticChoiceWindowMaterializer`, `RevealChoiceWindowMaterializer`, `SelectNEnvelope`, `SettledPromptOwner` | `partial` |

### Annotations

| Entity | Owner | Status |
|---|---|---|
| [ReplacementEffect](annotations/ReplacementEffect.md) | `AnnotationBuilder.replacementEffect`, `BlockingInteractionMaterializer.etbPayLifeOptional`, `EtbReplacementEffectKind` | `partial` |

## Exceptions

Each exception is written so it can be filed as an issue unchanged: what the
contract says, what Leyline does, and the owner to change. Exceptions are
grouped by family, then by entity.

### Model and stream exceptions

#### AnnotationOrdering

- **`diverges`** — [Identity before reference](stream/annotation-ordering.md#identity-before-reference)
  - Contract: `LayeredEffectCreated` and `ChoiceResult` rows that name a new
    ID come before its `ObjectIdChanged`.
  - Leyline: moves `ObjectIdChanged` ahead of every row that names its
    `new_id`, including those rows.
  - Owner: `ObjectIdChangedFirstRule`
- **`diverges`** — [Incremental changes to one object](stream/annotation-ordering.md#incremental-changes-to-one-object)
  - Contract: when one resolution changes an object in several steps, the
    rows follow the order of those steps.
  - Leyline: reorders every same-object row into the precedence table's
    order, even for changes made one after another.
  - Owner: `SameCardIncrementalRule`

#### IdentityAndZones

- **`diverges`** — [IdentityAndZones.limbo-retires](model/identity-and-zones.md#IdentityAndZones.limbo-retires)
  - Contract: a GSM that retires a visible object's ID re-sends that old ID's
    `GameObjectInfo` in Limbo.
  - Leyline: lists the old ID in Limbo but keeps its `GameObjectInfo` only for
    staged moves; ordinary moves send no object for it.
  - Owner: `ZoneTransferDetector`, `ZoneHandoffProjection`
- **`compat`** — [Open points](model/identity-and-zones.md#open-points), Graveyard and Exile member order
  - Contract: the order of Graveyard and Exile members is not yet known.
  - Leyline: lists Graveyard and Exile members newest first.
  - Owner: `ZoneMapper.clientOrderedZone`

#### Messages

- **`absent`** — [Messages.undo-full](stream/messages.md#Messages.undo-full)
  - Contract: an undo rewinds to an earlier state through a `Full` GSM with
    `update=Undo`.
  - Leyline: never emits `update=Undo` and does not handle `UndoReq`, so a
    player cannot undo, although some prompts set `allowUndo`.
  - Owner: `MatchConnection`
- **`absent`** — [Messages.queued-shape](stream/messages.md#Messages.queued-shape),
  [Messages.queued-closure](stream/messages.md#Messages.queued-closure),
  [Messages.queued-other-seat](stream/messages.md#Messages.queued-other-seat)
  - Contract: the other seat's still-cancellable commitment is published on
    `QueuedGameStateMessage` before a regular GSM closes it.
  - Leyline: never sends `QueuedGameStateMessage`. The builder exists but has
    no production caller; the recipient sees the other seat's cast only in the
    regular GSM that commits it.
  - Owner: `BundleBuilder.queuedGameState`
- **`absent`** — [Forced actions](stream/messages.md#forced-actions)
  - Contract: an action the server applies on a seat's behalf is reported as
    an `EdictalMessage`.
  - Leyline: never sends `EdictalMessage`. The builder exists but has no
    production caller.
  - Owner: `BundleBuilder.edictalPass`
- **`diverges`** — [Messages.pending-count](stream/messages.md#Messages.pending-count)
  - Contract: `pendingMessageCount` counts every message after the GSM in the
    array, including a `PromptReq` before the decision message.
  - Leyline: sets `pendingMessageCount=1` when it sends a coin-flip or
    starting-player `PromptReq` before the request, so the count is one short.
  - Owner: `BundleBuilder`, `GsmBuilder.embedActions`
- **`diverges`** — [Messages.action-rail-envelope](stream/messages.md#Messages.action-rail-envelope)
  - Contract: the GSM action rail carries no `Pass` or `FloatMana` rows.
  - Leyline: copies every action of the following `ActionsAvailableReq` onto
    the GSM rail, including `Pass` and `FloatMana` rows stripped to their
    action type.
  - Owner: `ActionMapper.stripActionForGsm`, `GsmBuilder.embedActions`
- **`absent`** — [Messages.action-rail-opponent](stream/messages.md#Messages.action-rail-opponent) *(provisional statement)*
  - Contract: the GSM action rail is board state for both seats, restated
    whether or not the recipient holds priority.
  - Leyline: fills the rail only on GSMs that carry or echo a priority
    decision, and only with that decision's actions, stamped with the
    recipient's or the priority player's seat. Opponent actions never appear.
  - Owner: `GsmBuilder.embedActions`, `StateMapper`

#### Priority

- **`diverges`** — [Priority.yield-turn](stream/priority.md#Priority.yield-turn) *(provisional statement)*
  - Contract: when a turn yield ends with its turn, the next settings
    response carries `autoPassOption=Clear`.
  - Leyline: restores `autoPassOption=ResolveMyStackEffects` when the yield
    ends.
  - Owner: `PriorityPolicyRuntime`

#### Timers

- **`diverges`** — [Timers.state-message-seat](stream/timers.md#Timers.state-message-seat)
  - Contract: every timer in a `TimerStateMessage` is one of the named seat's
    `timerIds`.
  - Leyline: the priority timer message always uses timer ID `1`. Seat 2's
    `timerIds` is `[2]`, so the message names seat 1's timer.
  - Owner: `BundleBuilder.timerStart`, `BundleBuilder.timerStop`
- **`compat`** — [Open points](stream/timers.md#open-points), the `Decision` timer type
  - Contract: the role of `TimerType` `Decision` is not yet known.
  - Leyline: sends the priority timer as a `Decision` timer with behavior
    `Timeout`, while GSMs list one `Inactivity` timer per seat.
  - Owner: `BundleBuilder.timerStart`, `PlayerMapper.buildTimers`
- **`absent`** — [Timeout lifecycle](stream/timers.md#timeout-lifecycle)
  - Contract: charged timeouts are reported by `TimeoutMessage`, and
    `gameInfo` carries the match timeout policy.
  - Leyline: never sends `TimeoutMessage` and sets none of `maxTimeoutCount`,
    `maxPipCount` or `timeoutDurationSec`.
  - Owner: `PlayerMapper.buildTimers`

### Object exceptions

#### Ability

- **`absent`** — [Zone and visibility](object_types/Ability.md#zone-and-visibility), abilities in Pending
  - Contract: abilities can wait in the Pending zone, for example triggers
    the player is ordering.
  - Leyline: places every `Ability` object on the Stack and never uses
    Pending.
  - Owner: `ZoneMapper.addStackAbilitiesFromSnapshot`

#### RevealedCard

- **`diverges`** — [RevealedCard.source-zone](object_types/RevealedCard.md#RevealedCard.source-zone)
  - Contract: only a card revealed from a hand gets a `RevealedCardCreated`
    row and a Revealed zone membership.
  - Leyline: emits `RevealedCardCreated` and lists the `RevealedCard` in the Revealed
    zone for every reveal, whatever its source zone.
  - Owner: `RevealStateContributor`, `StateMapper`
- **`diverges`** — [RevealedCard.created](object_types/RevealedCard.md#RevealedCard.created)
  - Contract: `RevealedCardCreated` carries the revealed card's instance ID as
    both `affectorId` and `affectedIds`.
  - Leyline: the reveal path for cards revealed to the opponent sets only
    `affectedIds`. The path for a permanent bounced to its owner's hand sets
    both.
  - Owner: `RevealStateContributor`, `AnnotationBuilder.revealedCardCreated`

### Action exceptions

#### Activate_Mana

- **`compat`** — [Open points](actions/Activate_Mana.md#open-points), `isBatchable` and single-color `manaSelections`
  - Contract: when `isBatchable` is false, and whether a fixed-color ability
    carries `manaSelections`, is not yet known.
  - Leyline: sets `isBatchable` false only for abilities whose mana is
    decided later, such as special mana; sends `manaSelections` for every
    ability that names its colors, including single-color ones.
  - Owner: `ActivatedActionEmitter`

#### CastAdventure

- **`compat`** — [CastAdventure.no-grp-id](actions/CastAdventure.md#CastAdventure.no-grp-id)
  - Contract: the offer carries no `grpId`.
  - Leyline: adds the permanent face's `grpId` to the
    `ActionsAvailableReq` offer and strips it from the GSM rail, so the two
    rails disagree on that field.
  - Owner: `ActionMapper.buildAdventureAction`

#### Special

- **`diverges`** — [Special.foretell-plot](actions/Special.md#Special.foretell-plot)
  - Contract: foretelling or plotting a card from the hand is offered as a
    `Special` action carrying the foretell or plot ability.
  - Leyline: offers both as a `Cast` with `alternativeGrpId`, then records
    the accepted foretell as `UserActionTaken` `actionType` `7`. The offer and
    the accepted row disagree on the action type.
  - Owner: `ActionMapper`, `CastRails`

### Prompt exceptions

#### SelectNReq

- **`absent`** — [SelectNReq.trigger-order](prompts/SelectNReq.md#SelectNReq.trigger-order),
  [SelectNReq.trigger-order-arbitrary](prompts/SelectNReq.md#SelectNReq.trigger-order-arbitrary)
  - Contract: simultaneous triggers controlled by one player are ordered
    through a `SelectNReq` with `TriggeredAbility` and `Stacking` over the
    pending abilities, and the client may ask for automatic ordering.
  - Leyline: never emits the trigger-ordering family. Forge's existing order
    is kept without a prompt, so the player cannot order their triggers. The
    owner's comment says the protocol has no prompt for this decision, which
    the contract contradicts.
  - Owner: `ClientGuiGame.order`
- **`diverges`** — [SelectNReq.mana-color](prompts/SelectNReq.md#SelectNReq.mana-color) *(provisional statement)*
  - Contract: a color choice made while a mana ability resolves uses
    `ManaFromAbility` with `Resolution`.
  - Leyline: every static choice uses `Resolution` for both contexts,
    including the color choice of a triggered mana ability such as Fertile
    Ground's additional mana.
  - Owner: `StaticChoiceWindowMaterializer.buildRequest`

### Annotation exceptions

#### ReplacementEffect

- **`absent`** — [Variants](annotations/ReplacementEffect.md#variants)
  - Contract: replacement events carry transient rows, and pending
    replacements carry persistent rows.
  - Leyline: emits `ReplacementEffect` only on the pay-life entry branch. No
    other replacement effect produces a row on either rail.
  - Owner: `BlockingInteractionMaterializer.etbPayLifeOptional`
- **`compat`** — [Open points](annotations/ReplacementEffect.md#open-points), other choices made before a permanent enters
  - Contract: unknown whether choices other than the pay-life entry choice are
    bracketed by a persistent row.
  - Leyline: publishes those choices, for example an optional copy entry, as an
    `OptionalActionMessage` sourced from the host card, with no
    `ReplacementEffect` row.
  - Owner: `PlayerController.confirmReplacementEffect`

## Leyline-defined shapes

Shapes Leyline emits that no protocol page defines. They are normative for
Leyline's heads only. A reviewer checks a change against the description here,
not against a protocol page.

### Forge-text cost labels

A host can choose how cast-time cost choices are labelled for the whole
match. The default, `Native`, uses the client's prompt IDs. `ForgeText`, used
by the Web head, adds a `Cost` prompt parameter of type `NonLocalizedString`
carrying Forge's description of the cost:

- on each optional-cost row of a `CastingTimeOptionsReq`, such as kicker or
  offspring, as the row's own `prompt`;
- on a `ChooseOrCost` branch choice, as one `Cost` parameter per branch on
  the inner `SelectNReq` prompt, aligned with its one-based `ids`, in place
  of the `PromptId` parameters `Native` sends.

The text is a label. It is not a recomputed total cost, and the row identity,
`ctoId` and response handling are the same in both modes.

- Owner: `CostChoicePresentation`, `CastingTimeOptionsBuilder`

### Vote

A vote is sent as a modal `CastingTimeOptionsReq`: one `Modal` option per
choice, selected by its `grpId`, and an outer prompt with a
`NonLocalizedString` parameter `choiceKind` set to `vote`. A vote has
`allowCancel=No` and no undo, and records no selected-mode marker on the
source. When abstaining is allowed the request's minimum is `0`, and an empty
response, or a timeout, abstains.

- Owner: `ModalChoiceWindowMaterializer`, `ModalChoiceWindowCapture`, `MatchModalChoiceRuntime`

### Entity choice through SelectTargetsReq

A Forge choice among game entities that is not targeting and has no client
prompt, such as choosing permanents and players to proliferate, or a choice
among creatures and players, is sent through the `SelectTargetsReq`
lifecycle. Candidates are the shown, selectable permanents by instance ID and
players by seat ID. An optional choice accepts an empty submission. Because
nothing is being targeted, the state carries no `PlayerSelectingTargets` or
`PlayerSubmittedTargets` rows.

- Owner: `MatchCompatibilityCostSelectionRuntime`, `MatchTargetingInteractionRuntime`, `BundleBuilder`

### Named label choice

A Forge choice between labels that has no client prompt, such as a named
option or a finite list of numbers, is sent as a `SelectNReq` with
`Resolution` for both contexts, `listType=Dynamic`,
`idType=PromptParameterIndex` and one-based `ids`. The inner prompt carries
one `NonLocalizedString` parameter named `Choice` per option, aligned with
`ids`. The response selects the option by its index.

- Owner: `StaticChoiceWindowMaterializer.buildRequest`, `StaticChoiceWindowCapture`

## Maintaining this map

When a protocol page adds, removes or changes a Contract statement, or a
change moves the code that emits an entity, update the entity's row and
exceptions in the same change.

1. **Find the owner from code.** For an annotation, the builder that adds its
   `AnnotationType` and the code that retires persistent rows. For a prompt,
   the code that builds the request and the code that admits its response.
   Name classes, or `Class.member` when one member owns the behavior. The
   lint checks every owner symbol exists in main code.
2. **Check each Contract statement against the owner.** Read the code path
   that produces each field, identity and ordering the statement names.
3. **Record only exceptions.** A statement the code meets needs no entry. A
   branch the code never produces is `absent`; a shape the contract rules out
   is `diverges`; a deliberate alternative is `compat`. Never delete a
   protocol statement to clear an exception.
4. **Cite proof where it exists.** Add `protocol: <ID>` to a test or
   conformance contract only when that check would fail if the statement were
   violated. Code reading is not proof; it leaves the statement uncovered.
5. **Set the entity status** from the exceptions.
