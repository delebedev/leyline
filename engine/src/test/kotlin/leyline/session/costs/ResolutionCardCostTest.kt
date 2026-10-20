package leyline.session.costs

import forge.card.MagicColor
import forge.game.keyword.Keyword
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

class ResolutionCardCostTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Forest;Forest
            humanhand=Grizzly Bears;Forest
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            aibattlefield=Charismatic Conqueror
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        for (accept in listOf(false, true)) {
            session(
                if (accept) "paying the entrant tap cost prevents the Vampire" else "declining the entrant tap cost creates the Vampire",
                puzzle = puzzle,
                fullControl = true,
            ) {
                castSpellByName("Grizzly Bears").shouldBeTrue()
                passUntil(maxPasses = 8) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
                val entrant = human.battlefield.card("Grizzly Bears")
                if (accept) {
                    selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
                } else {
                    cancelAction()
                }
                passUntilResolved()

                val tokens = ai.getZone(ZoneType.Battlefield).cards.filter { it.isToken }
                assertSoftly {
                    entrant.isTapped shouldBe accept
                    tokens shouldHaveSize if (accept) 0 else 1
                    if (!accept) {
                        val token = tokens.single()
                        token.controller shouldBe ai
                        token.type.hasSubtype("Vampire").shouldBeTrue()
                        token.color.color shouldBe MagicColor.WHITE
                        token.netPower shouldBe 1
                        token.netToughness shouldBe 1
                        token.hasKeyword(Keyword.LIFELINK).shouldBeTrue()
                    }
                }
                playLand("Forest").shouldBeTrue()
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Forest" } shouldBe 3
                game().isGameOver shouldBe false
            }
        }
    })
