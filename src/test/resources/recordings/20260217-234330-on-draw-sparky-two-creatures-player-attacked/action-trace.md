# Action Trace: on-draw recording (Sparky two creatures, player attacked)

**Recording:** `20260217-234330-on-draw-sparky-two-creatures-player-attacked`
**Perspective:** Both seats (seat 1 = Sparky, seat 2 = player)
**Messages:** 155 GRE messages
**gsId range:** 7–98
**Cards in play:** Blue deck (Islands, Birds grpId=75485, Wall grpId=75478, Aura grpId=75473, Turtle grpId=75465)

## Game Summary

Player (seat 2) is on the draw. Seat 2's deck is blue (Islands).
Seat 1 (Sparky) has green creatures (Snake/Ninja grpId=79618, Raccoon grpId=80288).
Over 4 turns: land drops, creature casts, aura enchantment with targeting,
ETB trigger (Wall → Scry), combat damage both ways, player concession.

---

## Turn 1 — Seat 2 Active (Player, on the draw)

### Play Land (gsId 7)
- Seat 2 plays **Island** (grpId=75554): instanceId 220 → ObjectIdChanged → 279
- ZoneTransfer: Hand(35) → Battlefield(28), category=PlayLand
- PersistentAnnotation: EnteredZoneThisTurn, ColorProduction(blue=2)
- **Dual delivery:** gsId=7 sent in separate .bin files for seat 1 vs seat 2
  - Seat 1: does NOT see instanceId 220 in Limbo (no Private viewer)
  - Seat 2: sees instanceId 220 in Limbo with Private visibility

### Cast Creature — Bird (gsId 8–10)
- Seat 2 casts **Bird** (grpId=75485, 1/1): instanceId 221 → ObjectIdChanged → 280
- ZoneTransfer: Hand(35) → Stack(27), category=CastSpell
- ManaPaid: Island(279) taps, pays 1 blue mana
- AbilityInstanceCreated/Deleted: mana ability 281 (transient)
- **gsId 10 (resolve):** Bird moves Stack(27) → Battlefield(28), category=Resolve
  - hasSummoningSickness=true
  - PersistentAnnotation: EnteredZoneThisTurn on battlefield

### Combat (gsId 12–16, no attackers)
- Phases cycle through: BeginCombat → DeclareAttack → EndCombat
- No attackers declared (summoning sickness on Bird)
- Priority bounces between seats at each step

### Main2 → End Step → New Turn (gsId 18–22)
- Standard phase progression through Main2, End step
- Turn ends, new turn starts (turn 2, seat 1 active)

---

## Turn 2 — Seat 1 Active (Sparky)

### Draw Step (gsId 24)
- Seat 1 draws: instanceId 166 → ObjectIdChanged → 282
- Card: **Raccoon Citizen** (grpId=80288, 1/2 Creature)
- ZoneTransfer: Library(32) → Hand(31), category=Draw

### Play Land (gsId 27)
- Seat 1 plays **Forest** (grpId=98595): instanceId 159 → 283
- ZoneTransfer: Hand(31) → Battlefield(28), category=PlayLand

### Sparky's turn passes with no further plays

---

## Turn 3 — Seat 2 Active (Player)

### Cast Aura with Targeting (gsId 48–52)
- Seat 2 casts **Aura** (grpId=75473, Enchantment — Aura): instanceId 219 → 288
- ZoneTransfer: Hand(35) → Stack(27), category=CastSpell
- **SelectTargetsReq** sent to seat 2 (promptId=10) — must choose target
- **PlayerSubmittedTargets:** target = Bird (instanceId 280)
- ManaPaid: Island(279) taps, pays 1 blue
- **gsId 52 (resolve):** Aura enters Battlefield attached to Bird
  - Bird P/T changes: 1/1 → **2/2** (ModifiedPower, ModifiedToughness, LayeredEffect)
  - PersistentAnnotation: Attachment(288→280), LayeredEffect(effect_id=7002)
  - AttachmentCreated annotation

### Cast Creature — Wall with ETB trigger (gsId 53–57)
- Seat 2 casts **Wall** (grpId=75478, 0/4 Creature — Wall): instanceId 224 → 290
- ZoneTransfer: Hand(35) → Stack(27), category=CastSpell
- ManaPaid: Island(287) taps
- **gsId 55 (resolve):** Wall enters Battlefield with hasSummoningSickness=true
  - **ETB Trigger:** AbilityInstanceCreated → Ability instanceId 292 (grpId=176406) on Stack
  - TriggeringObject persistent annotation links 292→290

### ETB Trigger Resolution — Scry (gsId 57–58)
- **GroupReq** sent (promptId=92) — Scry choice (top/bottom)
- MultistepEffectStarted (SubCategory=15 = Scry)
- Resolution: Scry annotation shows card 227 sent to bottom
- Ability 292 deleted from Stack after resolution

### Combat — Attack with Bird (gsId 60–68)
- Phases: BeginCombat → DeclareAttack
- **SubmitAttackersResp:** Bird (280) declared as attacker
  - TappedUntappedPermanent: Bird taps (isTapped=true)
- DeclareBlock step: No blockers (Sparky has no eligible creatures)
- **CombatDamage:**
  - DamageDealt: Bird(280) deals 2 damage to seat 1 (player)
  - ModifiedLife: seat 1 life 20→**18**
  - SyntheticEvent type=1 (combat damage to player)

---

## Turn 4 — Seat 1 Active (Sparky)

### Untap/Upkeep
- TappedUntappedPermanent: Forest(283) untaps
- Limbo cleanup: old instanceIds 220, 221 deleted from objects
- Seat 1 creature visible: Snake/Ninja (284, grpId=79618, 1/1)

### Draw Step (gsId 78)
- Seat 1 draws: instanceId 167 → 293, Forest (grpId=98595)

### Combat — Sparky attacks with Snake/Ninja (gsId 82–91)
- **DeclareAttackersReq** sent to seat 1 (promptId=6)
- **SubmitAttackersResp:** Snake/Ninja (284) declared attacker
  - TappedUntappedPermanent: 284 taps
- **DeclareBlockersReq** sent to seat 2 (promptId=7) — player can block
- **SubmitBlockersResp:** No blockers declared
- **CombatDamage:**
  - DamageDealt: Snake/Ninja(284) deals 1 damage to seat 2
  - ModifiedLife: seat 2 life 20→**19**

### Game End (gsId 96–98)
- Seat 1: PendingLoss (status change)
- Game concludes at Main2 of turn 4

---

## Key Patterns Observed

### 1. Dual-delivery per seat
Every significant GSM is delivered twice — once per seat — in separate .bin files
with the same msgId/gsId but different systemSeatIds and different Private object
visibility. Seat 2 sees their own Limbo objects (Private); seat 1 does not.

### 2. ObjectIdChanged lifecycle
Zone transfers always produce a new instanceId:
- Hand→Battlefield (PlayLand): 220→279, 159→283
- Hand→Stack (CastSpell): 221→280, 219→288, 224→290
- Stack→Battlefield (Resolve): keeps the stack instanceId
- Library→Hand (Draw): 166→282, 167→293

The old instanceId goes to Limbo with Private visibility for the owner.

### 3. Transient ability instances
Mana abilities and triggered abilities get temporary instanceIds that are
created and immediately deleted within the same GSM batch:
- AbilityInstanceCreated → AbilityInstanceDeleted (mana)
- Trigger goes on Stack as type=Ability, resolved, then deleted

### 4. Aura targeting flow
1. CastSpell puts Aura on Stack
2. SelectTargetsReq asks player to choose target
3. PlayerSubmittedTargets confirms
4. Resolution creates: LayeredEffect, Attachment persistent annotations
5. Target creature's P/T modified in the same resolve GSM

### 5. Combat damage annotations
Combat damage produces a chain of 4 annotations:
1. DamageDealt (damage amount, type=1 for combat)
2. SyntheticEvent (type=1 = damage to player)
3. ModifiedLife (delta)
4. Player life total updated in PlayerInfo

### 6. Priority passing pattern
Each phase/step generates 2 GSMs: one with priorityPlayer=activePlayer,
then one with priorityPlayer=opponent. This is how the game signals
"both players passed priority."

### 7. QueuedGameStateMessage
Some messages use greType=QueuedGameStateMessage instead of GameStateMessage.
These appear when the server batches updates for a seat that isn't the
active decision maker — the queue is flushed when their view catches up.
