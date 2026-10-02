package leyline.session.mechanics

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.SessionTest
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.detailString
import leyline.testkit.persistentAnnotationsOfType
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.IdType

class DungeonStatusTest :
    SessionTest({
        session(
            "dungeon row survives completion and reentry with distinct room identities",
            puzzleFile = "data/puzzles/venture-lost-mine.pzl",
        ) {
            castSpellUntilSelectNReq("Acererak the Archlich").idType shouldBe IdType.CardGrpId
            chooseCatalog("Lost Mine of Phandelver")
            passUntilResolved()
            keepScry()
            passUntilResolved()
            val first = allMessages.persistentAnnotationsOfType(AnnotationType.DungeonStatus).last()
            val dungeon = first.detailInt("CurrentDungeon")
            val firstIid = first.detailInt("CurrentDungeonZCID")
            val objects = allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.gameObjectsList }
            assertSoftly {
                objects.last { it.instanceId == firstIid }.type shouldBe GameObjectType.Card
                first.detailIntList("AllDungeonsCompleted").shouldBeEmpty()
                allMessages.annotationsOfType(AnnotationType.CounterAdded).shouldBeEmpty()
            }
            castSpellUntilSelectNReq("Acererak the Archlich").idType shouldBe IdType.AbilityGrpId
            chooseCatalog("Goblin Lair")
            passUntilResolved()
            val second = allMessages.persistentAnnotationsOfType(AnnotationType.DungeonStatus).last()
            assertSoftly {
                second.detailInt("CurrentRoom") shouldNotBe first.detailInt("CurrentRoom")
                second.id shouldBe first.id
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Goblin Token" }
                    .shouldBeTrue()
            }
            castSpellUntilSelectNReq("Acererak the Archlich")
            chooseCatalog("Dark Pool")
            passUntilResolved()
            assertSoftly {
                human.life shouldBe 21
                ai.life shouldBe 19
            }
            castSpellByName("Acererak the Archlich").shouldBeTrue()
            passUntilResolved()
            val completed = allMessages.persistentAnnotationsOfType(AnnotationType.DungeonStatus).last()
            assertSoftly {
                completed.id shouldBe first.id
                completed.detailString("CurrentDungeon") shouldBe "nil"
                completed.detailString("CurrentDungeonZCID") shouldBe "nil"
                completed.detailString("CurrentRoom") shouldBe "nil"
                completed.detailIntList("AllDungeonsCompleted") shouldBe listOf(dungeon)
                human
                    .getZone(ZoneType.Command)
                    .cards
                    .filter { it.type.isDungeon }
                    .shouldBeEmpty()
            }
            castSpellUntilSelectNReq("Acererak the Archlich")
            chooseCatalog("Lost Mine of Phandelver")
            passUntilResolved()
            keepScry()
            passUntilResolved()
            val reentry = allMessages.persistentAnnotationsOfType(AnnotationType.DungeonStatus).last()
            assertSoftly {
                reentry.id shouldBe first.id
                reentry.detailInt("CurrentDungeon") shouldBe dungeon
                reentry.detailInt("CurrentDungeonZCID") shouldNotBe firstIid
                reentry.detailInt("CurrentRoom") shouldBe first.detailInt("CurrentRoom")
                reentry.detailIntList("AllDungeonsCompleted") shouldBe listOf(dungeon)
            }
        }

        session(
            "activated Venture uses its stack ability as the catalog source",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Dungeon Map;Swamp;Swamp;Swamp
                humanlibrary=Forest;Forest
                ailibrary=Island;Island
                """.trimIndent(),
        ) {
            activateAbility("Dungeon Map").shouldBeTrue()
            passUntil(maxPasses = 10) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val request = lastSelectNReq()
            val objects = allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.gameObjectsList }
            objects.last { it.instanceId == request.sourceId }.type shouldBe GameObjectType.Ability
            chooseCatalog("Lost Mine of Phandelver")
            passUntilResolved()
            keepScry()
            passUntilResolved()
            val dungeon = allMessages.persistentAnnotationsOfType(AnnotationType.DungeonStatus).last().detailInt("CurrentDungeon")
            bridge.cardRepository.findNameByGrpId(dungeon) shouldBe "Lost Mine of Phandelver"
        }

        session(
            "ordinary creature cast creates no dungeon state",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Grizzly Bears
                humanbattlefield=Forest;Forest
                humanlibrary=Forest;Forest
                ailibrary=Island;Island
                """.trimIndent(),
        ) {
            castSpellByName("Grizzly Bears").shouldBeTrue()
            passUntilResolved()
            allMessages.persistentAnnotationsOfType(AnnotationType.DungeonStatus).shouldBeEmpty()
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Grizzly Bears" }
                .shouldBeTrue()
        }
    })

private fun MatchFlowHarness.chooseCatalog(label: String) {
    val req = lastSelectNReq()
    val id =
        req.idsList.single { id ->
            val text =
                if (req.idType == IdType.CardGrpId) {
                    bridge.cardRepository.findNameByGrpId(id)
                } else {
                    bridge.cardRepository.findAbilityLocalization(id)?.text
                }
            text == label || text?.startsWith("$label —") == true
        }
    respondToSelectN(listOf(id))
}

private fun MatchFlowHarness.keepScry() {
    val req = allMessages.last { it.hasGroupReq() }.groupReq
    respondToScry(emptyList(), req.instanceIdsList)
}
