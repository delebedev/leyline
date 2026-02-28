# Recording Findings: 2026-02-28

Source: `recordings/2026-02-28_09-33-05` (proxy capture, garnett vs Dalamor)
Decoded: `md-frames.jsonl` (413 GRE messages, seat 1)

Deck archetypes: seat 1 white aggro (cats/soldiers), seat 2 UR (otters/treasures).

## Mechanics Observed

Broad set exercised: tokens (Cat 94156, Soldier 94161, Otter 91865, Treasure 95104),
+1/+1 counters (Ajani's Pridemate 93848), modal ETB (Charming Prince 93996 — 3 options),
combat (single + all-in attacks), sacrifice (Treasure for mana), activated abilities
(opponent heavy — 484 Activate actions), discard, lifegain triggers.

## Specs

### 1. Token grpId resolution

**Problem:** Engine creates tokens with grpId=0 or wrong grpId. Client renders broken placeholder.

**Source:** Recording shows tokens with correct grpIds from real server:
- Cat 2/2 → 94156 (from Cat Collector 93717, ability 175756)
- Soldier 1/1 → 94161 (from Resolute Reinforcements 93858, ability 99866)
- Otter 1/1 → 91865
- Treasure → 95104

**DB linkage:** `Cards.AbilityIdToLinkedTokenGrpId` column. Format: `abilityId:tokenGrpId`.
See `docs/client-card-db.md` for schema.

**Approach:** When Forge creates a token, look up the source card's `AbilityIdToLinkedTokenGrpId`
in CardDb to find the correct token grpId. Wire into StateMapper/ObjectMapper token path.

### 2. TokenDeleted annotation

**Problem:** Not emitted. Client may keep ghost references to dead tokens.

**Source:** Recording index 315 — Treasure token sacrificed:
`{"types":["TokenDeleted"],"affectorId":339,"affectedIds":[339]}`
Fires after ZoneTransfer(Sacrifice) + diffDeletedInstanceIds.

**Approach:** Emit TokenDeleted(41) when a token leaves battlefield. Affector=affectedId=
the token's (post-realloc) instanceId. Already have `GameEventTokenCreated` → need
equivalent destroy-side event or detect token type in existing zone transfer flow.

### 3. Modal ETB (CastingTimeOptionsReq)

**Problem:** Cards with "choose one" ETB (Charming Prince, Brutal Cathar, etc.) unplayable.
Server must send CastingTimeOptionsReq with modalReq, client picks, server applies.

**Source:** Recording index 359 — Charming Prince ETB:
```
castingTimeOptions: [{
  ctoId: 5, type: Modal, grpId: 136341, isRequired: true,
  modal: { abilityGrpId: 136341, minSel: 1, maxSel: 1,
           options: [{grpId: 136338}, {grpId: 26167}, {grpId: 136340}] }
}]
```

**Approach:** Detect modal SpellAbility in Forge, build CastingTimeOptionsReq from
ability's modes, send to client, wait for CastingTimeOptionsResp, apply chosen mode.
Modal option grpIds come from `Abilities` table or `AbilityIds` on the card.

### 4. Cancel attacks

**Problem:** BUGS.md open bug — cancel button during attack declaration has no effect.

**Source:** Recording has 8 DeclareAttackersReq. All show `canSubmitAttackers: true`
(implicit default). Pattern: single attacker (index 194-203), then all 3 (index 370-377).

**Next step:** Capture a proxy session where player clicks Cancel during attack.
Check what message the client sends (likely DeclareAttackersResp with empty selectedAttackers,
or SubmitAttackersResp with autoDeclare=false + empty list). Then wire handler.

### 5. TimerStateMessage

**Problem:** Not implemented. 21 occurrences in this game.

**Source:** `"greType":"TimerStateMessage"` — sent periodically, carries chess-clock state.
Client uses it for the turn timer UI.

**Approach:** Build TimerStateMessage (type 56) with reasonable defaults. Send periodically
or on phase transitions. Low complexity but high polish value — without it client timer
is broken/missing.

### 6. ChooseStartingPlayerReq

**Problem:** Not implemented. 1 occurrence (index 3).

**Source:** `"greType":"ChooseStartingPlayerReq","msgId":5,"gsId":1`
Sent after ConnectResp + DieRollResultsResp. Winner chooses play/draw.

**Approach:** Send ChooseStartingPlayerReq to die roll winner, handle response
(ChooseStartingPlayerResp), set active player accordingly. Currently server always picks.
