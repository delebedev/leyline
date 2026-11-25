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
| [Ascend](Ascend.md) | Count, blessing | Seat-owned permanent count that grants the city's blessing at ten. |
| [Backup](Backup.md) | Enters trigger, target step, counters | Enters trigger that puts +1/+1 counters on a target creature. |
| [BattleCry](BattleCry.md) | Attack trigger, pump | Attack trigger that gives each other attacker +1/+0. |
| [Behold](Behold.md) | Cast submit, paid cost, hand reveal | Optional additional cost paid by revealing a qualifying card. |
| Blight | Not yet characterized | Keyword action that puts -1/-1 counters on a creature its controller chooses, often as an additional cost. |
| Blitz | Not yet characterized | Alternative cost that gives haste, draws a card when the creature dies, and sacrifices it at the next end step. |
| [Boast](Boast.md) | Offer, activation, use recorded | Once-per-turn activated ability of a creature that attacked. |
| [Cascade](Cascade.md) | Keyword stack ability, cast-permission window, free cast, residue | Cast trigger that exiles to a free cast of the first eligible card. |
| [Casualty](Casualty.md) | Chosen cost, sacrifice, copy trigger, copy | Optional creature sacrifice while casting that copies the spell. |
| [Cleave](Cleave.md) | Offer and accepted cast, cast marker, text change | Alternative cost from the hand that removes bracketed text. |
| [CollectEvidence](CollectEvidence.md) | Count, chosen cost, payment, cast submit | Additional cost paid by exiling graveyard cards by total mana value. |
| [Companion](Companion.md) | Chosen companion, put into the hand | Card from outside the game marked from the first state and put into the hand for `{3}`. |
| [Convoke](Convoke.md) | Payment step, payment bracket, count, resolution label | Tapping creatures to pay part of a spell's mana cost. |
| CraftWith | Not yet characterized | Activated ability that exiles its source and the named materials, then returns the source transformed. |
| [Crew](Crew.md) | Activation, total-power cost, helpers tapped, type change | Vehicle ability paid by tapping creatures with enough total power. |
| [Cycling](Cycling.md) | Activation from the hand, discard cost | Hand-zone activated ability whose cost discards the card. |
| Dash | Not yet characterized | Alternative cost that gives haste and returns the permanent to its owner's hand at the next end step. |
| [Decayed](Decayed.md) | Attack trigger, delayed cleanup, sacrifice | Attack trigger that sacrifices the creature at end of combat. |
| Delve | Not yet characterized | Exiling graveyard cards to pay generic mana while casting a spell. |
| [Discover](Discover.md) | Discover ability, cast-permission window, free cast, residue | Keyword action that exiles to a free cast or puts the card into the hand. |
| [Disguise](Disguise.md) | Face-down cast, face-down state, turning face up | Face-down 2/2 ward cast for `{3}`, turned face up for its disguise cost. |
| [Disturb](Disturb.md) | Graveyard cast, back face | Casting a double-faced card transformed from the graveyard. |
| [Earthbend](Earthbend.md) | Target prompt, layered effects, counters, AnimatedLand | Animates a land its controller controls and refreshes the animation when repeated. |
| [Emerge](Emerge.md) | Alternative-cost cast, sacrifice cost | Casts a spell for its emerge cost by sacrificing a creature. |
| [Enchant](Enchant.md) | Aura cast, target exchange, attachment | Restricts what an Aura spell targets and what the Aura enchants. |
| [Endure](Endure.md) | Resolution OptionalActionMessage, counters or token | Puts +1/+1 counters on a permanent or creates a Spirit token. |
| [Enlist](Enlist.md) | Attack alternative, PayCostsReq tap cost, linked trigger | Taps a nonattacking creature as a creature attacks to add its power. |
| [Equip](Equip.md) | Activated ability, target exchange, attachment | Attaches an Equipment to a target creature its controller controls. |
| [Escape](Escape.md) | Graveyard Cast, PayCostsReq exile cost, CastingTimeOption | Casts a card from the graveyard for its escape cost. |
| [Evoke](Evoke.md) | Alternative-cost cast, TemporaryPermanent, sacrifice trigger | Casts a card for its evoke cost; an enters trigger sacrifices the permanent when it resolves. |
| [Exploit](Exploit.md) | Enters trigger, resolution SelectNReq, sacrifice transfer | Optionally sacrifices a creature as the enters trigger resolves. |
| [Extort](Extort.md) | Cast trigger, optional payment | Drains each opponent when the controller pays after casting a spell. |
| [Firebending](Firebending.md) | Attack trigger, mana pool, ManaDetails | Adds red mana on attack that is kept until end of combat. |
| [Flashback](Flashback.md) | Graveyard Cast, CastingTimeOption, exile on leaving the stack | Casts an instant or sorcery from the graveyard and exiles it afterwards. |
| Flurry | Count row, trigger | Ability word for triggers on casting the second spell in a turn; `AbilityWordActive` `Flurry` counts spells against `threshold`. |
| [Foretell](Foretell.md) | Special action, FaceDown exile, Cast from exile | Exiles a card face down for {2} and casts it later for its foretell cost. |
| [Gift](Gift.md) | CastingTimeOptionsReq AdditionalCost, CastingTimeOption | Promises an opponent a benefit as a spell is cast. |
| [Harmonize](Harmonize.md) | Graveyard Cast, CastingTimeOptionsReq Selection, CastingTimeOption | Casts a card from the graveyard, tapping a creature to reduce the cost. |
| [Impending](Impending.md) | Alternative-cost cast, time counters, end-step trigger | Casts a permanent that counts down with time counters before it is a creature. |
| [Improvise](Improvise.md) | Hand Cast, artifact payment | Taps artifacts to pay generic mana while casting a spell. |
| [Increment](Increment.md) | Cast trigger, counter | Puts a +1/+1 counter on a creature when enough mana was spent on a spell. |
| Infusion | Label row | Ability word for effects conditioned on gaining life this turn; a label-only `AbilityWordActive` `Infusion` row. |
| [JobSelect](JobSelect.md) | Enters trigger, token, attachment | Creates a Hero token and attaches the Equipment to it. |
| [JumpStart](JumpStart.md) | Graveyard Cast, CastingTimeOption, discard cost, exile on leaving the stack | Casts an instant or sorcery from the graveyard by discarding a card. |
| [Learn](Learn.md) | SelectNReq 147/148, Sideboard to Hand | Discards to draw, or puts a Lesson from outside the game into the hand. |
| [Madness](Madness.md) | Discard to exile, trigger, staged Cast, CastingTimeOption | Discards a card into exile and lets its owner cast it for its madness cost. |
| [ManifestDread](ManifestDread.md) | SelectNReq 13125, ManifestLike, FaceDown, turn face up | Manifests one of the top two library cards face down. |
| [MaxSpeed](MaxSpeed.md) | PlayerSpeed, AddAbility | Grants an ability while its controller's speed is 4. |
| [Mentor](Mentor.md) | Attack trigger, SelectTargetsReq 2247, counter | Puts a +1/+1 counter on an attacking creature with lesser power. |
| [Mobilize](Mobilize.md) | Attack trigger, tokens, delayed cleanup | Creates attacking Warrior tokens and sacrifices them at end step. |
| [Mutate](Mutate.md) | Hand Cast, SelectTargetsReq 141, CastingTimeOption, merge LayeredEffect | Merges a creature spell into a non-Human creature with the same owner. |
| [Ninjutsu](Ninjutsu.md) | Hand activation, return cost, reveal, `Put` | Put a ninja from the hand onto the Battlefield attacking by returning an unblocked attacker. |
| [Offspring](Offspring.md) | Optional-cost choice, enters trigger, token copy | Pay an optional cost so the creature makes a 1/1 token copy of itself when it enters. |
| [OpeningHand](OpeningHand.md) | Opening-hand ability, Put transfer, dedicated action type | Use a card from the opening hand before the first turn, such as starting with it on the battlefield. |
| Opus | Cast trigger, label row | Ability word for triggers on casting an instant or sorcery; a label-only `Opus` row marks the mana-spent branch. |
| [Overload](Overload.md) | Alternative-cost cast without targets | Cast a spell for its overload cost so it affects each object instead of a target. |
| [Paradigm](Paradigm.md) | Self-exile, trigger holder, free copy cast | Exile the spell as it resolves, then cast a free copy of it in each later precombat main phase. |
| [PowerUp](PowerUp.md) | Once-only activation, exhausted-use row | Activate a once-only ability, cheaper the turn the permanent entered. |
| Proliferate | Keyword action | Add one counter of each kind already there to chosen permanents and players. |
| [Prowess](Prowess.md) | Cast trigger, temporary +1/+1 | Give a creature +1/+1 until end of turn whenever its controller casts a noncreature spell. |
| [Reconfigure](Reconfigure.md) | Attach and unattach activations, attachment rows, type change | Attach an Equipment creature to a creature, or unattach it so it is a creature again. |
| Renew | Ability word | Graveyard activated ability that exiles its source as a cost. |
| Retrace | Alternative cast from the graveyard | Cast from the graveyard by discarding a land card as an additional cost. |
| [Saddle](Saddle.md) | Activation, total-power helper payment, helper roster | Tap creatures with enough total power to make a Mount saddled this turn; see [Saddled](../designations/Saddled.md). |
| [SagaChapter](SagaChapter.md) | Lore counters, chapter triggers, final sacrifice | Advance a Saga with lore counters, trigger each chapter, and sacrifice it after the last. |
| [Sneak](Sneak.md) | Hand alternative cost, return cost | Cast a spell during declare blockers by returning an unblocked attacker; a permanent enters attacking. |
| [Spectacle](Spectacle.md) | Alternative-cost cast, condition row | Cast a spell for its spectacle cost after an opponent lost life this turn. |
| [Spree](Spree.md) | Modal casting-time option with per-mode costs | Choose one or more modes while casting and pay each chosen mode's cost. |
| [Station](Station.md) | Activation, tap cost, charge counters, striation rows | Tap a creature to add charge counters; striations apply at their charge thresholds. |
| Storied | Static ability, player designation | Give its controller the enduring story designation once they control three artifacts, Sagas or legendary permanents; Arena shows progress in a `Storied` `AbilityWordActive` row. |
| Surge | Alternative cost | Cast for the surge cost after its controller cast another spell this turn. |
| [Teamwork](Teamwork.md) | Optional-cost choice, weighted creature payment | Pay an optional additional cost by tapping creatures with enough total power. |
| [Tiered](Tiered.md) | Modal casting-time option with one tier | Choose exactly one tier while casting and pay its cost. |
| [Training](Training.md) | Attack trigger, +1/+1 counter, condition row | Put a +1/+1 counter on a creature that attacks with a creature of greater power. |
| [Unearth](Unearth.md) | Graveyard activation, `Return`, designation, pending exile | Return a card from the graveyard with haste until it is exiled at the next end step. |
| [Ward](Ward.md) | Trigger on being targeted, tax `PayCostsReq` | Counter an opponent's spell or ability that targets the permanent unless its controller pays the ward cost. |
| [Warp](Warp.md) | Hand alternative cost, end-step exile, cast from exile | Cast a card cheaply from the hand, exile it at end step, and cast it again from exile later. |
| [Waterbend](Waterbend.md) | Optional-cost choice, tap-to-pay mana actions | Pay generic mana of a waterbend cost by tapping artifacts and creatures. |
