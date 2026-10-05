package leyline.bridge.coord

import forge.game.cost.Cost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.OptionalActionGate
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import leyline.testkit.hand

class CostPaymentCoordinatorBoundsTest :
    BoardTest({
        test("repeatable bounds preserve complete costs and payment resources") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Joraga Warcaller", human, ZoneType.Hand)
                    repeat(5) { addCard("Forest", human, ZoneType.Battlefield) }
                }
            val ability =
                board.human.hand
                    .card("Joraga Warcaller")
                    .getSpells()
                    .first()
            ability.activatingPlayer = board.human
            val originalCost = ability.payCosts.toString()
            val coordinator =
                CostPaymentCoordinator(
                    board.bridge.promptBridge(SeatId(1)),
                    board.human,
                    OptionalActionGate(null, board.bridge.cutCoordinator),
                )

            assertSoftly {
                coordinator.repeatableKeywordMaximum(ability, Cost("1 G", false), Int.MAX_VALUE) shouldBe 2
                coordinator.repeatableKeywordMaximum(ability, Cost("1 G", false), 1) shouldBe 1
                ability.payCosts.toString() shouldBe originalCost
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .count { it.isTapped } shouldBe 0
                board.human.life shouldBe 20
            }
        }

        test("repeatable nonmana payment bounds preserve life") {
            val board = startWithBoard { _, human, _ -> addCard("Memnite", human, ZoneType.Hand) }
            board.human.setLife(5, null)
            val ability =
                board.human.hand
                    .card("Memnite")
                    .getSpells()
                    .first()
            ability.activatingPlayer = board.human
            val coordinator =
                CostPaymentCoordinator(
                    board.bridge.promptBridge(SeatId(1)),
                    board.human,
                    OptionalActionGate(null, board.bridge.cutCoordinator),
                )
            coordinator.repeatableKeywordMaximum(ability, Cost("PayLife<2>", false), Int.MAX_VALUE) shouldBe 2
            board.human.life shouldBe 5
        }

        test("granted convoke keeps repeat payments available without retaining probe selections") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Everflowing Chalice", human, ZoneType.Hand)
                    addCard("Chief Engineer", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val ability =
                board.human.hand
                    .card("Everflowing Chalice")
                    .getSpells()
                    .first()
            ability.activatingPlayer = board.human
            ability.hostCard.setCastFrom(ability.hostCard.zone)
            ability.hostCard = board.game.action.moveToStack(ability.hostCard, ability)
            ability.hostCard.castSA = ability
            board.game.action.checkStaticAbilities(false)
            ability.hostCard.hasKeyword(forge.game.keyword.Keyword.CONVOKE) shouldBe true
            val coordinator =
                CostPaymentCoordinator(
                    board.bridge.promptBridge(SeatId(1)),
                    board.human,
                    OptionalActionGate(null, board.bridge.cutCoordinator),
                )
            assertSoftly {
                coordinator.repeatableKeywordMaximum(ability, Cost("2", false), Int.MAX_VALUE) shouldBe 1
                ability.tappedForConvoke.size shouldBe 0
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .count { it.isTapped || it.isUsedToPay } shouldBe 0
            }
        }

        test("free repeat costs retain the upstream maximum after bounded probing") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Memnite", human, ZoneType.Hand)
                }
            val ability =
                board.human.hand
                    .card("Memnite")
                    .getSpells()
                    .first()
            ability.activatingPlayer = board.human
            val coordinator =
                CostPaymentCoordinator(
                    board.bridge.promptBridge(SeatId(1)),
                    board.human,
                    OptionalActionGate(null, board.bridge.cutCoordinator),
                )
            coordinator.repeatableKeywordMaximum(ability, Cost("0", false), Int.MAX_VALUE) shouldBe Int.MAX_VALUE
        }

        test("unpayable repeated color does not consume another available color") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Memnite", human, ZoneType.Hand)
                    repeat(3) { addCard("Forest", human, ZoneType.Battlefield) }
                }
            val ability =
                board.human.hand
                    .card("Memnite")
                    .getSpells()
                    .first()
            ability.activatingPlayer = board.human
            val coordinator =
                CostPaymentCoordinator(
                    board.bridge.promptBridge(SeatId(1)),
                    board.human,
                    OptionalActionGate(null, board.bridge.cutCoordinator),
                )
            coordinator.repeatableKeywordMaximum(ability, Cost("U", false), Int.MAX_VALUE) shouldBe 0
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .count { it.isTapped } shouldBe 0
        }
    })
