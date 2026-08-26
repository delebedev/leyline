package leyline.session.actions

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.after
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo

class ManaPoolSessionTest :
    SessionTest({
        // Racers' Ring isn't in the default deck registry — register it before
        // any puzzle parses its name, not inside a test body (too late: the
        // puzzle parser needs the card registered by the time it loads).
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Racers' Ring")
        }

        val racersRingPuzzle = PuzzleSource.definitionFromResource("data/puzzles/racers-ring-draw.pzl").content

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
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .first { it.name == "Llanowar Elves" }
                .setSickness(false)

            val forestMessages = after { activateMana("Forest").shouldBeTrue() }.messages
            val forestPool = forestMessages.latestHumanManaPool()
            assertSoftly {
                forestPool.size shouldBe 1
                withClue("forestPool=$forestPool forestIid=$forestIid") {
                    forestPool.hasGreenFrom(forestIid).shouldBeTrue()
                }
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

        session(
            "tapping dual land projects selected floating mana",
            puzzle = racersRingPuzzle,
        ) {
            val landIid = instanceIdOf("Racers' Ring")
            val messages =
                after { activateMana("Racers' Ring", selectedColor = ManaColor.Green_afc9).shouldBeTrue() }
                    .messages
            val pool = messages.latestHumanManaPool()

            assertSoftly {
                pool.size shouldBe 1
                pool.hasManaFrom(landIid, ManaColor.Green_afc9).shouldBeTrue()
            }
        }

        session(
            "unsupported mana color leaves projection state unchanged",
            puzzle = racersRingPuzzle,
        ) {
            val before = bridge.projectionStateSnapshot()

            activateMana("Racers' Ring", selectedColor = ManaColor.Blue_afc9).shouldBeFalse()

            bridge.projectionStateSnapshot() shouldBe before
        }

        session(
            "cast payment retains each producing mana ability identity",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Grizzly Bears
                humanbattlefield=Mountain;Llanowar Elves
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            human.battlefield.card("Llanowar Elves").setSickness(false)

            activateMana("Mountain").shouldBeTrue()
            activateMana("Llanowar Elves").shouldBeTrue()
            val messages = after { castSpellByName("Grizzly Bears").shouldBeTrue() }.messages
            val manaActions =
                messages
                    .annotationsOfType(AnnotationType.UserActionTaken)
                    .filter { it.detailInt("actionType") == ActionType.ActivateMana.number }
            val createdByAbility =
                messages
                    .annotationsOfType(AnnotationType.AbilityInstanceCreated)
                    .associateBy { it.affectedIdsList.single() }
            val tappedByAbility =
                messages
                    .annotationsOfType(AnnotationType.TappedUntappedPermanent)
                    .associateBy { it.affectorId }
            val deletedByAbility =
                messages
                    .annotationsOfType(AnnotationType.AbilityInstanceDeleted)
                    .associateBy { it.affectedIdsList.single() }

            manaActions.map { it.detailInt("abilityGrpId") }.toSet() shouldBe setOf(1004, 1005)
            manaActions.forEach { action ->
                val abilityIid = action.affectedIdsList.single()
                val created = createdByAbility.getValue(abilityIid)
                val tapped = tappedByAbility.getValue(abilityIid)
                val deleted = deletedByAbility.getValue(abilityIid)

                assertSoftly {
                    created.detailInt("source_zone") shouldBe ZoneIds.BATTLEFIELD
                    tapped.affectedIdsList shouldBe listOf(created.affectorId)
                    deleted.affectorId shouldBe created.affectorId
                }
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
