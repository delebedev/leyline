---
summary: "Battle Cry: an attack trigger that gives each other attacking creature +1/+0; the trigger born with the attack declaration and one shared ModifiedPower layer row."
entity: mechanic/BattleCry
---
# Battle Cry

Battle Cry is an attack trigger. When its creature attacks, a triggered
[Ability](../object_types/Ability.md) with the shared ability `92` goes on the
stack; when it resolves, one layered effect gives every other attacking
creature +1/+0, published as a single persistent
`ModifiedPower` row covering all of them.

## Rules grounding

Battle cry means "Whenever this creature attacks, each other attacking
creature gets +1/+0 until end of turn" (CR 702.91a).

## Surfaces

| Surface | Entity pages | Battle Cry values |
|---|---|---|
| Attack trigger | [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md) | `grpId` `92`, `source_zone` `28` |
| Pump | [LayeredEffectCreated](../annotations/LayeredEffectCreated.md), `ModifiedPower` | `sourceAbilityGRPID` `92`, one row for all pumped attackers |

## Trigger lifecycle

1. The GSM that declares the attackers carries `AbilityInstanceCreated`
   from the Battle Cry creature with `source_zone` `28`. The ability object
   has `grpId` `92`.
2. When it resolves while the Battle Cry creature is still on the
   battlefield, one GSM carries `ResolutionStart` with `grpid` `92`,
   one `LayeredEffectCreated` with the ability as `affectorId`,
   `ResolutionComplete`, and `AbilityInstanceDeleted` from the creature.
3. The same GSM creates one persistent `ModifiedPower` and `LayeredEffect`
   row: the Battle Cry creature as `affectorId`, every other attacking
   creature in `affectedIds`, `sourceAbilityGRPID` `92` and the new
   `effect_id`. The Battle Cry creature itself is not in `affectedIds`.
4. No `PowerToughnessModCreated` row accompanies the pump.

## Sibling shapes

Other "attacking creatures get +N/+0" triggers use the same shape with their
own ability; Battle Cry is identified by ability `92`. A pump that targets
one creature creates a row with that creature alone in `affectedIds`.

## Contract

- <a id="BattleCry.trigger"></a>**BattleCry.trigger.** *(provisional)* The
  Battle Cry ability `MUST` be created with `grpId` `92` from its creature,
  with `source_zone` `28`, in the GSM that declares it as an attacker.
- <a id="BattleCry.shared-row"></a>**BattleCry.shared-row.** *(provisional)*
  When the resolution gives +1/+0 to at least one other attacking creature
  while the Battle Cry creature is on the battlefield, it `MUST` create
  exactly one persistent `ModifiedPower` row for the effect, naming the Battle
  Cry creature as `affectorId`, every other attacking creature in
  `affectedIds`, and `sourceAbilityGRPID` `92`.
- <a id="BattleCry.no-pt-mod"></a>**BattleCry.no-pt-mod.** *(provisional)*
  The resolution `MUST NOT` carry `PowerToughnessModCreated` rows for the
  pump.

## Open points

- In which GSM is the `ModifiedPower` row deleted at end of turn?
- What does the resolution carry when the Battle Cry creature has left the
  battlefield, given that the other attackers still get +1/+0?
- With no other attacker, does the resolution still create an effect and a
  row with empty `affectedIds`?
