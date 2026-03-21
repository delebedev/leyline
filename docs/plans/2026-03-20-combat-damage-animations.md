# Combat Damage Animations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix combat damage animations (issue #33) by correcting the DamageDealt annotation shape and populating combat object state fields (issue #109).

**Architecture:** Four surgical fixes in the annotation/object pipeline. (1) Fix `damageDealt()` builder to set affectorId=source, affectedIds=target. (2) Rewrite `combatAnnotations()` to emit per-target damage for both attackers and blockers, using Forge's `getAssignedDamageMap()` for exact per-target amounts. (3) Populate attackInfo/blockInfo and Blocked/Unblocked states on game objects. (4) Fix SyntheticEvent builder to include affectorId.

**Tech Stack:** Kotlin, protobuf, Kotest FunSpec

**Wire spec:** `docs/plans/2026-03-20-combat-damage-wire-spec.md`

**Test commands:**
- Unit: `just test-unit matchdoor`
- Specific: `just test matchdoor -- --tests "leyline.game.AnnotationBuilderTest"`
- Combat flow: `just test matchdoor -- --tests "leyline.conformance.CombatFlowTest"`

**Key Forge APIs for per-target damage:**
- `blocker.getAssignedDamageMap()` → `Map<Card, Integer>` — maps source→damage on the target creature
- `combat.getDefenderByAttacker(card)` → `GameEntity` (Player or Card)
- `card.getTotalDamageDoneBy()` → total damage this card dealt this turn (all targets)

**PhaseOrStepModified note:** `BundleBuilder.priorityDiff()` (line 71-84) already adds `PhaseOrStepModified` for phase changes. The current `combatAnnotations()` also adds one (line 294) — this is the existing behavior. **Remove it from `combatAnnotations()` to avoid duplication.** BundleBuilder handles it.

---

### Task 1: Fix DamageDealt annotation shape

The `damageDealt()` builder currently puts the source creature in `affectedIds` and never sets `affectorId`. Real server: `affectorId`=source, `affectedIds`=target.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt:320-327`
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationBuilderTest.kt:287-311`

- [ ] **Step 1: Update existing `damageDealt` tests to match real server shape**

The builder signature changes to include `targetId`. Update both existing tests:

```kotlin
// --- DamageDealt ---

test("damageDealtFields") {
    val ann = AnnotationBuilder.damageDealt(
        sourceInstanceId = 1000,
        targetId = 2,  // player seat
        amount = 3,
    )
    ann.typeList shouldContain AnnotationType.DamageDealt_af5a
    ann.affectorId shouldBe 1000
    ann.affectedIdsList shouldBe listOf(2)

    val damage = ann.detailsList.first { it.key == "damage" }
    damage.type shouldBe KeyValuePairValueType.Uint32
    damage.getValueUint32(0) shouldBe 3

    val type = ann.detailsList.first { it.key == "type" }
    type.getValueUint32(0) shouldBe 1

    val markDamage = ann.detailsList.first { it.key == "markDamage" }
    markDamage.getValueUint32(0) shouldBe 3
}

test("damageDealtNonCombat") {
    val ann = AnnotationBuilder.damageDealt(
        sourceInstanceId = 1000,
        targetId = 500,  // creature
        amount = 2,
        type = 0,
        markDamage = 2,
    )
    ann.affectorId shouldBe 1000
    ann.affectedIdsList shouldBe listOf(500)
    val type = ann.detailsList.first { it.key == "type" }
    type.getValueUint32(0) shouldBe 0
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `just test matchdoor -- --tests "leyline.game.AnnotationBuilderTest.damageDealt*"`
Expected: FAIL — `affectorId` is 0, `affectedIds` contains source not target, missing `targetId` param.

- [ ] **Step 3: Fix the `damageDealt` builder**

In `AnnotationBuilder.kt:320-327`, add `targetId` parameter, set `affectorId`, put target in `affectedIds`:

```kotlin
fun damageDealt(sourceInstanceId: Int, targetId: Int, amount: Int, type: Int = 1, markDamage: Int = amount): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.DamageDealt_af5a)
        .setAffectorId(sourceInstanceId)
        .addAffectedIds(targetId)
        .addDetails(uint32Detail("damage", amount))
        .addDetails(uint32Detail("type", type))
        .addDetails(uint32Detail("markDamage", markDamage))
        .build()
```

- [ ] **Step 4: Fix compile errors in callers**

`combatAnnotations()` in `AnnotationPipeline.kt:260` calls `damageDealt(iid, dmg)` — this will break because of the new `targetId` param. Fix it in the next task, but for now add a placeholder to compile:

In `AnnotationPipeline.kt:260`, change:
```kotlin
annotations.add(AnnotationBuilder.damageDealt(iid, dmg))
```
to:
```kotlin
annotations.add(AnnotationBuilder.damageDealt(iid, targetId = 0, dmg)) // TODO: fix in Task 2
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `just test matchdoor -- --tests "leyline.game.AnnotationBuilderTest.damageDealt*"`
Expected: PASS

- [ ] **Step 6: Commit**

```
feat(matchdoor): fix DamageDealt annotation shape — affectorId + target

Real server sets affectorId=source creature, affectedIds=target (creature
or player seat). We had affectedIds=source and no affectorId.

refs #33
```

---

### Task 2: Rewrite combatAnnotations with per-target damage

Currently only attacker->X damage is emitted, using total damage (wrong for multi-blocker). Need per-target damage amounts, blocker->attacker damage, SyntheticEvent, and removal of duplicate PhaseOrStepModified.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt:243-298`
- Add import: `forge.game.player.Player` (not currently imported)

**Per-target damage strategy:**
- Attacker→blocker: use `blocker.getAssignedDamageMap()[attacker]` — exact per-source damage on the blocker
- Blocker→attacker: use `attacker.getAssignedDamageMap()[blocker]` — exact per-source damage on the attacker
- Unblocked→player: use `attacker.getTotalDamageDoneBy()` (all goes to player)
- Trample: blocked attacker with trample deals excess to player. Check `combat.getDefenderByAttacker()` — if attacker is blocked but also dealt damage to the player, emit both blocker and player DamageDealt annotations.

- [ ] **Step 1: Add Player import to AnnotationPipeline**

Add to imports in `AnnotationPipeline.kt`:
```kotlin
import forge.game.player.Player
```

- [ ] **Step 2: Rewrite `combatAnnotations()`**

Replace `AnnotationPipeline.combatAnnotations()` (lines 243-298):

```kotlin
internal fun combatAnnotations(
    game: Game,
    bridge: GameBridge,
): List<AnnotationInfo> {
    val handler = game.phaseHandler
    if (handler.phase != PhaseType.COMBAT_DAMAGE && handler.phase != PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) {
        return emptyList()
    }
    val combat = handler.combat ?: return emptyList()
    if (combat.attackers.isEmpty()) return emptyList()

    val annotations = mutableListOf<AnnotationInfo>()
    var playerDamageSeat: Int? = null
    var firstPlayerDamageAttackerIid: Int? = null

    // --- Attacker damage ---
    for (attacker in combat.attackers) {
        val attackerIid = bridge.getOrAllocInstanceId(ForgeCardId(attacker.id)).value
        val totalDmg = attacker.getTotalDamageDoneBy()
        if (totalDmg <= 0) continue

        // Damage to blockers (per-target via blocker's assignedDamageMap)
        for (blocker in combat.getBlockers(attacker)) {
            val dmgToBlocker = blocker.getAssignedDamageMap()[attacker] ?: 0
            if (dmgToBlocker > 0) {
                val blockerIid = bridge.getOrAllocInstanceId(ForgeCardId(blocker.id)).value
                annotations.add(AnnotationBuilder.damageDealt(attackerIid, targetId = blockerIid, dmgToBlocker))
            }
        }

        // Damage to player (unblocked, or trample excess)
        val defender = combat.getDefenderByAttacker(attacker)
        if (defender is Player) {
            // For unblocked: all damage goes to player
            // For trample: total minus what went to blockers = player damage
            val dmgToBlockers = combat.getBlockers(attacker).sumOf { blocker ->
                blocker.getAssignedDamageMap()[attacker] ?: 0
            }
            val dmgToPlayer = totalDmg - dmgToBlockers
            if (dmgToPlayer > 0) {
                val seat = resolvePlayerSeat(defender, bridge)
                if (seat != null) {
                    annotations.add(AnnotationBuilder.damageDealt(attackerIid, targetId = seat, dmgToPlayer))
                    playerDamageSeat = seat
                    if (firstPlayerDamageAttackerIid == null) firstPlayerDamageAttackerIid = attackerIid
                }
            }
        }
    }

    // --- Blocker → attacker damage ---
    val emittedBlockers = mutableSetOf<Int>() // dedup for multi-attacker blocking
    for (attacker in combat.attackers) {
        for (blocker in combat.getBlockers(attacker)) {
            if (blocker.id in emittedBlockers) continue
            emittedBlockers.add(blocker.id)
            val dmgToAttacker = attacker.getAssignedDamageMap()[blocker] ?: 0
            if (dmgToAttacker > 0) {
                val blockerIid = bridge.getOrAllocInstanceId(ForgeCardId(blocker.id)).value
                val attackerIid = bridge.getOrAllocInstanceId(ForgeCardId(attacker.id)).value
                annotations.add(AnnotationBuilder.damageDealt(blockerIid, targetId = attackerIid, dmgToAttacker))
            }
        }
    }

    // --- DamagedThisTurn badges ---
    val emittedDamagedBadges = mutableSetOf<Int>() // dedup blocker badges
    for (attacker in combat.attackers) {
        val attackerIid = bridge.getOrAllocInstanceId(ForgeCardId(attacker.id)).value
        if (attacker.getDamage() > 0) {
            annotations.add(AnnotationBuilder.damagedThisTurn(attackerIid))
        }
        for (blocker in combat.getBlockers(attacker)) {
            if (blocker.id in emittedDamagedBadges) continue
            emittedDamagedBadges.add(blocker.id)
            if (blocker.getDamage() > 0) {
                val blockerIid = bridge.getOrAllocInstanceId(ForgeCardId(blocker.id)).value
                annotations.add(AnnotationBuilder.damagedThisTurn(blockerIid))
            }
        }
    }

    // --- SyntheticEvent when player takes combat damage ---
    if (playerDamageSeat != null && firstPlayerDamageAttackerIid != null) {
        annotations.add(AnnotationBuilder.syntheticEvent(firstPlayerDamageAttackerIid, playerDamageSeat))
    }

    // --- ModifiedLife from baseline comparison ---
    val prev = bridge.getDiffBaselineState()
    if (prev != null) {
        for (playerInfo in prev.playersList) {
            val seat = playerInfo.systemSeatNumber
            val player = bridge.getPlayer(SeatId(seat))
            if (player != null) {
                val delta = player.life - playerInfo.lifeTotal
                if (delta != 0) {
                    annotations.add(AnnotationBuilder.modifiedLife(seat, delta))
                }
            }
        }
    }

    // NOTE: PhaseOrStepModified NOT emitted here — BundleBuilder.priorityDiff()
    // handles it via isPhaseChangedFromClientSeen(). Emitting here would duplicate.

    return annotations
}

/** Resolve a Forge Player to Arena seat ID (1 or 2). */
private fun resolvePlayerSeat(player: Player, bridge: GameBridge): Int? {
    val p1 = bridge.getPlayer(SeatId(1))
    val p2 = bridge.getPlayer(SeatId(2))
    return when (player.id) {
        p1?.id -> 1
        p2?.id -> 2
        else -> null
    }
}
```

- [ ] **Step 3: Run unit tests**

Run: `just test-unit matchdoor`
Expected: PASS (no compile errors, existing tests still green)

- [ ] **Step 4: Run combat integration test**

Run: `just test matchdoor -- --tests "leyline.conformance.CombatFlowTest"`
Expected: PASS. If tests assert annotation counts, they may need updating — the new code emits more DamageDealt annotations (blocker damage) and SyntheticEvent. Adjust counts if needed.

- [ ] **Step 5: Commit**

```
feat(matchdoor): rewrite combatAnnotations with per-target damage

- Per-target damage via getAssignedDamageMap() (correct for multi-blocker)
- Attacker→blocker and blocker→attacker DamageDealt annotations
- Trample: excess damage to player emitted alongside blocker damage
- SyntheticEvent with affectorId when player takes combat damage
- DamagedThisTurn badges deduplicated for multi-attacker blocking
- Removed duplicate PhaseOrStepModified (BundleBuilder handles it)

refs #33, #109
```

---

### Task 3: Populate attackInfo and blockInfo on game objects

`ObjectMapper.applyCardFields()` sets `attackState` and `blockState` but never fills `attackInfo.targetId` or `blockInfo.attackerIds`. Also need `Blocked`/`Unblocked` block states on attackers.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt:138-143`
- Verify imports: `AttackInfo`, `BlockInfo` from `Messages.*` (already wildcard-imported via `Messages.AttackState`, etc.)
- Test: `matchdoor/src/test/kotlin/leyline/game/GameBridgeTest.kt` (existing combat object tests)

**Forge APIs:**
- `combat.getDefenderByAttacker(card)` → `GameEntity` (Player or Card)
- `combat.getBandOfAttacker(card)` → `AttackingBand?` — `.isBlocked()` returns `Boolean?` (null=pre-blocker, true=blocked, false=unblocked)
- `combat.getAttackersBlockedBy(card)` → `CardCollection`
- `Card` extends `GameEntity` — use `is Card` check for planeswalker attacks

- [ ] **Step 1: Write failing test for attackInfo.targetId**

In `GameBridgeTest.kt`, find the existing combat object test (around line 518-542). Add assertions for `attackInfo.targetId` on attacking creatures. If the test uses `MatchFlowHarness`, assert in the post-SubmitAttackers state:

```kotlin
test("attacking creature has attackInfo with targetId") {
    // ... setup with combat puzzle or harness ...
    val attackerObj = gsm.gameObjectsList.first {
        it.zoneId == ZoneIds.BATTLEFIELD && it.attackState == AttackState.Attacking
    }
    attackerObj.hasAttackInfo() shouldBe true
    attackerObj.attackInfo.targetId shouldBe 2 // defending player seat
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test matchdoor -- --tests "leyline.game.GameBridgeTest.*attackInfo*"`
Expected: FAIL — `hasAttackInfo()` returns false.

- [ ] **Step 3: Expand combat state block in ObjectMapper**

Replace `ObjectMapper.kt:138-143`. Note: `Player` import needed — add `import forge.game.player.Player`:

```kotlin
// Combat state
val combat = game?.phaseHandler?.combat
if (combat != null && type.isCreature) {
    if (combat.isAttacking(card)) {
        setAttackState(AttackState.Attacking)
        // attackInfo: who is this creature attacking?
        val defender = combat.getDefenderByAttacker(card)
        if (defender != null) {
            val targetId = when (defender) {
                is Player -> {
                    val p1 = bridge?.getPlayer(SeatId(1))
                    when (defender.id) {
                        p1?.id -> 1
                        else -> 2
                    }
                }
                is Card -> bridge?.getOrAllocInstanceId(ForgeCardId(defender.id))?.value ?: 0
                else -> 0
            }
            if (targetId > 0) {
                setAttackInfo(AttackInfo.newBuilder().setTargetId(targetId))
            }
        }
        // blockState on attacker: Blocked or Unblocked (after blocker declaration)
        val band = combat.getBandOfAttacker(card)
        if (band != null) {
            val blocked = band.isBlocked() // Boolean? — null=pre-blocker
            if (blocked == true) setBlockState(BlockState.Blocked)
            else if (blocked == false) setBlockState(BlockState.Unblocked)
        }
    }
    if (combat.isBlocking(card)) {
        setBlockState(BlockState.Blocking)
        // blockInfo: which attackers is this creature blocking?
        val attackers = combat.getAttackersBlockedBy(card)
        if (attackers.isNotEmpty() && bridge != null) {
            setBlockInfo(
                BlockInfo.newBuilder().apply {
                    for (atk in attackers) {
                        addAttackerIds(bridge.getOrAllocInstanceId(ForgeCardId(atk.id)).value)
                    }
                }
            )
        }
    }
}
```

- [ ] **Step 4: Verify imports**

ObjectMapper already has `import wotc.mtgo.gre.external.messaging.Messages.*` or individual imports. Check and add if needed:
```kotlin
import forge.game.player.Player
import forge.game.card.Card as ForgeCard  // if needed for `is Card` check — verify existing import pattern
```

Note: Forge `Card` may conflict with proto `Card`. Check what's imported. If there's a conflict, use fully qualified name `forge.game.card.Card` in the `when` branch.

- [ ] **Step 5: Run tests**

Run: `just test matchdoor -- --tests "leyline.game.GameBridgeTest"`
Expected: PASS

Run: `just test matchdoor -- --tests "leyline.conformance.CombatFlowTest"`
Expected: CombatFlowTest lines 388-389 assert `attackState == None_a3a9` and `blockState == None_aa2d` **post-combat**. These should still pass because Forge clears combat state when combat ends — `game.phaseHandler.combat` becomes null, so our code doesn't set any combat fields. Verify.

Run: `just test matchdoor -- --tests "leyline.game.BundleBuilderTest"`
Expected: Lines 234-237, 266-269 assert no attackState/blockState on **echo objects**. These use `buildProvisionalCombatObject()` (ObjectMapper line 156+), not `applyCardFields()`, so they're unaffected.

- [ ] **Step 6: Run `just fmt`**

- [ ] **Step 7: Commit**

```
feat(matchdoor): populate attackInfo, blockInfo, Blocked/Unblocked on combat objects

- attackInfo.targetId set to defending player seat or planeswalker iid
- blockInfo.attackerIds set on blocking creatures
- Attackers get BlockState.Blocked or Unblocked after blocker declaration
- band.isBlocked() null-safety: null=pre-blocker (no blockState emitted)

refs #33, #109
```

---

### Task 4: Fix SyntheticEvent builder for combat damage

The recording shows `SyntheticEvent` has `affectorId` (first attacker) and `affectedIds` (player seat). Current builder only takes `seatId` and puts it in `affectedIds` with no `affectorId`.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt:367-372`
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationBuilderTest.kt:333-340`

- [ ] **Step 1: Update syntheticEvent test**

```kotlin
test("syntheticEventFields") {
    val ann = AnnotationBuilder.syntheticEvent(attackerIid = 290, targetSeatId = 2)
    ann.typeList shouldContain AnnotationType.SyntheticEvent
    ann.affectorId shouldBe 290
    ann.affectedIdsList shouldBe listOf(2)
    val type = ann.detailsList.first { it.key == "type" }
    type.getValueUint32(0) shouldBe 1
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test matchdoor -- --tests "leyline.game.AnnotationBuilderTest.syntheticEvent*"`
Expected: FAIL — wrong signature.

- [ ] **Step 3: Update builder**

```kotlin
fun syntheticEvent(attackerIid: Int, targetSeatId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.SyntheticEvent)
        .setAffectorId(attackerIid)
        .addAffectedIds(targetSeatId)
        .addDetails(uint32Detail("type", 1))
        .build()
```

- [ ] **Step 4: Run tests**

Run: `just test-unit matchdoor`
Expected: PASS. The only caller is `combatAnnotations()` which was already updated in Task 2.

- [ ] **Step 5: Commit**

```
feat(matchdoor): add affectorId to SyntheticEvent for combat damage

Real server sets affectorId to the attacking creature instanceId.

refs #33
```

---

### Task 5: Integration verification + format

- [ ] **Step 1: Run full matchdoor tests**

Run: `just test matchdoor`
Expected: All green. Pay attention to conformance tests that may assert annotation counts.

- [ ] **Step 2: Run `just fmt`**

- [ ] **Step 3: Verify with a combat puzzle**

Check if a combat puzzle exists:
```bash
ls puzzles/ | grep -i combat
grep -l "attack\|block" puzzles/*.pzl
```

If one exists, run it in tests:
```bash
just test matchdoor -- --tests "*puzzle*"
```

If no combat puzzle exists, write one (use `write-puzzle` skill) with:
- 2 creatures attacking (1 blocked, 1 unblocked)
- Verify both DamageDealt annotations fire with correct targets
- Player life decreases from unblocked attacker

- [ ] **Step 4: Final commit if any formatting changes**

```
chore: fmt
```

- [ ] **Step 5: Close issues**

After Arena playtest confirms damage animations work:
- Close #33 (no combat damage animation)
- Close #109 (combat object state missing)
