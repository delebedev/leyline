package leyline.unit

import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.cost.CostDiscard
import forge.game.cost.CostPayLife
import forge.game.cost.CostReveal
import forge.game.cost.PaymentDecision
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.WebCostDecision
import leyline.conformance.TestCardRegistry
import leyline.game.GameBridge
import leyline.game.PuzzleSource

class WebCostDecisionTest :
    FunSpec({

        tags(UnitTag)

        var bridge: GameBridge? = null

        beforeSpec {
            leyline.bridge.GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        data class Fixture(
            val bridge: GameBridge,
            val player: Player,
            val source: Card,
            val ability: SpellAbility,
            val decision: WebCostDecision,
        )

        fun fixture(): Fixture {
            val localBridge = GameBridge(bridgeTimeoutMs = 0, cards = TestCardRegistry.repo)
            bridge = localBridge
            localBridge.startPuzzle(
                PuzzleSource.loadFromText(
                    """
                    [metadata]
                    Name:Cost Decision Fixture
                    Goal:Win
                    Turns:1
                    Difficulty:Easy
                    Description:Web cost decision fixture.

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20

                    humanhand=Lightning Bolt
                    humanbattlefield=Mountain
                    humanlibrary=Mountain
                    ailibrary=Mountain
                    """.trimIndent(),
                ),
            )
            val player = localBridge.getPlayer(leyline.bridge.SeatId(1))!!
            val source = player.getCardsIn(forge.game.zone.ZoneType.Hand).first { it.name == "Lightning Bolt" }
            val ability = source.spellAbilities.first()
            val controller = localBridge.humanController ?: error("No human controller")
            return Fixture(
                bridge = localBridge,
                player = player,
                source = source,
                ability = ability,
                decision = WebCostDecision(
                    controller,
                    player,
                    ability,
                    false,
                    localBridge.promptBridge(1),
                    source,
                ),
            )
        }

        fun invokeSelectCards(
            decision: WebCostDecision,
            cards: CardCollectionView,
            min: Int,
            max: Int,
            cancelAllowed: Boolean,
        ): CardCollection? {
            val method = WebCostDecision::class.java.getDeclaredMethod(
                "selectCards",
                String::class.java,
                CardCollectionView::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return method.invoke(decision, "pick", cards, min, max, cancelAllowed) as CardCollection?
        }

        test("selectCards returns null for empty cancelable choice") {
            val fx = fixture()

            invokeSelectCards(fx.decision, CardCollection(), min = 1, max = 1, cancelAllowed = true).shouldBeNull()
        }

        test("selectCards auto-returns single forced choice") {
            val fx = fixture()
            val cards = CardCollection(fx.source)

            invokeSelectCards(fx.decision, cards, min = 1, max = 1, cancelAllowed = false)!!.map { it.name } shouldContainExactly listOf("Lightning Bolt")
        }

        test("visit pay life returns numeric payment when confirm defaults yes") {
            val fx = fixture()
            val result = fx.decision.visit(CostPayLife("3", null))

            result!!.c shouldBe 3
        }

        test("visit discard from source returns source card") {
            val fx = fixture()
            val result: PaymentDecision? = fx.decision.visit(CostDiscard("1", "CARDNAME", null))

            result!!.cards.map { it.name } shouldContainExactly listOf("Lightning Bolt")
        }

        test("visit reveal from source returns source card") {
            val fx = fixture()
            val result: PaymentDecision? = fx.decision.visit(CostReveal("1", "CARDNAME", null))

            result!!.cards.map { it.name } shouldContainExactly listOf("Lightning Bolt")
        }
    })
