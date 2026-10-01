package leyline.mechanics.flanking

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType

class FlankingLifecycleTest :
    SessionTest({
        session(
            "flanking reduces an eligible blocker in engine and GRE then expires",
            puzzleFile = "data/puzzles/flanking-blocker.pzl",
            aiScript = listOf(ScriptedAction.Attack(listOf("Benalish Cavalry"))),
            fullControl = true,
        ) {
            advanceToPhase("COMBAT_DECLARE_BLOCKERS")
            val blocker = human.battlefield.card("Those Who Serve")
            val blockerIid = human.battlefield.iid("Those Who Serve")
            declareBlockers(mapOf(blockerIid to ai.battlefield.iid("Benalish Cavalry")))
            passUntil { blocker.netPower == 1 }.shouldBeTrue()
            blocker.netPower shouldBe 1
            blocker.netToughness shouldBe 3
            val projected =
                allMessages
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .last { it.instanceId == blockerIid }
            projected.power.value shouldBe 1
            projected.toughness.value shouldBe 3
            println(
                "FLANKING eligible: Forge=${blocker.netPower}/${blocker.netToughness}, GRE=${projected.power.value}/${projected.toughness.value}",
            )
            val combatTurn = turn()
            passThroughCombat()
            (turn() > combatTurn).shouldBeTrue()
            blocker.netPower shouldBe 2
            blocker.netToughness shouldBe 4
            human.battlefield.card("Those Who Serve") shouldBe blocker
            human.life shouldBe 20
            println("FLANKING cleanup: Forge=${blocker.netPower}/${blocker.netToughness}, turn=${turn()}, life=${human.life}")
        }

        session(
            "Sidewinder grants flanking and source removal retires its projection",
            puzzle = """
            ActivePlayer=AI
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Those Who Serve
            humanlibrary=Plains;Plains;Plains
            aibattlefield=Sidewinder Sliver;Metallic Sliver
            ailibrary=Plains;Plains;Plains
        """,
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Sidewinder Sliver"))),
            fullControl = true,
        ) {
            advanceToPhase("COMBAT_DECLARE_BLOCKERS")
            val blocker = human.battlefield.card("Those Who Serve")
            val blockerIid = human.battlefield.iid("Those Who Serve")
            val recipient = ai.battlefield.card("Metallic Sliver")
            val recipientIid = ai.battlefield.iid("Metallic Sliver")
            val sourceIid = ai.battlefield.iid("Sidewinder Sliver")
            val repository = bridge.cardRepository
            val sourceGrpId = repository.findGrpIdByName("Sidewinder Sliver").shouldNotBeNull()
            val grantGrpId =
                repository
                    .findByGrpId(sourceGrpId)
                    .shouldNotBeNull()
                    .grantedKeywordAbilityIds
                    .getValue("Flanking")
            repository.findAbilityLocalization(grantGrpId).shouldNotBeNull().keyword shouldBe "Flanking"
            recipient.hasKeyword("Flanking").shouldBeTrue()
            val grant =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.AddAbility_af5a)
                    .last { recipientIid in it.affectedIdsList && it.detailInt("grpid") == grantGrpId }
            grant.affectorId shouldBe sourceIid
            grant.typeList shouldContain AnnotationType.LayeredEffect
            allMessages
                .flatMap { it.gameStateMessage.gameObjectsList }
                .last { it.instanceId == recipientIid }
                .uniqueAbilitiesList
                .map { it.grpId } shouldContain grantGrpId
            declareBlockers(mapOf(blockerIid to sourceIid))
            passUntil { blocker.netPower == 1 }.shouldBeTrue()
            blocker.netToughness shouldBe 3
            val projected =
                allMessages
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .last { it.instanceId == blockerIid }
            projected.power.value shouldBe 1
            projected.toughness.value shouldBe 3
            println(
                "FLANKING Sidewinder grant: Forge=${blocker.netPower}/${blocker.netToughness}, GRE=${projected.power.value}/${projected.toughness.value}",
            )
            passThroughCombat()
            recipient.hasKeyword("Flanking").shouldBeFalse()
            val liveRows = mutableMapOf<Int, AnnotationInfo>()
            for (message in allMessages) {
                if (!message.hasGameStateMessage()) continue
                val state = message.gameStateMessage
                if (state.type == GameStateType.Full) liveRows.clear()
                state.diffDeletedPersistentAnnotationIdsList.forEach(liveRows::remove)
                state.persistentAnnotationsList.forEach { liveRows[it.id] = it }
            }
            liveRows.values.any { recipientIid in it.affectedIdsList && it.detailInt("grpid") == grantGrpId }.shouldBeFalse()
            bridge
                .projectionStateSnapshot()
                .persistentAnnotations.activeAnnotations.values
                .any { recipientIid in it.affectedIdsList && it.detailInt("grpid") == grantGrpId }
                .shouldBeFalse()
        }

        session(
            "a blocker with flanking is excluded",
            puzzle = """
            ActivePlayer=AI
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Benalish Cavalry
            humanlibrary=Plains;Plains;Plains
            aibattlefield=Benalish Cavalry
            ailibrary=Plains;Plains;Plains
        """,
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Benalish Cavalry"))),
            fullControl = true,
        ) {
            advanceToPhase("COMBAT_DECLARE_BLOCKERS")
            val blocker = human.battlefield.card("Benalish Cavalry")
            declareBlockers(mapOf(human.battlefield.iid("Benalish Cavalry") to ai.battlefield.iid("Benalish Cavalry")))
            passUntilResolved()
            blocker.netPower shouldBe 2
            blocker.netToughness shouldBe 2
            println("FLANKING excluded: Forge=${blocker.netPower}/${blocker.netToughness}")
            passThroughCombat()
            human.graveyard.card("Benalish Cavalry").name shouldBe "Benalish Cavalry"
            ai.graveyard.card("Benalish Cavalry").name shouldBe "Benalish Cavalry"
        }
    })
