package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.game.event.GameEvent

/** Wire identity retained from ability creation until its matching lifecycle step. */
data class AbilityWireIdentity(
    val abilityIid: Int,
    val sourceIidAtCreate: Int,
    val sourceZoneAtCreate: Int,
    val abilityGrpId: Int,
)

/**
 * Cross-frame annotation correlation owned by projection commits.
 *
 * The planner is private to one frame compile. Readers outside compilation use
 * the committed value; only an accepted [Transition] installs a replacement.
 * Prompt journals and live Forge reads remain outside this boundary.
 */
data class ProjectionAnnotationJournal(
    val abilityLineage: AbilityLineageRegistry = AbilityLineageRegistry(),
    val pendingSpellCasts: PendingSpellEventRegistry<GameEvent.SpellCast> = PendingSpellEventRegistry(),
    val pendingSpellResolutions: PendingSpellEventRegistry<GameEvent.SpellResolved> = PendingSpellEventRegistry(),
    val paradigmSourceStackIids: Map<ForgeCardId, Int> = emptyMap(),
    val decayedCleanupSources: Set<ForgeCardId> = emptySet(),
    val activeStealForgeCardIds: Set<ForgeCardId> = emptySet(),
) {
    data class Transition(
        val expected: ProjectionAnnotationJournal,
        val next: ProjectionAnnotationJournal,
    )

    class Planner(
        initial: ProjectionAnnotationJournal,
    ) {
        private val expected = initial
        private var abilityLineage = initial.abilityLineage
        private var pendingSpellCasts = initial.pendingSpellCasts
        private var pendingSpellResolutions = initial.pendingSpellResolutions
        private val paradigmSourceStackIids = initial.paradigmSourceStackIids.toMutableMap()
        private val decayedCleanupSources = initial.decayedCleanupSources.toMutableSet()
        private var activeStealForgeCardIds = initial.activeStealForgeCardIds

        fun recordAbility(identity: AbilityWireIdentity) {
            abilityLineage = abilityLineage.record(identity)
        }

        fun consumeAbility(abilityIid: Int): AbilityWireIdentity? {
            val (identity, next) = abilityLineage.consume(abilityIid)
            abilityLineage = next
            return identity
        }

        fun ability(abilityIid: Int): AbilityWireIdentity? = abilityLineage.find(abilityIid)

        fun recordSpellCast(
            event: GameEvent.SpellCast,
            grpId: Int?,
        ) {
            pendingSpellCasts = pendingSpellCasts.record(event.cardId, grpId, event)
        }

        fun recordSpellResolution(
            event: GameEvent.SpellResolved,
            grpId: Int?,
        ) {
            pendingSpellResolutions = pendingSpellResolutions.record(event.cardId, grpId, event)
        }

        fun pendingSpellCast(
            cardId: ForgeCardId,
            grpId: Int?,
        ): GameEvent.SpellCast? = pendingSpellCasts.find(cardId, grpId)

        fun pendingSpellResolution(
            cardId: ForgeCardId,
            grpId: Int?,
        ): GameEvent.SpellResolved? = pendingSpellResolutions.find(cardId, grpId)

        fun consumeSpellCast(cardId: ForgeCardId) {
            pendingSpellCasts = pendingSpellCasts.consume(cardId)
        }

        fun consumeSpellResolution(cardId: ForgeCardId) {
            pendingSpellResolutions = pendingSpellResolutions.consume(cardId)
        }

        fun recordParadigmSourceStackIid(
            cardId: ForgeCardId,
            stackIid: Int,
        ) {
            paradigmSourceStackIids[cardId] = stackIid
        }

        fun recordParadigmSourceStackIidIfAbsent(
            cardId: ForgeCardId,
            stackIid: Int,
        ) {
            paradigmSourceStackIids.putIfAbsent(cardId, stackIid)
        }

        fun paradigmSourceStackIidFor(cardId: ForgeCardId): Int? = paradigmSourceStackIids[cardId]

        fun activeDecayedCleanupSources(): Set<ForgeCardId> = decayedCleanupSources.toSet()

        fun recordDecayedCleanupSource(cardId: ForgeCardId) {
            decayedCleanupSources.add(cardId)
        }

        fun clearDecayedCleanupSource(cardId: ForgeCardId) {
            decayedCleanupSources.remove(cardId)
        }

        fun activeStealForgeCardIds(): Set<ForgeCardId> = activeStealForgeCardIds

        fun replaceActiveSteals(next: Set<ForgeCardId>) {
            activeStealForgeCardIds = next.toSet()
        }

        fun transition(): Transition =
            Transition(
                expected = expected,
                next =
                    ProjectionAnnotationJournal(
                        abilityLineage = abilityLineage,
                        pendingSpellCasts = pendingSpellCasts,
                        pendingSpellResolutions = pendingSpellResolutions,
                        paradigmSourceStackIids = paradigmSourceStackIids.toMap(),
                        decayedCleanupSources = decayedCleanupSources.toSet(),
                        activeStealForgeCardIds = activeStealForgeCardIds,
                    ),
            )
    }
}

/** Immutable pending spell correlation. Only the projection journal planner advances it. */
data class PendingSpellEventRegistry<T>(
    private val byCardId: Map<ForgeCardId, T> = emptyMap(),
    private val byGrpId: Map<Int, T> = emptyMap(),
    private val grpIdByCardId: Map<ForgeCardId, Int> = emptyMap(),
) {
    fun record(
        cardId: ForgeCardId,
        grpId: Int?,
        event: T,
    ): PendingSpellEventRegistry<T> {
        val nextByCard = byCardId + (cardId to event)
        val previousEvent = byCardId[cardId]
        val priorGrpId = grpIdByCardId[cardId]
        val nextGrpByCard = if (grpId == null) grpIdByCardId - cardId else grpIdByCardId + (cardId to grpId)
        var nextByGrp = byGrpId
        if (priorGrpId != null && priorGrpId != grpId && byGrpId[priorGrpId] == previousEvent) nextByGrp -= priorGrpId
        if (grpId != null) nextByGrp += grpId to event
        return PendingSpellEventRegistry(nextByCard, nextByGrp, nextGrpByCard)
    }

    fun find(
        cardId: ForgeCardId,
        grpId: Int?,
    ): T? = byCardId[cardId] ?: grpId?.let(byGrpId::get)

    fun consume(cardId: ForgeCardId): PendingSpellEventRegistry<T> {
        val event = byCardId[cardId] ?: return this
        val grpId = grpIdByCardId[cardId]
        return PendingSpellEventRegistry(
            byCardId = byCardId - cardId,
            byGrpId = if (grpId != null && byGrpId[grpId] == event) byGrpId - grpId else byGrpId,
            grpIdByCardId = grpIdByCardId - cardId,
        )
    }
}

/** Immutable ability lineage. Projection creates and consumes identities through its journal planner. */
data class AbilityLineageRegistry(
    private val byAbilityIid: Map<Int, AbilityWireIdentity> = emptyMap(),
) {
    fun find(abilityIid: Int): AbilityWireIdentity? = byAbilityIid[abilityIid]

    fun record(identity: AbilityWireIdentity): AbilityLineageRegistry =
        copy(byAbilityIid = byAbilityIid + (identity.abilityIid to identity))

    fun consume(abilityIid: Int): Pair<AbilityWireIdentity?, AbilityLineageRegistry> =
        byAbilityIid[abilityIid] to copy(byAbilityIid = byAbilityIid - abilityIid)
}
