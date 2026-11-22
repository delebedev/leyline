package leyline.bridge

import forge.game.spellability.SpellAbility
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-match counts that show how generous action offers are, keyed by card name.
 *
 * [offeredWhileUnsure] counts active offers built from an [Affordability.Unsure]
 * verdict, once per published action list. [paymentFailed] counts offered actions
 * whose mana payment then failed, keyed by card name and the verdict that offered
 * them.
 */
class AffordabilityDiagnostics {
    data class Snapshot(
        val offeredWhileUnsureByCard: Map<String, Int>,
        val offeredThenPaymentFailedByCard: Map<String, Int>,
    )

    private val offeredWhileUnsure = ConcurrentHashMap<String, Int>()
    private val paymentFailed = ConcurrentHashMap<String, Int>()

    internal fun recordOfferedWhileUnsure(sa: SpellAbility) {
        offeredWhileUnsure.merge(cardName(sa), 1, Int::plus)
    }

    internal fun recordPaymentFailed(
        sa: SpellAbility,
        verdict: Affordability,
    ) {
        paymentFailed.merge("${cardName(sa)} (${verdict.name.lowercase()})", 1, Int::plus)
    }

    fun snapshot(): Snapshot = Snapshot(offeredWhileUnsure.toSortedMap(), paymentFailed.toSortedMap())

    private fun cardName(sa: SpellAbility): String = sa.hostCard?.name ?: "unknown"
}
