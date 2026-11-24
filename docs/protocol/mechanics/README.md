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
| [Firebending](Firebending.md) | Attack trigger, mana pool, ManaDetails | Adds red mana on attack that is kept until end of combat. |
| [Flashback](Flashback.md) | Graveyard Cast, CastingTimeOption, exile on leaving the stack | Casts an instant or sorcery from the graveyard and exiles it afterwards. |
| [Foretell](Foretell.md) | Special action, FaceDown exile, Cast from exile | Exiles a card face down for {2} and casts it later for its foretell cost. |
| [Gift](Gift.md) | CastingTimeOptionsReq AdditionalCost, CastingTimeOption | Promises an opponent a benefit as a spell is cast. |
| [Harmonize](Harmonize.md) | Graveyard Cast, CastingTimeOptionsReq Selection, CastingTimeOption | Casts a card from the graveyard, tapping a creature to reduce the cost. |
| [Impending](Impending.md) | Alternative-cost cast, time counters, end-step trigger | Casts a permanent that counts down with time counters before it is a creature. |
| [Improvise](Improvise.md) | Hand Cast, artifact payment | Taps artifacts to pay generic mana while casting a spell. |
| [Increment](Increment.md) | Cast trigger, counter | Puts a +1/+1 counter on a creature when enough mana was spent on a spell. |
| [JobSelect](JobSelect.md) | Enters trigger, token, attachment | Creates a Hero token and attaches the Equipment to it. |
| [JumpStart](JumpStart.md) | Graveyard Cast, CastingTimeOption, discard cost, exile on leaving the stack | Casts an instant or sorcery from the graveyard by discarding a card. |
| [Learn](Learn.md) | SelectNReq 147/148, Sideboard to Hand | Discards to draw, or puts a Lesson from outside the game into the hand. |
| [Madness](Madness.md) | Discard to exile, trigger, staged Cast, CastingTimeOption | Discards a card into exile and lets its owner cast it for its madness cost. |
| [ManifestDread](ManifestDread.md) | SelectNReq 13125, ManifestLike, FaceDown, turn face up | Manifests one of the top two library cards face down. |
| [MaxSpeed](MaxSpeed.md) | PlayerSpeed, AddAbility | Grants an ability while its controller's speed is 4. |
| [Mentor](Mentor.md) | Attack trigger, SelectTargetsReq 2247, counter | Puts a +1/+1 counter on an attacking creature with lesser power. |
| [Mobilize](Mobilize.md) | Attack trigger, tokens, delayed cleanup | Creates attacking Warrior tokens and sacrifices them at end step. |
