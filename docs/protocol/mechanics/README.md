---
summary: "Mechanic family overview: how a rules mechanic maps onto prompts, actions, annotations, objects and designations, and one row per mechanic."
read_when:
  - "implementing or reviewing a mechanic's player flow against the protocol"
  - "finding which protocol entities a mechanic uses"
---
# Mechanics

A mechanic page follows one rules mechanic across the protocol entities it
uses. A mechanic can span an offer, a prompt, an accepted action, stack
objects, annotations and zone changes while keeping one rules identity. The
entity pages define each shape; a mechanic page states the sequence and the
values that are specific to the mechanic.

Mechanics that also create a durable state flag, such as Saddle or Plot, have
that flag described under [designations](../designations/README.md).

## Mechanics

| Mechanic | Surfaces | Role |
|---|---|---|
| [Earthbend](Earthbend.md) | Target prompt, layered effects, counters, AnimatedLand | Animates a land its controller controls and refreshes the animation when repeated. |
| [Emerge](Emerge.md) | Alternative-cost cast, sacrifice cost | Casts a spell for its emerge cost by sacrificing a creature. |
| [Enchant](Enchant.md) | Aura cast, target exchange, attachment | Restricts what an Aura spell targets and what the Aura enchants. |
| [Endure](Endure.md) | Resolution OptionalActionMessage, counters or token | Puts +1/+1 counters on a permanent or creates a Spirit token. |
| [Enlist](Enlist.md) | Attack alternative, PayCostsReq tap cost, linked trigger | Taps a nonattacking creature as a creature attacks to add its power. |
| [Equip](Equip.md) | Activated ability, target exchange, attachment | Attaches an Equipment to a target creature its controller controls. |
| [Escape](Escape.md) | Graveyard Cast, PayCostsReq exile cost, CastingTimeOption | Casts a card from the graveyard for its escape cost. |
| [Evoke](Evoke.md) | Alternative-cost cast, TemporaryPermanent, sacrifice trigger | Casts a card for its evoke cost and sacrifices the permanent as it enters. |
| [Exploit](Exploit.md) | Enters trigger, resolution SelectNReq, sacrifice transfer | Optionally sacrifices a creature as the enters trigger resolves. |
| [Extort](Extort.md) | Cast trigger, optional payment | Drains each opponent when the controller pays after casting a spell. |
