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
| [Offspring](Offspring.md) | Optional-cost choice, enters trigger, token copy | Pay an optional cost so the creature makes a 1/1 token copy of itself when it enters. |
| [Overload](Overload.md) | Alternative-cost cast without targets | Cast a spell for its overload cost so it affects each object instead of a target. |
| [Paradigm](Paradigm.md) | Self-exile, trigger holder, free copy cast | Exile the spell as it resolves, then cast a free copy of it in each later precombat main phase. |
| [PowerUp](PowerUp.md) | Once-only activation, exhausted-use row | Activate a once-only ability, cheaper the turn the permanent entered. |
| Proliferate | Keyword action | Add one counter of each kind already there to chosen permanents and players. |
| [Prowess](Prowess.md) | Cast trigger, temporary +1/+1 | Give a creature +1/+1 until end of turn whenever its controller casts a noncreature spell. |
| [Reconfigure](Reconfigure.md) | Attach and unattach activations, attachment rows, type change | Attach an Equipment creature to a creature, or unattach it so it is a creature again. |
| Renew | Ability word | Graveyard activated ability that exiles its source as a cost. |
| Retrace | Alternative cast from the graveyard | Cast from the graveyard by discarding a land card as an additional cost. |
| [Saddle](Saddle.md) | Activation, total-power helper payment, helper roster | Tap creatures with enough total power to make a Mount saddled this turn; see [Saddled](../designations/Saddled.md). |
| [Sneak](Sneak.md) | Hand alternative cost, return cost | Cast a spell during declare blockers by returning an unblocked attacker; a permanent enters attacking. |
| [Spectacle](Spectacle.md) | Alternative-cost cast, condition row | Cast a spell for its spectacle cost after an opponent lost life this turn. |
| [Spree](Spree.md) | Modal casting-time option with per-mode costs | Choose one or more modes while casting and pay each chosen mode's cost. |
| [Station](Station.md) | Activation, tap cost, charge counters, striation rows | Tap a creature to add charge counters; striations apply at their charge thresholds. |
| Storied | Ability word | Not yet described. |
| Surge | Alternative cost | Cast for the surge cost after its controller cast another spell this turn. |
| [Teamwork](Teamwork.md) | Optional-cost choice, weighted creature payment | Pay an optional additional cost by tapping creatures with enough total power. |
| [Tiered](Tiered.md) | Modal casting-time option with one tier | Choose exactly one tier while casting and pay its cost. |
| [Training](Training.md) | Attack trigger, +1/+1 counter, condition row | Put a +1/+1 counter on a creature that attacks with a creature of greater power. |
| [Unearth](Unearth.md) | Graveyard activation, `Return`, designation, pending exile | Return a card from the graveyard with haste until it is exiled at the next end step. |
| [Ward](Ward.md) | Trigger on being targeted, tax `PayCostsReq` | Counter an opponent's spell or ability that targets the permanent unless its controller pays the ward cost. |
| [Warp](Warp.md) | Hand alternative cost, end-step exile, cast from exile | Cast a card cheaply from the hand, exile it at end step, and cast it again from exile later. |
| [Waterbend](Waterbend.md) | Optional-cost choice, tap-to-pay mana actions | Pay generic mana of a waterbend cost by tapping artifacts and creatures. |
