## Combat Damage Wire Spec

**Recording:** `recordings/2026-03-17_20-34-31/`
**Issues:** #33 (no combat damage animation), #109 (missing combat object state)

### DamageDealt annotation — real server shape

```json
{
  "types": ["DamageDealt"],
  "affectorId": <source creature instanceId>,
  "affectedIds": [<target — creature instanceId OR player seatId>],
  "details": { "damage": N, "type": 1, "markDamage": 1 }
}
```

One annotation per source->target pair. All damage in a single CombatDamage GSM.
Blocker->attacker damage included (mutual lethal = both directions present).

### Our DamageDealt — what's wrong

1. **`affectorId` never set** — real server sets it to source creature
2. **`affectedIds` has source, not target** — real server puts the damage recipient here
3. **No blocker damage annotations** — only attacker->X, never blocker->attacker

### Combat object state — real server fields

| Phase | attackState | blockState | attackInfo | blockInfo |
|-------|------------|------------|------------|-----------|
| Echo after DeclareAttackersResp | Declared | — | {targetId: N} | — |
| After SubmitAttackers | Attacking | — | {targetId: N} | — |
| Echo after DeclareBlockersResp | — | — | — | Declared + {attackerIds: [N]} |
| After SubmitBlockers (attacker, unblocked) | Attacking | Unblocked | {targetId: N} | — |
| After SubmitBlockers (attacker, blocked) | Attacking | Blocked | {targetId: N} | — |
| After SubmitBlockers (blocker) | — | Blocking | — | {attackerIds: [N]} |

### Our combat object state — what's wrong

1. **attackInfo never populated** — we set `attackState=Attacking` but no `attackInfo.targetId`
2. **blockInfo never populated** — we set `blockState=Blocking` but no `blockInfo.attackerIds`
3. **BlockState.Blocked / BlockState.Unblocked never used** — attackers stuck at just `Attacking`

### Companion annotations in CombatDamage GSM

When player takes damage:
- `SyntheticEvent {type:1}` — affectorId=attacker, affectedIds=[player seat]
- `ModifiedLife {life: -N}` — already implemented correctly

### Gaps checklist

- [ ] Fix `damageDealt()` builder: set `affectorId`, put target in `affectedIds`
- [ ] Add blocker->attacker DamageDealt in `combatAnnotations()`
- [ ] Populate `attackInfo.targetId` on attacking creatures in ObjectMapper
- [ ] Populate `blockInfo.attackerIds` on blocking creatures in ObjectMapper
- [ ] Set `BlockState.Blocked` / `BlockState.Unblocked` on attackers after blocker declaration
- [ ] Add `SyntheticEvent` annotation when player takes combat damage
