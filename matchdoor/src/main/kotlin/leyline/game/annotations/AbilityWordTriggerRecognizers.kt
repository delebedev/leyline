package leyline.game.annotations

import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.state.AbilityRegistry

/**
 * Text-pattern recognizers for ability-word families that don't surface as Forge
 * `Condition$` rows. Detection is by `<AbilityWord> —` (em dash, U+2014) prefix on
 * a card's `TriggerDescription$` / `Description$` / keyword text, since the
 * ability-word label is the canonical handle in Forge card text.
 *
 * Per-word evaluators register through [register] and decide whether to emit an
 * [AbilityWordScanner.AbilityWordEntry] given the card, its controller, and a
 * computed instance id + seat index. Each evaluator owns its own gating
 * (e.g. attacked-this-turn for Raid, life-gained-this-turn for Infusion).
 *
 * Same fixture-evidence rule as [AbilityWordScanner.CONDITIONS]: only register
 * recognizers whose emission contents are exercised by a puzzle fixture plus a
 * `ConformanceTag` test asserting the expected annotation shape.
 */
object AbilityWordTriggerRecognizers {
    private const val EM_DASH = '—'

    /** Per-word evaluator. Returns zero or more entries to emit for the given card. */
    fun interface Recognizer {
        fun evaluate(
            card: Card,
            controller: Player,
            iid: Int,
            seatIdx: Int,
            registry: AbilityRegistry?,
        ): List<AbilityWordScanner.AbilityWordEntry>
    }

    private val recognizers = mutableMapOf<String, Recognizer>()

    /** Test whether `<word> —` appears in any trigger / static / keyword description. */
    fun cardHasAbilityWordPrefix(
        card: Card,
        word: String,
    ): Boolean {
        val needle = "$word $EM_DASH"
        for (trigger in card.triggers ?: emptyList()) {
            val desc = trigger.getParam("TriggerDescription") ?: continue
            if (desc.startsWith(needle)) return true
        }
        for (sa in card.staticAbilities ?: emptyList()) {
            val desc = sa.getParam("Description") ?: continue
            if (desc.startsWith(needle)) return true
        }
        for (kw in card.keywords ?: emptyList()) {
            if (kw.toString().contains(needle)) return true
        }
        return false
    }

    fun scan(
        battlefieldCards: List<Card>,
        instanceIdResolver: (ForgeCardId) -> InstanceId,
        registryResolver: (Card) -> AbilityRegistry?,
    ): List<AbilityWordScanner.AbilityWordEntry> {
        if (recognizers.isEmpty()) return emptyList()
        val results = mutableListOf<AbilityWordScanner.AbilityWordEntry>()
        for (card in battlefieldCards) {
            val controller = card.controller ?: continue
            val iid = instanceIdResolver(ForgeCardId(card.id)).value
            val seatIdx = controller.game.registeredPlayers.indexOf(controller) + 1
            val registry = registryResolver(card)
            for ((word, recognizer) in recognizers) {
                if (!cardHasAbilityWordPrefix(card, word)) continue
                results.addAll(recognizer.evaluate(card, controller, iid, seatIdx, registry))
            }
        }
        return results
    }

    internal fun register(
        word: String,
        recognizer: Recognizer,
    ) {
        recognizers[word] = recognizer
    }
}
