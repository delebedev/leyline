package leyline.session.actions

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.copilot.CopilotProposalService
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.ZoneIds
import leyline.testkit.FixturePinned
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.after
import leyline.testkit.annotationsOfType
import leyline.testkit.beMissingFrom
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo

@FixturePinned
class ManaPoolSessionTest :
    SessionTest({
        // Racers' Ring isn't in the default deck registry — register it before
        // any puzzle parses its name, not inside a test body (too late: the
        // puzzle parser needs the card registered by the time it loads).
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Racers' Ring")
            TestCardRegistry.ensureCardRegistered("Bayou")
            TestCardRegistry.ensureCardRegistered("Ashnod's Altar")
            TestCardRegistry.ensureCardRegistered("Path of Ancestry")
            TestCardRegistry.ensureCardRegistered("Mossfire Valley")
            TestCardRegistry.ensureCardRegistered("Ruby Medallion")
            TestCardRegistry.ensureCardRegistered("Kenrith, the Returned King")
            TestCardRegistry.ensureCardRegistered("Golden Egg")
            TestCardRegistry.ensureCardRegistered("Cavalier of Dawn")
            TestCardRegistry.ensureCardRegistered("Wan Shi Tong, Librarian")
            TestCardRegistry.ensureCardRegistered("Stonecoil Serpent")
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
            "type-derived dual-land mana retains the selected floating color identity",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Bayou
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val landIid = instanceIdOf("Bayou")
            val mana =
                after { activateMana("Bayou", abilityIndex = 1).shouldBeTrue() }
                    .messages
                    .latestHumanManaPool()
                    .single()

            assertSoftly {
                mana.srcInstanceId shouldBe landIid
                mana.color shouldBe ManaColor.Green_afc9
                mana.abilityGrpId shouldBe 1005
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
            "type-granted duplicate activates the printed mana ability",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Takenuma, Abandoned Mire;Urborg, Tomb of Yawgmoth
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val takenumaIid = instanceIdOf("Takenuma, Abandoned Mire")
            val messages = after { activateMana("Takenuma, Abandoned Mire").shouldBeTrue() }.messages
            val mana = messages.latestHumanManaPool().single()

            assertSoftly {
                mana.srcInstanceId shouldBe takenumaIid
                mana.color shouldBe ManaColor.Black_afc9
                mana.count shouldBe 1
                human.battlefield.card("Takenuma, Abandoned Mire").isTapped shouldBe true
            }
        }

        session(
            "sacrifice mana ability uses the bridged cost decision",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Ashnod's Altar;Grizzly Bears
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val altarIid = human.battlefield.iid("Ashnod's Altar")
            val creatureIid = human.battlefield.iid("Grizzly Bears")

            activateMana("Ashnod's Altar").shouldBeTrue()
            respondToEffectCost(listOf(creatureIid))

            val mana = allMessages.latestHumanManaPool().single { it.srcInstanceId == altarIid }
            assertSoftly {
                human.graveyard.card("Grizzly Bears")
                mana.color shouldBe ManaColor.Colorless_afc9
                mana.count shouldBe 2
            }
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

        session(
            "paid mana filter is used after its activation cost source",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Forest;Mountain;Ruby Medallion
                humanbattlefield=Path of Ancestry;Mossfire Valley
                humancommand=Kenrith, the Returned King|IsCommander
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val rubyIid = human.hand.iid("Ruby Medallion")
            val offered =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq
                    .actionsList
                    .count { it.actionType == ActionType.Cast && it.instanceId == rubyIid }
            offered shouldBe 1

            castSpellByName("Ruby Medallion").shouldBeTrue()
            passUntilResolved()

            human.battlefield.card("Ruby Medallion")
            assertSoftly {
                human.battlefield
                    .card("Path of Ancestry")
                    .isTapped
                    .shouldBeTrue()
                human.battlefield
                    .card("Mossfire Valley")
                    .isTapped
                    .shouldBeTrue()
            }
        }

        session(
            "paid source availability is independent of hand position",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ruby Medallion;Forest;Ruby Medallion;Mountain;Ruby Medallion
                humanbattlefield=Path of Ancestry;Mossfire Valley
                humancommand=Kenrith, the Returned King|IsCommander
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val rubyIds =
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .filter { it.name == "Ruby Medallion" }
                    .map { bridge.instanceId(it) }
                    .toSet()
            val castIds =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq
                    .actionsList
                    .filter { it.actionType == ActionType.Cast }
                    .map { it.instanceId }
                    .toSet()

            castIds shouldBe rubyIds
        }

        session(
            "net-zero mana filter is not free casting mana",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Cavalier of Dawn
                humanbattlefield=Plains;Plains;Plains;Plains;Golden Egg
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val actions = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
            val cavalierIid = human.hand.iid("Cavalier of Dawn")
            val eggIid = human.battlefield.iid("Golden Egg")

            assertSoftly {
                actions.actionsList.count { it.actionType == ActionType.Cast && it.instanceId == cavalierIid } shouldBe 0
                actions.inactiveActionsList.count { it.actionType == ActionType.Cast && it.instanceId == cavalierIid } shouldBe 1
                actions.actionsList.count { it.actionType == ActionType.ActivateMana && it.instanceId == eggIid } shouldBe 1
            }
        }

        session(
            "paid mana filter without seed mana is not castable",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ruby Medallion
                humanbattlefield=Mossfire Valley
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val actions = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
            val rubyIid = human.hand.iid("Ruby Medallion")

            assertSoftly {
                actions.actionsList.count { it.actionType == ActionType.Cast && it.instanceId == rubyIid } shouldBe 0
                actions.inactiveActionsList.count { it.actionType == ActionType.Cast && it.instanceId == rubyIid } shouldBe 1
            }
        }

        session(
            "five ordinary mana sources keep the spell castable",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Cavalier of Dawn
                humanbattlefield=Plains;Plains;Plains;Plains;Plains;Golden Egg
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            val actions = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
            val cavalierIid = human.hand.iid("Cavalier of Dawn")
            val eggIid = human.battlefield.iid("Golden Egg")
            val cast = actions.actionsList.single { it.actionType == ActionType.Cast && it.instanceId == cavalierIid }

            assertSoftly {
                cast.autoTapSolution.autoTapActionsCount shouldBe 5
                cast.autoTapSolution.autoTapActionsList
                    .none { it.instanceId == eggIid }
                    .shouldBeTrue()
            }
        }

        session(
            "greedy numeric choice keeps X cast payable and commits it",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Wan Shi Tong, Librarian
                humanbattlefield=Island;Island;Island;Island
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            holdNextNumericInput()
            castSpellByName("Wan Shi Tong, Librarian").shouldBeTrue()
            val prompt = allMessages.last { it.hasNumericInputReq() }
            val proposal = CopilotProposalService(bridge, SeatId(1)).propose(prompt)

            assertSoftly {
                proposal.numericValue shouldBe 2
                game()
                    .stackZone.cards
                    .single { it.name == "Wan Shi Tong, Librarian" }
                    .castSA.xManaCostPaid shouldBe null
            }
            respondToNumericInput(checkNotNull(proposal.numericValue))

            assertSoftly {
                "Wan Shi Tong, Librarian" should beMissingFrom(ZoneType.Hand, human)
                human.battlefield.card("Wan Shi Tong, Librarian").netPower shouldBe 3
            }
        }

        session(
            "greedy numeric choice respects payable X on a zero-base-cost spell",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Stonecoil Serpent
                humanbattlefield=Forest;Forest
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            holdNextNumericInput()
            castSpellByName("Stonecoil Serpent").shouldBeTrue()
            val prompt = allMessages.last { it.hasNumericInputReq() }
            val proposal = CopilotProposalService(bridge, SeatId(1)).propose(prompt)

            proposal.numericValue shouldBe 2
            respondToNumericInput(checkNotNull(proposal.numericValue))

            human.battlefield.card("Stonecoil Serpent").netPower shouldBe 2
        }

        session(
            "explicit paid mana filter activation remains available",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ruby Medallion
                humanbattlefield=Path of Ancestry;Mossfire Valley
                humancommand=Kenrith, the Returned King|IsCommander
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            assertSoftly {
                activateMana("Path of Ancestry", selectedColor = ManaColor.Red_afc9).shouldBeTrue()
                activateMana("Mossfire Valley").shouldBeTrue()
                allMessages.latestHumanManaPool().size shouldBe 2
                human.manaPool.totalMana() shouldBe 2
            }
            castSpellByName("Ruby Medallion").shouldBeTrue()
            passUntilResolved()

            human.battlefield.card("Ruby Medallion")
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
