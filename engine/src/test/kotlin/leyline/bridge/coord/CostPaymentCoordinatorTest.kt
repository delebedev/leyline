package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.battlefield
import leyline.testkit.graveyard

class CostPaymentCoordinatorTest :
    SessionTest({
        session(
            "automatic payment handles a mana source that sacrifices another creature",
            fullControl = true,
            puzzleFile = "data/puzzles/phyrexian-tower-sacrifice-mana.pzl",
        ) {
            val bridgedController = human.controller
            castSpellByName("Bad Moon").shouldBeTrue()

            val resolved =
                passUntil(maxPasses = 8) {
                    runCatching { human.battlefield.card("Bad Moon") }.isSuccess
                }
            assertSoftly {
                human.controller shouldBe bridgedController
                resolved.shouldBeTrue()
                human.graveyard.card("Grizzly Bears").name shouldBe "Grizzly Bears"
                human.battlefield
                    .card("Phyrexian Tower")
                    .isTapped
                    .shouldBeTrue()
            }
        }
    })
