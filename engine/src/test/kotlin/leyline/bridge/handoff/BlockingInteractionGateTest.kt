package leyline.bridge.handoff

import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class BlockingInteractionGateTest :
    FunSpec({
        tags(UnitTag)

        val unusedRuntime =
            object : BlockingInteractionRuntime {
                override fun awaitOptional(
                    interaction: BlockingInteraction.Optional,
                    timeoutMs: Long?,
                    defaultOnTimeout: Boolean,
                ): Boolean = error("null host must not publish")

                override fun awaitNumeric(
                    interaction: BlockingInteraction.Numeric,
                    timeoutMs: Long?,
                ): Int = error("null source must not publish")

                override fun awaitDamage(
                    interaction: BlockingInteraction.Damage,
                    attacker: Card,
                    blockers: CardCollectionView,
                    defender: GameEntity?,
                    timeoutMs: Long?,
                    fallback: () -> MutableMap<Card?, Int>?,
                ): MutableMap<Card?, Int>? = error("unused")

                override fun takeCachedDamage(
                    attacker: Card,
                    blockers: CardCollectionView,
                ): MutableMap<Card?, Int>? = error("unused")
            }

        test("null optional host keeps the established true fallback for either timeout default") {
            val gate = OptionalActionGate(null, unusedRuntime)

            gate.await(null, defaultOnTimeout = false, logContext = "test") shouldBe true
            gate.await(null, defaultOnTimeout = true, logContext = "test") shouldBe true
        }

        test("null numeric source returns the minimum without publication") {
            NumericInputGate(null, unusedRuntime).await(null, min = 3, max = 9, defaultOnTimeout = 7, logContext = "test") shouldBe 3
        }

        test("damage assignment retains its thirty-second default and configured override") {
            damageAssignmentTimeout(null) shouldBe GameActionBridge.DEFAULT_TIMEOUT_MS
            damageAssignmentTimeout(7_000) shouldBe 7_000
        }
    })
