package leyline.game.annotations

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.codes.CounterTypes
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Bundle of the shared annotation-time resolvers used across the
 * [AnnotationPipeline] spine and (after the port slice) the contributors.
 *
 * Holds the four pieces of state every resolver needs — the live [bridge], the
 * frame [snap], the frame-scoped [frameIds], and this frame's [events] — so
 * call sites read `ctx.counterAffectorFor(...)` instead of threading six args.
 * [transferResult] is optional frame context, supplied only by transfer-stage
 * contributors (Convoke) that diff this frame's zone transfers.
 *
 * The frame-pure helpers ([stackAbilityIid], [keywordCounterResolutionForEvent])
 * are also exposed on the companion for callers that hold only a
 * [FrameIdResolver] / event list (e.g. transfer-model patchers in StateMapper
 * and the test harness).
 */
class AnnotationContext(
    val bridge: GameBridge,
    val snap: GsmSnapshot,
    val frameIds: FrameIdResolver,
    val events: List<GameEvent>,
    val transferResult: TransferResult? = null,
) {
    /**
     * Convoke payments still pending per cast spell this frame, keyed by source
     * card. Feeds mechanic-annotation resolution; the Convoke resolve emission
     * consumes the journal entries separately.
     */
    fun activeConvokePaymentsBySource(): Map<ForgeCardId, List<TransferAnnotations.ConvokePaymentRecord>> {
        val promptSeatIds = bridge.allSeatIds()
        return events
            .filterIsInstance<GameEvent.SpellCast>()
            .filterNot { it.isAbility }
            .mapNotNull { ev ->
                if (ev.seatId.value !in promptSeatIds) return@mapNotNull null
                val payments = bridge.promptBridge(ev.seatId).journal.activeConvokePayments(ev.cardId)
                if (payments.isEmpty()) {
                    null
                } else {
                    ev.cardId to payments.map { it.toConvokePaymentRecord() }
                }
            }.toMap()
    }

    private fun PromptSideEffect.ConvokePayment.toConvokePaymentRecord(): TransferAnnotations.ConvokePaymentRecord =
        TransferAnnotations.ConvokePaymentRecord(
            paymentForgeCardId = paymentForgeCardId,
            color = color,
        )

    /** Affector iid for a `CountersChanged` event, or null when none resolves. */
    fun counterAffectorFor(
        eventIndex: Int,
        ev: GameEvent.CountersChanged,
    ): InstanceId? {
        if (ev.affectorAbilityForgeId != 0 && ev.affectorCardId != null) {
            return InstanceId(stackAbilityIid(ev.affectorAbilityForgeId, ev.affectorCardId))
        }
        val resolved =
            keywordCounterResolutionForEvent(eventIndex, ev, events) { resolved ->
                isCounterAffectingKeywordResolution(resolved)
            } ?: return null
        return InstanceId(stackAbilityIid(resolved.abilityForgeId, resolved.cardId))
    }

    private fun isCounterAffectingKeywordResolution(resolved: GameEvent.SpellResolved): Boolean {
        if (resolved.abilityGrpId in counterAffectingKeywordTriggerIds) return true
        val sourceGrpId = snap.boundCards[resolved.cardId]?.snapshot?.grpId ?: return false
        return bridge.cardRepository.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.BACKUP) == resolved.abilityGrpId
    }

    /** Affector iid for a `PlayerCountersChanged` event, or null when none resolves. */
    fun playerCounterAffectorFor(
        eventIndex: Int,
        ev: GameEvent.PlayerCountersChanged,
    ): InstanceId? {
        if (CounterTypes.counterTypeId(ev.counterType) == 0) return null
        for (next in events.asSequence().drop(eventIndex + 1)) {
            when (next) {
                is GameEvent.SpellResolved -> return InstanceId(stackAbilityIid(next.abilityForgeId, next.cardId))
                else -> Unit
            }
        }
        return null
    }

    /** Best-effort owner seat lookup for an event-derived source card. */
    fun ownerSeatOf(card: forge.game.card.Card): Int {
        val owner = card.owner ?: return 1
        return bridge.seatOf(owner)?.value ?: 1
    }

    /** Instance-scoped surrogate iid for a stack-resident trigger / activated ability. */
    fun stackAbilityIid(
        forgeAbilityId: Int,
        sourceForgeId: ForgeCardId,
    ): Int = stackAbilityIid(forgeAbilityId, sourceForgeId, frameIds)

    /** Best-effort source-zone lookup for an event-derived trigger. Falls back
     *  to Battlefield (28) — the dominant case for combat / state-change triggers.
     *  ZoneType has many rarely-used values (Sideboard, Ante, Subgame…) that
     *  don't host triggering objects we'd surface to the wire; mapping each
     *  is noise. The else-branch keeps the fallback explicit. */
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    fun currentSourceZoneId(cardId: ForgeCardId): Int {
        val card = bridge.findCard(cardId) ?: return ZoneIds.BATTLEFIELD
        val ownerSeat = ownerSeatOf(card)
        return when (card.zone?.zoneType) {
            ForgeZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ForgeZoneType.Stack -> ZoneIds.STACK
            ForgeZoneType.Graveyard -> ZoneIds.graveyardOf(ownerSeat)
            ForgeZoneType.Exile -> ZoneIds.EXILE
            ForgeZoneType.Hand -> ZoneIds.handOf(ownerSeat)
            ForgeZoneType.Library -> ZoneIds.libraryOf(ownerSeat)
            ForgeZoneType.Command -> ZoneIds.COMMAND
            else -> ZoneIds.BATTLEFIELD
        }
    }

    /** Look up the outbound ability grpId for a triggered source. For known
     *  keyword triggers (Mobilize, …) this resolves to the per-card keyword
     *  ability grpId — e.g. 188698 for a Mobilize 1 source — so
     *  `ResolutionStart`/`Complete` carry the keyword row id rather than the
     *  source card's grpId. Falls back to the source card's grpId for triggers
     *  whose keyword isn't in [leyline.game.data.KeywordAbilityIds] yet. */
    fun abilityGrpIdForSource(
        cardId: ForgeCardId,
        abilityForgeId: Int,
    ): Int {
        val bound = snap.boundCards[cardId] ?: return 0
        if (abilityForgeId != 0) {
            val card = bridge.findCard(cardId)
            val registry = if (card != null) bridge.abilityRegistryFor(card, bound.data) else null
            registry?.forSpellAbility(abilityForgeId)?.takeIf { it != 0 }?.let { return it }
            registry?.forTrigger(abilityForgeId)?.takeIf { it != 0 }?.let { return it }
        }
        for (keywordId in keywordTriggerIds) {
            bridge.cardRepository.findKeywordAbilityGrpId(bound.snapshot.grpId, keywordId)?.let { return it }
            bound.altCost(keywordId)?.abilityGrpId?.let { return it }
            bridge.cardRepository.findKeywordAbilityGrpId(bound.snapshot.grpId, keywordId)?.let { return it }
        }
        return bound.snapshot.grpId
    }

    fun targetSpecAbilityGrpId(spec: InteractivePromptBridge.PendingTarget): Int {
        spec.abilityGrpId?.let { return it }
        if (spec.forgeAbilityId != 0) {
            val resolved = abilityGrpIdForSource(ForgeCardId(spec.spellForgeCardId), spec.forgeAbilityId)
            if (resolved != 0) return resolved
        }
        return bridge.cardRepository.findGrpIdByName(spec.spellName) ?: 0
    }

    companion object {
        /** Keywords whose triggers we want to surface on the wire as
         *  `ResolutionStart`/`Complete grpid = <keyword ability id>`. Extend as new
         *  combat/ETB/state-trigger keywords ship and need precise grpId fidelity. */
        private val keywordTriggerIds =
            listOf(
                KeywordAbilityIds.BACKUP,
                KeywordAbilityIds.MENTOR,
                KeywordAbilityIds.MOBILIZE,
                KeywordAbilityIds.DECAYED,
                KeywordAbilityIds.ENLIST,
            )

        private val counterAffectingKeywordTriggerIds =
            setOf(KeywordAbilityIds.BACKUP, KeywordAbilityIds.MENTOR, KeywordAbilityIds.TRAINING)

        /**
         * SA-id-keyed surrogate iid for a stack-resident trigger or activated
         * ability, with source-card fallback when the collector didn't surface
         * the SA id (defensive 0). Both lifecycle paths share this minter so a
         * single AB iid threads through Created → ZoneTransfer affector → Deleted.
         */
        fun stackAbilityIid(
            forgeAbilityId: Int,
            sourceForgeId: ForgeCardId,
            frameIds: FrameIdResolver,
        ): Int =
            if (forgeAbilityId != 0) {
                frameIds.triggerStackAbilityIid(forgeAbilityId).value
            } else {
                frameIds.stackAbilityIid(sourceForgeId).value
            }

        fun keywordCounterResolutionForEvent(
            eventIndex: Int,
            ev: GameEvent.CountersChanged,
            events: List<GameEvent>,
            isCounterAffectingResolution: (GameEvent.SpellResolved) -> Boolean = { resolved ->
                resolved.abilityGrpId in counterAffectingKeywordTriggerIds
            },
        ): GameEvent.SpellResolved? {
            if (ev.counterType != "P1P1" && ev.counterType != "+1/+1") return null
            for (next in events.asSequence().drop(eventIndex + 1)) {
                when {
                    next is GameEvent.CountersChanged -> return null
                    next is GameEvent.SpellResolved -> {
                        if (next.isTrigger && isCounterAffectingResolution(next)) {
                            return next
                        }
                        return null
                    }
                }
            }
            return null
        }
    }
}

internal fun GameEvent.SpellCast.isParadigmDelayedTrigger(): Boolean =
    isTrigger && abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER

internal fun GameEvent.SpellResolved.isParadigmDelayedTrigger(): Boolean =
    isTrigger && abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER
