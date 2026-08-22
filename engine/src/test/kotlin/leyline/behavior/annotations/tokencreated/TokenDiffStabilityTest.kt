package leyline.behavior.annotations.tokencreated

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.*
import leyline.tooling.headless.HeadlessCard
import leyline.tooling.headless.HeadlessMatch

/** Clue tokens keep their semantic identity in both full and subsequent observations. */
class TokenDiffStabilityTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Novice Inspector")
            TestCardRegistry.ensureCardRegistered("Plains")
        }
        val puzzle =
            """
            [metadata]
            Name:Clue Token Diff Stability
            Goal:Win
            Turns:5
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Novice Inspector
            humanbattlefield=Plains
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            aibattlefield=Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        fun HeadlessMatch.castInspectorAndWaitForClue(): HeadlessCard {
            human.hand.cards
                .any { it.name == "Novice Inspector" }
                .shouldBeTrue()
            castSpellByName("Novice Inspector").shouldBeTrue()
            repeat(15) { if (!human.battlefield.cards.any { it.isToken }) passPriority() }
            return human.battlefield.cards
                .firstOrNull { it.isToken }
                .shouldNotBeNull()
        }

        session("Clue token has Artifact type, Clue subtype, and an ability", puzzle = puzzle) {
            val clue = castInspectorAndWaitForClue()
            assertSoftly {
                clue.cardTypes shouldContain "Artifact_a80b"
                clue.subtypes shouldContain "Clue"
                clue.abilityIds.shouldNotBeEmpty()
                clue.isToken shouldBe true
            }
        }

        session("Clue token retains identity across a later diff observation", puzzle = puzzle) {
            val clue = castInspectorAndWaitForClue()
            val checkpoint = checkpoint()
            passPriority()
            val observed = cardByIid(clue.id).shouldNotBeNull()
            val diffObjects =
                messagesSince(checkpoint)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.instanceId == clue.id }
            assertSoftly {
                observed.instanceId shouldBe clue.id
                observed.cardTypes shouldContain "Artifact_a80b"
                observed.subtypes shouldContain "Clue"
                observed.abilityIds.shouldNotBeEmpty()
                messagesSince(checkpoint).shouldNotBeEmpty()
                if (diffObjects.isNotEmpty()) {
                    diffObjects.forEach { diffObject ->
                        diffObject.cardTypesList.shouldContain("Artifact_a80b")
                        diffObject.subtypesList.shouldContain("Clue")
                        diffObject.instanceId shouldBe clue.id
                    }
                }
            }
        }
    })
