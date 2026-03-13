# Rosetta: Arena Protocol / Forge Engine / Leyline

Translation reference: Arena protocol ↔ Forge engine ↔ leyline code.

## Table 1: Annotation Types

Arena type numbers, Forge events, and leyline handling. `--` = no mapping. `MISSING` = not yet implemented.

| Type# | Arena Name | Forge GameEvent | Leyline GameEvent | AnnotationBuilder Method | Key Detail Keys | Status |
|------:|------------|-----------------|----------------|--------------------------|-----------------|--------|
| 1 | ZoneTransfer | `GameEventCardChangeZone` | `ZoneChanged` | `zoneTransfer()` | `zone_src`, `zone_dest`, `category` | Implemented |
| 3 | DamageDealt | `GameEventCardDamaged`, `GameEventPlayerDamaged` | `DamageDealtToCard`, `DamageDealtToPlayer` | `damageDealt()` | `damage`, `type`, `markDamage` | Implemented |
| 4 | TappedUntapped | `GameEventCardTapped` | `CardTapped` | `tappedUntappedPermanent()` | `tapped` | Implemented |
| 5 | ModifiedPower | `GameEventCardStatsChanged` | `PowerToughnessChanged` | `modifiedPower()` | `value` (Int32) | Implemented |
| 6 | ModifiedToughness | `GameEventCardStatsChanged` | `PowerToughnessChanged` | `modifiedToughness()` | `value` (Int32) | Implemented |
| 7 | ModifiedColor | -- | -- | -- | values from affectedIds | MISSING |
| 8 | PhaseOrStepModified | `GameEventTurnPhase` | -- | `phaseOrStepModified()` | `phase`, `step` | Implemented (hardcoded, not event-driven) |
| 9 | AddAbility | -- | -- | -- | `grpid`, `effect_id`, `sourceAbilityGRPID`, `UniqueAbilityId` | MISSING |
| 10 | ModifiedLife | `GameEventPlayerLivesChanged` | `LifeChanged` | `modifiedLife()` | `delta` | Implemented |
| 11 | CreateAttachment | `GameEventCardAttachment` | `CardAttached` | `attachmentCreated()` | affector/affected ids | Implemented |
| 12 | RemoveAttachment | `GameEventCardAttachment` | `CardDetached` | `removeAttachment()` | aura/equipment id | Implemented |
| 13 | ObjectIdChanged | (internal to StateMapper) | -- | `objectIdChanged()` | `orig_id`, `new_id` | Implemented |
| 14 | Counter | `GameEventCardCounters` | `CountersChanged` | `counter()` (persistent) | `count`, `counter_type` | Implemented (persistent; Forge display name → proto enum mapping) |
| 15 | ControllerChanged | `GameEventPlayerControl` | -- | -- | affector/affected ids | MISSING |
| 16 | CounterAdded | `GameEventCardCounters` | `CountersChanged` | `counterAdded()` | `counter_type`, `transaction_amount` | Implemented |
| 17 | CounterRemoved | `GameEventCardCounters` | `CountersChanged` | `counterRemoved()` | `counter_type`, `transaction_amount` | Implemented |
| 18 | LayeredEffectCreated | `GameEventCardStatsChanged` | `PowerToughnessChanged` | `layeredEffectCreated()` | `effect_id`, `sourceAbilityGRPID`, `LayeredEffectType` | Implemented |
| 20 | Attachment | `GameEventCardAttachment` | `CardAttached` | `attachment()` (persistent) | affector/affected ids | Implemented |
| 22 | CopiedObject | -- | -- | -- | `abilityGrpId`, `LayeredEffectType`, `CopyObject` | MISSING |
| 23 | RemoveAbility | -- | -- | -- | `effect_id` | MISSING |
| 25 | ModifiedType | -- | -- | -- | `effect_id`, `temporaryCardType` | MISSING |
| 26 | TargetSpec | -- | -- | -- | `index`, `abilityGrpId`, `distributions`, `promptId` | MISSING |
| 28 | FaceDown | -- | -- | -- | `abilityGrpId`, `REASON` | MISSING |
| 29 | TurnPermanent | -- | -- | -- | (none) | MISSING |
| 30 | DynamicAbility | -- | -- | -- | `cost`, `grpid`, `base_grpid`, `action_cost_string` | MISSING |
| 31 | ObjectsSelected | -- | -- | -- | (none) | MISSING |
| 34 | ManaPaid | `GameEventManaPool` | -- | `manaPaid()` | `id`, `color` | Implemented (stub, no details) |
| 35 | TokenCreated | `GameEventTokenCreated` | `TokenCreated` | `tokenCreated()` | affected ids | Implemented |
| 36 | AbilityInstanceCreated | `GameEventSpellAbilityCast` | `SpellCast` | `abilityInstanceCreated()` | affected ids | Implemented |
| 37 | AbilityInstanceDeleted | `GameEventSpellResolved` / `GameEventSpellRemovedFromStack` | `SpellResolved` | `abilityInstanceDeleted()` | affected ids | Implemented |
| 38 | DisplayCardUnderCard | -- | -- | -- | `Disable`, `DisplayUnderObjects` | MISSING |
| 39 | AbilityWordActive | -- | -- | -- | `AbilityWordName`, `value`, `colors`, `AbilityGrpId` | MISSING |
| 40 | LinkInfo | -- | -- | -- | `LinkType`, `Choice_Value` | MISSING |
| 41 | TokenDeleted | `GameEventCardChangeZone` | `TokenDestroyed` | `tokenDeleted()` | affectorId=instanceId, affectedIds=[instanceId] | Implemented |
| 42 | Qualification | -- | -- | -- | `QualificationType`, `QualificationSubtype`, `grpid` | MISSING |
| 43 | ResolutionStart | `GameEventSpellResolved` | `SpellResolved` | `resolutionStart()` | `grpid` | Implemented |
| 44 | ResolutionComplete | `GameEventSpellResolved` | `SpellResolved` | `resolutionComplete()` | `grpid` | Implemented |
| 45 | Designation | -- | -- | -- | `PromptMessage`, `CostIncrease`, `grpid` | MISSING |
| 46 | GainDesignation | -- | -- | -- | designation type | MISSING |
| 47 | CardRevealed | -- | -- | -- | `source_zone` | MISSING |
| 48 | NewTurnStarted | `GameEventTurnBegan` | -- | `newTurnStarted()` | (none) | Implemented (hardcoded, not event-driven) |
| 49 | ManaDetails | -- | -- | -- | `Enum_Type`, `Enum_Value_` prefix | MISSING |
| 50 | DisqualifiedEffect | -- | -- | -- | affector id | MISSING |
| 51 | LayeredEffect | `GameEventCardStatsChanged` | `PowerToughnessChanged` | `layeredEffect()` (persistent) | `effect_id`, `abilityGrpId`, `isTop`, `Duration` | Implemented |
| 52 | MiscContinuousEffect | -- | -- | -- | `grpid`, `QualificationType` | MISSING |
| 53 | ShouldntPlay | -- | -- | -- | `Reason`, `ability_grpid` | MISSING |
| 54 | UseOrCostsManaCost | -- | -- | -- | from affectedIds | MISSING |
| 55 | RemainingSelections | -- | -- | -- | (none) | MISSING |
| 56 | Shuffle | `GameEventShuffle` | `LibraryShuffled` | `shuffle()` | `OldIds`, `NewIds` | Implemented (no detail keys) |
| 57 | CoinFlip | `GameEventFlipCoin` | -- | -- | `CoinFlipResult` | MISSING |
| 58 | ChoiceResult | -- | -- | -- | `Choice_Value`, `Choice_Options`, `Choice_Domain` | MISSING |
| 59 | RevealedCardCreated | -- | -- | `revealedCardCreated()` | affected ids | Implemented |
| 60 | RevealedCardDeleted | -- | -- | `revealedCardDeleted()` | affected ids | Implemented |
| 61 | SuspendLike | -- | -- | -- | `abilityGrpid` | MISSING |
| 62 | ReplacementEffect | -- | -- | -- | `grpid`, `replacedEffectSource` | MISSING |
| 63 | EnteredZoneThisTurn | (internal to StateMapper) | -- | `enteredZoneThisTurn()` | (none) | Implemented (persistent) |
| 64 | CastingTimeOption | -- | -- | -- | `alternateCostGrpId`, `castAbilityGrpId`, `type` | N/A (see GRE CastingTimeOptionsReq) |
| 65 | Scry | `GameEventScry` | `Scry` | `scry()` | `topCount`, `bottomCount` | Implemented |
| 66 | PredictedDirectDamage | -- | -- | -- | `value`, `modifierString` | MISSING |
| 67 | SwitchPowerToughness | -- | -- | -- | (none) | MISSING |
| 69 | PendingEffect | -- | -- | -- | `effect_type`, `counter_type`, `count` | MISSING |
| 70 | AttachmentCreated | `GameEventCardAttachment` | `CardAttached` | `attachmentCreated()` | affector/affected ids | Implemented |
| 71 | PowerToughnessModCreated | `GameEventCardStatsChanged` | `PowerToughnessChanged` | `powerToughnessModCreated()` | `power`, `toughness` | Implemented |
| 72 | SyntheticEvent | -- | -- | `syntheticEvent()` | `type` | Implemented |
| 73 | UserActionTaken | -- | -- | `userActionTaken()` | `actionType`, `abilityGrpId` | Implemented |
| 74 | DelayedTriggerAffectees | -- | -- | -- | `abilityGrpId`, `removesFromZone` | MISSING |
| 75 | InstanceRevealedToOpponent | -- | -- | -- | (none) | MISSING |
| 77 | ReplacementEffectApplied | -- | -- | -- | `grpid`, `IsDamageReplacement` | MISSING |
| 78 | ReferencedObjects | -- | -- | -- | (none) | MISSING |
| 79 | ChoosingAttachments | -- | -- | -- | `Selections`, `SelectingFor` | MISSING |
| 80 | TemporaryPermanent | -- | -- | -- | (none) | MISSING |
| 81 | GamewideHistoryCount | -- | -- | -- | `AbilityGrpId`, `count` | MISSING |
| 82 | AbilityExhausted | -- | -- | -- | `AbilityGrpId`, `UsesRemaining`, `UniqueAbilityId` | MISSING |
| 83 | MultistepEffectStarted | -- | -- | -- | `SubCategory` | MISSING |
| 84 | MultistepEffectComplete | -- | -- | -- | `SubCategory` | MISSING |
| 85 | DieRoll | `GameEventRollDie` | -- | -- | `Result`, `NaturalResult`, `Faces`, `Ignored` | MISSING |
| 86 | DungeonStatus | -- | -- | -- | `CurrentDungeon`, `CurrentRoom` | MISSING |
| 87 | LinkedDamage | -- | -- | -- | `abilityGrpId` | MISSING |
| 88 | ClassLevel | -- | -- | -- | `Level` | MISSING |
| 89 | TokenImmediatelyDied | -- | -- | -- | affected ids | MISSING |
| 90 | DamagedThisTurn | `GameEventCardDamaged` | `DamageDealtToCard` | `damagedThisTurn()` (persistent) | (none) | Implemented |
| 91 | ReferencedCardNames | -- | -- | -- | (none) | MISSING |
| 92 | PlayerSelectingTargets | -- | -- | -- | affector/affected ids | MISSING |
| 93 | PlayerSubmittedTargets | -- | -- | -- | affector/affected ids | MISSING |
| 94 | CrewedThisTurn | -- | -- | -- | (none) | MISSING |
| 95 | PhasedOut | `GameEventCardPhased` | -- | -- | affected ids | MISSING |
| 96 | PhasedIn | `GameEventCardPhased` | -- | -- | affected ids | MISSING |
| 97 | LoyaltyActivationsRemaining | -- | -- | -- | `ActivationsRemaining` | MISSING |
| 99 | RegeneratePending | `GameEventCardRegenerated` | -- | -- | (none) | MISSING |
| 100 | PermanentRegenerated | `GameEventCardRegenerated` | -- | -- | affected ids | MISSING |
| 101 | ObjectsToDistinguish | -- | -- | -- | (none) | MISSING |
| 102 | BoonInfo | -- | -- | -- | `TotalUses`, `UsesRemaining` | MISSING |
| 103 | LoseDesignation | -- | -- | -- | designation type | MISSING |
| 104 | SaddledThisTurn | -- | -- | -- | (none) | MISSING |
| 105 | HiddenInformation | -- | -- | -- | `ManaValue` | MISSING |
| 106 | LoopCount | -- | -- | -- | (none) | MISSING |
| 108 | SuppressedPowerAndToughness | -- | -- | -- | `power`, `toughness` | MISSING |
| 110 | ColorProduction | `LandPlayed` | `AnnotationPipeline` | `colorProduction()` | `colors` (int32 bitmask) | WIRED |
| 111 | CopyException | -- | -- | -- | `manaCost` | MISSING |
| 113 | ModifiedCost | -- | -- | -- | (unknown) | MISSING |

**Implemented: 32 types. Missing: 62+ types.**

## Table 2: Zone Transfer Categories

| Code | Arena Name | Typical Zone Pair | TransferCategory Variant | GameEvent Driver | Status |
|-----:|------------|-------------------|--------------------------|----------------------|--------|
| 6 | CastSpell | Hand → Stack | `CastSpell` | `SpellCast` | Implemented |
| 7 | PlayLand | Hand → Battlefield | `PlayLand` | `LandPlayed` | Implemented |
| 8 | Return | GY/Exile → Hand/BF | `Return` | `ZoneChanged` inferred (GY/Exile→Hand/BF) | Implemented |
| 9 | Countered | Stack → GY | `Countered` | `SpellResolved(fizzled=true)` | Implemented |
| 10 | Draw | Library → Hand | `Draw` | `ZoneChanged` inferred (Lib→Hand stays generic) | Implemented |
| 11 | Discard | Hand → GY | `Discard` | `CardDiscarded` (enriched zone handler) | Implemented |
| 13 | Resolve | Stack → Battlefield | `Resolve` | `SpellResolved` | Implemented |
| 14 | Search | Library → Hand/BF | `Search` | `ZoneChanged` inferred (Library→BF) | Implemented |
| 16 | Exile | Any → Exile | `Exile` | `CardExiled` (enriched zone handler) | Implemented |
| 17 | Destroy | Battlefield → GY | `Destroy` | `CardDestroyed` (enriched zone handler) | Implemented |
| 18 | Sacrifice | Battlefield → GY | `Sacrifice` | `CardSacrificed` | Implemented |
| 19 | Put | Any → Any | `Put` | (not zone-pair inferred; requires dedicated event) | Partial |
| 20 | ZeroToughness | Battlefield → GY | -- | -- | MISSING (falls back to `ZoneTransfer`) |
| 21 | Damage | Battlefield → GY | -- | -- | MISSING (falls back to `ZoneTransfer`) |
| 22 | IllegalAttachment | Battlefield → GY | -- | -- | MISSING |
| 23 | UnattachedAura | Battlefield → GY | -- | -- | MISSING |
| 24 | ZeroLoyalty | Battlefield → GY | -- | -- | MISSING |
| 25 | Legend | Battlefield → GY | -- | -- | MISSING |
| 28 | Token | (creation) | -- | -- | MISSING |
| 32 | Bounce | Battlefield → Hand/Library | `Bounce` | `CardBounced` (enriched zone handler) | Implemented |
| 33 | Mill | Library → GY (same owner) | `Mill` | `CardMilled` (enriched zone handler) | Implemented |
| 34 | LibToExile | Library → Exile | -- | -- | MISSING (falls back to `Exile`) |
| 37 | Conjure | (creation) | -- | -- | MISSING |

**Zone-pair overrides** (applied after reason code):
- BF → Hand or Library → `Bounce` (32)
- Library → GY (same owner) → `Mill` (33)
- Library → Exile → `LibToExile` (34)

**Fallback**: Any unmatched zone change → `ZoneTransfer` (generic, code 2)

## Table 3: Zone IDs

| Zone ID | Arena ZoneType | Forge ZoneType | ZoneIds Constant | Shared/Per-Seat | Visibility |
|--------:|----------------|----------------|------------------|----------------|------------|
| 18 | Revealed | -- | `REVEALED_P1` | Per-seat (P1) | Public |
| 19 | Revealed | -- | `REVEALED_P2` | Per-seat (P2) | Public |
| 24 | Suppressed | -- | `SUPPRESSED` | Shared | Public |
| 25 | Pending | -- | `PENDING` | Shared | Public |
| 26 | Command | `Command` | `COMMAND` | Shared | Public |
| 27 | Stack | `Stack` | `STACK` | Shared | Public |
| 28 | Battlefield | `Battlefield` | `BATTLEFIELD` | Shared | Public |
| 29 | Exile | `Exile` | `EXILE` | Shared | Public |
| 30 | Limbo | -- | `LIMBO` | Shared | Public |
| 31 | Hand | `Hand` | `P1_HAND` | Per-seat (P1) | Private |
| 32 | Library | `Library` | `P1_LIBRARY` | Per-seat (P1) | Hidden |
| 33 | Graveyard | `Graveyard` | `P1_GRAVEYARD` | Per-seat (P1) | Public |
| 34 | Sideboard | `Sideboard` | `P1_SIDEBOARD` | Per-seat (P1) | Private |
| 35 | Hand | `Hand` | `P2_HAND` | Per-seat (P2) | Private |
| 36 | Library | `Library` | `P2_LIBRARY` | Per-seat (P2) | Hidden |
| 37 | Graveyard | `Graveyard` | `P2_GRAVEYARD` | Per-seat (P2) | Public |
| 38 | Sideboard | `Sideboard` | `P2_SIDEBOARD` | Per-seat (P2) | Private |

**Forge zones with no Arena equivalent**: `Flashback`, `Ante`, `Merged`, `SchemeDeck`, `PlanarDeck`, `AttractionDeck`, `Junkyard`, `ContraptionDeck`, `Subgame`, `ExtraHand`, `None`

**Arena zones with no Forge equivalent**: `Revealed`, `Limbo`, `Suppressed`, `Pending`, `PhasedOut` (Forge tracks phasing as card state, not zone)

## Table 4: Action Types

| Value | Arena ActionType | MatchSession Handler | Forge PlayerAction | Status |
|------:|------------------|---------------------|-------------------|--------|
| 0 | None | -- | -- | -- |
| 1 | Cast | `onPerformAction` | `PlayerAction.CastSpell(forgeCardId)` | Implemented |
| 2 | Activate | `onPerformAction` | -- | MISSING (logged, falls back to PassPriority) |
| 3 | Play | `onPerformAction` | `PlayerAction.PlayLand(forgeCardId)` | Implemented |
| 4 | ActivateMana | -- | -- | MISSING |
| 5 | Pass | `onPerformAction` | `PlayerAction.PassPriority` | Implemented |
| 6 | ActivateTest | -- | -- | MISSING |
| 7 | Special | -- | -- | MISSING |
| 8 | SpecialTurnFaceUp | -- | -- | MISSING |
| 9 | ResolutionCost | -- | -- | MISSING |
| 10 | CastLeft | -- | -- | MISSING |
| 11 | CastRight | -- | -- | MISSING |
| 12 | MakePayment | -- | -- | MISSING |
| 14 | CombatCost | -- | -- | MISSING |
| 15 | OpeningHandAction | -- | -- | MISSING |
| 16 | CastAdventure | -- | -- | MISSING |
| 17 | FloatMana | -- | -- | MISSING |
| 18 | CastMdfc | -- | -- | MISSING |
| 19 | PlayMdfc | -- | -- | MISSING |
| 20 | SpecialPayment | -- | -- | MISSING |
| 21 | CastPrototype | -- | -- | MISSING |
| 22 | CastLeftRoom | -- | -- | MISSING |
| 23 | CastRightRoom | -- | -- | MISSING |
| 24 | CastOmen | -- | -- | MISSING |

**Action groupings**: Play (3, 19) = land actions. Cast (1, 10, 11, 16, 18, 21-24) = spell casting. Activate (2, 4, 6) = ability activation. Pass (5) = explicit priority pass.

**Combat actions** are handled by separate message types, not ActionType (see Table 5).

## Table 5: GRE Message Types (Key)

| Type# | Name | Direction | BundleBuilder Method | MatchSession Handler |
|------:|------|-----------|---------------------|---------------------|
| 1 | GameStateMessage | S→C | `postAction`, `stateOnlyDiff`, `aiActionDiff`, `phaseTransitionDiff` | -- |
| 2 | ActionsAvailableReq | S→C | `postAction`, `phaseTransitionDiff` | `onPerformAction` (response) |
| 6 | ChooseStartingPlayerReq | S→C | (HandshakeMessages) | `onChooseStartingPlayer` (response) |
| 7 | ConnectResp | S→C | (HandshakeMessages) | -- |
| 10 | SetSettingsResp | S→C | (HandshakeMessages) | `onSettings` (response) |
| 15 | MulliganReq | S→C | (HandshakeMessages) | `onMulliganKeep` (via bridge) |
| 17 | OrderReq | S→C | -- | -- (MISSING) |
| 18 | PromptReq | S→C | `phaseTransitionDiff` | -- |
| 22 | SelectNreq | S→C | `selectNBundle` | `onSelectN` (response) |
| 26 | DeclareAttackersReq | S→C | `declareAttackersBundle` | `onDeclareAttackers` (response) |
| 27 | SubmitAttackersResp | S→C | (inline in MatchSession) | -- |
| 28 | DeclareBlockersReq | S→C | `declareBlockersBundle` | `onDeclareBlockers` (response) |
| 29 | SubmitBlockersResp | S→C | (inline in MatchSession) | -- |
| 30 | AssignDamageReq | S→C | -- | -- (MISSING) |
| 34 | SelectTargetsReq | S→C | `selectTargetsBundle` | `onSelectTargets` (response) |
| 35 | SubmitTargetsResp | S→C | (inline in MatchSession) | -- |
| 36 | CastingTimeOptionsReq | S→C | `castingTimeOptionsBundle` | `onCastingTimeOptions` (response) |
| 37 | IntermissionReq | S→C | (inline in sendGameOver) | -- |
| 51 | QueuedGameStateMessage | S→C | `queuedGameState` | -- |
| 54 | EdictalMessage | S→C | `edictalPass` | -- |
| 56 | TimerStateMessage | S→C | `timerStart`, `timerStop` | -- |

**Client message types handled**:

| Type# | Name | MatchSession Handler |
|------:|------|---------------------|
| 5 | CancelActionReq | `onCancelAction` |
| 7 | ConcedeReq | `onConcede` |
| 15 | PerformActionResp | `onPerformAction` |
| 18 | SelectNresp | `onSelectN` |
| 20 | SetSettingsReq | `onSettings` |
| 30 | DeclareAttackersResp | `onDeclareAttackers` |
| 32 | DeclareBlockersResp | `onDeclareBlockers` |
| 36 | SelectTargetsResp | `onSelectTargets` |

## Table 6: Front Door CmdTypes (Key)

Selected FD command types relevant to gameplay. Full list in `frontdoor/.../CmdType.kt`.

| CmdType | Name | Handler | Status |
|--------:|------|---------|--------|
| 0 | Authenticate | `onAuthenticate` | Implemented |
| 1 | StartHook | `onStartHook` | Implemented |
| 600 | Event_Join | `onEventJoin` | Implemented |
| 603 | Event_EnterPairing | `onEnterPairing` | Implemented |
| 612 | Event_AiBotMatch | `onAiBotMatch` | Implemented |
| 622 | Event_SetDeckV2 | `onSetDeckV2` | Implemented |
| 623 | Event_GetCoursesV2 | `onGetCoursesV2` | Implemented |
| 624 | Event_GetActiveEventsV2 | `onGetActiveEventsV2` | Implemented |
| 1800 | BotDraft_StartDraft | `onBotDraftStart` | Implemented |
| 1801 | BotDraft_DraftPick | `onBotDraftPick` | Implemented |
| 1802 | BotDraft_DraftStatus | `onBotDraftStatus` | Implemented |
| 621 | Event_PlayerDraftConfirmCardPoolGrant | stub no-op | Stubbed |
| 1908 | Draft_CompleteDraft | stub no-op | Stubbed |

## Table 7: Phase/Step Mapping

Forge uses a single `PhaseType` enum covering both phases and steps. Arena uses separate `Phase` + `Step` enums.

| Arena Phase | Arena Step | Arena Phase# | Arena Step# | Forge PhaseType |
|-------------|-----------|:------------:|:-----------:|-----------------|
| Beginning | Untap | 1 | 1 | `UNTAP` |
| Beginning | Upkeep | 1 | 2 | `UPKEEP` |
| Beginning | Draw | 1 | 3 | `DRAW` |
| Main1 | -- | 2 | 0 | `MAIN1` |
| Combat | BeginCombat | 3 | 4 | `COMBAT_BEGIN` |
| Combat | DeclareAttack | 3 | 5 | `COMBAT_DECLARE_ATTACKERS` |
| Combat | DeclareBlock | 3 | 6 | `COMBAT_DECLARE_BLOCKERS` |
| Combat | FirstStrikeDamage | 3 | 11 | `COMBAT_FIRST_STRIKE_DAMAGE` |
| Combat | CombatDamage | 3 | 7 | `COMBAT_DAMAGE` |
| Combat | EndCombat | 3 | 8 | `COMBAT_END` |
| Main2 | -- | 4 | 0 | `MAIN2` |
| Ending | End | 5 | 9 | `END_OF_TURN` |
| Ending | Cleanup | 5 | 10 | `CLEANUP` |

**Notes**:
- Arena step 11 (FirstStrikeDamage) is out of sequence in the enum but slots between DeclareBlock (6) and CombatDamage (7) in gameplay.
- Forge's `COMBAT_FIRST_STRIKE_DAMAGE` only occurs when a creature with first/double strike is in combat.
- Arena sends `PhaseOrStepModified` annotations with both phase# and step# on every transition; forge-nexus produces these in `phaseTransitionDiff` and `aiActionDiff`.

## Table 8: GameObjectType

| Value | Arena Name | Forge Equivalent | Notes |
|------:|------------|------------------|-------|
| 1 | Card | `Card` (non-token) | Standard card objects |
| 2 | Token | `Card` (isToken=true) | Token creatures/permanents |
| 3 | Ability | `SpellAbilityStackInstance` | Abilities on the stack |
| 4 | Emblem | `Card` (in Command zone) | Planeswalker emblems |
| 5 | SplitCard | `Card` (split parent) | Parent of split card halves |
| 6 | SplitLeft | `Card` (split left state) | Left half (e.g. Fire of Fire//Ice) |
| 7 | SplitRight | `Card` (split right state) | Right half |
| 8 | RevealedCard | -- | Client-only revealed card proxy |
| 9 | TriggerHolder | `SpellAbility` (trigger) | Triggered ability placeholder |
| 10 | Adventure | `Card` (adventure state) | Adventure half of adventure cards |
| 11 | Mdfcback | `Card` (MDFC back state) | Modal DFC back face |
| 12 | DisturbBack | `Card` (disturb back state) | Disturb transformed face |
| 13 | Boon | -- | Conjured boon object |
| 14 | PrototypeFacet | `Card` (prototype state) | Prototype cast mode |
| 15 | RoomLeft | `Card` (room left) | Room left half |
| 16 | RoomRight | `Card` (room right) | Room right half |
| 17 | Omen | `Card` (foretold state) | Foretell/omen face |

## Appendix: Forge GameEvents (Complete)

All 57 concrete `GameEvent` classes in `forge.game.event`:

| GameEvent Class | Payload | Wired to GameEvent? |
|-----------------|---------|--------------------------|
| `GameEventAnteCardsSelected` | cards multimap | No |
| `GameEventAttackersDeclared` | player, attackersMap | Yes → `AttackersDeclared` |
| `GameEventBlockersDeclared` | defendingPlayer, blockers | Yes → `BlockersDeclared` |
| `GameEventCardAttachment` | equipment, oldEntity, newTarget | Yes → `CardAttached` / `CardDetached` |
| `GameEventCardChangeZone` | card, from, to | Yes → `ZoneChanged` / `CardDestroyed` / `CardBounced` / `CardExiled` / `CardDiscarded` / `CardMilled` (zone-pair dispatch) |
| `GameEventCardCounters` | card, type, oldValue, newValue | Yes → `CountersChanged` |
| `GameEventCardDamaged` | card, source, amount, type | Yes → `DamageDealtToCard` |
| `GameEventCardDestroyed` | (empty) | No (empty record; BF→GY inferred via `CardDestroyed` from zone handler) |
| `GameEventCardForetold` | activatingPlayer | No |
| `GameEventCardModeChosen` | player, cardName, mode | No |
| `GameEventCardPhased` | card, phaseState | No |
| `GameEventCardPlotted` | card, activatingPlayer | No |
| `GameEventCardRegenerated` | cards | No |
| `GameEventCardSacrificed` | card | Yes → `CardSacrificed` |
| `GameEventCardStatsChanged` | cards, transform | Yes → `PowerToughnessChanged` (delta tracking) |
| `GameEventCardTapped` | card, tapped | Yes → `CardTapped` |
| `GameEventCombatChanged` | (empty) | No |
| `GameEventCombatEnded` | attackers, blockers | Yes → `CombatEnded` |
| `GameEventCombatUpdate` | attackers, blockers | No |
| `GameEventDayTimeChanged` | daytime | No |
| `GameEventDoorChanged` | activatingPlayer, card, state, unlock | No |
| `GameEventFlipCoin` | (empty) | No |
| `GameEventGameFinished` | (empty) | No |
| `GameEventGameOutcome` | result, history | No |
| `GameEventGameRestarted` | whoRestarted | No |
| `GameEventGameStarted` | gameType, firstTurn, players | No |
| `GameEventLandPlayed` | player, land | Yes → `LandPlayed` |
| `GameEventManaBurn` | player, causedLifeLoss, amount | No |
| `GameEventManaPool` | player, mode, mana | No |
| `GameEventMulligan` | player | No |
| `GameEventPlayerControl` | player, old/new controller | No |
| `GameEventPlayerCounters` | receiver, type, oldValue, amount | No |
| `GameEventPlayerDamaged` | target, source, amount, combat, infect | Yes → `DamageDealtToPlayer` |
| `GameEventPlayerLivesChanged` | player, oldLives, newLives | Yes → `LifeChanged` |
| `GameEventPlayerPoisoned` | receiver, source, oldValue, amount | No |
| `GameEventPlayerPriority` | turn, phase, priority | No |
| `GameEventPlayerRadiation` | receiver, source, change | No |
| `GameEventPlayerShardsChanged` | player, oldShards, newShards | No |
| `GameEventPlayerStatsChanged` | players, updateCards | No |
| `GameEventRandomLog` | message | No |
| `GameEventRollDie` | (empty) | No |
| `GameEventScry` | player, toTop, toBottom | Yes → `Scry` |
| `GameEventShuffle` | player | Yes → `LibraryShuffled` |
| `GameEventSnapshotRestored` | start | No |
| `GameEventSpeedChanged` | player, oldValue, newValue | No |
| `GameEventSpellAbilityCast` | sa, si, stackIndex | Yes → `SpellCast` |
| `GameEventSpellRemovedFromStack` | sa | No |
| `GameEventSpellResolved` | spell, hasFizzled | Yes → `SpellResolved` |
| `GameEventSprocketUpdate` | contraption, oldSprocket, sprocket | No |
| `GameEventSubgameEnd` | maingame, message | No |
| `GameEventSubgameStart` | subgame, message | No |
| `GameEventSurveil` | player, toLibrary, toGraveyard | Yes → `Surveil` + `CardSurveiled` (enriched zone handler) |
| `GameEventTokenCreated` | tokens (List\<Card\>) | Yes → `TokenCreated` |
| `GameEventTurnBegan` | turnOwner, turnNumber | No |
| `GameEventTurnEnded` | (empty) | No |
| `GameEventTurnPhase` | playerTurn, phase, phaseDesc | No |
| `GameEventZone` | zoneType, player, mode, card, sa | No |

**Wired: 21 of 57 events** (37%). `CardChangeZone` now dispatches 6 zone-specific variants in addition to generic `ZoneChanged`. Key unwired events for future work: `CardPhased`, `SpellRemovedFromStack`, `FlipCoin`, `RollDie`, `PlayerControl`.

**Known limitation:** `GameEventCardDestroyed` is an empty Java record (`record Foo() implements GameEvent`) — no card field. Zone-pair inference from `GameEventCardChangeZone` covers destroy/exile/bounce/etc. `GameEventTokenCreated` was enriched with `List<Card> tokens` (4 of 5 call sites pass token refs; `InvestigateEffect` uses empty fallback due to per-player loop).
