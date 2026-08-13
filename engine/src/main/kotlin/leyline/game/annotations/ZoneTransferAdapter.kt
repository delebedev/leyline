package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.event.GameEvent
import leyline.game.event.ZoneMove
import leyline.game.mapping.StateZoneProjection
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.game.state.InstanceIdRegistry
import leyline.game.state.ProjectionAnnotationJournal
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo

/** Shell adapter for zone-transfer identity planning and journal consumption. */
object ZoneTransferAdapter {
    internal fun detectZoneTransfers(
        gameObjects: List<GameObjectInfo>,
        zones: List<ZoneInfo>,
        bridge: GameBridge,
        snapshot: GsmSnapshot,
        events: List<GameEvent>,
        annotationJournal: ProjectionAnnotationJournal.Planner,
        zoneMoves: List<ZoneMove> = emptyList(),
    ): TransferResult {
        val plannedReallocs = mutableListOf<InstanceIdRegistry.IdReallocation>()
        val cardFacts = StateZoneProjection.zoneTransferFacts(snapshot)
        val paradigmEventSources =
            events
                .mapNotNull { event ->
                    if (event is GameEvent.SpellCast) {
                        event.paradigmSourceCardId?.let { event.cardId to it }
                    } else if (event is GameEvent.SpellResolved) {
                        event.paradigmSourceCardId?.let { event.cardId to it }
                    } else {
                        null
                    }
                }.toMap()
        events.filterIsInstance<GameEvent.SpellCast>().filter { !it.isAbility && !it.isTrigger }.forEach { event ->
            annotationJournal.recordSpellCast(event, event.spellGrpId.takeIf { it != 0 } ?: cardFacts.card(event.cardId)?.grpId)
        }
        events.filterIsInstance<GameEvent.SpellResolved>().filter { !it.isAbility && !it.isTrigger }.forEach { event ->
            annotationJournal.recordSpellResolution(
                event,
                event.spellGrpId.takeIf { it != 0 } ?: cardFacts.card(event.cardId)?.grpId,
            )
        }

        val forwardOverlay = mutableMapOf<ForgeCardId, InstanceId>()
        val reverseOverlay = mutableMapOf<InstanceId, ForgeCardId>()
        val idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation = { fid ->
            val plan = bridge.ids.realloc(fid)
            forwardOverlay[fid] = plan.new
            reverseOverlay[plan.new] = fid
            plannedReallocs.add(plan)
            plan
        }
        val forgeIdLookup: (InstanceId) -> ForgeCardId? = { iid ->
            reverseOverlay[iid] ?: bridge.getForgeCardId(iid)
        }
        val idLookup: (ForgeCardId) -> InstanceId = { fid ->
            forwardOverlay[fid] ?: bridge.getOrAllocInstanceId(fid)
        }

        val result =
            ZoneTransferDetector.detectZoneTransfers(
                gameObjects = gameObjects,
                zones = zones,
                events = events,
                context =
                    ZoneTransferContext(
                        previousZones = bridge.diff.allZones(),
                        forgeIdLookup = forgeIdLookup,
                        idAllocator = idAllocator,
                        idLookup = idLookup,
                        manaAbilityGrpIdResolver = { fid -> GrpId(cardFacts.card(fid)?.basicLandManaAbilityGrpId ?: 0) },
                        grpIdResolver = { fid -> GrpId(cardFacts.card(fid)?.grpId ?: 0) },
                        isForetoldLookup = { fid -> cardFacts.card(fid)?.isForetold ?: false },
                        pendingSpellCastLookup = { fid -> annotationJournal.pendingSpellCast(fid, cardFacts.card(fid)?.grpId) },
                        pendingSpellResolutionLookup = { fid ->
                            annotationJournal.pendingSpellResolution(fid, cardFacts.card(fid)?.grpId)
                        },
                        forgeCardKnown = cardFacts::contains,
                        paradigmSourceIidLookup = { fid ->
                            StateZoneProjection.paradigmSourceStackIid(
                                cardFacts,
                                fid,
                                paradigmEventSources[fid],
                                annotationJournal::paradigmSourceStackIidFor,
                            )
                        },
                        zoneMoves = zoneMoves,
                    ),
            )
        result.transfers
            .filter { it.category == TransferCategory.CastSpell }
            .mapNotNull { it.forgeCardId }
            .forEach(annotationJournal::consumeSpellCast)
        result.transfers
            .filter { it.category == TransferCategory.Resolve || it.category == TransferCategory.Countered }
            .mapNotNull { it.forgeCardId }
            .forEach(annotationJournal::consumeSpellResolution)
        return result.copy(idReallocations = plannedReallocs.toList())
    }
}
