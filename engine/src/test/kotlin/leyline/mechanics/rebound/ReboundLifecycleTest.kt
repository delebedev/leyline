package leyline.mechanics.rebound

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.SessionTest
import leyline.testkit.beInExileOf
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beMissingFrom
import leyline.testkit.beOnBattlefieldOf

class ReboundLifecycleTest :
    SessionTest({
        session(
            "hand cast rebounds at the next upkeep for free and does not rebound again",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Ephemerate
                humanbattlefield=Grizzly Bears
                humanmanapool=W
                humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 7,
            fullControl = true,
        ) {
            val firstTarget = human.battlefield.iid("Grizzly Bears")
            castSpellByName("Ephemerate").shouldBeTrue()
            selectTargets(listOf(firstTarget))
            passUntilResolved()
            assertSoftly {
                "Ephemerate" should beInExileOf(human)
                "Ephemerate" should beMissingFrom(ZoneType.Graveyard, human)
                "Grizzly Bears" should beOnBattlefieldOf(human)
                human.manaPool.totalMana() shouldBe 0
            }
            val secondTarget = human.battlefield.iid("Grizzly Bears")
            secondTarget shouldNotBe firstTarget

            holdNextOptionalAction()
            val upkeepStart = messageSnapshot()
            passUntil(maxPasses = 80) {
                messagesSince(upkeepStart).any { it.hasOptionalActionMessage() }
            }.shouldBeTrue()
            assertSoftly {
                turn() shouldBe 3
                phase() shouldBe "UPKEEP"
                "Ephemerate" should beInExileOf(human)
                human.manaPool.totalMana() shouldBe 0
            }

            val recastStart = messageSnapshot()
            respondToOptionalAction(accept = true)
            messagesSince(recastStart).any { it.hasSelectTargetsReq() }.shouldBeTrue()
            selectTargets(listOf(secondTarget))
            passUntilResolved()
            assertSoftly {
                game().stack.isEmpty.shouldBeTrue()
                "Ephemerate" should beInGraveyardOf(human)
                "Ephemerate" should beMissingFrom(ZoneType.Exile, human)
                "Grizzly Bears" should beOnBattlefieldOf(human)
                human.battlefield.iid("Grizzly Bears") shouldNotBe secondTarget
                human.manaPool.totalMana() shouldBe 0
            }

            val resolvedStart = messageSnapshot()
            advanceToPhase("MAIN1", turn = 5)
            assertSoftly {
                "Ephemerate" should beInGraveyardOf(human)
                "Ephemerate" should beMissingFrom(ZoneType.Exile, human)
                messagesSince(resolvedStart).count { it.hasOptionalActionMessage() } shouldBe 0
            }
        }
    })
