package leyline.mechanics.annihilator

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.bridge.types.InstanceId
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.tooling.headless.HeadlessResponseMode

class AnnihilatorLifecycleTest :
    SessionTest({
        fun puzzle(permanents: String) =
            """
            ActivePlayer=AI
            ActivePhase=Main1
            HumanLife=40
            AILife=20
            humanbattlefield=$permanents
            humanlibrary=Plains;Plains;Plains
            aibattlefield=Emrakul, the Aeons Torn
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        session(
            "human defender chooses six sacrifices before blockers and combat continues",
            puzzle = puzzle("Healer's Hawk;Plains;Island;Swamp;Mountain;Forest;Wastes;Wastes"),
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Emrakul, the Aeons Torn"))),
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
        ) {
            passUntil { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val promptMessage = allMessages.last { it.hasSelectNReq() }
            val selection = promptMessage.selectNReq
            val blocker = human.battlefield.card("Healer's Hawk")
            val blockerIid = human.battlefield.iid("Healer's Hawk")
            val before = human.getZone(ZoneType.Battlefield).cards.toList()
            val selectedIds = selection.idsList.filter { it != blockerIid }.takeLast(6)
            val selectedForgeIds = selectedIds.map { bridge.getForgeCardId(InstanceId(it))!!.value }.toSet()
            val sacrificed = before.filter { it.id in selectedForgeIds }
            val retained = before.filter { it.id !in selectedForgeIds }
            assertSoftly {
                promptMessage.systemSeatIdsList shouldBe listOf(HUMAN_SEAT)
                selection.minSel shouldBe 6
                selection.maxSel shouldBe 6
                selection.idsCount shouldBe 8
                sacrificed.size shouldBe 6
                phase() shouldBe "COMBAT_DECLARE_ATTACKERS"
                allMessages.any { it.hasDeclareBlockersReq() }.shouldBeFalse()
                human.getZone(ZoneType.Graveyard).size() shouldBe 0
            }

            respondToSelectN(selectedIds)
            passUntil { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()
            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.toList() shouldContainExactlyInAnyOrder sacrificed
                human.getZone(ZoneType.Battlefield).cards.toList() shouldContainExactlyInAnyOrder retained
                blocker.zone.zoneType shouldBe ZoneType.Battlefield
                allMessages
                    .last { it.hasDeclareBlockersReq() }
                    .declareBlockersReq.blockersList
                    .any { it.blockerInstanceId == blockerIid }
                    .shouldBeTrue()
            }
            declareBlockers(emptyMap())
            assertSoftly {
                passUntil { phase() == "MAIN2" }.shouldBeTrue()
                human.life shouldBe 25
                isGameOver().shouldBeFalse()
            }
        }

        session(
            "fewer than six permanents are all sacrificed without a choice and combat continues",
            puzzle = puzzle("Plains;Island;Swamp"),
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Emrakul, the Aeons Torn"))),
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
        ) {
            val permanents = human.getZone(ZoneType.Battlefield).cards.toList()
            assertSoftly {
                permanents.size shouldBe 3
                passUntil { phase() == "MAIN2" }.shouldBeTrue()
                human.getZone(ZoneType.Battlefield).size() shouldBe 0
                human.getZone(ZoneType.Graveyard).cards.toList() shouldContainExactlyInAnyOrder permanents
                allMessages.any { it.hasSelectNReq() }.shouldBeFalse()
                human.life shouldBe 25
                isGameOver().shouldBeFalse()
            }
        }
    })
