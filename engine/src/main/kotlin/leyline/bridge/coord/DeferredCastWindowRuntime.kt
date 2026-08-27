package leyline.bridge.coord

import leyline.bridge.handoff.DeferredCastCostPlan
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.SeatId
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

internal data class DeferredCastOptionResponse(
    val ctoId: Int,
    val manaColor: ManaColor?,
)

internal data class DeferredCastResponse(
    val gameStateId: Int,
    val ctoId: Int,
    val selectedCtoId: Int?,
    val options: List<DeferredCastOptionResponse>,
)

internal class DeferredCastReceipt internal constructor(
    internal val actionId: String,
    internal val token: Long,
)

internal enum class DeferredCastRejection {
    Stale,
    Duplicate,
    WrongOption,
}

internal sealed interface DeferredCastAdmission {
    data class Rejected(
        val reason: DeferredCastRejection,
    ) : DeferredCastAdmission

    data object Optional : DeferredCastAdmission

    data class Hybrid(
        val receipt: DeferredCastReceipt,
    ) : DeferredCastAdmission

    data object Alternate : DeferredCastAdmission
}

internal interface DeferredCastActionOwner {
    fun completeDeferredClaim(
        claim: MatchActionWindowRuntime.ActionClaim,
        childToken: Long? = null,
    ): Boolean

    fun isDeferredClaim(claim: MatchActionWindowRuntime.ActionClaim): Boolean

    fun reopenDeferredClaim(claim: MatchActionWindowRuntime.ActionClaim): Boolean

    fun seatFor(actionId: String): SeatId
}

/** Owns exact deferred cast prompt correlation and retained cost choices. */
internal class DeferredCastWindowRuntime(
    private val owner: MatchCutCoordinator,
    private val actions: DeferredCastActionOwner,
) {
    private sealed interface Prompt {
        val actionClaim: MatchActionWindowRuntime.ActionClaim
        val promptGameStateId: Int
        var adopted: Boolean

        data class Optional(
            override val actionClaim: MatchActionWindowRuntime.ActionClaim,
            override val promptGameStateId: Int,
            val costCtoIds: List<Int>,
            val additionalCostGrpIdsByCtoId: Map<Int, Int>,
            val keywordCostsByCtoId: Map<Int, String>,
            override var adopted: Boolean = false,
        ) : Prompt

        data class HybridManaType(
            override val actionClaim: MatchActionWindowRuntime.ActionClaim,
            override val promptGameStateId: Int,
            val ctoIds: List<Int>,
            val promptColors: List<ManaColor>,
            val paymentColors: List<ManaColor>,
            override var adopted: Boolean = false,
        ) : Prompt

        data class AlternateCostChoice(
            override val actionClaim: MatchActionWindowRuntime.ActionClaim,
            override val promptGameStateId: Int,
            val runtimeTokensByCtoId: Map<Int, Long>,
            override var adopted: Boolean = false,
        ) : Prompt
    }

    private var prompt: Prompt? = null

    fun publishHybrid(
        claim: MatchActionWindowRuntime.ActionClaim,
        promptGameStateId: Int,
        ctoIds: List<Int>,
        promptColors: List<ManaColor>,
        paymentColors: List<ManaColor>,
    ) = synchronized(owner.feedLock) {
        owner.ensureOpen()
        checkClaim(claim)
        prompt =
            Prompt.HybridManaType(claim, promptGameStateId, ctoIds.toList(), promptColors.toList(), paymentColors.toList())
    }

    fun publishOptional(
        claim: MatchActionWindowRuntime.ActionClaim,
        promptGameStateId: Int,
        ctoIds: List<Int>,
    ) = synchronized(owner.feedLock) { installOptional(claim, promptGameStateId, ctoIds) }

    fun publishOptional(
        receipt: DeferredCastReceipt,
        promptGameStateId: Int,
        ctoIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            val pending = prompt as? Prompt.HybridManaType ?: return@synchronized false
            if (!pending.adopted ||
                pending.actionClaim.actionId != receipt.actionId ||
                pending.actionClaim.token != receipt.token
            ) {
                return@synchronized false
            }
            installOptional(pending.actionClaim, promptGameStateId, ctoIds)
            true
        }

    fun publishAlternate(
        claim: MatchActionWindowRuntime.ActionClaim,
        promptGameStateId: Int,
        ctoIds: List<Int>,
    ) = synchronized(owner.feedLock) {
        owner.ensureOpen()
        checkClaim(claim)
        val tokens =
            claim.deferredCostPlan
                ?.alternate
                ?.choices
                ?.map { it.runtimeToken }
                .orEmpty()
        check(tokens.size == ctoIds.size) { "Deferred alternate-cost catalog no longer matches the action plan" }
        prompt = Prompt.AlternateCostChoice(claim, promptGameStateId, ctoIds.zip(tokens).toMap())
    }

    fun deferredCostPlan(receipt: DeferredCastReceipt): DeferredCastCostPlan? =
        synchronized(owner.feedLock) {
            val pending = prompt ?: return@synchronized null
            if (pending.actionClaim.actionId != receipt.actionId ||
                pending.actionClaim.token != receipt.token ||
                !pending.adopted
            ) {
                return@synchronized null
            }
            pending.actionClaim.deferredCostPlan
        }

    fun hasPrompt(): Boolean = synchronized(owner.feedLock) { prompt != null }

    fun discard() = synchronized(owner.feedLock) { prompt = null }

    fun close(actionId: String) {
        synchronized(owner.feedLock) {
            if (prompt?.actionClaim?.actionId == actionId) prompt = null
        }
    }

    fun admit(response: DeferredCastResponse): DeferredCastAdmission =
        synchronized(owner.feedLock) {
            val pending =
                prompt ?: return@synchronized DeferredCastAdmission.Rejected(
                    DeferredCastRejection.Stale,
                )
            if (response.gameStateId != pending.promptGameStateId) {
                return@synchronized DeferredCastAdmission.Rejected(
                    DeferredCastRejection.Stale,
                )
            }
            if (pending.adopted) {
                return@synchronized DeferredCastAdmission.Rejected(
                    DeferredCastRejection.Duplicate,
                )
            }
            val receipt = DeferredCastReceipt(pending.actionClaim.actionId, pending.actionClaim.token)
            when (pending) {
                is Prompt.Optional -> admitOptional(pending, response)
                is Prompt.HybridManaType -> admitHybrid(pending, response, receipt)
                is Prompt.AlternateCostChoice -> admitAlternate(pending, response)
            }
        }

    fun complete(receipt: DeferredCastReceipt): Boolean =
        synchronized(owner.feedLock) {
            val pending = prompt ?: return@synchronized false
            if (!pending.adopted ||
                pending.actionClaim.actionId != receipt.actionId ||
                pending.actionClaim.token != receipt.token
            ) {
                return@synchronized false
            }
            actions.completeDeferredClaim(pending.actionClaim).also { if (it) prompt = null }
        }

    fun cancel(promptGameStateId: Int): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    val pending = prompt ?: return@synchronized false
                    if (pending.promptGameStateId != promptGameStateId || !actions.isDeferredClaim(pending.actionClaim)) {
                        return@synchronized false
                    }
                    val claim = pending.actionClaim
                    prompt = null
                    claim.deferredCostPlan?.sourceCardId?.let { owner.bridge.setSelectedSpellGrpId(it, null) }
                    owner.bridge
                        .seat(actions.seatFor(claim.actionId))
                        .prompt.journal
                        .clearHybridManaStash()
                    actions.reopenDeferredClaim(claim)
                }
            }
        }

    private fun admitOptional(
        pending: Prompt.Optional,
        response: DeferredCastResponse,
    ): DeferredCastAdmission {
        val chosen = response.ctoId
        val accepted = chosen != 0 && chosen in pending.costCtoIds
        val valid = chosen == 0 || accepted || chosen in pending.keywordCostsByCtoId
        if (!valid) {
            return DeferredCastAdmission.Rejected(
                DeferredCastRejection.WrongOption,
            )
        }
        pending.adopted = true
        val acceptedIndices = if (accepted && chosen !in pending.keywordCostsByCtoId) listOf(chosen - 1) else emptyList()
        val decisions = pending.keywordCostsByCtoId.values.associateWith { it == pending.keywordCostsByCtoId[chosen] }
        val seatBridge = owner.bridge.seat(actions.seatFor(pending.actionClaim.actionId))
        seatBridge.prompt.journal.record(PromptSideEffect.OptionalCostStash(acceptedIndices))
        pending.additionalCostGrpIdsByCtoId[chosen]?.let { grpId ->
            pending.actionClaim.deferredCostPlan
                ?.sourceCardId
                ?.let { cardId -> owner.bridge.setSelectedAdditionalCostGrpId(cardId, grpId) }
        }
        if (decisions.isNotEmpty()) seatBridge.prompt.journal.record(PromptSideEffect.KeywordCostStash(decisions))
        check(actions.completeDeferredClaim(pending.actionClaim)) { "Deferred optional action claim did not complete" }
        prompt = null
        return DeferredCastAdmission.Optional
    }

    private fun admitHybrid(
        pending: Prompt.HybridManaType,
        response: DeferredCastResponse,
        receipt: DeferredCastReceipt,
    ): DeferredCastAdmission {
        val seen = response.options.map { it.ctoId }
        if (seen.any { it != 0 && it !in pending.ctoIds } || seen.filter { it != 0 }.distinct().size != seen.count { it != 0 }) {
            return DeferredCastAdmission.Rejected(DeferredCastRejection.WrongOption)
        }
        val byCtoId = response.options.associateBy { it.ctoId }
        val promptChoices =
            pending.ctoIds.mapIndexed { index, ctoId ->
                byCtoId[ctoId]?.manaColor ?: response.options.getOrNull(index)?.manaColor
                    ?: pending.promptColors.getOrNull(index) ?: ManaColor.TwoGeneric
            }
        val choices = reorderHybridChoices(promptChoices, pending.promptColors, pending.paymentColors)
        pending.adopted = true
        owner.bridge
            .seat(actions.seatFor(pending.actionClaim.actionId))
            .prompt.journal
            .record(PromptSideEffect.HybridManaStash(choices))
        return DeferredCastAdmission.Hybrid(receipt)
    }

    private fun admitAlternate(
        pending: Prompt.AlternateCostChoice,
        response: DeferredCastResponse,
    ): DeferredCastAdmission {
        val selected = response.selectedCtoId ?: response.ctoId
        val runtimeToken =
            pending.runtimeTokensByCtoId[selected]
                ?: return DeferredCastAdmission.Rejected(
                    DeferredCastRejection.WrongOption,
                )
        pending.adopted = true
        check(actions.completeDeferredClaim(pending.actionClaim, runtimeToken)) { "Deferred alternate action claim did not complete" }
        prompt = null
        return DeferredCastAdmission.Alternate
    }

    private fun installOptional(
        claim: MatchActionWindowRuntime.ActionClaim,
        promptGameStateId: Int,
        ctoIds: List<Int>,
    ) {
        owner.ensureOpen()
        checkClaim(claim)
        val entries =
            claim.deferredCostPlan
                ?.optional
                ?.entries
                .orEmpty()
        check(entries.size == ctoIds.size) { "Deferred optional-cost catalog no longer matches the action plan" }
        prompt =
            Prompt.Optional(
                claim,
                promptGameStateId,
                ctoIds.toList(),
                entries
                    .mapIndexedNotNull { index, entry ->
                        entry.abilityGrpId.takeIf { entry.type == CastingTimeOptionType.AdditionalCost }?.let { ctoIds[index] to it }
                    }.toMap(),
                entries.mapIndexedNotNull { index, entry -> entry.keywordName?.let { ctoIds[index] to it } }.toMap(),
            )
    }

    private fun checkClaim(claim: MatchActionWindowRuntime.ActionClaim) {
        check(actions.isDeferredClaim(claim)) { "Deferred cast prompt is not owned by its action claim" }
    }

    private fun reorderHybridChoices(
        promptChoices: List<ManaColor>,
        promptColors: List<ManaColor>,
        paymentColors: List<ManaColor>,
    ): List<ManaColor> {
        val used = BooleanArray(promptChoices.size)
        return paymentColors.map { paymentColor ->
            val promptIndex = promptColors.indices.firstOrNull { index -> !used[index] && promptColors[index] == paymentColor }
            if (promptIndex == null) {
                paymentColor
            } else {
                used[promptIndex] = true
                promptChoices.getOrNull(promptIndex) ?: paymentColor
            }
        }
    }
}
