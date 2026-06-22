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
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.interaction.ConvokeOrImproviseCostPlan
import leyline.bridge.interaction.ConvokeOrImproviseCostPlanner
import leyline.bridge.interaction.candidateRefs
import leyline.bridge.interaction.shouldInclude
import leyline.bridge.interaction.shouldRecord
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ManaColorMapping
import leyline.bridge.types.ManaCostText
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

        val plan =
            ConvokeOrImproviseCostPlanner.plan(
                optionCount = options.size,
                maxReduction = maxReduction,
                artifacts = artifacts,
                creatures = creatures,
            )
        val request = buildConvokeOrImproviseRequest(sa, manaCost, untappedCards, options, plan)
        val indices = bridge.requestChoice(request)
        if (indices.isEmpty()) return emptyMap()

        val cardList = untappedCards.toList()
        val selectedCards = indices.mapNotNull { cardList.getOrNull(it) }
        val result =
            if (artifacts) {
                selectedCards
                    .take(manaCost.genericCost)
                    .associateWith { ManaCostShard.GENERIC }
            } else {
                ConvokeShardAssigner
                    .assign(selectedCards, ConvokeShardAssigner.costCounts(manaCost)) { it.color }
                    .toMap()
            }
        if (plan.convokePaymentRecordPolicy.shouldRecord) recordConvokePayments(sa, result)
        return result
    }

    private fun buildConvokeOrImproviseRequest(
        sa: SpellAbility,
        manaCost: ManaCost,
        untappedCards: CardCollectionView,
        options: List<String>,
        plan: ConvokeOrImproviseCostPlan,
    ): PromptRequest {
        val includeManaFields = plan.manaFieldsPolicy.shouldInclude
        val displayedCost = if (includeManaFields) manaCost.toColorCounts() else emptyList()
        return PromptRequest(
            promptType = "choose_cards",
            message = "Choose cards to tap for ${plan.keyword}",
            options = options,
            min = 0,
            max = plan.maxSelection,
            defaultIndex = 0,
            semantic = plan.semantic,
            candidateRefs = plan.candidateRefsPolicy.candidateRefs(buildCandidateRefs(untappedCards)),
            sourceEntityId = sa.hostCard?.id,
            sourceCardName = sa.hostCard?.name,
            waterbendManaCost = displayedCost,
            waterbendCostString = if (includeManaFields) ManaCostText.clientText(displayedCost) else null,
        )
    }

    private fun buildCandidateRefs(cards: CardCollectionView): List<PromptCandidateRefDto> =
        cards.mapIndexed { index, card ->
            PromptCandidateRefDto(index = index, kind = "card", entityId = card.id, zone = card.zone?.zoneType?.name)
        }

    private fun recordConvokePayments(
        sa: SpellAbility,
        payments: Map<Card, ManaCostShard>,
    ) {
        val source = sa.hostCard ?: return
        if (payments.isEmpty()) return
        bridge.journal.record(
            PromptSideEffect.ConvokePayments(
                sourceForgeCardId = ForgeCardId(source.id),
                payments =
                    payments.map { (card, shard) ->
                        PromptSideEffect.ConvokePayment(
                            paymentForgeCardId = ForgeCardId(card.id),
                            color = ManaColorMapping.paymentWireColor(shard).number,
                        )
                    },
            ),
        )
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
        applyHybridManaChoices(toPay, ability)
        return ComputerUtilMana.payManaCost(toPay, ability, player, effect)
    }

    private fun applyHybridManaChoices(
        toPay: ManaCostBeingPaid,
        ability: SpellAbility,
    ) {
        val choices = bridge.journal.consumeHybridManaStash() ?: return
        val hybridShards = toPay.getUnpaidShards().filter { it.isOr2Generic }
        if (hybridShards.isEmpty()) return

        for ((index, shard) in hybridShards.withIndex()) {
            val coloredChoice = colorForTwoGenericShard(shard) ?: continue
            val choice = choices.getOrNull(index) ?: coloredChoice
            toPay.decreaseShard(shard, 1)
            if (choice == ManaColor.TwoGeneric) {
                toPay.increaseGenericMana(2)
                continue
            }
            val replacement = monoColorShard(choice.takeIf { it == coloredChoice } ?: coloredChoice)
            if (replacement != null) {
                toPay.increaseShard(replacement, 1)
            }
        }
        log.info("applyManaToCost: applied hybrid mana choices {} for {}", choices, ability.hostCard?.name)
    }

    private fun colorForTwoGenericShard(shard: ManaCostShard): ManaColor? {
        if (!shard.isOr2Generic || !shard.isMonoColor) return null
        return when {
            shard.isWhite -> ManaColor.White_afc9
            shard.isBlue -> ManaColor.Blue_afc9
            shard.isBlack -> ManaColor.Black_afc9
            shard.isRed -> ManaColor.Red_afc9
            shard.isGreen -> ManaColor.Green_afc9
            else -> null
        }
    }

    private fun monoColorShard(color: ManaColor): ManaCostShard? =
        when {
            color == ManaColor.White_afc9 -> ManaCostShard.WHITE
            color == ManaColor.Blue_afc9 -> ManaCostShard.BLUE
            color == ManaColor.Black_afc9 -> ManaCostShard.BLACK
            color == ManaColor.Red_afc9 -> ManaCostShard.RED
            color == ManaColor.Green_afc9 -> ManaCostShard.GREEN
            else -> null
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

    private fun ManaCost.toColorCounts(): List<Pair<ManaColor, Int>> = ManaColorMapping.deriveWubrgCostWithGenericFirst(this)

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
