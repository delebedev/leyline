package leyline.testkit

import io.kotest.assertions.fail
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq

/**
 * A bounded slice of `GREToClientMessage` produced by `SessionTest.after { ... }`.
 *
 * The point of the slice is to make prompt-shape assertions read like
 * protocol contracts — `expectOneCastingTimeOptionsReq()` instead of
 * `messages.any { it.hasCastingTimeOptionsReq() }`. Failure messages name the
 * expected prompt and dump the slice's observed `GREMessageType` values, which
 * is far more useful than `false should be true`.
 *
 * The raw [messages] list stays public — slice is a lens, not a wall. Walk
 * extensions in [MessageWalk] (`gameStateMessages`, `allAnnotations`, etc.)
 * compose directly: `slice.messages.allAnnotations()`.
 */
class MessageSlice(
    val messages: List<GREToClientMessage>,
) {
    fun expectOneCastingTimeOptionsReq(): CastingTimeOptionsReq =
        expectOnePrompt("CastingTimeOptionsReq", { it.hasCastingTimeOptionsReq() }) { it.castingTimeOptionsReq }

    fun expectNoCastingTimeOptionsReq() = expectNoPrompt("CastingTimeOptionsReq") { it.hasCastingTimeOptionsReq() }

    fun expectOneSelectTargetsReq(): SelectTargetsReq =
        expectOnePrompt("SelectTargetsReq", { it.hasSelectTargetsReq() }) { it.selectTargetsReq }

    fun expectNoSelectTargetsReq() = expectNoPrompt("SelectTargetsReq") { it.hasSelectTargetsReq() }

    fun expectOnePayCostsReq(): PayCostsReq =
        expectOnePrompt("PayCostsReq", { it.hasPayCostsReq() }) { it.payCostsReq }

    fun expectNoPayCostsReq() = expectNoPrompt("PayCostsReq") { it.hasPayCostsReq() }

    private fun <T> expectOnePrompt(
        name: String,
        predicate: (GREToClientMessage) -> Boolean,
        extract: (GREToClientMessage) -> T,
    ): T {
        val matches = messages.filter(predicate)
        return when (matches.size) {
            1 -> extract(matches.single())
            0 ->
                fail(
                    "Expected exactly one $name in slice, found 0. " +
                        "Observed message types: ${observedTypes()}",
                )
            else ->
                fail(
                    "Expected exactly one $name in slice, found ${matches.size}. " +
                        "Observed message types: ${observedTypes()}",
                )
        }
    }

    private fun expectNoPrompt(
        name: String,
        predicate: (GREToClientMessage) -> Boolean,
    ) {
        val count = messages.count(predicate)
        if (count == 0) return
        fail(
            "Expected no $name in slice, found $count. " +
                "Observed message types: ${observedTypes()}",
        )
    }

    /**
     * Distinct `GREMessageType` values seen, excluding `GameStateMessage_695e`
     * which dominates every slice and adds noise to failure messages. Used for
     * failure diagnostics; not load-bearing.
     */
    private fun observedTypes(): List<GREMessageType> =
        messages
            .map { it.type }
            .distinct()
            .filter { it != GREMessageType.GameStateMessage_695e }
}
