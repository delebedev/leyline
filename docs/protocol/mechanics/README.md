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
| [Afterlife](Afterlife.md) | Dies trigger, token creation | Dies trigger that creates Spirit tokens. |
| [BattleCry](BattleCry.md) | Attack trigger, pump | Attack trigger that gives each other attacker +1/+0. |
| [Cascade](Cascade.md) | Keyword stack ability, cast-permission window, free cast, residue | Cast trigger that exiles to a free cast of the first eligible card. |
| [Companion](Companion.md) | Chosen companion, put into the hand | Card from outside the game marked from the first state and put into the hand for `{3}`. |
| [Convoke](Convoke.md) | Payment step, payment bracket, count, resolution label | Tapping creatures to pay part of a spell's mana cost. |
| [Crew](Crew.md) | Activation, total-power cost, helpers tapped, type change | Vehicle ability paid by tapping creatures with enough total power. |
| [Cycling](Cycling.md) | Activation from the hand, discard cost | Hand-zone activated ability whose cost discards the card. |
| [Discover](Discover.md) | Discover ability, cast-permission window, free cast, residue | Keyword action that exiles to a free cast or puts the card into the hand. |
| [Disguise](Disguise.md) | Face-down cast, face-down state, turning face up | Face-down 2/2 ward cast for `{3}`, turned face up for its disguise cost. |
| [Disturb](Disturb.md) | Graveyard cast, back face | Casting a double-faced card transformed from the graveyard. |
