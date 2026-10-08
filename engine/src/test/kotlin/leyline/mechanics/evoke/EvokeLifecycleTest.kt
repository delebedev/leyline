package leyline.mechanics.evoke

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.annotationsOfType
import leyline.testkit.detailString
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class EvokeLifecycleTest :
    SessionTest({
        session(
            "ordinary cast stays on the battlefield without Evoke state",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Mulldrifter
                humanbattlefield=Island;Island;Island;Island;Island
                humanlibrary=Island;Island;Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val snap = messageSnapshot()
            castSpellByName("Mulldrifter").shouldBeTrue()
            passUntilResolved(maxPasses = 12)
            val lifecycle = messagesSince(snap)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Mulldrifter"
                human.getZone(ZoneType.Graveyard).cards.any { it.name == "Mulldrifter" } shouldBe false
                lifecycle.persistentAnnotationsOfType(AnnotationType.TemporaryPermanent).shouldBeEmpty()
                lifecycle.persistentAnnotationsOfType(AnnotationType.CastingTimeOption).shouldBeEmpty()
                lifecycle.annotationsOfType(AnnotationType.ZoneTransfer_af5a).none {
                    it.detailString("category") == "Sacrifice"
                } shouldBe true
            }
        }
    })
