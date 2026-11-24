---
summary: "ZoneTransfer annotation: an object moved between zones; source and destination zones, the category that names the cause, the moved object's identity and the affector by cause."
entity: annotation/ZoneTransfer
---
# ZoneTransfer

## Meaning

`ZoneTransfer` reports that one object moved from one zone to another and
names why. It is the event row for every ordinary zone change: drawing,
casting, playing a land, resolving, dying, exiling, milling and the rest.

## Rules grounding

An object that moves between zones becomes a new object (CR 400.7), so most
transfers come with an [ObjectIdChanged](ObjectIdChanged.md). Permanent spells
become permanents on resolution (CR 608.3) and keep their identity across that
move.

## Variants

One transient row per moved object. `category` is the discriminator. Its values
fall into three groups:

- movement causes, such as `Draw`, `CastSpell`, `PlayLand`, `Resolve`,
  `Discard`, `Destroy`, `Sacrifice`, `Exile`, `Countered`, `Return`, `Put`,
  `Mill`, `Surveil`, `Seek` and `Warp`;
- state-based actions, prefixed `SBA_`, such as `SBA_Damage`,
  `SBA_ZeroToughness`, `SBA_Deathtouch`, `SBA_LegendRule`,
  `SBA_UnattachedAura` and `SBA_ZeroLoyalty`;
- mechanic-specific causes, such as `Foretell`, `Warp`, `Seek`, `Conjure`,
  `Copy` and `ManifestLike`.

The category set is open: new mechanics add values. A related mechanic does not
imply a dedicated category; a plotted card, for example, moves to exile with
the generic `Exile` category.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The source of the move, by cause; see Identity model. Absent for some causes. |
| `affectedIds` | The moved object, as a one-element list, under its ID after the move. |
| `zone_src` | The zone ID the object left. |
| `zone_dest` | The zone ID the object entered. |
| `category` | The cause of the move, as a string. |

## Identity model

`affectedIds` names the object after the move. When the move renames it, that
is the `new_id` of the `ObjectIdChanged` in the same GSM; when it does not, as
for a permanent spell resolving onto the battlefield, it is the unchanged ID.
An object that moves twice in one GSM, such as a flickered permanent, is
renamed twice: each `ZoneTransfer` names the `new_id` of the
`ObjectIdChanged` just before it, so the first names the intermediate ID.

`affectorId` depends on the category:

- `Resolve` names the seat that controls the resolving spell when that seat
  also owns it. For a spell controlled by a player who does not own it,
  either seat occurs; see Open points.
- `SBA_` categories have no affector: the game, not an object, moves the card.
- Playing a land from hand has no affector. Casting and drawing carry one
  only in some cases; see Open points.
- Moves caused by a resolving spell or ability, such as `Destroy`, `Exile`,
  `Sacrifice` or `Return`, name that spell or ability instance.

Zone IDs are fixed: `27` is the Stack, `28` the Battlefield, `29` Exile, and
each seat has its own hand, library and graveyard zones.

## Lifecycle

Typical sequences:

1. **Cast from hand.** `ObjectIdChanged` maps the hand ID to a stack ID, then
   `ZoneTransfer` moves that stack ID from the hand zone to `27` with
   `CastSpell`.
2. **Play a land.** `ObjectIdChanged` maps the hand ID to a battlefield ID,
   then `ZoneTransfer` moves it from the hand zone to `28` with `PlayLand`.
3. **Resolve.** `ResolutionStart` and `ResolutionComplete` bracket the
   resolution. A permanent spell moves from `27` to `28` with `Resolve` under
   its stack ID; any other spell is renamed and moves to its destination, such
   as its owner's graveyard, with `Resolve`.
4. **State-based death.** A creature with lethal damage moves from `28` to its
   owner's graveyard with `SBA_Damage`.

The destination zone's [EnteredZoneThisTurn](EnteredZoneThisTurn.md) row in
the same GSM lists the moved object.

## Sibling shapes

`ObjectIdChanged` records identity, not movement; a rename without a move, such
as a shuffle, has no `ZoneTransfer`. Parking a retired ID in Limbo is not a
transfer. Phasing in or out carries no `ZoneTransfer`.

## Contract

- <a id="ZoneTransfer.single-object"></a>**ZoneTransfer.single-object.**
  A `ZoneTransfer` row `MUST` carry exactly one ID in `affectedIds`.
- <a id="ZoneTransfer.post-transfer-id"></a>**ZoneTransfer.post-transfer-id.**
  When an `ObjectIdChanged` for the moved object precedes the
  `ZoneTransfer` in the same GSM, `affectedIds` `MUST` be the `new_id` of the
  last such row, not an `orig_id`.
- <a id="ZoneTransfer.cast-from-hand"></a>**ZoneTransfer.cast-from-hand.**
  Casting a spell from hand `MUST` produce a `ZoneTransfer` with `category`
  `CastSpell`, `zone_src` the caster's hand zone and `zone_dest` `27`.
- <a id="ZoneTransfer.play-land"></a>**ZoneTransfer.play-land.**
  Playing a land from hand `MUST` produce a `ZoneTransfer` with `category`
  `PlayLand`, `zone_src` the player's hand zone and `zone_dest` `28`.
- <a id="ZoneTransfer.sba-no-affector"></a>**ZoneTransfer.sba-no-affector.**
  A `ZoneTransfer` whose `category` starts with `SBA_` `MUST NOT` carry an
  `affectorId`.
- <a id="ZoneTransfer.resolve-affector"></a>**ZoneTransfer.resolve-affector.**
  *(provisional)* For a spell resolving from the stack whose controller owns
  it, a `ZoneTransfer` with `category` `Resolve` `MUST` carry `affectorId`
  equal to that seat.

## Open points

- What does an empty or absent `category` mean?
- What selects an `affectorId` on a `CastSpell` or `Draw` transfer?
- Which source does `affectorId` name for moves caused by a triggered ability
  versus the spell that created it?
- Which moves of a resolving spell use `Resolve` and which use the cause
  category of its effect?
- Which seat does `Resolve` name for a spell whose controller does not own
  it?
