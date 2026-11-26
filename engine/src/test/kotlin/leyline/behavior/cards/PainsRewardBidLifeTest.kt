package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import leyline.bridge.handoff.AutomaticAnswerRule
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest

private val PAINS_REWARD_PUZZLE =
    """
    # Design Narrative
    # Mechanic: life bidding. The caster opens the bid, then each other player may top it in turn order.
    # Forcing: Pain's Reward is the only hand card and three Swamps pay for it.
    # AI behavior: the opponent tops a bid below Pain's Reward's AI bid limit of 10 while it has life to spare.
    # Card roles:
    #   Pain's Reward - the bidding spell; the high bidder loses the bid in life and draws four cards.
    #   Swamps - pay {2}{B}.
    # Protocol path: Cast -> NumericInputReq opening bid -> SelectNReq top-or-pass per bidding round.

    [metadata]
    Name:Pain's Reward Life Bidding
    Goal:Win
    Turns:1
    Difficulty:Easy
    Description:Cast Pain's Reward and bid life against the opponent.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Pain's Reward
    humanbattlefield=Swamp;Swamp;Swamp
    humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp;Swamp
    ailibrary=Forest;Forest;Forest;Forest;Forest;Forest
    """.trimIndent()

class PainsRewardBidLifeTest :
    SessionTest({
        session("An opening bid the opponent will not top wins the bidding", puzzle = PAINS_REWARD_PUZZLE) {
            val human = bridge.getPlayer(SeatId(1))!!
            nextNumericInput(10)
            check(castSpellByName("Pain's Reward"))
            passUntilResolved()
            val automatic =
                bridge
                    .promptBridge(SeatId(1))
                    .history
                    .mapNotNull { (it.route as? ResolvedPromptRoute.AutoResolve)?.rule }

            assertSoftly {
                automatic shouldBe listOf(AutomaticAnswerRule.HighBidderHoldsBid)
                human.life shouldBe 10
                human.getZone(ZoneType.Hand).size() shouldBe 4
                allMessages.none { it.hasSelectNReq() } shouldBe true
            }
        }

        session("A topped bid asks the caster to top it or let it stand", puzzle = PAINS_REWARD_PUZZLE) {
            val human = bridge.getPlayer(SeatId(1))!!
            val ai = bridge.getPlayer(SeatId(2))!!
            nextNumericInput(1)
            val req = castSpellUntilSelectNReq("Pain's Reward")
            val labels = req.prompt.parametersList.map { it.stringValue }

            assertSoftly {
                labels.size shouldBe 2
                labels[0] shouldStartWith "Top the high bid of "
                labels[1] shouldBe "Let the high bid stand"
            }
            respondToSelectN(listOf(req.idsList[1]))
            passUntilResolved()

            assertSoftly {
                human.life shouldBe 20
                human.getZone(ZoneType.Hand).size() shouldBe 0
                ai.life shouldBeLessThan 20
                ai.getZone(ZoneType.Hand).size() shouldBe 4
            }
        }
    })
