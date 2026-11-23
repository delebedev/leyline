---
summary: "Shared identity model: numeric identity domains, the fixed zone layout, stack order, visibility, and how instance IDs are replaced and retired."
read_when:
  - "interpreting an instance ID, zone ID or seat ID in any GRE message"
  - "projecting zones, hidden information or the stack"
  - "allocating, remapping or retiring game object instance IDs"
entity: model/IdentityAndZones
---
# Identity and zones

## Meaning

Seats, zones, game objects, synthetic effects and replacement events share
one numeric identity space. A number's domain comes from the field that
carries it and from its value range; the number alone is not a universal
object identifier.

## Identity domains

| Range | Domain | Typical fields |
|---|---|---|
| `1`–`2` | Player seat | `ModifiedLife.affectedIds`, `turnInfo.activePlayer`, result messages |
| `12`, `18`–`38` | Zone | `zoneId`, `ZoneTransfer` `zone_src` and `zone_dest` |
| above `38` | Game object instance | Cards, abilities, tokens and most annotation identities |
| `7000` and above | Synthetic effect | Layered-effect identities |
| `9000` and above | Replacement event | Replacement-effect lifecycle identities |
| absent | System or no source | Phase, game-result and system-owned rows |

The high ranges are conventions of their owning fields, not reserved integer
types. Read each value through the field that carries it.

## Zone layout

Shared zones have one ID. Each seat's personal zones occupy a fixed run.

| ID | Zone | Seat | Default visibility |
|---:|---|:---:|---|
| 12 | PhasedOut | shared | Hidden |
| 18 | Revealed | 1 | Public |
| 19 | Revealed | 2 | Public |
| 24 | Suppressed | shared | Public |
| 25 | Pending | shared | Public |
| 26 | Command | shared | Public |
| 27 | Stack | shared | Public |
| 28 | Battlefield | shared | Public |
| 29 | Exile | shared | Public |
| 30 | Limbo | shared | Public |
| 31 | Hand | 1 | Private |
| 32 | Library | 1 | Hidden |
| 33 | Graveyard | 1 | Public |
| 34 | Sideboard | 1 | Private |
| 35 | Hand | 2 | Private |
| 36 | Library | 2 | Hidden |
| 37 | Graveyard | 2 | Public |
| 38 | Sideboard | 2 | Private |

The recipient's own seat, announced at connection (see
[messages](../stream/messages.md#seat-identity)), turns the fixed per-seat
runs into own and opponent zones.

### Stack order

The Stack zone's `objectInstanceIds` lists the top of the stack first. Spell
cards and ability objects share one order; object type does not group them.
An ability therefore precedes the spell below it while that spell waits on
the stack, as when a cascade ability resolves above the spell that triggered
it.

## Visibility

| Value | Name | Meaning |
|---:|---|---|
| 0 | `None` | Fallback; read as public. |
| 1 | `Public` | Identity is visible to both seats. |
| 2 | `Private` | Identity is visible to the seats listed in `viewers`. |
| 3 | `Hidden` | The zone lists members without exposing their identities. |
| 4 | `Deceptive` | Defined; see Open points. |

A zone's visibility supplies the default for its members.
`GameObjectInfo.visibility` can override it for one object, so a face-down
card's hidden identity belongs to the object rather than to the Exile or
Battlefield zone.

A zone the recipient cannot see into still lists every member in
`objectInstanceIds`. That is how the client shows the size of an opponent's
hand or library. A member carries card identity for that viewer only while it
is revealed.

## Instance identity over time

A game object's instance ID names one lifetime in one zone. Moving the object
normally allocates a new ID:

1. `ObjectIdChanged` maps `orig_id` to `new_id` in the GSM where the move
   happens.
2. The new ID becomes the object's identity in its destination. The old ID
   is listed in the Limbo zone, with no game object of its own, so the client
   can reconcile references to it. Parking the old ID in Limbo is not a card
   movement and carries no `ZoneTransfer`.
3. `diffDeletedInstanceIds` removes game objects from the running diff state.
   It is distinct from `diffDeletedPersistentAnnotationIds`, which retires
   persistent annotations; a GSM can carry either or both.

A resolving permanent spell keeps its stack ID when it enters the
battlefield. A resolving spell that goes anywhere else receives a new ID.

## Contract

- <a id="IdentityAndZones.zone-ids"></a>**IdentityAndZones.zone-ids.** Zone
  IDs `MUST` follow the layout table: each shared zone has its listed ID, and
  each seat's Revealed, Hand, Library, Graveyard and Sideboard zones have the
  IDs listed for that seat.
- <a id="IdentityAndZones.stack-top-first"></a>**IdentityAndZones.stack-top-first.**
  The Stack zone's `objectInstanceIds` `MUST` list the top object first, with
  spells and abilities interleaved in stack order.
- <a id="IdentityAndZones.hidden-members"></a>**IdentityAndZones.hidden-members.**
  A zone whose members the recipient cannot see `MUST` still list every
  member in `objectInstanceIds`, and `MUST NOT` send that viewer a game object
  with the member's card identity unless the member is revealed to it.
- <a id="IdentityAndZones.limbo-retires"></a>**IdentityAndZones.limbo-retires.**
  When `ObjectIdChanged` replaces an object's ID, the old ID `MUST` appear in
  the Limbo zone's `objectInstanceIds`, with no `GameObjectInfo` for it and no
  `ZoneTransfer` for the parking.
- <a id="IdentityAndZones.resolve-keeps-id"></a>**IdentityAndZones.resolve-keeps-id.**
  A spell that resolves onto the battlefield `MUST` keep its stack instance
  ID, with no `ObjectIdChanged`.
- <a id="IdentityAndZones.resolve-renames"></a>**IdentityAndZones.resolve-renames.**
  A spell that resolves to any zone other than the battlefield `MUST` receive
  a new instance ID through an `ObjectIdChanged` in the same GSM as its
  `Resolve` zone transfer.

## Open points

- What does `Deceptive` visibility project, and on which objects?
- Does any GSM carry a `ZoneInfo` for PhasedOut, or is `12` only an object's
  `zoneId`?
- In what order do Graveyard and Exile list their members?
- When one GSM replaces the same object's ID twice, does the second
  `ObjectIdChanged` always map from the first one's `new_id`?
