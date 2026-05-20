package leyline.session.actions

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo

class ManaPoolSessionTest :
    SessionTest({
        test("tapping land and mana creature projects floating mana pool") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Grizzly Bears
                humanbattlefield=Forest;Llanowar Elves
                humanlibrary=Mountain
                ailibrary=Mountain
                """,
                name = "Floating Mana Pool",
                validating = true,
            )

            val forestIid = instanceIdOf("Forest")
            val elfIid = instanceIdOf("Llanowar Elves")
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .first { it.name == "Llanowar Elves" }
                .setSickness(false)

            val forestMessages = after { activateMana("Forest").shouldBeTrue() }.messages
            val forestPool = forestMessages.latestHumanManaPool()
            assertSoftly {
                forestPool.size shouldBe 1
                forestPool.hasGreenFrom(forestIid).shouldBeTrue()
            }

            val elfPool =
                after { activateMana("Llanowar Elves").shouldBeTrue() }
                    .messages
                    .latestHumanManaPool()
            assertSoftly {
                elfPool.size shouldBe 2
                elfPool.hasGreenFrom(forestIid).shouldBeTrue()
                elfPool.hasGreenFrom(elfIid).shouldBeTrue()
            }
        }
    })

private fun List<GREToClientMessage>.latestHumanManaPool(): List<ManaInfo> =
    gameStateMessages()
        .flatMap { it.playersList }
        .lastOrNull { it.systemSeatNumber == SessionTest.HUMAN_SEAT }
        ?.manaPoolList
        ?: error(
            "No human PlayerInfo in slice; player seats=${
                gameStateMessages().map { gsm ->
                    gsm.playersList.map { it.systemSeatNumber }
                }
            }",
        )

private fun List<ManaInfo>.hasGreenFrom(instanceId: Int): Boolean =
    any { mana ->
        mana.srcInstanceId == instanceId &&
            mana.color == ManaColor.Green_afc9 &&
            mana.count == 1 &&
            mana.manaId >= 10
    }
