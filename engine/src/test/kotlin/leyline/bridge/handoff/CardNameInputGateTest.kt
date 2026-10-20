package leyline.bridge.handoff

import forge.card.ICardFace
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.spellability.SpellAbility
import forge.player.PlayerControllerHuman
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import java.util.concurrent.TimeUnit
import java.util.function.Predicate

class CardNameInputGateTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:Name validation budget
            Goal:Survive
            Turns:3
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Demonic Consultation
            humanbattlefield=Swamp
            humanlibrary=Forest
            ailibrary=Plains
            """.trimIndent()

        test("inherited card validity retries share the original monotonic deadline") {
            val board = startPuzzleAtMain1(puzzle)
            val source = board.human.getCardsIn(forge.game.zone.ZoneType.Hand).first()
            val sa = source.spellAbilities.first()
            var now = 0L
            val timeouts = mutableListOf<Long>()
            val answers = ArrayDeque(listOf("Forest", "Grizzly Bears"))
            val runtime =
                NameRuntime { _, timeout ->
                    timeouts += timeout
                    now += TimeUnit.MILLISECONDS.toNanos(100)
                    answers.removeFirst()
                }
            val gate = CardNameInputGate(null, runtime) { now }
            val controller =
                object : PlayerControllerHuman(board.game, board.human, board.human.lobbyPlayer) {
                    override fun chooseSingleCardFace(
                        sa: SpellAbility,
                        message: String,
                        cpp: Predicate<ICardFace>,
                        name: String,
                    ): ICardFace = gate.await(sa, message, cpp)
                }
            gate.withChoiceDeadline { controller.chooseCardName(sa, Predicate { true }, "Creature", "Name a creature card.") } shouldBe
                "Grizzly Bears"
            timeouts shouldBe listOf(45_000L, 44_900L)
        }

        test("exceptions restore the deadline scope and an independent choice gets a fresh budget") {
            val board = startPuzzleAtMain1(puzzle)
            val sa =
                board.human
                    .getCardsIn(forge.game.zone.ZoneType.Hand)
                    .first()
                    .spellAbilities
                    .first()
            var now = 0L
            val timeouts = mutableListOf<Long>()
            val gate =
                CardNameInputGate(
                    null,
                    NameRuntime { _, timeout ->
                        timeouts += timeout
                        "Island"
                    },
                ) { now }
            shouldThrow<IllegalStateException> {
                gate.withChoiceDeadline {
                    now = TimeUnit.MILLISECONDS.toNanos(30_000)
                    error("interrupted validation")
                }
            }
            assertSoftly {
                gate.withChoiceDeadline { gate.await(sa, "Name a card.", Predicate { true }) }.name shouldBe "Island"
                timeouts shouldBe listOf(45_000L)
            }
        }

        test("validation cannot return a name after its deadline") {
            val board = startPuzzleAtMain1(puzzle)
            val sa =
                board.human
                    .getCardsIn(forge.game.zone.ZoneType.Hand)
                    .first()
                    .spellAbilities
                    .first()
            var now = 0L
            val timeouts = mutableListOf<Long>()
            val gate =
                CardNameInputGate(
                    null,
                    NameRuntime { _, timeout ->
                        timeouts += timeout
                        check(timeout > 0) { "expired" }
                        now = TimeUnit.MILLISECONDS.toNanos(45_000)
                        "Island"
                    },
                ) { now }
            shouldThrow<IllegalStateException> { gate.withChoiceDeadline { gate.await(sa, "Name a card.", Predicate { true }) } }
            timeouts shouldBe listOf(45_000L, 0L)
        }
    })

private class NameRuntime(
    private val answer: (BlockingInteraction.CardName, Long) -> String,
) : BlockingInteractionRuntime {
    override fun awaitCardName(
        interaction: BlockingInteraction.CardName,
        timeoutMs: Long,
    ): String = answer(interaction, timeoutMs)

    override fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean = error("unused")

    override fun awaitNumeric(
        interaction: BlockingInteraction.Numeric,
        timeoutMs: Long?,
    ): Int = error("unused")

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
    ): MutableMap<Card?, Int>? = null
}
