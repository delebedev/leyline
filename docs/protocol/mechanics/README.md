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
| [Enchant](Enchant.md) | Aura cast, target exchange, attachment | Restricts what an Aura spell targets and what the Aura enchants. |
| [Exploit](Exploit.md) | Enters trigger, resolution SelectNReq, sacrifice transfer | Optionally sacrifices a creature as the enters trigger resolves. |
| [Extort](Extort.md) | Cast trigger, optional payment | Drains each opponent when the controller pays after casting a spell. |
