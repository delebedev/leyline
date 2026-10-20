package leyline.bridge.handoff

import forge.StaticData
import forge.card.ICardFace
import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import java.util.concurrent.TimeUnit
import java.util.function.Predicate

/** The engine thread resolves only the submitted face, preserving Forge's name predicate. */
class CardNameInputGate(
    private val actionBridge: GameActionBridge?,
    private val runtime: BlockingInteractionRuntime,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var choiceDeadline: Long? = null

    /** Engine-thread scope includes retries made by the inherited card-validity loop. */
    fun <T> withChoiceDeadline(block: () -> T): T {
        val prior = choiceDeadline
        choiceDeadline = prior ?: newDeadline()
        return try {
            block()
        } finally {
            choiceDeadline = prior
        }
    }

    fun await(
        sa: SpellAbility,
        instruction: String,
        predicate: Predicate<ICardFace>,
    ): ICardFace {
        val deadline = choiceDeadline ?: newDeadline()
        var feedback: String? = null
        while (true) {
            val interaction = BlockingInteraction.CardName(ForgeCardId(sa.hostCard.id), instruction, feedback)
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - nanoTime())
            val name = runtime.awaitCardName(interaction, remaining).trim()
            val face = if (name.isEmpty() || name.length > MAX_INPUT_LENGTH) null else resolveFace(name)
            val accepted = face != null && predicate.test(face)
            if (nanoTime() >= deadline) runtime.awaitCardName(interaction, 0)
            if (accepted) return checkNotNull(face)
            feedback = "Enter a valid card name allowed by this effect."
        }
    }

    private fun resolveFace(name: String): ICardFace? {
        val data = StaticData.instance()
        data.attemptToLoadCard(name)
        return data.commonCards.getFaceByName(name)
    }

    private fun newDeadline(): Long {
        val timeout = actionBridge?.getTimeoutMs()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS
        return nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 45_000L
        const val MAX_INPUT_LENGTH = 512
    }
}
