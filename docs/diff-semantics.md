# GRE Diff Semantics — Findings from Recording Analysis

> **Status:** Working notes — derived from two recordings, not yet validated as
> ground truth. Patterns may not generalize to all game situations. Cross-check
> against additional recordings before treating as authoritative.

Based on analysis of two real Arena recordings:
- `on-play`: 31 messages, simple land-drop game ending in concession
- `on-draw-sparky-two-creatures-player-attacked`: 155 messages, full game with
  creature casts, aura targeting, ETB triggers, combat damage both ways

## 1. Full vs Diff GSM

The GameStateMessage has two modes:

| gsmType | Behavior |
|---------|----------|
| `Full`  | Replace all client state: zones, objects, players, turnInfo |
| `Diff`  | Incremental: delete listed ids, then upsert provided objects/zones |

**Diff application algorithm:**
1. For each id in `diffDeletedInstanceIds`: remove from objects map
2. For each id in `diffDeletedPersistentAnnotationIds`: remove from persistent annotations
3. For each object in `gameObjects`: upsert by `instanceId`
4. For each zone in `zones`: upsert by `zoneId`
5. If `turnInfo` present: replace entirely
6. If `players` present: upsert by `systemSeatNumber`

**Key finding:** Diff GSMs only include *changed* objects. If a zone's `objectInstanceIds`
list changes (because an object entered/left), the zone is re-sent with the complete
`objectInstanceIds` list. Objects that haven't changed are NOT re-sent.

## 2. Dual Delivery — Per-Seat GSMs

Every significant game state change is delivered as **two separate** MatchServiceToClientMessage
payloads — one for each seat. They share the same `msgId` and `gsId` but differ in:

| Field | Seat 1 (Sparky) | Seat 2 (Player) |
|-------|-----------------|------------------|
| `systemSeatIds` | `[1]` | `[2]` |
| Private objects | Not visible | Visible (with `viewers: [2]`) |
| Actions list | Seat 1's available actions | Seat 2's available actions |
| Object visibility | Public only | Public + own Private |

**Example:** gsId=7 in on-draw recording (Play Island):
- Seat 1 .bin: sees `279` on battlefield (Public), does NOT see `220` in Limbo
- Seat 2 .bin: sees `279` on battlefield AND `220` in Limbo (Private, viewers=[2])

**Implication for bridge:** The bridge must filter objects by visibility when
constructing per-seat views. A single Forge game state produces two different
GSM payloads.

## 3. ObjectIdChanged and the Limbo Zone

When a card changes zones, it gets a **new instanceId**. The protocol communicates
this as:

1. `ObjectIdChanged` annotation: `details.orig_id` → `details.new_id`
2. The old instanceId appears in Limbo zone (zoneId=30) with `visibility=Private`
3. The new instanceId appears in the destination zone with `visibility=Public`

**Zone transfer patterns observed:**

| Transfer | Category | Example |
|----------|----------|---------|
| Hand → Battlefield | `PlayLand` | 161→279 (Forest), 220→279 (Island) |
| Hand → Stack | `CastSpell` | 221→280 (Bird), 219→288 (Aura) |
| Stack → Battlefield | `Resolve` | 280 stays same id |
| Library → Hand | `Draw` | 166→282 (Raccoon) |

**Key finding:** `Stack → Battlefield` (Resolve) does NOT change the instanceId.
The card keeps its Stack id when it enters the battlefield. Only zone-transfers
that cross a visibility boundary (Private→Public or vice versa) produce id changes.

## 4. Transient Objects (Abilities, Mana)

Some objects are created and destroyed within a single GSM diff batch:

### Mana Abilities
```
AbilityInstanceCreated(source=279, ability=281)  // Island creates mana ability
TappedUntappedPermanent(281 taps 279)             // Ability taps the land
ManaPaid(279 pays for 280)                         // Mana spent on spell
AbilityInstanceDeleted(source=279, ability=281)   // Ability cleaned up
```
The ability instanceId (281) appears in `diffDeletedInstanceIds` of the same GSM.

### Triggered Abilities
ETB triggers go on the Stack as `type=Ability`:
```
gsId 55: Wall resolves → AbilityInstanceCreated(290→292), type=Ability on Stack
gsId 57: Trigger resolution starts (ResolutionStart), GroupReq for Scry
gsId 58: Scry completes → AbilityInstanceDeleted(290→292), 292 removed from Stack
```

**Implication for bridge:** The bridge must handle transient objects that
appear and disappear within a single diff. These should NOT be tracked as
persistent game objects.

## 5. Persistent Annotations

Persistent annotations survive across GSM diffs until explicitly deleted via
`diffDeletedPersistentAnnotationIds`. Key types:

| Type | Meaning | Lifetime |
|------|---------|----------|
| `EnteredZoneThisTurn` | Tracks which objects entered a zone this turn | Deleted on turn change |
| `ColorProduction` | What colors a land can produce | Permanent (until land leaves) |
| `Attachment` | Aura attached to creature | Permanent (until detached) |
| `ModifiedPower/Toughness/LayeredEffect` | P/T modification from Aura | Permanent |
| `TargetSpec` | Targeting info during spell cast | Deleted on resolution |
| `TriggeringObject` | Links trigger to its source | Deleted after trigger resolves |

## 6. Priority Passing Pattern

Each phase/step transition generates exactly **2** GSM diffs:
1. First: `priorityPlayer = activePlayer` (active player gets priority)
2. Second: `priorityPlayer = opponent` (priority passes)

When both players pass, the next phase/step begins.

**Pattern for a full turn with no actions:**
```
Upkeep:     priority→active, priority→opponent
Draw:       priority→active, priority→opponent
Main1:      priority→active, priority→opponent  (SendAndRecord)
BeginCombat: priority→active, priority→opponent
DeclareAttack: priority→active, priority→opponent
EndCombat:  priority→active, priority→opponent
Main2:      priority→active, priority→opponent
End:        priority→active, priority→opponent
Cleanup:    (implicit, no GSMs)
```

**Implication for bridge:** The bridge needs to emit 2 GSMs per phase transition,
even when no game actions are taken.

## 7. SendAndRecord vs SendHiFi

The `updateType` field signals how the client should handle the update:

| updateType | Meaning |
|------------|---------|
| `SendAndRecord` | Checkpoint — client should save this state. Appears at decision points |
| `SendHiFi` | Animation-quality update — intermediate state for visual fidelity |
| `Send` | One-shot, potentially speculative (during targeting, etc.) |

**Key finding:** `ActionsAvailableReq` always follows a `SendAndRecord` GSM.
The client needs a stable state before presenting action choices.

## 8. Combat Damage Annotation Chain

Combat damage produces a specific annotation chain:

```
DamageDealt:    affector=attacker, affected=defender, details={damage:N, type:1, markDamage:1}
SyntheticEvent: affector=attacker, affected=defender, details={type:1}
ModifiedLife:   affector=attacker, affected=defender, details={life:-N}
```

Where `type:1` = combat damage. Player life is also updated in `PlayerInfo.lifeTotal`.

## 9. Aura Targeting Flow

Aura casting has a unique multi-step flow:

1. `CastSpell`: Aura on Stack, `PlayerSelectingTargets` annotation
2. `SelectTargetsReq` (greType): prompt for target choice
3. Player submits target → `PlayerSubmittedTargets` annotation
4. Mana ability created/tapped/deleted (same as any spell)
5. Resolve: `AttachmentCreated`, `LayeredEffectCreated` annotations
6. Target creature's P/T updated in same resolve GSM
7. Persistent annotations: `Attachment(aura→creature)`, `LayeredEffect`

**Implication for bridge:** Aura resolution needs to:
- Create the Attachment relationship
- Apply the layered effect to the target creature
- Update P/T in the same state update

## 10. QueuedGameStateMessage

Some messages have `greType=QueuedGameStateMessage` instead of `GameStateMessage`.
These appear when the server batches updates for a non-active seat. The queue is
flushed when the seat's view catches up.

**Example in on-draw:** gsId 48–49 are sent as `QueuedGameStateMessage` to seat 1
while seat 2 is doing targeting for the Aura. Seat 1 receives the queued updates
alongside the resolution GSMs.

**Implication for bridge:** The bridge should treat `QueuedGameStateMessage` identically
to `GameStateMessage` for state accumulation purposes.

## 11. Summary of Conformance-Critical Patterns

These are the patterns that the Forge bridge MUST replicate correctly:

1. **ObjectIdChanged on zone transfer** — new ids for Hand→Battlefield, Hand→Stack, Library→Hand
2. **No id change on Resolve** — Stack→Battlefield keeps the same instanceId
3. **Limbo zone** — old ids visible only to the object's owner
4. **Per-seat filtering** — Private objects excluded from opponent's view
5. **Dual GSM delivery** — same gsId, different content per seat
6. **Transient ability objects** — created and deleted in same diff
7. **Persistent annotation lifecycle** — EnteredZoneThisTurn cleared on turn change
8. **Priority passing pattern** — 2 GSMs per phase transition
9. **SendAndRecord checkpointing** — stable state before action prompts
10. **Combat damage chain** — DamageDealt → SyntheticEvent → ModifiedLife
11. **Aura targeting** — SelectTargetsReq/SubmitTargetsResp flow
12. **ETB triggers** — Ability on Stack, GroupReq for choices, then resolve
