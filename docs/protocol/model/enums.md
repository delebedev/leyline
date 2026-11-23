---
summary: "Shared protocol enums: turn structure, game object types, action types, game-state type and update, highlights, results, combat warnings and timers."
read_when:
  - "mapping a phase, step, object type or action type to its protocol value"
  - "reading an integer-valued annotation detail that carries an enum"
  - "choosing a game-state type or update value"
entity: model/Enums
---
# Protocol enums

Enum values are written without their type prefix: `Combat` is
`Phase_Combat`, `Card` is `GameObjectType_Card`. Annotation details that carry
an enum, such as `UserActionTaken.actionType` or `PhaseOrStepModified.step`,
carry its integer value. Values whose role is unknown are listed under Open
points; the tables keep them so their numbers are not reused by mistake.

## Turn structure

| Phase | Name | Step | Name |
|---:|---|---:|---|
| 1 | Beginning | 1 | Untap |
| 1 | Beginning | 2 | Upkeep |
| 1 | Beginning | 3 | Draw |
| 2 | Main1 | 0 | — |
| 3 | Combat | 4 | BeginCombat |
| 3 | Combat | 5 | DeclareAttack |
| 3 | Combat | 6 | DeclareBlock |
| 3 | Combat | 11 | FirstStrikeDamage |
| 3 | Combat | 7 | CombatDamage |
| 3 | Combat | 8 | EndCombat |
| 4 | Main2 | 0 | — |
| 5 | Ending | 9 | End |
| 5 | Ending | 10 | Cleanup |

`FirstStrikeDamage` is numbered out of sequence but occurs between blocking
and ordinary combat damage (CR 510.4).

## Game object types

| Value | Type | Family |
|---:|---|---|
| 1 | `Card` | generic |
| 2 | `Token` | generic |
| 3 | `Ability` | generic |
| 4 | `Emblem` | generic |
| 5 | `SplitCard` | card side |
| 6 | `SplitLeft` | card side |
| 7 | `SplitRight` | card side |
| 8 | `RevealedCard` | protocol utility |
| 9 | `TriggerHolder` | protocol utility |
| 10 | `Adventure` | card side |
| 11 | `MDFCBack` | card side |
| 12 | `DisturbBack` | card side |
| 13 | `Boon` | card side |
| 14 | `PrototypeFacet` | card side |
| 15 | `RoomLeft` | card side |
| 16 | `RoomRight` | card side |
| 17 | `Omen` | card side |

The families are described in the game object types overview.

## Action types

| Value | Type | Role |
|---:|---|---|
| 0 | `None` | No action. |
| 1 | `Cast` | Ordinary or alternative spell cast. |
| 2 | `Activate` | Non-mana activated ability. |
| 3 | `Play` | Land play. |
| 4 | `ActivateMana` | Mana ability. |
| 5 | `Pass` | Explicit priority pass. |
| 6 | `ActivateTest` | See Open points. |
| 7 | `Special` | Mechanic-specific special action. |
| 8 | `SpecialTurnFaceUp` | Turn a face-down permanent face up. |
| 9 | `ResolutionCost` | Cost paid during resolution. |
| 10 | `CastLeft` | Split card left half. |
| 11 | `CastRight` | Split card right half. |
| 12 | `MakePayment` | Payment sub-action. |
| 14 | `CombatCost` | Combat cost sub-action. |
| 15 | `OpeningHandAction` | Opening-hand action. |
| 16 | `CastAdventure` | Adventure face. |
| 17 | `FloatMana` | Float available mana. |
| 18 | `CastMdfc` | Modal double-faced card spell face. |
| 19 | `PlayMdfc` | Modal double-faced card land face. |
| 20 | `SpecialPayment` | Special payment sub-action. |
| 21 | `CastPrototype` | Prototype face. |
| 22 | `CastLeftRoom` | Room left door. |
| 23 | `CastRightRoom` | Room right door. |
| 24 | `CastOmen` | Omen face. |

The offer and submission rules live in the actions overview.

## Game-state type and update

| Value | `GameStateType` | Value | `GameStateUpdate` |
|---:|---|---:|---|
| 1 | `Full` | 0 | `None` |
| 2 | `Diff` | 1 | `Send` |
| 3 | `Binary` | 2 | `SendAndRecord` |
| | | 3 | `SendHiFi` |
| | | 4 | `Undo` |
| | | 5 | `Restore` |

The two are independent axes of one GSM. Their meanings and constraints live
in [messages](../stream/messages.md#update-and-snapshot).

## Action highlights

| Value | Highlight |
|---:|---|
| 0 | `None` |
| 1 | `Cold` |
| 2 | `Tepid` |
| 3 | `Hot` |
| 4 | `Counterspell` |
| 5 | `Random` |
| 6 | `CopySpell` |
| 7 | `ReplaceRole` |

`Cold` marks an offered action on `ActionsAvailableReq`. `Action.shouldStop`
is independent of `highlight`: it breaks automatic passing for that offer.

## Results

| Enum | Values |
|---|---|
| `GameStage` | `Start`, `Play`, `GameOver` |
| `MatchState` | `GameInProgress`, `GameComplete`, `MatchComplete`, `Sideboarding` |
| `ResultType` | `Suspended`, `Draw`, `WinLoss` |
| `ResultReason` | `Game`, `Concede`, `Timeout`, `Loop`, `Force` |
| `MatchScope` | `Game`, `Match` |
| `MatchWinCondition` | `SingleElimination`, `Best2Of3`, `Best3Of5` |

`ResultSpec` carries `scope`, `result`, `winningTeamId` and `reason`.
`LossOfGame.reason` is a separate string domain for state-based loss causes.

## Combat

| Enum | Values |
|---|---|
| `DamageRecType` | `Team`, `Player`, `PlanesWalker` |
| `AttackWarningType` | `MustAttackWithAtLeastOne`, `CannotAttackAlone`, `MustAttack`, `UnpayableEffectCost`, `CannotBeAttackedByMoreThanOne` |
| `BlockWarningType` | `InsufficientBlockers`, `CannotBlockAlone`, `MustBeBlocked`, `MustBlock`, `MustBeBlockedByAll` |

## Timers

| Enum | Values |
|---|---|
| `TimerType` | `Decision`, `Inactivity`, `ActivePlayer`, `NonActivePlayer`, `Prologue`, `Epilogue`, `Delay`, `MatchClock` |
| `TimerBehavior` | `Timeout`, `AutoRespond`, `TakeControl`, `ReleaseControl`, `Start`, `Restart`, `Stop`, `Reset`, `Activate`, `Deactivate`, `StartDelayedTimer` |

Their use on the stream lives in [timers](../stream/timers.md).

## Contract

- <a id="Enums.phase-step"></a>**Enums.phase-step.** `turnInfo.phase` and
  `turnInfo.step` `MUST` pair as in the turn-structure table. Both main phases
  carry step `0`.
- <a id="Enums.first-strike-step"></a>**Enums.first-strike-step.** The
  first-strike combat damage step `MUST` be step `11` in phase `Combat`, and
  `MUST NOT` be reported as step `7`.
- <a id="Enums.action-type-detail"></a>**Enums.action-type-detail.** An
  annotation detail that names an action type, such as
  `UserActionTaken.actionType`, `MUST` carry that action type's integer value
  from the action-types table.

## Open points

- What do action type `ActivateTest`, game-state type `Binary`, and updates
  `None` and `Restore` represent on the stream?
- Which highlights other than `Cold` appear on offered actions, and what
  selects them?
- Which `AttackWarningType` values are sent, and on which requests?
- What role does `TimerType` `Decision` play, if any?
