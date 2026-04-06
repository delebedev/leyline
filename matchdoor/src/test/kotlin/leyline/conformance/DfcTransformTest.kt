package leyline.conformance

import forge.card.CardStateName
import forge.game.event.GameEventCardStatsChanged
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.PlayerAction
import leyline.game.awaitFreshPending
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class DfcTransformTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("transform emits Qualification pAnn for Menace on back face") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Concealing Curtains", human, ZoneType.Battlefield)
            }
            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Concealing Curtains" }

            // Prime the collector's backside cache with front-face state
            game.fireEvent(GameEventCardStatsChanged(card))
            // Drain initial events via baseline snapshot
            base.stateOnlyDiff(game, b, counter)

            // Simulate transform to back face and capture the diff GSM
            val gsm = base.captureAfterAction(b, game, counter) {
                card.setState(CardStateName.Backside, true)
                card.setBackSide(true)
                game.fireEvent(GameEventCardStatsChanged(card))
            }

            val qualAnns = gsm.persistentAnnotationsList.filter {
                AnnotationType.Qualification in it.typeList
            }
            qualAnns.shouldNotBeEmpty()
            val menaceAnn = qualAnns.first()
            menaceAnn.detailUint("grpid") shouldBe 142 // Menace keyword grpId
            menaceAnn.detailUint("QualificationType") shouldBe 40
        }

        test("activated transform resolves through bridge").config(tags = setOf(IntegrationTag)) {
            val puzzleText = """
                [metadata]
                Name:DFC Transform Test
                Goal:Win
                Turns:1
                Difficulty:Tutorial
                Description:Activate Concealing Curtains transform ability

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Concealing Curtains;Swamp;Swamp;Swamp
                aibattlefield=Runeclaw Bear
            """.trimIndent()

            val (b, game, _) = base.startPuzzleAtMain1(puzzleText)
            val player = game.humanPlayer

            val curtains = player.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Concealing Curtains" }
            curtains.shouldNotBeNull()

            // Verify the transform ability is found as a non-mana activated ability
            val abilities = curtains.spellAbilities.filter {
                it.isActivatedAbility && !it.isManaAbility()
            }
            abilities.shouldNotBeEmpty()

            // Submit ActivateAbility through the bridge
            val pending = awaitFreshPending(b, null)
            pending.shouldNotBeNull()

            val submitted = b.actionBridge(1).submitAction(
                pending.actionId,
                PlayerAction.ActivateAbility(ForgeCardId(curtains.id), 0),
            )
            submitted.shouldBeTrue()

            // Wait for the engine to process (ability goes on stack, resolves)
            // Pass priority to let the ability resolve
            val pending2 = awaitFreshPending(b, pending.actionId)
            pending2.shouldNotBeNull()
            b.actionBridge(1).submitAction(
                pending2.actionId,
                PlayerAction.PassPriority,
            )

            // Wait for resolution — engine processes SetState effect
            val pending3 = awaitFreshPending(b, pending2.actionId)
            pending3.shouldNotBeNull()

            // Verify the card transformed to back face
            curtains.isBackSide shouldBe true
            curtains.currentStateName shouldBe CardStateName.Backside
            curtains.name shouldBe "Revealing Eye"
        }

        test("activated transform resolves through MatchSession").config(tags = setOf(IntegrationTag)) {
            val puzzleText = """
                [metadata]
                Name:DFC Transform MatchSession Test
                Goal:Win
                Turns:1
                Difficulty:Tutorial
                Description:Activate Concealing Curtains transform via MatchSession

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Concealing Curtains;Swamp;Swamp;Swamp
                aibattlefield=Runeclaw Bear
            """.trimIndent()

            val harness = MatchFlowHarness(validating = false)
            try {
                harness.connectAndKeepPuzzleText(puzzleText)

                val game = harness.bridge.getGame()!!
                val player = game.humanPlayer
                val curtains = player.getZone(ZoneType.Battlefield).cards
                    .first { it.name == "Concealing Curtains" }

                // Activate the transform ability through MatchSession
                val activated = harness.activateAbility("Concealing Curtains", 0)
                activated.shouldBeTrue()

                // Pass priority to let the ability resolve off the stack
                harness.passPriority()

                // Verify the card transformed
                curtains.isBackSide shouldBe true
                curtains.currentStateName shouldBe CardStateName.Backside
                curtains.name shouldBe "Revealing Eye"
            } finally {
                harness.shutdown()
            }
        }
    })
