package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class EquipCostInteractionTest :
    SessionTest({
        session(
            "repeated zero-cost equips retire every staged ability and permit attacking",
            forgeCatalog = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Lightning Greaves;Grizzly Bears;Runeclaw Bear
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
            """,
        ) {
            val equipment = human.battlefield.card("Lightning Greaves")
            val start = messageSnapshot()
            val checkpoints = mutableListOf<Pair<Set<Int>, Set<Int>>>()
            val lifetimes = mutableListOf<Set<Int>>()
            val membershipMismatches = mutableListOf<Int>()
            for (target in listOf("Grizzly Bears", "Runeclaw Bear", "Grizzly Bears")) {
                val activationStart = messageSnapshot()
                activateAbility("Lightning Greaves").shouldBeTrue()
                selectTargets(listOf(human.battlefield.iid(target)))
                passUntilResolved()
                equipment.attachedTo shouldBe human.battlefield.card(target)
                game().stack.isEmpty.shouldBeTrue()
                val frames = messagesSince(start).gameStateMessages()
                val published =
                    frames
                        .flatMap { it.gameObjectsList }
                        .filter { it.type == GameObjectType.Ability && it.zoneId == ZoneIds.STACK }
                        .map { it.instanceId }
                        .toSet()
                val retired =
                    frames
                        .flatMap {
                            it.diffDeletedInstanceIdsList +
                                it.zonesList
                                    .filter { zone -> zone.zoneId == ZoneIds.LIMBO }
                                    .flatMap { zone -> zone.objectInstanceIdsList }
                        }.toSet()
                checkpoints += published to retired
                val activationFrames = messagesSince(activationStart).gameStateMessages()
                lifetimes +=
                    activationFrames
                        .flatMap { it.gameObjectsList }
                        .filter { it.type == GameObjectType.Ability && it.zoneId == ZoneIds.STACK }
                        .map { it.instanceId }
                        .toSet()
                for (frame in activationFrames) {
                    val stackZone = frame.zonesList.singleOrNull { it.zoneId == ZoneIds.STACK } ?: continue
                    membershipMismatches +=
                        frame.gameObjectsList
                            .filter {
                                it.type == GameObjectType.Ability &&
                                    it.zoneId == ZoneIds.STACK &&
                                    it.instanceId !in stackZone.objectInstanceIdsList
                            }.map { it.instanceId }
                }
            }
            passUntil { messagesSince(start).any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            declareAttackers(listOf(human.battlefield.iid("Grizzly Bears")))
            passUntil { ai.life == 18 }.shouldBeTrue()
            assertSoftly {
                checkpoints.forEach { (published, retired) -> (published - retired) shouldBe emptySet() }
                lifetimes.map { it.size } shouldBe listOf(1, 1, 1)
                lifetimes.flatten().distinct().size shouldBe 3
                membershipMismatches shouldBe emptyList()
            }
        }

        for ((slot, cost, target) in listOf(
            Triple(0, 3, "Isamaru, Hound of Konda"),
            Triple(1, 5, "Grizzly Bears"),
        )) {
            session(
                "selected equip declaration $slot pays $cost and attaches to its intended target",
                forgeCatalog = true,
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Commander's Plate;Isamaru, Hound of Konda|IsCommander;Grizzly Bears;Plains;Plains;Plains;Plains;Plains
                    humanhand=Forest
                    humanlibrary=Plains;Plains;Plains
                    ailibrary=Mountain;Mountain;Mountain
                """,
            ) {
                val plate = human.battlefield.card("Commander's Plate")
                val repo = bridge.cardRepository
                val data = checkNotNull(repo.findByGrpId(checkNotNull(repo.findGrpIdByName(plate.name))))
                val projection = ActionMapper.buildProjectionFromSnapshot(1, GsmSnapshot.capture(game(), bridge, "test", 0), bridge)
                val offers =
                    projection.offers.filter {
                        it.action.instanceId == bridge.instanceId(plate) && it.action.actionType == ActionType.Activate_add3
                    }
                offers.size shouldBe 2
                val selected = offers.single { it.action.abilityGrpId == data.abilityIds[slot].first }
                selected.action.uniqueAbilityId shouldBe 50 + slot
                submitAction(selected.action)
                selectTargets(listOf(human.battlefield.iid(target)))
                passUntilResolved()
                assertSoftly {
                    plate.attachedTo shouldBe human.battlefield.card(target)
                    human.getCardsIn(ZoneType.Battlefield).count { it.name == "Plains" && it.isTapped } shouldBe cost
                }
                playLand("Forest").shouldBeTrue()
            }
        }

        session("target reduction equips without mana and permits another action", puzzleFile = "data/puzzles/strong-back-equip.pzl") {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            passUntilResolved()
            assertSoftly {
                human.battlefield.card("Basilisk Collar").attachedTo shouldBe human.battlefield.card("Grizzly Bears")
                human.battlefield.card("Grizzly Bears").netPower shouldBe 6
            }
            playLand("Forest").shouldBeTrue()
        }

        session(
            "unenchanted target does not attach when its cost cannot be paid",
            puzzleFile = "data/puzzles/strong-back-equip-unpaid.pzl",
        ) {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Centaur Courser")))
            assertSoftly {
                human.battlefield.card("Basilisk Collar").attachedTo shouldBe null
                game().stack.isEmpty.shouldBeTrue()
            }
            playLand("Forest").shouldBeTrue()
        }

        session("unenchanted target pays the unreduced equip cost", puzzleFile = "data/puzzles/strong-back-equip-paid.pzl") {
            activateAbility("Basilisk Collar").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Centaur Courser")))
            passUntilResolved()
            assertSoftly {
                human.battlefield.card("Basilisk Collar").attachedTo shouldBe human.battlefield.card("Centaur Courser")
                human.getCardsIn(ZoneType.Battlefield).count { it.name == "Mountain" && it.isTapped } shouldBe 2
            }
            playLand("Forest").shouldBeTrue()
        }
    })
