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
| [Cascade](Cascade.md) | Keyword stack ability, cast-permission window, free cast, residue | Cast trigger that exiles to a free cast of the first eligible card. |
| [Convoke](Convoke.md) | Payment step, payment bracket, count, resolution label | Tapping creatures to pay part of a spell's mana cost. |
| [Crew](Crew.md) | Activation, total-power cost, helpers tapped, type change | Vehicle ability paid by tapping creatures with enough total power. |
| [Discover](Discover.md) | Discover ability, cast-permission window, free cast, residue | Keyword action that exiles to a free cast or puts the card into the hand. |
| [Disguise](Disguise.md) | Face-down cast, face-down state, turning face up | Face-down 2/2 ward cast for `{3}`, turned face up for its disguise cost. |
| [Disturb](Disturb.md) | Graveyard cast, back face | Casting a double-faced card transformed from the graveyard. |
