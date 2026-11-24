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
| [DisturbBack](object_types/DisturbBack.md) | `ZoneMapper.addDisturbBackObject`, `ObjectMapper.buildDisturbBackObject`, `StateMapper` | `partial` |
| [Emblem](object_types/Emblem.md) | `ObjectMapper.buildFromSnapshot`, `EmblemSnapshot` | `implemented` |
| [MDFCBack](object_types/MDFCBack.md) | `LinkedFaceCompanionProjector` | `absent` |
| [Omen](object_types/Omen.md) | `LinkedFaceCompanionProjector`, `ObjectMapper.buildLinkedFaceObject` | `implemented` |
| [RevealedCard](object_types/RevealedCard.md) | `ObjectMapper.buildRevealedCardProxy`, `RevealStateContributor`, `StateMapper` | `partial` |
| [RoomLeft](object_types/RoomLeft.md) | `LinkedFaceCompanionProjector` | `absent` |
| [RoomRight](object_types/RoomRight.md) | `LinkedFaceCompanionProjector` | `absent` |
| [SplitLeft](object_types/SplitLeft.md) | `LinkedFaceCompanionProjector` | `absent` |
| [SplitRight](object_types/SplitRight.md) | `LinkedFaceCompanionProjector` | `absent` |
| [Token](object_types/Token.md) | `ObjectMapper.buildFromSnapshot`, `AnnotationBuilder.tokenCreated`, `AnnotationBuilder.tokenDeleted` | `implemented` |
| [TriggerHolder](object_types/TriggerHolder.md) | `ObjectMapper.buildTriggerHolderObject`, `StateMapper` | `implemented` |

### Actions

| Entity | Owner | Status |
|---|---|---|
| [Activate_Mana](actions/Activate_Mana.md) | `ActivatedActionEmitter`, `ManaSourcePaymentMaterializer`, `ActionPerformer` | `partial` |
| [Cast](actions/Cast.md) | `ActionMapper`, `CastDisplayCost`, `CastRails`, `CastingTimeOptionsBuilder` | `implemented` |
| [CastAdventure](actions/CastAdventure.md) | `ActionMapper.buildAdventureAction`, `TransferAnnotations.castSpellEventAnnotations` | `partial` |
| [CastOmen](actions/CastOmen.md) | `ActionMapper.buildOmenAction`, `TransferAnnotations.castSpellEventAnnotations` | `partial` |
| [CastRight](actions/CastRight.md) | `ActionMapper.addSplitCastActions`, `TransferAnnotations.castSpellEventAnnotations` | `absent` |
| [MDFC](actions/MDFC.md) | `ActionMapper.addMdfcFaceActions`, `TransferAnnotations.castSpellEventAnnotations` | `partial` |
| [Rooms](actions/Rooms.md) | `ActionMapper.addRoomCastActions`, `CardStateDesignations` | `partial` |
| [Special](actions/Special.md) | `ActionMapper`, `CardStateDesignations`, `ActionPerformer` | `partial` |
| [Special_TurnFaceUp](actions/Special_TurnFaceUp.md) | `ActionMapper.addSpecialTurnFaceUpActions` | `partial` |

### Designations

| Entity | Owner | Status |
|---|---|---|
| [AnimatedLand](designations/AnimatedLand.md) | `EarthbendEmitter`, `AnnotationBuilder.manaCreatureDesignation` | `partial` |
| [DayNight](designations/DayNight.md) | `DayNightDesignationKind`, `AnnotationBuilder.dayNightDesignation`, `AnnotationBuilder.gainDesignationOnGame`, `AnnotationBuilder.loseDesignationOnGame` | `partial` |
| [PlayerSpeed](designations/PlayerSpeed.md) | `AnnotationBuilder.playerSpeedDesignation`, `PlayerSpeedDesignationKind`, `StateMapper` | `implemented` |
| [Plotted](designations/Plotted.md) | `CardStateDesignations`, `AnnotationBuilder.plottedDesignation`, `PlottedDesignationKind` | `partial` |
| [Prepared](designations/Prepared.md) | `CardStateDesignations`, `AnnotationBuilder.preparedDesignation`, `PreparedDesignationKind`, `PersistentFeedBuilder` | `implemented` |
| [Saddled](designations/Saddled.md) | `CardStateDesignations`, `AnnotationBuilder.saddledDesignation`, `SaddledDesignationKind` | `implemented` |
| [Solved](designations/Solved.md) | `CardStateDesignations`, `AnnotationBuilder.solvedDesignation`, `SolvedDesignationKind` | `implemented` |
| [Suspected](designations/Suspected.md) | `CardStateDesignations`, `AnnotationBuilder.suspectedDesignation`, `AnnotationPipeline` | `implemented` |

### Prompts

| Entity | Owner | Status |
|---|---|---|
| [ActionsAvailableReq](prompts/ActionsAvailableReq.md) | `ActionMapper`, `BundleBuilder`, `BlockingInteractionMaterializer`, `ActionPerformer`, `MatchActionWindowRuntime.claimPriority` | `partial` |
| [AssignDamageReq](prompts/AssignDamageReq.md) | `BlockingInteractionMaterializer.damage`, `CombatHandler.onAssignDamage`, `MatchBlockingInteractionRuntime.submitDamageCommand` | `partial` |
| [CastingTimeOptionsReq](prompts/CastingTimeOptionsReq.md) | `CastingTimeOptionsBuilder`, `ModalChoiceWindowMaterializer`, `DeferredCastCostPlanMaterializer`, `DeferredCastWindowRuntime`, `MatchModalChoiceRuntime` | `partial` |
| [ChooseStartingPlayerReq](prompts/ChooseStartingPlayerReq.md) | `LifecycleMessageMaterializer.initialBundle`, `GsmBuilder.buildInitialGameState`, `MulliganHandler` | `partial` |
| [DeclareAttackersReq](prompts/DeclareAttackersReq.md) | `RequestBuilder.buildDeclareAttackersReq`, `BundleBuilder`, `CombatHandler` | `partial` |
| [DeclareBlockersReq](prompts/DeclareBlockersReq.md) | `RequestBuilder.buildDeclareBlockersReq`, `CombatHandler.onDeclareBlockers` | `partial` |
| [DistributionReq](prompts/DistributionReq.md) | `DistributionWindowMaterializer`, `MatchDistributionInteractionRuntime` | `partial` |
| [GroupReq](prompts/GroupReq.md) | `GroupingWindowMaterializer`, `MatchGroupingInteractionRuntime`, `GsmBuilder.buildGroupReq`, `MulliganHandler.onGroupResp` | `partial` |
| [IntermissionReq](prompts/IntermissionReq.md) | `BundleBuilder.prepareGameOverBundle`, `MatchGameOverRuntime` | `partial` |
| [NumericInputReq](prompts/NumericInputReq.md) | `BlockingInteractionMaterializer.numeric`, `NumericInputHandler` | `partial` |
| [OptionalActionMessage](prompts/OptionalActionMessage.md) | `BlockingInteractionMaterializer`, `OptionalActionGate`, `MatchCutCoordinator.submitOptionalAnswer` | `partial` |
| [OrderReq](prompts/OrderReq.md) | `OrderWindowMaterializer`, `MatchOrderInteractionRuntime` | `partial` |
| [PayCostsReq](prompts/PayCostsReq.md) | `OneShotPayCostsMaterializer`, `OneShotPayCostsWindowCapture`, `ManaSourcePaymentMaterializer`, `GatherCountersWindowMaterializer`, `MatchOneShotPayCostsRuntime` | `partial` |
| [SearchFromGroupsReq](prompts/SearchFromGroupsReq.md) | `SearchWindowMaterializer`, `MatchSearchInteractionRuntime` | `partial` |
| [SearchReq](prompts/SearchReq.md) | `SearchWindowMaterializer`, `RequestBuilder.buildSearchRequest`, `MatchSearchInteractionRuntime` | `partial` |
| [SelectNReq](prompts/SelectNReq.md) | `CardSelectWindowMaterializer`, `StaticChoiceWindowMaterializer`, `RevealChoiceWindowMaterializer`, `SelectNEnvelope`, `SettledPromptOwner` | `partial` |
| [SelectReplacementReq](prompts/SelectReplacementReq.md) | `ReplacementWindowMaterializer`, `MatchReplacementInteractionRuntime` | `partial` |
| [SelectTargetsReq](prompts/SelectTargetsReq.md) | `TargetingWindowMaterializer`, `TargetingWindowCapture`, `TargetingCoordinator`, `TargetingHandler` | `partial` |

### Annotations

| Entity | Owner | Status |
|---|---|---|
| [AbilityInstanceCreated](annotations/AbilityInstanceCreated.md) | `AnnotationBuilder.abilityInstanceCreated`, `TransferAnnotations`, `AnnotationPipeline`, `FrameIdResolver.manaAbilityForgeId` | `partial` |
| [AbilityInstanceDeleted](annotations/AbilityInstanceDeleted.md) | `AnnotationBuilder.abilityInstanceDeleted`, `TransferAnnotations`, `AnnotationPipeline` | `implemented` |
| [AbilityWordActive](annotations/AbilityWordActive.md) | `AnnotationBuilder.abilityWordActive`, `AbilityWordScanner`, `AbilityWordTriggerRecognizers`, `PersistentAbilityWordFeedBuilder`, `ConvokeContributor` | `partial` |
| [AddAbility](annotations/AddAbility.md) | `AnnotationBuilder.addAbilityLayered`, `AnnotationBuilder.addAbilityPacked`, `AnnotationBuilder.addAbilityMulti`, `MechanicAnnotations` | `implemented` |
| [CardRevealed](annotations/CardRevealed.md) | `AnnotationBuilder.cardRevealed`, `RevealStateContributor`, `CardRevealedKind` | `partial` |
| [CastingTimeOption](annotations/CastingTimeOption.md) | `AnnotationBuilder.castingTimeOption`, `AnnotationBuilder.castingTimeOptionKicker`, `AnnotationBuilder.castingTimeOptionAdditionalCost`, `AnnotationBuilder.castingTimeOptionChosenCost`, `AnnotationBuilder.castingTimeOptionChooseX`, `TransferAnnotations` | `partial` |
| [ChoiceResult](annotations/ChoiceResult.md) | `AnnotationBuilder.choiceResult`, `AnnotationPipeline`, `MatchStaticChoiceInteractionRuntime`, `MatchCardSelectInteractionRuntime` | `partial` |
| [CoinFlip](annotations/CoinFlip.md) | `AnnotationBuilder.coinFlip`, `AnnotationPipeline`, `BundleBuilder`, `GameEventCollector` | `partial` |
| [CopiedObject](annotations/CopiedObject.md) | — | `absent` |
| [DamageDealt](annotations/DamageDealt.md) | `AnnotationBuilder.damageDealt`, `CombatAnnotations`, `GameEventCollector` | `implemented` |
| [DamagedThisTurn](annotations/DamagedThisTurn.md) | `AnnotationBuilder.damagedThisTurn`, `CombatAnnotations`, `PersistentAnnotationStore` | `partial` |
| [DelayedTriggerAffectees](annotations/DelayedTriggerAffectees.md) | `AnnotationBuilder.delayedTriggerAffectees`, `PersistentTemporaryFeedBuilder` | `partial` |
| [DungeonStatus](annotations/DungeonStatus.md) | `AnnotationBuilder.dungeonStatus`, `PersistentFeedBuilder` | `compat-only` |
| [EnteredZoneThisTurn](annotations/EnteredZoneThisTurn.md) | `AnnotationBuilder.enteredZoneThisTurn`, `TransferAnnotations.annotationsForTransfer`, `EnteredZoneThisTurnKind` | `partial` |
| [FaceDown](annotations/FaceDown.md) | `AnnotationBuilder.faceDownPersistent`, `PersistentFeedBuilder` | `partial` |
| [InstanceRevealedToOpponent](annotations/InstanceRevealedToOpponent.md) | `AnnotationBuilder.instanceRevealedToOpponent`, `RevealStateContributor`, `InstanceRevealedToOpponentKind` | `partial` |
| [LayeredEffectCreated](annotations/LayeredEffectCreated.md) | `AnnotationBuilder.layeredEffectCreated`, `AnnotationBuilder.layeredEffectDestroyed`, `AnnotationBuilder.layeredEffect` | `implemented` |
| [LinkInfo](annotations/LinkInfo.md) | `AnnotationBuilder.linkInfoChoice`, `AnnotationBuilder.linkInfoActivatedAbility`, `PersistentFeedBuilder`, `LinkInfoChoiceKind`, `LinkInfoStackAbilityKind` | `partial` |
| [LoyaltyActivationsRemaining](annotations/LoyaltyActivationsRemaining.md) | — | `absent` |
| [ManaPaid](annotations/ManaPaid.md) | `AnnotationBuilder.manaPaid`, `TransferAnnotations`, `GameEventCollector` | `partial` |
| [MiscContinuousEffect](annotations/MiscContinuousEffect.md) | — | `absent` |
| [ModifiedCost](annotations/ModifiedCost.md) | — | `absent` |
| [ModifiedType](annotations/ModifiedType.md) | `AnnotationBuilder.modifiedTypeLayeredEffect`, `AnnotationBuilder.earthbendModifiedTypeLayeredEffect`, `VehicleAttachContributor`, `EarthbendEmitter`, `ModifiedTypeForCrewKind` | `partial` |
| [ObjectIdChanged](annotations/ObjectIdChanged.md) | `AnnotationBuilder.objectIdChanged`, `ZoneTransferDetector`, `TransferAnnotations.annotationsForTransfer`, `AnnotationOrderEnforcer` | `implemented` |
| [PhaseOrStepModified](annotations/PhaseOrStepModified.md) | `AnnotationBuilder.phaseOrStepModified`, `AnnotationBuilder.newTurnStarted`, `AnnotationPipeline`, `StateProjectionCompiler` | `implemented` |
| [PhasedIn](annotations/PhasedIn.md) | `AnnotationBuilder.phasedPermanent`, `AnnotationPipeline` | `compat-only` |
| [PhasedOut](annotations/PhasedOut.md) | `AnnotationBuilder.phasedPermanent`, `AnnotationPipeline`, `StateZoneProjection` | `compat-only` |
| [Qualification](annotations/Qualification.md) | `AnnotationBuilder.qualification`, `PersistentFeedBuilder`, `QualificationKind`, `QualificationType` | `partial` |
| [RemoveAbility](annotations/RemoveAbility.md) | `AnnotationBuilder.removeAbility`, `MechanicAnnotations.abilityRemovalAnnotations`, `ObjectMapper` | `implemented` |
| [ReplacementEffect](annotations/ReplacementEffect.md) | `AnnotationBuilder.replacementEffect`, `BlockingInteractionMaterializer.etbPayLifeOptional`, `EtbReplacementEffectKind` | `partial` |
| [ResolutionComplete](annotations/ResolutionComplete.md) | `AnnotationBuilder.resolutionComplete`, `TransferAnnotations.annotationsForTransfer` | `implemented` |
| [ResolutionStart](annotations/ResolutionStart.md) | `AnnotationBuilder.resolutionStart`, `TransferAnnotations.annotationsForTransfer`, `StateProjectionCompiler`, `AnnotationPipeline` | `partial` |
| [ShouldntPlay](annotations/ShouldntPlay.md) | `AnnotationBuilder.redundantActivation`, `VehicleAttachContributor` | `partial` |
| [SyntheticEvent](annotations/SyntheticEvent.md) | `AnnotationBuilder.syntheticEvent`, `CombatAnnotations`, `AnnotationPipeline` | `partial` |
| [TappedUntappedPermanent](annotations/TappedUntappedPermanent.md) | `AnnotationBuilder.tappedUntappedPermanent`, `TransferAnnotations`, `MechanicAnnotations`, `AnnotationPipeline` | `implemented` |
| [TargetSpec](annotations/TargetSpec.md) | `AnnotationBuilder.targetSpec`, `TargetSpecContributor`, `TargetSpecKind` | `implemented` |
| [TemporaryPermanent](annotations/TemporaryPermanent.md) | `AnnotationBuilder.temporaryPermanent`, `PersistentTemporaryFeedBuilder` | `implemented` |
| [TriggeringObject](annotations/TriggeringObject.md) | `AnnotationBuilder.triggeringObject`, `AnnotationPipeline`, `TriggeringObjectProjection.shouldEmit` | `partial` |
| [UserActionTaken](annotations/UserActionTaken.md) | `AnnotationBuilder.userActionTaken`, `TransferAnnotations` | `implemented` |
| [ZoneTransfer](annotations/ZoneTransfer.md) | `AnnotationBuilder.zoneTransfer`, `ZoneTransferDetector`, `TransferAnnotations.annotationsForTransfer` | `partial` |

Annotation types without a page have no Contract to check. This table records
only whether Leyline emits each one and which builder owns it. A type moves to
the table above when its page is written.

| Type | Owner | Emission |
|---|---|---|
| `AbilityExhausted` | `AnnotationBuilder.abilityExhausted` | emitted |
| `Attachment` | `AnnotationBuilder.attachment` | emitted |
| `AttachmentCreated` | `AnnotationBuilder.attachmentCreated` | emitted |
| `ClassLevel` | — | `absent` |
| `ColorProduction` | `AnnotationBuilder.colorProduction` | emitted |
| `ControllerChanged` | `AnnotationBuilder.controllerChanged`, `AnnotationBuilder.controllerChangedEffect` | emitted |
| `Counter` | `AnnotationBuilder.counter`, `AnnotationBuilder.playerCounter` | emitted |
| `CounterAdded` | `AnnotationBuilder.counterAdded`, `AnnotationBuilder.playerCounterAdded` | emitted |
| `CounterRemoved` | `AnnotationBuilder.counterRemoved`, `AnnotationBuilder.playerCounterRemoved` | emitted |
| `CrewedThisTurn` | `AnnotationBuilder.crewedThisTurn` | emitted |
| `Designation` | `AnnotationBuilder.designation` | emitted |
| `DisplayCardUnderCard` | `AnnotationBuilder.displayCardUnderCard` | emitted |
| `DisqualifiedEffect` | — | `absent` |
| `DynamicAbility` | — | `absent` |
| `GainDesignation` | `AnnotationBuilder.gainDesignation` | emitted |
| `GamewideHistoryCount` | — | `absent` |
| `GroupedIds` | — | `absent` |
| `HighlightReason` | — | `absent` |
| `LayeredEffect` | `AnnotationBuilder.layeredEffect` | emitted |
| `LayeredEffectDestroyed` | `AnnotationBuilder.layeredEffectDestroyed` | emitted |
| `LoseDesignation` | `AnnotationBuilder.loseDesignation` | emitted |
| `LossOfGame` | `AnnotationBuilder.lossOfGame` | emitted |
| `ManaDetails` | `AnnotationBuilder.manaDetails` | emitted |
| `ModifiedColor` | — | `absent` |
| `ModifiedLife` | `AnnotationBuilder.modifiedLife` | emitted |
| `ModifiedName` | — | `absent` |
| `ModifiedPower` | `AnnotationBuilder.modifiedPower`, `AnnotationBuilder.layeredEffect`, `AnnotationBuilder.counter` | emitted |
| `ModifiedToughness` | `AnnotationBuilder.modifiedToughness`, `AnnotationBuilder.layeredEffect`, `AnnotationBuilder.counter` | emitted |
| `MultistepEffectComplete` | — | `absent` |
| `MultistepEffectStarted` | — | `absent` |
| `NewTurnStarted` | `AnnotationBuilder.newTurnStarted` | emitted |
| `ObjectsSelected` | — | `absent` |
| `ObjectsToDistinguish` | — | `absent` |
| `PlayerSelectingTargets` | `AnnotationBuilder.playerSelectingTargets` | emitted |
| `PlayerSubmittedTargets` | `AnnotationBuilder.playerSubmittedTargets` | emitted |
| `PowerToughnessModCreated` | `AnnotationBuilder.powerToughnessModCreated` | emitted |
| `PredictedDirectDamage` | `AnnotationBuilder.predictedDirectDamage` | emitted |
| `RemoveAttachment` | `AnnotationBuilder.removeAttachment` | emitted |
| `ReplacementEffectApplied` | — | `absent` |
| `RevealedCardCreated` | `AnnotationBuilder.revealedCardCreated` | emitted |
| `RevealedCardDeleted` | `AnnotationBuilder.revealedCardDeleted` | emitted |
| `SaddledThisTurn` | `AnnotationBuilder.saddledThisTurn` | emitted |
| `Scry` | `AnnotationBuilder.scry` | emitted |
| `SelectNdecoration` | `AnnotationBuilder.selectNDecoration` | emitted |
| `Shuffle` | `AnnotationBuilder.shuffle` | emitted |
| `SupplementalText` | — | `absent` |
| `SuppressedPowerAndToughness` | `AnnotationBuilder.suppressedPowerAndToughness` | emitted |
| `TextChange` | — | `absent` |
| `TokenCreated` | `AnnotationBuilder.tokenCreated` | emitted |
| `TokenDeleted` | `AnnotationBuilder.tokenDeleted` | emitted |
| `TurnPermanent` | — | `absent` |
| `UseOrCostsManaCost` | — | `absent` |
| `WinTheGame` | — | `absent` |

### Mechanics

A mechanic row names the code that owns the mechanic-specific behavior. Shapes
shared with other mechanics are covered by the entity rows above. A mechanic
Leyline cannot play at all is `absent`.

| Entity | Owner | Status |
|---|---|---|
| [Afterlife](mechanics/Afterlife.md) | `AnnotationPipeline`, `TriggeringObjectProjection`, `AnnotationBuilder.tokenCreated` | `implemented` |
| [Ascend](mechanics/Ascend.md) | `PersistentAbilityWordFeedBuilder`, `PersistentFeedBuilder`, `AnnotationBuilder.citysBlessingDesignation` | `implemented` |
| [BattleCry](mechanics/BattleCry.md) | `MechanicAnnotations`, `AnnotationPipeline` | `partial` |
| [Boast](mechanics/Boast.md) | `AnnotationBuilder.abilityExhausted`, `ActivatedActionEmitter` | `implemented` |
| [Cascade](mechanics/Cascade.md) | `PlayerController.playSaFromPlayEffect`, `BlockingInteractionMaterializer`, `StackAbilityGrpIdResolver` | `partial` |
| [CollectEvidence](mechanics/CollectEvidence.md) | `PersistentAbilityWordFeedBuilder`, `PersistentFeedFactsCapture`, `TransferCategoryResolver` | `implemented` |
| [Companion](mechanics/Companion.md) | `CompanionAction`, `ActionMapper`, `PersistentFeedBuilder` | `implemented` |
| [Convoke](mechanics/Convoke.md) | `ManaSourcePaymentMaterializer`, `TransferAnnotations`, `PersistentAbilityWordFeedBuilder`, `ConvokeContributor` | `implemented` |
| [Crew](mechanics/Crew.md) | `VehicleAttachContributor`, `AnnotationBuilder.crewedThisTurn`, `OneShotPayCostsMaterializer` | `implemented` |
| [Cycling](mechanics/Cycling.md) | `ActivatedActionEmitter`, `TransferCategoryResolver`, `ZoneMoveLedger` | `implemented` |
| [Discover](mechanics/Discover.md) | `PlayerController.playSaFromPlayEffect`, `BlockingInteractionMaterializer`, `StackAbilityGrpIdResolver` | `implemented` |
| [Disguise](mechanics/Disguise.md) | `CastRails`, `ActionMapper.addSpecialTurnFaceUpActions`, `PersistentFeedBuilder` | `partial` |
| [Disturb](mechanics/Disturb.md) | `CastRails`, `ActionMapper`, `ZoneMapper.addDisturbBackObject` | `implemented` |

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

#### DisturbBack

- **`absent`** — [DisturbBack.every-zone](object_types/DisturbBack.md#DisturbBack.every-zone),
  [DisturbBack.disturb-cast](object_types/DisturbBack.md#DisturbBack.disturb-cast) *(provisional statement)*
  - Contract: a card with a Disturb back face carries its companion in every
    zone the parent is projected in, including the Stack and the Battlefield.
  - Leyline: emits `DisturbBack` only for cards projected in a hand, graveyard,
    sideboard or visible library. A card on the Stack, on the Battlefield or in
    Exile has no companion.
  - Owner: `ZoneMapper.addDisturbBackObject`, `StateMapper`

#### MDFCBack

- **`absent`** — [MDFCBack.back-identity](object_types/MDFCBack.md#MDFCBack.back-identity),
  [MDFCBack.selected-back](object_types/MDFCBack.md#MDFCBack.selected-back),
  [MDFCBack.remap](object_types/MDFCBack.md#MDFCBack.remap)
  - Contract: a visible modal double-faced card projects a back-face companion
    that carries the other face and follows the parent through remaps.
  - Leyline: never emits `MDFCBack`. The projection layer supports only
    `Adventure` and `Omen` companions, so a modal double-faced card shows no
    alternate-face object.
  - Owner: `LinkedFaceCompanionProjector`

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

#### RoomLeft

- **`absent`** — [RoomLeft.pair-together](object_types/RoomLeft.md#RoomLeft.pair-together),
  [RoomLeft.selected-door](object_types/RoomLeft.md#RoomLeft.selected-door),
  [RoomLeft.battlefield-persist](object_types/RoomLeft.md#RoomLeft.battlefield-persist)
  *(provisional statements)*
  - Contract: a Room projects both door companions, the Stack parent adopts the
    cast door's `grpId`, and the pair persists onto the Battlefield when the
    parent instance ID is preserved.
  - Leyline: never emits `RoomLeft` or `RoomRight`. The Room is projected as one
    `Card`; door identity appears only as the stack `grpId` and the door
    designation on the parent.
  - Owner: `LinkedFaceCompanionProjector`

#### RoomRight

- **`absent`** — [RoomRight.pair-together](object_types/RoomRight.md#RoomRight.pair-together),
  [RoomRight.selected-door](object_types/RoomRight.md#RoomRight.selected-door),
  [RoomRight.battlefield-persist](object_types/RoomRight.md#RoomRight.battlefield-persist)
  *(provisional statements)*
  - Contract: a Room projects both door companions, the Stack parent adopts the
    cast door's `grpId`, and the pair persists onto the Battlefield when the
    parent instance ID is preserved.
  - Leyline: never emits `RoomRight` or `RoomLeft`. The Room is projected as one
    `Card`; door identity appears only as the stack `grpId` and the door
    designation on the parent.
  - Owner: `LinkedFaceCompanionProjector`

#### SplitLeft

- **`absent`** — [SplitLeft.pair-together](object_types/SplitLeft.md#SplitLeft.pair-together),
  [SplitLeft.selected-face](object_types/SplitLeft.md#SplitLeft.selected-face)
  *(provisional statement)*
  - Contract: a split card's projection carries both half companions, and the
    Stack parent adopts the selected half's `grpId`.
  - Leyline: never emits `SplitLeft` or `SplitRight`. A split half is projected
    as an ordinary `Card` on the Stack, with the face `grpId` and no companion
    pair.
  - Owner: `LinkedFaceCompanionProjector`

#### SplitRight

- **`absent`** — [SplitRight.pair-together](object_types/SplitRight.md#SplitRight.pair-together),
  [SplitRight.selected-face](object_types/SplitRight.md#SplitRight.selected-face)
  *(provisional statement)*
  - Contract: a split card's projection carries both half companions, and the
    Stack parent adopts the selected half's `grpId`.
  - Leyline: never emits `SplitRight` or `SplitLeft`. A split half is projected
    as an ordinary `Card` on the Stack, with the face `grpId` and no companion
    pair.
  - Owner: `LinkedFaceCompanionProjector`

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

- **`diverges`** — [CastAdventure.request-face](actions/CastAdventure.md#CastAdventure.request-face)
  - Contract: the `ActionsAvailableReq` offer carries `grpId` naming the
    Adventure face and `facetId` naming the parent.
  - Leyline: sets `grpId` to the permanent face and sends no `facetId`.
  - Owner: `ActionMapper.buildAdventureAction`

#### CastOmen

- **`absent`** — [CastOmen.request-face](actions/CastOmen.md#CastOmen.request-face)
  - Contract: the `ActionsAvailableReq` offer carries `grpId` naming the Omen
    face and `facetId` naming the parent.
  - Leyline: sends neither field on `CastOmen`.
  - Owner: `ActionMapper.buildOmenAction`
- **`absent`** — [CastOmen.permission-pair](actions/CastOmen.md#CastOmen.permission-pair) *(provisional statement)*
  - Contract: a cast through a non-hand permission carries `sourceId` and
    `abilityGrpId` together, identifying the permission.
  - Leyline: offers `CastOmen` only from the hand, with `instanceId` and
    `manaCost`; no `CastOmen` offer carries `sourceId` or `abilityGrpId`.
  - Owner: `ActionMapper.buildOmenAction`

#### CastRight

- **`absent`** — [CastRight.source-rail](actions/CastRight.md#CastRight.source-rail),
  [CastRight.accepted](actions/CastRight.md#CastRight.accepted)
  - Contract: a split half is offered as `CastRight` with the half companion in
    `sourceId` and the half's spell ability in `abilityGrpId`, and the accepted
    action is recorded with `actionType` `11`.
  - Leyline: never emits `CastRight` or `CastLeft`. Both halves are offered as
    `Cast` carrying the face `grpId`, and the accepted rows use `actionType`
    `1`.
  - Owner: `ActionMapper.addSplitCastActions`, `TransferAnnotations.castSpellEventAnnotations`

#### MDFC

- **`absent`** — [MDFC.request-face](actions/MDFC.md#MDFC.request-face)
  - Contract: `CastMdfc` and `PlayMdfc` offers on `ActionsAvailableReq` carry
    `grpId` naming the selected face and `facetId` naming the parent.
  - Leyline: sends neither field on either offer.
  - Owner: `ActionMapper.addMdfcFaceActions`
- **`diverges`** — [MDFC.cast-source-pair](actions/MDFC.md#MDFC.cast-source-pair) *(provisional statement)*
  - Contract: a `CastMdfc` offer that carries `sourceId` also carries
    `abilityGrpId`.
  - Leyline: sets `sourceId` to the parent instance on every `CastMdfc` offer
    and adds `abilityGrpId` only when the back-face ability resolves, so an
    offer can carry `sourceId` alone.
  - Owner: `ActionMapper.buildMdfcSpellAction`

#### Rooms

- **`diverges`** — [Rooms.door-discriminator](actions/Rooms.md#Rooms.door-discriminator),
  [Rooms.accepted](actions/Rooms.md#Rooms.accepted)
  - Contract: a door is selected by `actionType` `22` or `23`, and the accepted
    cast is recorded with the same integer.
  - Leyline: offers `CastLeftRoom` and `CastRightRoom`, then records the
    accepted cast as `UserActionTaken` `actionType` `1`, because the cast-event
    mapping recognizes no room branch. The offer and the accepted row disagree
    on the action type.
  - Owner: `TransferAnnotations.castSpellEventAnnotations`, `ActionMapper.addRoomCastActions`
- **`absent`** — [Rooms.request-face](actions/Rooms.md#Rooms.request-face)
  - Contract: a door offer on `ActionsAvailableReq` carries `grpId` naming the
    selected door's face and `facetId` naming the parent.
  - Leyline: sends neither field on door offers.
  - Owner: `ActionMapper.addRoomCastActions`
- **`diverges`** — [Rooms.unlock-offer](actions/Rooms.md#Rooms.unlock-offer),
  [Rooms.unlock-applied](actions/Rooms.md#Rooms.unlock-applied)
  - Contract: a locked door of a Room on the Battlefield is unlocked through a
    `Special` action that applies the designation at once, without the stack.
  - Leyline: offers `CastLeftRoom` or `CastRightRoom` for the Battlefield
    Room's locked door and runs it as a spell through the stack.
  - Owner: `ActionMapper.addRoomCastActions`

#### Special

- **`diverges`** — [Special.foretell-plot](actions/Special.md#Special.foretell-plot)
  - Contract: foretelling or plotting a card from the hand is offered as a
    `Special` action carrying the foretell or plot ability.
  - Leyline: offers both as a `Cast` with `alternativeGrpId`, then records
    the accepted foretell as `UserActionTaken` `actionType` `7`. The offer and
    the accepted row disagree on the action type.
  - Owner: `ActionMapper`, `CastRails`

#### Special_TurnFaceUp

- **`absent`** — [Special_TurnFaceUp.accepted](actions/Special_TurnFaceUp.md#Special_TurnFaceUp.accepted),
  [Special_TurnFaceUp.accepted-ability](actions/Special_TurnFaceUp.md#Special_TurnFaceUp.accepted-ability),
  [Special_TurnFaceUp.turn-transient](actions/Special_TurnFaceUp.md#Special_TurnFaceUp.turn-transient)
  - Contract: an accepted turn-face-up is recorded with `actionType` `8` and
    `alternativeGrpId`, and the event carries a `TurnPermanent` row naming the
    permanent.
  - Leyline: offers the action and applies the turn, but emits no
    `UserActionTaken` for it and no `TurnPermanent` annotation, so the accepted
    turn is visible only through the changed object projection.
  - Owner: `ActionMapper.addSpecialTurnFaceUpActions`
- **`absent`** — [Special_TurnFaceUp.request-identity](actions/Special_TurnFaceUp.md#Special_TurnFaceUp.request-identity)
  - Contract: the `ActionsAvailableReq` offer carries the permanent's `grpId`
    and a `facetId` naming the permanent.
  - Leyline: sends neither field.
  - Owner: `ActionMapper.addSpecialTurnFaceUpActions`
- **`compat`** — [Open points](actions/Special_TurnFaceUp.md#open-points), `alternativeSourceZcid`
  - Contract: whether the source hint is always present, and what it identifies
    when it differs from `instanceId`, is not yet known.
  - Leyline: sets `alternativeSourceZcid` to the permanent's own instance ID on
    every offer.
  - Owner: `ActionMapper.addSpecialTurnFaceUpActions`

### Designation exceptions

#### AnimatedLand

- **`absent`** — [AnimatedLand.persistent-shape](designations/AnimatedLand.md#AnimatedLand.persistent-shape) *(provisional statement)*
  - Contract: a land that currently has creature characteristics carries a
    persistent `Designation` with `DesignationType` `23` and its controlling
    seat.
  - Leyline: emits the designation only for earthbend animation. A land animated
    by any other effect carries no designation.
  - Owner: `EarthbendEmitter`

#### DayNight

- **`diverges`** — [DayNight.flip-in-place](designations/DayNight.md#DayNight.flip-in-place)
  - Contract: a flip rewrites the existing persistent row in place and sends
    only the incoming state's `GainDesignation`.
  - Leyline: sends the outgoing state's `LoseDesignation` beside the incoming
    `GainDesignation`.
  - Owner: `AnnotationBuilder.loseDesignationOnGame`

#### Plotted

- **`compat`** — [Open points](designations/Plotted.md#open-points), the loss row when the exile object is consumed
  - Contract: whether the plotted state emits a loss row when the exile object
    is consumed is not yet known.
  - Leyline: emits `LoseDesignation` for `18` in the GSM where the plotted role
    ends, including the cast that consumes the exile instance.
  - Owner: `CardStateDesignations`, `AnnotationBuilder.loseDesignation`

### Prompt exceptions

#### ActionsAvailableReq

- **`compat`** — [ActionsAvailableReq.permission-window](prompts/ActionsAvailableReq.md#ActionsAvailableReq.permission-window), permissions other than cascade and discover
  - Contract: a cast-permission window, such as casting a card exiled by
    madness, is an `ActionsAvailableReq` with a permission-specific prompt,
    a `CardId` parameter, `allowCancel=No`, and the permitted `Cast` beside
    `Pass`.
  - Leyline: sends the window as a priority request with prompt `1134` only
    for cascade and discover. Every other cast permission granted while an
    effect resolves is an `OptionalActionMessage` sourced from the card.
  - Owner: `PlayerController.playSaFromPlayEffect`, `BlockingInteractionMaterializer`

#### AssignDamageReq

- **`diverges`** — [AssignDamageReq.proposal-total](prompts/AssignDamageReq.md#AssignDamageReq.proposal-total)
  - Contract: the proposal assigns all of `totalDamage`, putting damage left
    over after lethal on the last slot.
  - Leyline: without trample, leaves damage beyond the blockers' lethal
    amounts unassigned, so the proposal sums to less than `totalDamage` and
    fails Leyline's own sum check if returned unchanged.
  - Owner: `BlockingInteractionMaterializer.damage`
- **`diverges`** — [Request `canIgnoreBlockers`](prompts/AssignDamageReq.md#request)
  - Contract: trample assigners carry no `canIgnoreBlockers`.
  - Leyline: sets `canIgnoreBlockers` on every trample assigner.
  - Owner: `BlockingInteractionMaterializer.damage`

#### CastingTimeOptionsReq

- **`diverges`** — [CastingTimeOptionsReq.cost-type](prompts/CastingTimeOptionsReq.md#CastingTimeOptionsReq.cost-type)
  - Contract: bargain and casualty rows use the `Bargain` and `Casualty`
    types.
  - Leyline: sends every optional cost other than kicker as
    `AdditionalCost`, including bargain, casualty, offspring and conspire.
  - Owner: `DeferredCastCostPlanMaterializer`
- **`compat`** — [Value of X](prompts/CastingTimeOptionsReq.md#families)
  - Contract: the value of X is a `ChooseX` row with `numericInputReq` in a
    `CastingTimeOptionsReq` with prompt `51`.
  - Leyline: asks for X with a standalone `NumericInputReq` and never sends
    a `ChooseX` row.
  - Owner: `PlayerController.announceRequirements`

#### ChooseStartingPlayerReq

- **`diverges`** — [ChooseStartingPlayerReq.decider](prompts/ChooseStartingPlayerReq.md#ChooseStartingPlayerReq.decider)
  - Contract: the request is sent to the die-roll winner.
  - Leyline: sends it to seat 2 whatever the winner, so with
    `dieRollWinner=1` the losing seat chooses who starts.
  - Owner: `LifecycleMessageMaterializer.initialBundle`,
    `LifecycleMessageMaterializer.initialBundles`
- **`diverges`** — [ChooseStartingPlayerReq.candidates](prompts/ChooseStartingPlayerReq.md#ChooseStartingPlayerReq.candidates)
  - Contract: the candidate seats are listed as `[1, 2]`.
  - Leyline: lists them as `[2, 1]`.
  - Owner: `LifecycleMessageMaterializer`

#### DeclareAttackersReq

- **`diverges`** — [DeclareAttackersReq.selected-single-row](prompts/DeclareAttackersReq.md#DeclareAttackersReq.selected-single-row)
  - Contract: a declared creature appears in exactly one row, the selected
    one.
  - Leyline: keeps every alternative row of a declared creature that has
    Enlist or Exert, so the request shows the selected row beside the
    creature's other rows.
  - Owner: `RequestBuilder.buildDeclareAttackersReq`
- **`diverges`** — [DeclareAttackersReq.resp-auto](prompts/DeclareAttackersReq.md#DeclareAttackersReq.resp-auto)
  - Contract: `autoDeclare=true` declares every creature with an ordinary
    attack row against `autoDeclareDamageRecipient`.
  - Leyline: ignores `autoDeclareDamageRecipient` and declares every attacker
    against the default defending player, so a planeswalker named there is
    never attacked.
  - Owner: `CombatHandler.onDeclareAttackers`, `DeclarationAnswer`,
    `RuntimeCombatWindow.nextAttackers`
- **`compat`** — [Open points](prompts/DeclareAttackersReq.md#open-points), attack alternatives other than Enlist
  - Contract: unknown which `alternativeGrpId` other alternatives use.
  - Leyline: offers Exert as an extra attacker row whose `alternativeGrpId` is
    Exert's keyword ability ID, in the Enlist row layout.
  - Owner: `RequestBuilder.buildDeclareAttackersReq`

#### DeclareBlockersReq

- **`absent`** — [Blocker row `mustBlock`](prompts/DeclareBlockersReq.md#request)
  - Contract: a blocker row carries `mustBlock` when the creature is
    required to block.
  - Leyline: never sets `mustBlock`.
  - Owner: `RequestBuilder.buildDeclareBlockersReq`

#### DistributionReq

- **`diverges`** — [DistributionReq.resp-bounds](prompts/DistributionReq.md#DistributionReq.resp-bounds)
  - Contract: a response whose total falls outside the allowed amount is not
    applied.
  - Leyline: sums the response amounts as `Int`, so amounts near the `Int`
    limit can wrap to the allowed total and be applied.
  - Owner: `MatchDistributionInteractionRuntime.validate`

#### GroupReq

- **`diverges`** — [GroupReq.source-card](prompts/GroupReq.md#GroupReq.source-card)
  - Contract: for scry and surveil, the `CardId` parameter names the source
    card.
  - Leyline: sends the `CardId` parameter with no value.
  - Owner: `GroupingWindowMaterializer`
- **`absent`** — [Piles](prompts/GroupReq.md#uses)
  - Contract: effects that separate cards into piles use a `GroupReq` with
    `GroupType_Arbitrary` and no `context`.
  - Leyline: sends `GroupReq` only for scry, surveil and the London
    mulligan.
  - Owner: `GroupingWindowCapture`

#### IntermissionReq

- **`diverges`** — [IntermissionReq.draw-result](prompts/IntermissionReq.md#IntermissionReq.draw-result)
  - Contract: a draw uses `intermissionPrompt` `28` with no parameters, no
    `winningTeamId`, and offers only the hand reveal.
  - Leyline: sends a draw with `intermissionPrompt` `27`, a `WinningTeamId`
    parameter of `0`, and both options.
  - Owner: `BundleBuilder.prepareGameOverBundle`

#### NumericInputReq

- **`diverges`** — [NumericInputReq.any-amount](prompts/NumericInputReq.md#NumericInputReq.any-amount)
  - Contract: a number chosen during resolution uses
    `NumericInputType_ChooseAnyAmount` and a prompt specific to the effect.
  - Leyline: sends every number request as `NumericInputType_ChooseX` with
    prompt `51`.
  - Owner: `BlockingInteractionMaterializer.numeric`

#### OptionalActionMessage

- **`diverges`** — [OptionalActionMessage.ability-source](prompts/OptionalActionMessage.md#OptionalActionMessage.ability-source)
  - Contract: when the optional effect belongs to a resolving ability,
    `sourceId` is that ability on the stack and the first `CardId`
    parameter is its source card.
  - Leyline: sets `sourceId` and the `CardId` parameter both to the host
    card, so the ability on the stack is never named.
  - Owner: `OptionalActionGate`, `BlockingInteractionMaterializer`
- **`diverges`** — [OptionalActionMessage.card-parameters](prompts/OptionalActionMessage.md#OptionalActionMessage.card-parameters)
  - Contract: every outer prompt parameter is a `CardId` number.
  - Leyline: adds a `Cost` parameter of type `NonLocalizedString` when the
    optional action is paying a mana cost to prevent an effect.
  - Owner: `BlockingInteractionMaterializer`, `PlayerController.payCostToPreventEffect`
- **`compat`** — [Prompt](prompts/OptionalActionMessage.md#prompt)
  - Contract: the prompt ID is specific to the optional effect.
  - Leyline: uses one generic prompt, `1159`, for every optional effect that
    has no dedicated prompt mapping.
  - Owner: `BlockingInteractionMaterializer`

#### OrderReq

- **`absent`** — [Battlefield ordering](prompts/OrderReq.md#uses)
  - Contract: cards an effect puts onto the battlefield together are ordered
    with an `OrderReq` using prompt `91`.
  - Leyline: sends `OrderReq` only for the top and bottom of a library.
  - Owner: `OrderWindowMaterializer`

#### PayCostsReq

- **`compat`** — [Mana payment](prompts/PayCostsReq.md#mana-payment) for ordinary mana costs
  - Contract: a mana payment can be a `PayCostsReq` offering mana abilities
    as `Activate_Mana` steps, pool mana and `autoTapActionsReq` solutions,
    answered by `PerformAutoTapActionsResp` or one `PerformActionResp` step.
  - Leyline: pays ordinary mana costs from the `autoTapSolution` of the
    priority action and never sends a mana-payment `PayCostsReq` for them.
    It sends the mana family only for convoke, improvise and waterbend, as
    `Make_Payment` steps.
  - Owner: `ManaSourcePaymentMaterializer`, `ActionMapper`
- **`absent`** — [PayCostsReq.auto-tap-index](prompts/PayCostsReq.md#PayCostsReq.auto-tap-index)
  - Contract: a mana payment can propose `autoTapActionsReq` solutions that
    the client accepts with `PerformAutoTapActionsResp`.
  - Leyline: the convoke, improvise and waterbend requests carry no
    `autoTapActionsReq`, and no handler admits `PerformAutoTapActionsResp`.
  - Owner: `ManaSourcePaymentMaterializer`
- **`diverges`** — [PayCostsReq.select-threshold](prompts/PayCostsReq.md#PayCostsReq.select-threshold), collect evidence
  - Contract: a total-weight cost carries the threshold in `minSel`, sets
    `maxSel` to `2147483647`, and leaves `minWeight` at the signed 32-bit
    minimum.
  - Leyline: sends collect evidence with the threshold in `minWeight` and
    the selection-count range in `minSel` and `maxSel`. Total-power tap costs
    already use the contract's shape.
  - Owner: `OneShotPayCostsMaterializer.payCostsRequest`
- **`diverges`** — [PayCostsReq.effect-prompt](prompts/PayCostsReq.md#PayCostsReq.effect-prompt), costs of activated abilities other than tap payments
  - Contract: the `CardId` parameter names the spell or ability being paid
    for, which for an activated ability is the ability on the stack.
  - Leyline: when the cost route supplies no prompt source, as for a
    sacrifice or graveyard-exile cost, the parameter names the card the
    ability belongs to. Spells and tap payments name the right object.
  - Owner: `OneShotPayCostsWindowCapture.freezePromptSource`

#### SearchFromGroupsReq

- **`compat`** — [Request prompt](prompts/SearchFromGroupsReq.md#request)
  - Contract: the prompt ID is specific to the search effect.
  - Leyline: uses `3713` for every grouped search.
  - Owner: `SearchWindowMaterializer`

#### SearchReq

- **`diverges`** — [Prompt](prompts/SearchReq.md#prompt)
  - Contract: a second `CardId` parameter, when present, carries a number the
    instruction uses; it is not a seat.
  - Leyline: always sends a second `CardId` parameter holding the searched
    player's seat ID.
  - Owner: `SearchWindowMaterializer`
- **`compat`** — [Prompt](prompts/SearchReq.md#prompt)
  - Contract: the prompt ID is specific to the search effect.
  - Leyline: uses `1065` for every search except typecycling, which uses
    `11626`.
  - Owner: `SearchWindowMaterializer`

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

#### SelectReplacementReq

- **`diverges`** — [Row identities](prompts/SelectReplacementReq.md#request)
  - Contract: `affectedObject` is the object whose event is replaced, an
    identity separate from `objectInstance`.
  - Leyline: sets `affectedObject` to the host of the replacement ability in
    every row, which is correct only when the replacement affects its own
    host.
  - Owner: `ReplacementWindowMaterializer`

#### SelectTargetsReq

- **`diverges`** — [SelectTargetsReq.groups-together](prompts/SelectTargetsReq.md#SelectTargetsReq.groups-together)
  - Contract: a targeting ability with several target requirements presents
    every requirement's group in each request.
  - Leyline: publishes one requirement per targeting window, so each
    request carries a single group and the requirements are asked one after
    another, each with its own submission.
  - Owner: `TargetingWindowMaterializer`, `TargetingCoordinator`
- **`diverges`** — [SelectTargetsReq.trigger-echo](prompts/SelectTargetsReq.md#SelectTargetsReq.trigger-echo)
  - Contract: after a selection, the echoed request for a triggered ability
    carries `allowCancel=Abort` and `allowUndo=true`.
  - Leyline: fixes the cancellation policy per window, so every echo for a
    triggered ability keeps `allowCancel=No` without `allowUndo`.
  - Owner: `TargetingWindowMaterializer.rePrompt`, `TargetingWindowCapture`

### Annotation exceptions

#### AbilityInstanceCreated

- **`diverges`** — [Lifecycle](annotations/AbilityInstanceCreated.md#lifecycle), instance IDs of mana abilities
  - Contract: a mana ability activation normally names a new instance ID.
  - Leyline: derives the mana ability instance from a surrogate keyed by the
    source card, so every activation of one land or creature names the same
    instance ID for the whole game.
  - Owner: `FrameIdResolver.manaAbilityForgeId`, `TransferAnnotations`

#### AbilityWordActive

- **`diverges`** — [AbilityWordActive.affector-domain](annotations/AbilityWordActive.md#AbilityWordActive.affector-domain) *(provisional statement)*
  - Contract: `Delirium` rows name a seat and `LifeGainedThisTurn` rows name
    the affected card.
  - Leyline: names the affected card for `Delirium`, which it scans per
    card, and the controller's seat for `LifeGainedThisTurn`, which it
    publishes for infusion triggers.
  - Owner: `AbilityWordScanner`, `AbilityWordTriggerRecognizers`
- **`diverges`** — [AbilityWordActive.update-in-place](annotations/AbilityWordActive.md#AbilityWordActive.update-in-place)
  - Contract: a change of `value` republishes the row under the same `id`.
  - Leyline: deletes the row and creates a new one when the value changes,
    for example for `Threshold`, `Delirium` and `LifeGainedThisTurn`.
  - Owner: `AbilityWordActiveKind`

#### CardRevealed

- **`diverges`** — [CardRevealed.one-row-per-view](annotations/CardRevealed.md#CardRevealed.one-row-per-view)
  - Contract: one row per view, with `source_zone` fixed once the outcome is
    known.
  - Leyline: keys the row by its view and replaces it with a new row when
    `source_zone` changes, so a card revealed from the library and then put
    into the hand is named by a library-zone row and then a hand-zone row
    for the same view.
  - Owner: `CardRevealedKind`, `RevealStateContributor`
- **`compat`** — [Variants](annotations/CardRevealed.md#variants), lifecycle choice
  - Contract: most reveals use the unbracketed lifecycle; the selection rule
    is unknown.
  - Leyline: brackets every view with `RevealedCardCreated` and
    `RevealedCardDeleted`, and names only a resolving spell or ability as
    `affectorId`.
  - Owner: `RevealStateContributor`
- **`diverges`** — [CardRevealed.view-affected](annotations/CardRevealed.md#CardRevealed.view-affected)
  - Contract: a row names a view that is new in the row's GSM.
  - Leyline: the replacement row for a changed `source_zone` names the
    existing view.
  - Owner: `CardRevealedKind`

#### CastingTimeOption

- **`absent`** — [Variants](annotations/CastingTimeOption.md#variants), `Casualty`, `Bargain` and `OptionalCostPaid`
  - Contract: casualty, bargain and named optional costs produce rows of
    `type` `15`, `17` and `18`.
  - Leyline: builds rows only for `ChooseX`, `Kicker`, `AdditionalCost`,
    `ChooseOrCost` and `CastThroughAbility`.
  - Owner: `TransferAnnotations`
- **`absent`** — [Variants](annotations/CastingTimeOption.md#variants), ability rows and the transient Kicker row
  - Contract: an activated ability with a chosen X and the triggered ability
    of a kicked permanent carry a persistent row, and a kicked permanent's
    resolution can carry a transient Kicker row.
  - Leyline: builds rows only for spells, while they are on the stack.
  - Owner: `TransferAnnotations`

#### ChoiceResult

- **`diverges`** — [ChoiceResult.before-resolution](annotations/ChoiceResult.md#ChoiceResult.before-resolution) *(provisional statement)*
  - Contract: `ChoiceResult` precedes `ResolutionComplete` in its GSM.
  - Leyline: appends the prompt's choice results after the transfer and
    resolution annotations of the GSM, so a choice made while a spell or
    ability resolves follows its `ResolutionComplete`.
  - Owner: `AnnotationPipeline`
- **`absent`** — [Variants](annotations/ChoiceResult.md#variants), choices outside static-list and card-selection prompts
  - Contract: choices answered through other prompts, such as `PromptReq` or a
    declined optional choice with `Choice_Value` `-1`, also produce a row.
  - Leyline: records a choice result only from a static-list `SelectNReq`
    and from a card-selection `SelectNReq`.
  - Owner: `MatchStaticChoiceInteractionRuntime`, `MatchCardSelectInteractionRuntime`

#### CoinFlip

- **`diverges`** — [CoinFlip.result-code](annotations/CoinFlip.md#CoinFlip.result-code)
  - Contract: a lost flip carries `CoinFlipResult` `2`.
  - Leyline: writes `1` for a won flip and `0` for a lost one. The prompt's
    localization reference is still `48` for a loss.
  - Owner: `GameEventCollector`, `AnnotationPipeline`

#### CopiedObject

- **`absent`** — [CopiedObject](annotations/CopiedObject.md), the entity
  - Leyline: publishes no `CopiedObject` row for any copy effect.

#### DamagedThisTurn

- **`diverges`** — [Lifecycle](annotations/DamagedThisTurn.md#lifecycle), retirement at the turn boundary
  - Contract: the row is normally deleted in the first GSM of the next turn.
  - Leyline: deletes the row when the upkeep step begins, so it survives the
    GSMs of the new turn's untap step.
  - Owner: `CombatAnnotations`, `PersistentAnnotationStore`
- **`diverges`** — [DamagedThisTurn.member-leaves](annotations/DamagedThisTurn.md#DamagedThisTurn.member-leaves) *(provisional statement)*
  - Contract: a member that receives a new instance ID is removed from the
    row in that GSM.
  - Leyline: leaves the row unchanged, so it keeps naming the old instance ID
    until the row is cleared. Such a row breaks the rule in the
    [annotation reference](annotations/README.md) that a row names objects by
    their current ID.
  - Owner: `PersistentAnnotationStore`

#### DelayedTriggerAffectees

- **`absent`** — [Variants](annotations/DelayedTriggerAffectees.md#variants), mana branch and holderless triggers
  - Contract: a delayed mana trigger carries `mana_to_add`, and a trigger
    that fires as it is created starts its row on the ability.
  - Leyline: builds rows only from pending trigger holders, and never writes
    `mana_to_add`.
  - Owner: `PersistentTemporaryFeedBuilder`

#### DungeonStatus

- **`compat`** — [DungeonStatus](annotations/DungeonStatus.md), the entity
  - Leyline: publishes one row per player that has dungeon state, with the
    seat as `affectorId` and `affectedIds`. Each of `CurrentDungeon`,
    `CurrentDungeonZCID` and `CurrentRoom` is an Int32 when its value is
    present and the typed string `"nil"` when it is absent, field by field,
    so `CurrentRoom` can be `"nil"` while a dungeon is active.
    `AllDungeonsCompleted` lists completed dungeon grpIds and is key-only when
    empty. A player without dungeon state has no row. The contract for these
    fields is not established.
  - Owner: `AnnotationBuilder.dungeonStatus`, `PersistentFeedBuilder`

#### EnteredZoneThisTurn

- **`diverges`** — [EnteredZoneThisTurn.one-row-per-zone](annotations/EnteredZoneThisTurn.md#EnteredZoneThisTurn.one-row-per-zone)
  - Contract: one live row per zone; an object entering a zone that already
    has a row is added to that row.
  - Leyline: adds a new single-object row for every transfer, so two objects
    entering the battlefield in one turn, or in one GSM, produce two rows with
    `affectorId` `28`.
  - Owner: `TransferAnnotations.annotationsForTransfer`, `EnteredZoneThisTurnKind`
- **`diverges`** — [Lifecycle](annotations/EnteredZoneThisTurn.md#lifecycle), retirement at the turn boundary
  - Contract: the rows of the ending turn are deleted when the next turn
    starts.
  - Leyline: deletes a row only in the upkeep of its object's controller's
    turn. A creature that enters during its controller's turn stays listed
    through the opponent's whole next turn.
  - Owner: `EnteredZoneThisTurnKind.shouldExpire`
- **`diverges`** — [EnteredZoneThisTurn.zone-affector](annotations/EnteredZoneThisTurn.md#EnteredZoneThisTurn.zone-affector)
  - Contract: every object in a row is in the zone named by `affectorId`.
  - Leyline: an object that passes through exile and returns in one GSM stays
    listed in the exile row under its intermediate ID, now in Limbo.
  - Owner: `TransferAnnotations.annotationsForTransfer`, `EnteredZoneThisTurnKind`

#### FaceDown

- **`absent`** — [FaceDown.disguise-on-stack](annotations/FaceDown.md#FaceDown.disguise-on-stack) *(provisional statement)*
  - Contract: a disguised spell carries its row on the stack, and the row
    stays live when the spell resolves onto the battlefield.
  - Leyline: creates the disguise row only once the face-down permanent is on
    the battlefield.
  - Owner: `PersistentFeedBuilder`
- **`compat`** — [Open points](annotations/FaceDown.md#open-points), cloak
  - Contract: the cloak path's `REASON` and `abilityGrpId` are unknown.
  - Leyline: publishes a cloaked permanent with `REASON` `7` and the cloak
    ability's definition as `abilityGrpId`.
  - Owner: `PersistentFeedBuilder`, `AnnotationConstants`

#### InstanceRevealedToOpponent

- **`diverges`** — [InstanceRevealedToOpponent.not-library](annotations/InstanceRevealedToOpponent.md#InstanceRevealedToOpponent.not-library) *(provisional statement)*
  - Contract: no row names a card in a library.
  - Leyline: records opponent knowledge for a card revealed from a library
    as well, until the card moves or the library is shuffled.
  - Owner: `RevealStateContributor`, `InstanceRevealedToOpponentKind`
- **`diverges`** — [InstanceRevealedToOpponent.retired-on-rename](annotations/InstanceRevealedToOpponent.md#InstanceRevealedToOpponent.retired-on-rename) *(provisional statement)*
  - Contract: the row is deleted in the GSM whose `ObjectIdChanged` renames
    its card.
  - Leyline: keeps some rows after the card is renamed, for example when it
    moves from the hand to the battlefield.
  - Owner: `InstanceRevealedToOpponentKind`

#### LinkInfo

- **`absent`** — [Variants](annotations/LinkInfo.md#variants), `LinkType` `2` for sacrifice costs
  - Contract: an activated ability that sacrifices an object as a cost, such
    as a Clue's or a Food's, carries a row naming the sacrificed object.
  - Leyline: emits `LinkType` `2` only for an activated discover ability,
    naming its source card.
  - Owner: `AnnotationPipeline`
- **`absent`** — [Variants](annotations/LinkInfo.md#variants), `Parity` and `Amount` choices
  - Contract: parity and amount choices are remembered under `LinkType` `3`.
  - Leyline: remembers only a chosen creature type and the first chosen
    color.
  - Owner: `PersistentFeedBuilder`
- **`diverges`** — [LinkInfo.choice-with-result](annotations/LinkInfo.md#LinkInfo.choice-with-result) *(provisional statement)*
  - Contract: the row first appears with the choice's `ChoiceResult`.
  - Leyline: derives the row from the permanent's remembered choice, and
    records a `ChoiceResult` only for a choice made through a prompt, so a
    choice made by a computer-controlled opponent produces the row with no
    `ChoiceResult`.
  - Owner: `PersistentFeedBuilder`, `MatchStaticChoiceInteractionRuntime`

#### LoyaltyActivationsRemaining

- **`absent`** — [LoyaltyActivationsRemaining](annotations/LoyaltyActivationsRemaining.md), the entity
  - Leyline: publishes no `LoyaltyActivationsRemaining` row for any
    planeswalker.

#### ManaPaid

- **`diverges`** — [ManaPaid.color-value](annotations/ManaPaid.md#ManaPaid.color-value) *(provisional statement)*
  - Contract: `color` is a `ManaColor` value, `4` for red and `5` for green.
  - Leyline: writes a color bit mask, `1`, `2`, `4`, `8` and `16` for white,
    blue, black, red and green.
  - Owner: `GameEventCollector`, `TransferAnnotations`

#### MiscContinuousEffect

- **`absent`** — [MiscContinuousEffect](annotations/MiscContinuousEffect.md), the entity
  - Leyline: publishes no `MiscContinuousEffect` row for any player-level
    continuous effect.

#### ModifiedCost

- **`absent`** — [ModifiedCost](annotations/ModifiedCost.md), the entity
  - Leyline: publishes no `ModifiedCost` row. Its `RemoveAbility` rows are
    typed `RemoveAbility` and `LayeredEffect` only.

#### ModifiedType

- **`absent`** — [Variants](annotations/ModifiedType.md#variants), effects other than crew, reconfigure and earthbend
  - Contract: every continuous effect that changes a type line publishes a
    row, including animated lands and artifacts, Spacecraft thresholds and
    perpetual and type-replacement effects.
  - Leyline: emits `ModifiedType` only for a crewed Vehicle, an attached
    reconfigure permanent and an earthbended land. Other type changes reach
    the object's type line with no row, and `temporaryCardType`,
    `perpetualAffectedId` and the type-replacement branch are never produced.
  - Owner: `VehicleAttachContributor`, `EarthbendEmitter`
- **`diverges`** — [Variants](annotations/ModifiedType.md#variants), reconfigure on the plain branch
  - Contract: an attached reconfigure Equipment's row is a plain row, with
    `effect_id` only.
  - Leyline: adds `sourceAbilityGRPID`, the attach ability, to that row, and
    ends it with `LayeredEffectDestroyed`.
  - Owner: `VehicleAttachContributor`

#### PhasedIn

- **`compat`** — [PhasedIn](annotations/PhasedIn.md), the entity
  - Leyline: publishes one row per returning permanent with its unchanged
    instance ID, no `affectorId` and no details, after the untap step's
    `PhaseOrStepModified` and before its untap rows. The contract for this
    shape is not established.
  - Owner: `AnnotationBuilder.phasedPermanent`, `AnnotationPipeline`

#### PhasedOut

- **`compat`** — [PhasedOut](annotations/PhasedOut.md), the entity
  - Leyline: publishes one row naming the permanent by its unchanged
    instance ID, with the resolving spell or ability as `affectorId` and no
    details, without `ObjectIdChanged` or `ZoneTransfer`, and republishes the
    object with `zoneId` `12` while keeping it in the battlefield zone. The
    contract for this shape is not established.
  - Owner: `AnnotationBuilder.phasedPermanent`, `AnnotationPipeline`, `StateZoneProjection`

#### Qualification

- **`absent`** — [Variants](annotations/Qualification.md#variants), predicates other than combat restrictions, combat keywords and adventure casting
  - Contract: rows publish every qualifying predicate, including doesn't
    untap, can't be countered, cost changes, cast permissions granted by
    other cards and player-scoped predicates, with `parent_grpid` and the
    duration keys where they apply.
  - Leyline: emits `QualificationType` `30`, `31`, `32` and `40`, and `47`
    only for an adventure card in exile. It never writes `parent_grpid` or a
    duration key.
  - Owner: `PersistentFeedBuilder`, `QualificationType`

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

#### ResolutionStart

- **`diverges`** — [ResolutionStart.grpid](annotations/ResolutionStart.md#ResolutionStart.grpid)
  - Contract: `grpid` equals the resolving object's `grpId` or an entry of its
    `uniqueAbilities`.
  - Leyline: writes `grpid` `0` for some resolving abilities whose object
    carries a nonzero `grpId`.
  - Owner: `AnnotationPipeline`, `StateProjectionCompiler`

#### ShouldntPlay

- **`absent`** — [Variants](annotations/ShouldntPlay.md#variants), `EntersTapped`, `Legendary` and `ConsequentialConditionNotMet`
  - Contract: the client is warned about tapped lands, duplicate legendary
    permanents and abilities whose condition is not met.
  - Leyline: emits only `RedundantActivation`, once, in the GSM where a
    Vehicle becomes crewed.
  - Owner: `VehicleAttachContributor`

#### SyntheticEvent

- **`absent`** — [Variants](annotations/SyntheticEvent.md#variants), `type` `3`
  - Contract: an object-scoped cue exists as `type` `3`.
  - Leyline: builds only `type` `1`.
  - Owner: `AnnotationBuilder.syntheticEvent`

#### TriggeringObject

- **`diverges`** — [TriggeringObject.born-with-instance](annotations/TriggeringObject.md#TriggeringObject.born-with-instance)
  - Contract: the row first appears in the GSM whose `AbilityInstanceCreated`
    names the ability.
  - Leyline: for a trigger that chooses targets as it goes on the stack,
    publishes `AbilityInstanceCreated` with the target selection and the
    row only when the trigger's cast event is drained, two GSMs later.
  - Owner: `AnnotationPipeline`
- **`diverges`** — [TriggeringObject.source-zone](annotations/TriggeringObject.md#TriggeringObject.source-zone)
  - Contract: `source_zone` is a zone ID.
  - Leyline: writes `0` when neither the triggering object's zone nor the
    ability's source zone is known, as happens for some Leyline-defined
    trigger abilities.
  - Owner: `AnnotationPipeline`, `ZoneTransferDetector`
- **`absent`** — [Variants](annotations/TriggeringObject.md#variants), source-and-event-objects and renamed-source branches
  - Contract: a row can name several objects of the trigger event, and can
    carry `NEW_OBJECT_ID` for a source that changed instance ID.
  - Leyline: always names exactly one object and never writes
    `NEW_OBJECT_ID`.
  - Owner: `AnnotationBuilder.triggeringObject`

#### ZoneTransfer

- **`diverges`** — [ZoneTransfer.resolve-affector](annotations/ZoneTransfer.md#ZoneTransfer.resolve-affector) *(provisional statement)*
  - Contract: for a spell resolving from the stack whose controller owns it,
    a `Resolve` transfer names that seat. Spells whose owner and controller
    differ are not covered.
  - Leyline: names the priority player of the published state, or seat `2`
    when no player has priority. An opponent's instant that resolves while the
    active player receives priority is attributed to the active player.
  - Owner: `TransferAnnotations.annotationsForTransfer`, `StateMapper`

### Mechanic exceptions

#### BattleCry

- **`diverges`** — [BattleCry.no-pt-mod](mechanics/BattleCry.md#BattleCry.no-pt-mod) *(provisional statement)*
  - Contract: the Battle Cry pump carries no `PowerToughnessModCreated` rows.
  - Leyline: emits one `PowerToughnessModCreated` per affected creature for
    every created boost effect with a nonzero power or toughness change.
  - Owner: `MechanicAnnotations`

#### Cascade

- **`diverges`** — [Cascade.no-resolution-start](mechanics/Cascade.md#Cascade.no-resolution-start) *(provisional statement)*
  - Contract: the Cascade ability's resolution carries no `ResolutionStart`
    row for the ability.
  - Leyline: brackets the Cascade ability like every resolving ability, with a
    `ResolutionStart` naming the ability before the reveal transfers.
  - Owner: `AnnotationPipeline`

#### Disguise

- **`absent`** — [Disguise.turn-up](mechanics/Disguise.md#Disguise.turn-up) *(provisional statement)*
  - Contract: the GSM that turns a disguised permanent face up carries
    `TurnPermanent` before `UserActionTaken` with `actionType` `8`.
  - Leyline: flips the permanent and deletes its `FaceDown` row but emits
    neither `TurnPermanent` nor the accepted `UserActionTaken`, as recorded
    under `Special_TurnFaceUp`.
  - Owner: `ActionMapper.addSpecialTurnFaceUpActions`

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
