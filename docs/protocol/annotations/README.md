---
summary: "Annotation family overview: transient event rows and persistent state rows carried inside a GSM, with every described annotation type grouped by family."
read_when:
  - "finding the page or role of a GRE annotation type"
  - "emitting or consuming annotations on either rail"
  - "choosing which annotation types accompany a game event"
---
# Annotations

Annotations explain what changed in a GSM and what durable state applies to
its objects. `annotations` carries one-shot event rows; `persistentAnnotations`
carries durable state. A persistent row is sent when it is created or changes,
and stays in force until its ID is listed in
`diffDeletedPersistentAnnotationIds`; a Diff GSM does not repeat unchanged
rows. A type can appear on either rail or both;
the rail belongs to each emission.

## Reading a row

Every row is an `AnnotationInfo` with an `id`, one or more `type` values, an
optional `affectorId`, a list of `affectedIds` and a list of typed `details`.

- `affectorId` names the source of the event or state: a game object, a seat,
  a zone, or an identity that exists only for the lifetime of one effect.
  Some rows have no affector.
- `affectedIds` names what the event or state applies to. Depending on the
  type it holds instance IDs, seat numbers or zone IDs.
- `details` are key/value pairs. A key's value is a typed list, so a single
  value is a one-element list.
- One row can carry several types. A continuous effect, for example, is often
  one persistent row typed both `LayeredEffect` and the specific modification.

The same number can belong to different identity domains in different fields.
Interpret an ID through the field and type that carry it. Instance IDs change
when an object changes zones, and `ObjectIdChanged` records each change. A row
names an object by the ID that is current in the GSM carrying it, unless a
detail key is defined to hold an earlier ID, such as `orig_id` or
`ReplacementSourceZcid`.

Transient rows exist only in the GSM that carries them. A persistent row keeps
its `id` while it lives; publishing a row again under the same `id` updates it,
and listing its `id` in `diffDeletedPersistentAnnotationIds` retires it.

## Types by family

Rail is the rail a type uses: transient, persistent, or both. Detail keys are
the keys every row of the type carries; branch-specific keys are described on
the type's page. Types without a page are described only by their row.

### Zones and identity

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| [ZoneTransfer](ZoneTransfer.md) | transient | `zone_src`, `zone_dest`, `category` | An object moved from one zone to another; `category` names the cause. |
| [ObjectIdChanged](ObjectIdChanged.md) | transient | `orig_id`, `new_id` | An object received a new instance ID, usually because it changed zones. |
| [EnteredZoneThisTurn](EnteredZoneThisTurn.md) | persistent | none | The objects that entered a zone during the current turn, keyed by that zone. |
| `TokenCreated` | transient | none | Tokens were created; `affectedIds` are the new token instance IDs. |
| `TokenDeleted` | transient | none | A token left the battlefield; `affectedIds` are the removed token instance IDs. |
| `Shuffle` | transient | `OldIds`, `NewIds` | A library was shuffled; maps the instance IDs before the shuffle to the IDs after it. |
| `ControllerChanged` | both | none | A control-changing effect: a transient row for the change and a persistent row for the ongoing effect, which carries effect identity such as `effect_id`. |
| `DisplayCardUnderCard` | persistent | `Disable` | Relates a card displayed under a host card (`affectorId`) to that host, such as a card exiled by a permanent. `TemporaryZoneTransfer` marks a card that will return. |
| `PhasedIn` | transient | none | An object phased in. |
| `PhasedOut` | transient | none | An object phased out. |
| [TemporaryPermanent](TemporaryPermanent.md) | persistent | `AbilityGrpId` | A permanent that a named ability will remove when its effect ends. |
| [FaceDown](FaceDown.md) | persistent | `REASON`, `abilityGrpId` | An object is face down; `REASON` names the face-down path and `abilityGrpId` its shared mechanic ability. |

### Reveals and visibility

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| `RevealedCardCreated` | transient | none | A card from a hidden zone became revealed. |
| `RevealedCardDeleted` | transient | none | A reveal ended, normally because the card moved to its destination. |
| [CardRevealed](CardRevealed.md) | persistent | `source_zone` | A card is revealed to one or both players from a hidden zone. |
| `InstanceRevealedToOpponent` | persistent | none | A card has been revealed to the opponent and stays marked as known. |

### Actions, stack and resolution

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| [UserActionTaken](UserActionTaken.md) | transient | `actionType`, `abilityGrpId` | A seat performed an action: cast, play, activate and similar. `actionType` names the kind; `abilityGrpId` the ability used. |
| [AbilityInstanceCreated](AbilityInstanceCreated.md) | transient | `source_zone` | An ability instance was created from a source object, including mana abilities that never use the stack. |
| [AbilityInstanceDeleted](AbilityInstanceDeleted.md) | transient | none | An ability instance ended: it resolved, was countered or left the stack. |
| [ResolutionStart](ResolutionStart.md) | transient | `grpid` | A spell or ability began resolving. |
| [ResolutionComplete](ResolutionComplete.md) | transient | `grpid` | A spell or ability finished resolving. |
| [TriggeringObject](TriggeringObject.md) | persistent | `source_zone` | Links a triggered ability instance to the object that triggered it. |
| [DelayedTriggerAffectees](DelayedTriggerAffectees.md) | persistent | `abilityGrpId` | The objects a delayed triggered ability will act on, such as a card exiled until it returns. |
| `MultistepEffectStarted` | transient | `SubCategory` | A multi-step effect such as scry began; `SubCategory` names the effect kind. |
| `MultistepEffectComplete` | transient | `SubCategory` | The matching multi-step effect finished. |
| `PredictedDirectDamage` | persistent | `value` | The damage a damage-dealing stack object will deal on resolution, after modifiers such as kicker or X. Self-affecting: the stack object is both affector and affected. |
| `TurnPermanent` | transient | `turned` | A seat took a special action specific to one permanent. |
| [CastingTimeOption](CastingTimeOption.md) | both | `type` | How a spell was cast: alternative cost, kicker, additional cost or X. A record of the decision, not the prompt. |

### Targets and choices

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| [TargetSpec](TargetSpec.md) | persistent | `abilityGrpId`, `index`, `promptParameters`, `promptId` | The targets chosen for a spell or ability: which ability, which target slot, and optional damage distribution. |
| `PlayerSelectingTargets` | transient | none | A seat began choosing targets for a stack object. Carries no target data. |
| `PlayerSubmittedTargets` | transient | none | A seat finished choosing targets for a stack object. |
| [ChoiceResult](ChoiceResult.md) | transient | `Choice_Value`, `Choice_Sentiment` | The result of a player choice made during resolution. |
| `ObjectsSelected` | persistent | `playerId` | A seat committed a selection for an effect; `affectedIds` holds the chosen object, or `0` for an implicit selection. |
| `GroupedIds` | persistent | `group_id` | One row per group, such as a pile in a pile split; `affectedIds` are the group's members. |
| `SelectNdecoration` | transient | `affected_objects` | The object set a selection applies to. |
| `Scry` | transient | `topIds`, `bottomIds` | A scry result: the instance IDs kept on top and those put on the bottom. |
| `ObjectsToDistinguish` | persistent | none | A source-defined set of objects; the row keeps its `id` while `affectedIds` changes. |
| [LinkInfo](LinkInfo.md) | persistent | `LinkType` | Links a permanent to a choice or associated object, such as a chosen color or a linked card; `LinkType` selects the kind. |
| `CoinFlip` | transient | `CoinFlipResult` | The result of a coin flip. |

### Combat, damage and life

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| [DamageDealt](DamageDealt.md) | transient | `damage`, `type`, `markDamage` | A source dealt damage to a player or object; `type` classifies the damage. |
| [DamagedThisTurn](DamagedThisTurn.md) | persistent | none | Objects dealt damage during the current turn. |
| `ModifiedLife` | transient | `life` | A player's life total changed by the signed amount in `life`. |
| [ReplacementEffect](ReplacementEffect.md) | both | `grpid` | Links a replacement ability or lifecycle to the object whose event it modifies. |
| `ReplacementEffectApplied` | transient | `grpid`, `IsDamageReplacement` | A replacement effect applied to damage; it accompanies the resulting damage event. |
| [SyntheticEvent](SyntheticEvent.md) | transient | `type` | A server-generated event marker that accompanies an effect's result, such as life loss; `type` selects the kind. |

### Mana and costs

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| [ManaPaid](ManaPaid.md) | transient | `id`, `color` | Mana from a source was spent on a spell or ability. |
| `ManaDetails` | persistent | none | Riders on mana in a pool, such as spending restrictions or mana that does not empty. |
| `ColorProduction` | persistent | `colors` | The colors of mana a permanent can produce. |
| `UseOrCostsManaCost` | persistent | `OrCost` | A card whose cost offers paying mana instead of a non-mana action; `OrCost` is the alternative mana cost as a packed symbol string such as `1B`. |
| `DynamicAbility` | persistent | `action_cost_string`, `cost`, `base_grpid`, `grpid` | Grants a card a generated action entry with an explicit cost. |
| `ModifiedCost` | persistent | `effect_id` | A continuous effect modified a card's mana cost. |

### Turn structure

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| `NewTurnStarted` | transient | none | A new turn began for the seat in `affectedIds`. |
| [PhaseOrStepModified](PhaseOrStepModified.md) | transient | `phase`, `step` | The game entered a new phase or step. |
| `LoyaltyActivationsRemaining` | persistent | `ActivationsRemaining` | Loyalty-ability activations a planeswalker has left this turn. |

### Continuous effects

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| `LayeredEffect` | both | `effect_id` | An active continuous effect on an object. Usually shares its row with the specific modification type. |
| [LayeredEffectCreated](LayeredEffectCreated.md) | transient | none | A layered effect began; the row's identity matches the effect's `effect_id`. |
| `LayeredEffectDestroyed` | transient | none | A layered effect ended, for example because its duration expired or its source left. |
| [AddAbility](AddAbility.md) | persistent | `UniqueAbilityId`, `grpid`, `originalAbilityObjectZcid`, `effect_id` | A continuous effect grants an ability or keyword to an object. |
| [RemoveAbility](RemoveAbility.md) | persistent | `effect_id` | A continuous effect removes abilities from an object. |
| [ModifiedType](ModifiedType.md) | persistent | `effect_id` | A continuous effect changed an object's types, subtypes or supertypes. |
| `ModifiedColor` | persistent | `color`, `modificationType`, `effect_id` | A continuous effect changed an object's color; `modificationType` distinguishes setting from adding. |
| `ModifiedName` | persistent | `effect_id` | A continuous effect changed an object's name. |
| `TextChange` | persistent | `effect_id` | A continuous effect changed an object's rules text. |
| `ModifiedPower` | both | none | An object's power is modified; counter-based rows carry `counter_type` and `count`, effect-based rows carry `effect_id`. |
| `ModifiedToughness` | both | none | An object's toughness is modified, with the same branches as `ModifiedPower`. |
| `PowerToughnessModCreated` | transient | `power`, `toughness` | A direct power/toughness modification began, such as a +2/+2 pump. |
| `SuppressedPowerAndToughness` | persistent | none | An object's ordinary power and toughness are suppressed, optionally replaced by `power` and `toughness`. |
| `CopiedObject` | persistent | `copyFromGrpid`, `LayeredEffectType`, `effect_id` | An object is a copy of the card definition in `copyFromGrpid`. |
| `MiscContinuousEffect` | persistent | none | A continuous effect on a player, such as maximum hand size, extra turns or additional land plays. |
| [Qualification](Qualification.md) | persistent | `SourceParent`, `grpid`, `QualificationSubtype`, `QualificationType` | A restriction or permission on an object, such as "can't block". |
| `DisqualifiedEffect` | transient | none | A continuous effect stopped applying, for example because its condition is no longer met. |

### Attachments

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| `Attachment` | persistent | none | An attachment relationship, such as an Aura or Equipment attached to a creature. |
| `AttachmentCreated` | transient | none | An attachment began; accompanies the persistent `Attachment` row. |
| `RemoveAttachment` | transient | none | An attachment ended; `invalidating_grpid`, when present, names the ability that ended it. |

### Counters and object or player state

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| [TappedUntappedPermanent](TappedUntappedPermanent.md) | transient | `tapped` | A permanent became tapped (`tapped` is `1`) or untapped (`0`). |
| `Counter` | persistent | `count`, `counter_type` | The current number of counters of one kind on an object or player, including a player's poison total. |
| `CounterAdded` | transient | `counter_type`, `transaction_amount` | Counters were added; `transaction_amount` is the change and `Counter` carries the result. |
| `CounterRemoved` | transient | `counter_type`, `transaction_amount` | Counters were removed; `transaction_amount` is the change and `Counter` carries the result. |
| `AbilityExhausted` | persistent | `UniqueAbilityId`, `UsesRemaining`, `AbilityGrpId` | An activated ability with limited uses, and how many remain. |
| `ClassLevel` | persistent | `Level` | The current level of a Class. |
| `CrewedThisTurn` | persistent | none | A Vehicle crewed this turn, related to the creatures that crewed it. |
| `SaddledThisTurn` | persistent | none | A Mount saddled this turn, related to the creatures that saddled it. |
| `GamewideHistoryCount` | persistent | `count`, `AbilityGrpId` | A game-wide count tracked for a player and the ability that uses it. |
| `SupplementalText` | persistent | `AbilityWordName`, `value` | A named value associated with a source card and a player. |
| [AbilityWordActive](AbilityWordActive.md) | both | `AbilityWordName` | An ability word condition or tracked value on an object, such as threshold or raid. `AbilityWordName` is an open set. |
| `DungeonStatus` | persistent | `CurrentDungeon`, `CurrentDungeonZCID`, `CurrentRoom`, `AllDungeonsCompleted` | A player's current dungeon, room and completed dungeons. |

### Designations

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| `Designation` | persistent | `DesignationType` | A designation on a player, object or the game, such as the monarch or day and night. |
| `GainDesignation` | transient | `DesignationType` | A designation was gained; accompanies the persistent `Designation` row. |
| `LoseDesignation` | transient | `DesignationType` | A designation was lost. |

### Game result

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| `LossOfGame` | both | `reason` | A player lost the game; `reason` names the cause. |
| `WinTheGame` | persistent | `gameEffectCardGrpId`, `reason` | A card effect made a player win the game; `affectorId` is the effect source and `affectedIds` the winning seat. |

### Presentation hints

| Annotation | Rail | Detail keys always present | Role |
|---|---|---|---|
| `HighlightReason` | transient | `Reason` | Why an object is highlighted. Several identical rows can appear in one GSM. |
| `ShouldntPlay` | transient | `Reason` | A card the player is advised not to play, with the reason. |

## Types without a description

The schema also defines `BoonInfo`, `ChoosingAttachments`, `CopyException`,
`CreateAttachment`, `DamageSource`, `DieRoll`, `GenericRelationship`, `Haunt`,
`HiddenInformation`, `LinkedDamage`, `LoopCount`, `MultiplayerNumericAid`,
`PendingEffect`, `PermanentRegenerated`, `ReferencedCardNames`,
`ReferencedObjects`, `RegeneratePending`, `RemainingSelections`, `SuspendLike`,
`SwitchPowerToughness`, `TokenImmediatelyDied` and `Vote`. This reference does
not describe them yet.
