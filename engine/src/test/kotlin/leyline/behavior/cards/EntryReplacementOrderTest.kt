package leyline.behavior.cards

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.AutomaticAnswerRule
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest

/** The order of entry replacements is asked only when they come from more than the entering permanent. */
class EntryReplacementOrderTest :
    SessionTest({
        session(
            "a Siege applies its own protector choice and defense counters without asking their order",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Invasion of Zendikar
            humanbattlefield=Forest;Forest;Forest;Forest
            humanlibrary=Forest;Island;Forest;Forest;Forest
            ailibrary=Island;Island;Island;Island;Island
            """.trimIndent(),
        ) {
            castSpellByName("Invasion of Zendikar").shouldBeTrue()
            passUntil(maxPasses = 12) { allMessages.any { it.hasSearchReq() } }.shouldBeTrue()
            val siege = human.battlefield.card("Invasion of Zendikar")
            val automatic =
                bridge
                    .promptBridge(SeatId(1))
                    .history
                    .mapNotNull { (it.route as? ResolvedPromptRoute.AutoResolve)?.rule }
            assertSoftly {
                allMessages.none { it.hasSelectNReq() }.shouldBeTrue()
                automatic shouldBe listOf(AutomaticAnswerRule.OwnEntryReplacementOrder)
                siege.protectingPlayer shouldBe ai
                siege.getCounters(CounterEnumType.DEFENSE) shouldBe 3
            }
        }

        session(
            "an opponent's entry replacement and the entering creature's own counters still ask their order",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Servant of the Scale
            humanbattlefield=Forest
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            aibattlefield=Blind Obedience
            ailibrary=Island;Island;Island;Island;Island
            """.trimIndent(),
        ) {
            castSpellByName("Servant of the Scale").shouldBeTrue()
            val asked = passUntil(maxPasses = 6) { allMessages.any { it.hasSelectNReq() } }
            val entered = passUntil(maxPasses = 6) { human.isCardInPlay("Servant of the Scale") }
            val servant = human.battlefield.card("Servant of the Scale")
            assertSoftly {
                asked.shouldBeTrue()
                entered.shouldBeTrue()
                servant.isTapped shouldBe true
                servant.getCounters(CounterEnumType.P1P1) shouldBe 1
            }
        }
    })
