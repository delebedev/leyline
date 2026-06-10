package leyline.bridge.coord

import forge.ai.ComputerUtilMana
import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.cost.Cost
import forge.game.cost.CostPartMana
import forge.game.cost.CostPayLife
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.Player
import forge.game.spellability.OptionalCostValue
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.OptionalActionGate
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateRefDto
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Owns the cost-payment override surface that routes user decisions through
 * [InteractivePromptBridge] rather than Forge's desktop UI.
 *
 * The cost overrides share a common concern (the user resolving a cost) and
 * distinct wiring: stashed decisions from
 * [TargetingHandler][leyline.match.TargetingHandler], shock-land prompts via
 * [OptionalActionGate], convoke/improvise shard resolution.
 *
 * Three overrides stay on `PlayerController` because they must hand
 * `this: PlayerControllerHuman` to a collaborator:
 *
 * - `getCostDecisionMaker` — constructs `CostDecision` with the controller.
 * - `payManaCost` — hands `this` to `PlaySpellAbility.payManaCost`.
 * - `chooseCardsForCost` — trivial delegation to `TargetingCoordinator`.
 *
 * See [leyline.bridge.forge.PlayerController]'s KDoc for the coordinator pattern.
 */
class CostPaymentCoordinator(
    private val bridge: InteractivePromptBridge,
    private val player: Player,
    private val optionalActionGate: OptionalActionGate,
) {
    private val log = LoggerFactory.getLogger(CostPaymentCoordinator::class.java)

    /**
     * Convoke / improvise — prompt for a subset of untapped cards and map each
     * chosen card to a mana cost shard (colored first in WUBRG order, then
     * generic). Empty result means the player tapped no cards.
     */
    fun chooseCardsForConvokeOrImprovise(
        sa: SpellAbility,
        manaCost: ManaCost,
        untappedCards: CardCollectionView,
        artifacts: Boolean,
        creatures: Boolean,
        maxReduction: Int?,
    ): Map<Card, ManaCostShard> {
        val options = untappedCards.map { it.name }
        if (options.isEmpty()) return emptyMap()

        val isWaterbend = artifacts && creatures
        val keyword =
            when {
                isWaterbend -> "waterbend"
                artifacts -> "improvise"
                else -> "convoke"
            }
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose cards to tap for $keyword",
                options = options,
                min = 0,
                max = options.size.coerceAtMost(maxReduction ?: options.size),
                defaultIndex = 0,
                semantic = if (isWaterbend) PromptSemantic.WaterbendCost else PromptSemantic.Generic,
                candidateRefs =
                    if (isWaterbend) {
                        untappedCards.mapIndexed { index, card ->
                            PromptCandidateRefDto(index = index, kind = "card", entityId = card.id, zone = card.zone?.zoneType?.name)
                        }
                    } else {
                        emptyList()
                    },
                sourceEntityId = sa.hostCard?.id,
                sourceCardName = sa.hostCard?.name,
                waterbendManaCost = if (isWaterbend) manaCost.toColorCounts() else emptyList(),
                waterbendCostString = if (isWaterbend) manaCost.toArenaCostString() else null,
            )
        val indices = bridge.requestChoice(request)
        if (indices.isEmpty()) return emptyMap()

        // Map selected cards to mana cost shards.
        // TODO: delegate shard assignment to ComputerUtilMana when implementing full convoke support.
        // Greedy WUBRG-order assignment can be suboptimal for multi-color
        // creatures vs costs with mixed colored / generic.
        val colorShardCounts = mutableMapOf<ManaCostShard, Int>()
        for (shard in listOf(ManaCostShard.WHITE, ManaCostShard.BLUE, ManaCostShard.BLACK, ManaCostShard.RED, ManaCostShard.GREEN)) {
            val count = manaCost.getShardCount(shard)
            if (count > 0) colorShardCounts[shard] = count
        }
        var genericRemaining = manaCost.genericCost

        val cardList = untappedCards.toList()
        val result = mutableMapOf<Card, ManaCostShard>()
        for (idx in indices) {
            val card = cardList.getOrNull(idx) ?: continue
            val shard = pickShardForConvoke(card, colorShardCounts, genericRemaining, artifacts)
            if (shard != null) {
                result[card] = shard
                if (shard == ManaCostShard.GENERIC) {
                    genericRemaining--
                } else {
                    val remaining = (colorShardCounts[shard] ?: 1) - 1
                    if (remaining <= 0) colorShardCounts.remove(shard) else colorShardCounts[shard] = remaining
                }
            }
        }
        return result
    }

    /**
     * AI-driven mana payment for engine-initiated abilities. The human seat
     * still pays via [Forge's PlaySpellAbility.payManaCost][payManaCostForHuman]
     * on `PlayerController` (which must hand `this` to the helper).
     */
    fun applyManaToCost(
        toPay: ManaCostBeingPaid,
        ability: SpellAbility,
        effect: Boolean,
    ): Boolean {
        log.debug("applyManaToCost [AI]: {} for {}", toPay, ability.hostCard?.name)
        return ComputerUtilMana.payManaCost(toPay, ability, player, effect)
    }

    /**
     * Binary keyword-cost prompt (max == 1, e.g. Offspring's "pay the
     * additional cost?"). When [keywordName] is supplied and a CTO-side
     * decision is already stashed (set by `TargetingHandler.checkOptionalCosts`
     * when the player picked from the cost modal), use it — that's the path
     * that lets the client render a proper CastingTimeOptionsReq instead of a bare
     * confirm prompt. Fall back to the confirm prompt only when no CTO was
     * sent for this keyword (legacy / dev-harness paths). For max > 1 the
     * caller keeps `super.chooseNumberForKeywordCost` which routes through
     * `ClientGuiGame.getInteger`.
     */
    fun chooseKeywordCostBinary(
        prompt: String,
        keywordName: String? = null,
    ): Int {
        val stashedAnswer = resolveKeywordCostFromStash(bridge, keywordName)
        if (stashedAnswer != null) {
            log.info("chooseKeywordCostBinary: using stashed decision for keyword={} → {}", keywordName, stashedAnswer == 1)
            return stashedAnswer
        }
        val request =
            PromptRequest(
                promptType = "confirm",
                message = prompt,
                options = listOf("Yes", "No"),
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
        val indices = bridge.requestChoice(request)
        return if (indices.firstOrNull() == 0) 1 else 0
    }

    /**
     * Optional cost resolution (kicker, buyback, flashback, cycling, warp,
     * Madness alt-cost). Reads the stashed decision from [leyline.bridge.handoff.PromptJournal] (set by
     * [TargetingHandler.onCastingTimeOptionsResp][leyline.match.TargetingHandler]
     * after the client responded to `CastingTimeOptionsReq`). Falls back to
     * auto-accepting all optional costs when no stash is present (e.g. test
     * harness paths that bypass the castingTimeOptions flow).
     */
    fun chooseOptionalCosts(
        chosenSa: SpellAbility,
        optionalCosts: MutableList<OptionalCostValue>,
    ): MutableList<OptionalCostValue> {
        val stashed = consumeStashFor(bridge)
        if (stashed != null) {
            val chosen = stashed.mapNotNull { optionalCosts.getOrNull(it) }.toMutableList()
            log.info(
                "chooseOptionalCosts: using stashed decision — chose {} of {} for {}",
                chosen.size,
                optionalCosts.size,
                chosenSa.hostCard?.name,
            )
            return chosen
        }
        log.info("chooseOptionalCosts: auto-accepting {} optional costs for {}", optionalCosts.size, chosenSa.hostCard?.name)
        return optionalCosts
    }

    /**
     * Shock-land pay-life prompt: accept → [Player.payLife], decline → land
     * enters tapped. Routed through [OptionalActionGate] so the client gets an
     * `OptionalActionMessage` (GRE type 45) rather than a generic confirm.
     *
     * Returns true when the player chose to pay; the caller is expected to
     * pass `false` through to `super.payCostToPreventEffect` for non-PayLife
     * costs (echo, cumulative upkeep) — those paths are not our concern here.
     */
    fun payShockLand(
        lifePart: CostPayLife,
        sa: SpellAbility,
    ): Boolean {
        val amount = lifePart.getAbilityAmount(sa)
        val hostCard = sa.hostCard
        log.info("payCostToPreventEffect: shock land PayLife<{}> for {}", amount, hostCard?.name)
        // Decline on timeout — land enters tapped, which is the safe outcome.
        val accepted =
            optionalActionGate.await(
                hostCard = hostCard,
                defaultOnTimeout = false,
                logContext = "payCostToPreventEffect",
            )
        if (accepted) player.payLife(amount, sa, true)
        return accepted
    }

    /**
     * Ward {N} mana tax. Yes/No via [OptionalActionGate]; on accept, drain
     * mana via [ComputerUtilMana.payManaCost] (auto-tap solver). Decline on
     * timeout — the spell counters, the safe outcome for the warded
     * permanent's controller.
     *
     * Payer is `[player]` (the controller whose [PlayerController] Forge
     * dispatches `payCostToPreventEffect` on), NOT `sa.activatingPlayer` —
     * Forge sets the latter to the warded permanent's controller (the
     * trigger's "you"), which is the wrong seat for paying the tax.
     */
    fun payWardManaTax(
        cost: Cost,
        sa: SpellAbility,
    ): Boolean {
        val hostCard = sa.hostCard
        log.info(
            "payCostToPreventEffect: Ward mana tax {} for {} (payer seat={})",
            cost,
            hostCard?.name,
            player.lobbyPlayer?.name,
        )
        val accepted =
            optionalActionGate.await(
                hostCard = hostCard,
                defaultOnTimeout = false,
                logContext = "payCostToPreventEffect:ward",
            )
        if (!accepted) return false

        val manaPart = cost.costParts.firstOrNull { it is CostPartMana } as? CostPartMana
        if (manaPart == null) {
            log.warn("payWardManaTax accepted but no CostPartMana in cost {} — declining", cost)
            return false
        }
        val toPay = ManaCostBeingPaid(manaPart.mana)
        // effect=true: tax mana, not a primary spell cost.
        val paid = ComputerUtilMana.payManaCost(toPay, sa, player, true)
        if (!paid) {
            log.warn("payWardManaTax: auto-tap could not pay {} for {}", cost, hostCard?.name)
        }
        return paid
    }

    private fun pickShardForConvoke(
        card: Card,
        colorCounts: Map<ManaCostShard, Int>,
        genericRemaining: Int,
        artifacts: Boolean,
    ): ManaCostShard? {
        if (artifacts) return ManaCostShard.GENERIC.takeIf { genericRemaining > 0 }
        val colors = card.color
        if (colors.hasWhite() && (colorCounts[ManaCostShard.WHITE] ?: 0) > 0) return ManaCostShard.WHITE
        if (colors.hasBlue() && (colorCounts[ManaCostShard.BLUE] ?: 0) > 0) return ManaCostShard.BLUE
        if (colors.hasBlack() && (colorCounts[ManaCostShard.BLACK] ?: 0) > 0) return ManaCostShard.BLACK
        if (colors.hasRed() && (colorCounts[ManaCostShard.RED] ?: 0) > 0) return ManaCostShard.RED
        if (colors.hasGreen() && (colorCounts[ManaCostShard.GREEN] ?: 0) > 0) return ManaCostShard.GREEN
        if (genericRemaining > 0) return ManaCostShard.GENERIC
        return null
    }

    private fun ManaCost.toColorCounts(): List<Pair<ManaColor, Int>> =
        buildList {
            if (genericCost > 0) add(ManaColor.Generic to genericCost)
            val shards =
                listOf(
                    ManaCostShard.WHITE to ManaColor.White_afc9,
                    ManaCostShard.BLUE to ManaColor.Blue_afc9,
                    ManaCostShard.BLACK to ManaColor.Black_afc9,
                    ManaCostShard.RED to ManaColor.Red_afc9,
                    ManaCostShard.GREEN to ManaColor.Green_afc9,
                )
            for ((shard, color) in shards) {
                val count = getShardCount(shard)
                if (count > 0) add(color to count)
            }
        }

    private fun ManaCost.toArenaCostString(): String =
        toColorCounts().joinToString(separator = "") { (color, count) ->
            when (color) {
                ManaColor.Generic -> "o$count"
                ManaColor.White_afc9 -> "oW".repeat(count)
                ManaColor.Blue_afc9 -> "oU".repeat(count)
                ManaColor.Black_afc9 -> "oB".repeat(count)
                ManaColor.Red_afc9 -> "oR".repeat(count)
                ManaColor.Green_afc9 -> "oG".repeat(count)
                else -> ""
            }
        }

    companion object {
        /** Drain the optional cost stash from [bridge]'s journal, or null if none recorded. */
        fun consumeStashFor(bridge: InteractivePromptBridge): List<Int>? = bridge.journal.consumeOptionalCostStash()

        /**
         * Resolve a binary keyword-cost decision from [bridge]'s journal stash.
         * Returns 1 (pay) or 0 (decline) when a decision is stashed for
         * [keywordName]. Returns null when [keywordName] is null OR no
         * decision is stashed for it — caller should fall back to a confirm
         * prompt. Pure function, no side effects.
         */
        fun resolveKeywordCostFromStash(
            bridge: InteractivePromptBridge,
            keywordName: String?,
        ): Int? {
            if (keywordName == null) return null
            val cached = bridge.journal.peekKeywordCostDecision(keywordName) ?: return null
            return if (cached) 1 else 0
        }
    }
}
