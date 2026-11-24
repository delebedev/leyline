---
summary: "DungeonStatus annotation: persistent per-player dungeon progress (current dungeon, room and completed dungeons); rules grounding and the open questions, since its shape is not yet established."
entity: annotation/DungeonStatus
---
# DungeonStatus

## Meaning

Venturing into the dungeon puts a dungeon card into the command zone and
moves its venture marker from room to room (CR 701.49). A dungeon is
completed when it is removed from the game after its venture marker reaches
the bottommost room. `DungeonStatus` publishes one player's
dungeon progress: the dungeon they are in, the room they are in, and the
dungeons they have completed.

## Rules grounding

A player owns at most one dungeon in the command zone at a time (CR 309.3),
and a venture marker on it shows the current room (CR 309.4). When the
marker is on the bottommost room and no room ability of the dungeon is
waiting to resolve, the dungeon is removed from the game (CR 309.6), and the
player completes it (CR 309.7).

## Variants

Persistent. The detail keys and their value types are not established; see
Open points.

## Contract

No statements yet. The row's fields, identities and lifecycle are not
established.

## Open points

- Which detail keys name the current dungeon, its command-zone instance, the
  current room and the completed dungeons, and with which value types?
- Are `affectorId` and `affectedIds` the player's seat?
- Is the row republished under one `id` as the marker moves, and what does it
  carry once a dungeon is completed?
- Where in the GSM does the row appear relative to the room ability's
  `AbilityInstanceCreated` and the venture ability's `ResolutionComplete`?
