---
summary: "Gift: promising a benefit to an opponent as a cast-time option; the AdditionalCost row of CastingTimeOptionsReq, the persistent AdditionalCost marker and the promised branch."
entity: mechanic/Gift
---
# Gift

Gift lets a caster promise an opponent a listed benefit as they cast the
spell; if they do, the spell or permanent gets its promised effect. The
promise is a [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md)
with one `AdditionalCost` option and a `Done` option. The choice is recorded
on the stack spell by the `AdditionalCost` branch of
[CastingTimeOption](../annotations/CastingTimeOption.md).

## Rules grounding

"Gift a [something]" is an optional additional cost: choosing an opponent as
the spell is cast. A second ability gives that opponent the gift and changes
the spell's effect when the gift was promised; on a permanent it is an
enters trigger (CR 702.174a, CR 702.174b). The
choice needs no extra mana. For an instant or sorcery, the gift is given
before the spell's other effects (CR 702.174j). Targets that exist only on the
promised branch are chosen only if the gift was promised (CR 702.174m).

## Surfaces

| Surface | Entity pages |
|---|---|
| Promise | [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md) |
| Cast marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `5` |
| Promised targets | [SelectTargetsReq](../prompts/SelectTargetsReq.md) |

The gift ability ID names the kind of gift, not the card: every card that
gifts a tapped Fish, for example, uses the same ability.

## Cast lifecycle

1. The card moves to the stack with `CastSpell` under a new instance ID.
2. The caster receives a `CastingTimeOptionsReq` with prompt `23` and
   `allowCancel` `Abort`. Its options are one `AdditionalCost` row, whose
   `grpId` is the gift ability and whose `affectorId` and `affectedId` are
   the stack spell, and a `Done` row for casting without the promise.
3. Choosing the `AdditionalCost` row promises the gift. The stack spell then
   carries a persistent `CastingTimeOption` row of `type` `5` with the gift
   ability in `additionalCostGrpId`.
4. Targets for the promised branch, if any, are chosen after the promise.
5. An instant or sorcery gives the gift as it resolves, before its other
   effects. A permanent gives it through an enters trigger.

## Sibling shapes

Kicker and other optional costs use the same request. Their option rows are
typed `Kicker` or `AdditionalCost` too, but they add a cost. A gift row's
`manaCost` does not add mana to the spell; it is not the price of the
promise.

## Contract

- <a id="Gift.option"></a>**Gift.option.** *(provisional)* The gift promise
  `MUST` be offered in a `CastingTimeOptionsReq` with prompt `23` as an
  `AdditionalCost` row whose `grpId` is the gift ability, beside a `Done` row.
- <a id="Gift.cast-marker"></a>**Gift.cast-marker.** *(provisional)* A spell
  cast with the gift promised `MUST` carry a persistent `CastingTimeOption`
  row of `type` `5` with the gift ability in `additionalCostGrpId`.

The response is stated by
[CastingTimeOptionsReq.resp-cost-ctoid](../prompts/CastingTimeOptionsReq.md#CastingTimeOptionsReq.resp-cost-ctoid)
and [CastingTimeOptionsReq.resp-done](../prompts/CastingTimeOptionsReq.md#CastingTimeOptionsReq.resp-done).

## Open points

- What does the gift row's `manaCost` carry?
- With more than one opponent, how is the chosen opponent requested and
  recorded?
- Does a permanent spell with gift carry the promise past resolution?
