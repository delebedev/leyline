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
| [Ninjutsu](Ninjutsu.md) | Hand activation, return cost, reveal, `Put` | Put a ninja from the hand onto the Battlefield attacking by returning an unblocked attacker. |
| [Reconfigure](Reconfigure.md) | Attach and unattach activations, attachment rows, type change | Attach an Equipment creature to a creature, or unattach it so it is a creature again. |
| [Station](Station.md) | Activation, tap cost, charge counters, striation rows | Tap a creature to add charge counters; striations apply at their charge thresholds. |
| [Unearth](Unearth.md) | Graveyard activation, `Return`, designation, pending exile | Return a card from the graveyard with haste until it is exiled at the next end step. |
