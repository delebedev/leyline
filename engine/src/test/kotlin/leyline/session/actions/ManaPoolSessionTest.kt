package leyline.session.actions

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo
import java.io.File

class ManaPoolSessionTest :
    SessionTest({
        // Racers' Ring isn't in the default deck registry — register it before
        // any puzzle parses its name, not inside a test body (too late: the
        // puzzle parser needs the card registered by the time it loads).
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureCardRegistered("Racers' Ring")
        }

        val racersRingPuzzle = File("../puzzles/racers-ring-draw.pzl").readText()

        session(
            "tapping land and mana creature projects floating mana pool",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Grizzly Bears
                humanbattlefield=Forest;Llanowar Elves
                humanlibrary=Mountain
                ailibrary=Mountain
                """,
        ) {
            val forestIid = instanceIdOf("Forest")
            val elfIid = instanceIdOf("Llanowar Elves")
            after { activateMana("Forest").shouldBeTrue() }
            val forestPool =
                observe()
                    .client.players[SessionTest.HUMAN_SEAT]
                    ?.manaPoolList
                    .orEmpty()
            assertSoftly {
                forestPool.size shouldBe 1
                withClue("forestPool=$forestPool forestIid=$forestIid") {
                    forestPool.hasGreenFrom(forestIid).shouldBeTrue()
                }
            }

            after { activateMana("Llanowar Elves").shouldBeTrue() }
            val elfPool =
                observe()
                    .client.players[SessionTest.HUMAN_SEAT]
                    ?.manaPoolList
                    .orEmpty()
            assertSoftly {
                elfPool.size shouldBe 2
                elfPool.hasGreenFrom(forestIid).shouldBeTrue()
                elfPool.hasGreenFrom(elfIid).shouldBeTrue()
            }
        }

        session(
            "tapping dual land projects selected floating mana",
            puzzle = racersRingPuzzle,
        ) {
            val landIid = instanceIdOf("Racers' Ring")
            after { activateMana("Racers' Ring", selectedColor = ManaColor.Green_afc9).shouldBeTrue() }
            val pool =
                observe()
                    .client.players[SessionTest.HUMAN_SEAT]
                    ?.manaPoolList
                    .orEmpty()

            assertSoftly {
                pool.size shouldBe 1
                pool.hasManaFrom(landIid, ManaColor.Green_afc9).shouldBeTrue()
            }
        }

        session(
            "unsupported mana color leaves projection state unchanged",
            puzzle = racersRingPuzzle,
        ) {
            val before = checkpoint()

            activateMana("Racers' Ring", selectedColor = ManaColor.Blue_afc9).shouldBeFalse()

            messagesSince(before).filter { it.hasGameStateMessage() }.shouldBeEmpty()
        }
    })

private fun List<ManaInfo>.hasGreenFrom(instanceId: Int): Boolean =
    any { mana ->
        mana.srcInstanceId == instanceId &&
            mana.color == ManaColor.Green_afc9 &&
            mana.count == 1 &&
            mana.manaId >= 10
    }

private fun List<ManaInfo>.hasManaFrom(
    instanceId: Int,
    color: ManaColor,
): Boolean =
    any { mana ->
        mana.srcInstanceId == instanceId &&
            mana.color == color &&
            mana.count == 1 &&
            mana.manaId >= 10
    }
