# Protocol Catalog

Corpus: 39 games, 11341 game state messages.

## Annotations

| Type | Instances | Games | Persistence | Always keys |
|------|-----------|-------|-------------|-------------|
| PhaseOrStepModified | 5035 | 30 | transient | phase, step |
| TappedUntappedPermanent | 2674 | 27 | transient | tapped |
| EnteredZoneThisTurn | 2036 | 29 | persistent | — |
| ZoneTransfer | 1985 | 29 | transient | zone_src, zone_dest, category |
| UserActionTaken | 1975 | 29 | transient | actionType, abilityGrpId |
| ObjectIdChanged | 1676 | 29 | transient | orig_id, new_id |
| AbilityInstanceDeleted | 1521 | 27 | transient | — |
| AbilityInstanceCreated | 1515 | 27 | transient | source_zone |
| ManaPaid | 1149 | 27 | transient | color |
| ResolutionComplete | 745 | 27 | transient | grpid |
| ResolutionStart | 735 | 27 | transient | grpid |
| NewTurnStarted | 455 | 39 | transient | — |
| ColorProduction | 441 | 29 | persistent | colors |
| DamageDealt | 420 | 24 | transient | damage, type |
| TriggeringObject | 233 | 25 | persistent | — |
| ModifiedLife | 212 | 25 | transient | life |
| LayeredEffectCreated | 191 | 25 | transient | — |
| PlayerSelectingTargets | 162 | 26 | transient | — |
| TargetSpec | 161 | 25 | persistent | abilityGrpId, index, promptParameters, promptId |
| PlayerSubmittedTargets | 156 | 25 | transient | — |
| SyntheticEvent | 153 | 25 | transient | type |
| LayeredEffect | 141 | 20 | persistent | effect_id |
| ShouldntPlay | 141 | 5 | transient | Reason |
| LayeredEffectDestroyed | 134 | 23 | transient | — |
| AbilityWordActive | 126 | 19 | mixed | AbilityWordName |
| ModifiedPower | 118 | 24 | persistent | — |
| ModifiedToughness | 108 | 24 | persistent | — |
| Counter | 108 | 18 | persistent | count, counter_type |
| PowerToughnessModCreated | 102 | 21 | transient | power, toughness |
| CounterAdded | 94 | 18 | transient | counter_type, transaction_amount |
| TemporaryPermanent | 77 | 5 | persistent | AbilityGrpId |
| Designation | 76 | 6 | persistent | DesignationType |
| TokenCreated | 70 | 19 | transient | — |
| AddAbility | 54 | 13 | persistent | originalAbilityObjectZcid, UniqueAbilityId, grpid |
| Qualification | 51 | 18 | persistent | SourceParent, grpid, QualificationSubtype, QualificationType |
| DamagedThisTurn | 43 | 16 | persistent | — |
| RevealedCardCreated | 37 | 15 | transient | — |
| CastingTimeOption | 35 | 11 | mixed | type |
| Attachment | 30 | 13 | persistent | — |
| RevealedCardDeleted | 28 | 14 | transient | — |
| TokenDeleted | 27 | 12 | transient | — |
| LoyaltyActivationsRemaining | 27 | 4 | persistent | ActivationsRemaining |
| AttachmentCreated | 26 | 13 | transient | — |
| InstanceRevealedToOpponent | 24 | 12 | persistent | — |
| DisqualifiedEffect | 21 | 4 | transient | — |
| MultistepEffectComplete | 19 | 11 | transient | SubCategory |
| ReplacementEffect | 19 | 11 | persistent | grpid |
| MultistepEffectStarted | 18 | 11 | transient | SubCategory |
| Scry | 18 | 10 | transient | topIds, bottomIds |
| ModifiedType | 17 | 11 | persistent | effect_id |
| Shuffle | 14 | 7 | transient | OldIds, NewIds |
| LinkInfo | 14 | 7 | persistent | LinkType |
| CounterRemoved | 13 | 6 | transient | counter_type, transaction_amount |
| LossOfGame | 11 | 11 | persistent | reason |
| ChoiceResult | 11 | 7 | transient | Choice_Value, Choice_Sentiment |
| ObjectsSelected | 11 | 6 | persistent | playerId |
| CardRevealed | 10 | 1 | persistent | source_zone |
| DelayedTriggerAffectees | 10 | 4 | persistent | abilityGrpId |
| AbilityExhausted | 4 | 2 | persistent | UniqueAbilityId, UsesRemaining, AbilityGrpId |
| FaceDown | 4 | 1 | persistent | REASON, abilityGrpId |
| ModifiedName | 4 | 2 | persistent | effect_id |
| RemoveAbility | 4 | 2 | persistent | effect_id |
| ModifiedColor | 4 | 3 | persistent | color, modificationType, effect_id |
| GroupedIds | 2 | 1 | persistent | group_id |
| DisplayCardUnderCard | 2 | 1 | persistent | TemporaryZoneTransfer, Disable |
| UseOrCostsManaCost | 1 | 1 | persistent | OrCost |
| ManaDetails | 1 | 1 | persistent | ManaSpecType_DoesNotEmpty |
| GainDesignation | 1 | 1 | transient | DesignationType |
| ModifiedCost | 1 | 1 | persistent | 316, effect_id |
| TextChange | 1 | 1 | persistent | 316, effect_id |

## Prompts

| Type | Occurrences | Games | Always fields |
|------|-------------|-------|---------------|
| ActionsAvailableReq | 762 | 30 | gameStateId, prompt, actionsAvailableReq |
| DeclareAttackersReq | 235 | 25 | gameStateId, prompt, declareAttackersReq |
| SelectTargetsReq | 225 | 25 | gameStateId, prompt, selectTargetsReq, allowCancel |
| DeclareBlockersReq | 76 | 18 | gameStateId, prompt, declareBlockersReq |
| PromptReq | 67 | 33 | gameStateId, prompt |
| SelectNReq | 53 | 16 | gameStateId, prompt, selectNReq |
| MulliganReq | 42 | 39 | gameStateId, prompt, mulliganReq |
| IntermissionReq | 38 | 38 | gameStateId, intermissionReq |
| CastingTimeOptionsReq | 37 | 15 | gameStateId, prompt, castingTimeOptionsReq |
| OptionalActionMessage | 22 | 11 | gameStateId, prompt, optionalActionMessage, allowCancel |
| ChooseStartingPlayerReq | 18 | 18 | gameStateId, chooseStartingPlayerReq |
| GroupReq | 8 | 7 | gameStateId, prompt, groupReq, allowCancel |
| SearchReq | 6 | 4 | gameStateId, prompt, searchReq, allowCancel |
| OrderReq | 4 | 4 | gameStateId, prompt, orderReq, allowCancel |

## Actions

| Type | Occurrences | Games | Always fields |
|------|-------------|-------|---------------|
| Activate_Mana | 105472 | 29 | instanceId, abilityGrpId |
| Cast | 43461 | 39 | instanceId |
| Activate | 13100 | 19 | instanceId, abilityGrpId |
| Play | 8221 | 39 | instanceId |
| CastAdventure | 238 | 3 | instanceId, manaCost |
| Special_TurnFaceUp | 179 | 1 | instanceId, alternativeGrpId, manaCost, alternativeSourceZcid |
| Special | 116 | 1 | instanceId, abilityGrpId, alternativeGrpId, manaCost |
