package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptResponseMapper
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.mapping.SearchShape
import leyline.game.mapping.ZoneIds
import org.slf4j.LoggerFactory

/** Owns the library-search request/response lifecycle. */
internal class SearchPromptInteractionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val ctx: SessionContext,
    private val getPendingInteraction: () -> PendingClientInteraction?,
    private val setPendingInteraction: (PendingClientInteraction?) -> Unit,
) {
    private val log = LoggerFactory.getLogger(SearchPromptInteractionHandler::class.java)

    fun onSearchResp(
        itemsFound: List<Int>,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val pending =
            getPendingInteraction() as? PendingClientInteraction.Search ?: run {
                log.warn("SearchResp received but no search pending (likely timeout race)")
                DevCheck.failOnAutoPass { "SearchResp but no search pending" }
                return
            }
        setPendingInteraction(null)

        val seatBridge = bridge.seat(counters.seatId)
        val prompt = seatBridge.prompt.getPendingPrompt()
        if (prompt != null && prompt.promptId == pending.promptId) {
            val responseIndices = responseIndices(itemsFound, prompt)
            seatBridge.prompt.submitResponse(pending.promptId, responseIndices)
            bridge.awaitPriority()
            drainPendingPlayback()
        }
        bundles.bundleBuilder.cursor.invalidate()
        sink.sendRealGameState(bridge)
        autoPass()
    }

    fun sendSearchReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        // Reveal library contents so the client can populate the search picker.
        sink.sendRealGameState(bridge, revealForSeat = counters.seatId.value)

        val req = pendingPrompt.request
        val player = bridge.getPlayer(counters.seatId)
        val library = player?.getZone(forge.game.zone.ZoneType.Library)
        val libZoneId = ZoneIds.libraryOf(counters.seatId)
        val allLibIds =
            library?.cards?.map {
                bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value
            } ?: emptyList()
        val validIds =
            req.candidateRefs.map { ref ->
                bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            }

        val stackTop = ctx.game.stack.firstOrNull()
        val sa = stackTop?.spellAbility
        val saId = sa?.id
        val isAbilityOnStack = stackTop?.isAbility == true
        val hostCardForgeId = sa?.hostCard?.id ?: req.sourceEntityId
        val hostCardIid = hostCardForgeId?.let { bridge.getOrAllocInstanceId(ForgeCardId(it)).value } ?: 0
        val sourceId =
            when {
                isAbilityOnStack && saId != null -> {
                    val abForgeId = FrameIdResolver.triggerStackAbilityForgeId(saId)
                    bridge.getOrAllocInstanceId(abForgeId).value
                }
                hostCardIid != 0 -> hostCardIid
                stackTop != null -> bridge.getOrAllocInstanceId(ForgeCardId(stackTop.id)).value
                else -> 0
            }
        val promptId =
            if (isAbilityOnStack && SearchShape.isTypeCycling(sa)) {
                PromptIds.SEARCH_TYPECYCLING
            } else {
                PromptIds.SEARCH
            }

        val msg =
            bundles.bundleBuilder.buildSearchReq(
                msgId = counters.counter.nextMsgId(),
                gsId = counters.counter.currentGsId(),
                sourceInstanceId = sourceId,
                hostCardInstanceId = hostCardIid,
                searchingSeat = counters.seatId.value,
                libraryZoneId = libZoneId,
                allLibraryIds = allLibIds,
                validTargetIds = validIds,
                maxFind = req.max,
                allowFailToFind = req.min == 0,
                promptId = promptId,
            )
        sink.sendBundledGRE(listOf(msg))
        setPendingInteraction(PendingClientInteraction.Search(pendingPrompt.promptId))
        log.info(
            "SearchReq sent: lib={} valid={} source={}, awaiting SearchResp",
            allLibIds.size,
            validIds.size,
            sourceId,
        )
    }

    private fun responseIndices(
        itemsFound: List<Int>,
        prompt: InteractivePromptBridge.PendingPrompt,
    ): List<Int> {
        val bridge = ctx.bridge
        return if (itemsFound.isEmpty()) {
            log.info("SearchResp: player declined (fail to find)")
            listOf(prompt.request.options.size)
        } else {
            itemsFound.map { chosenInstanceId ->
                val idx =
                    PromptResponseMapper
                        .cardInstanceIdsToPromptIndices(
                            listOf(chosenInstanceId),
                            prompt.request,
                        ) { instanceId -> bridge.getForgeCardId(InstanceId(instanceId)) }
                        .firstOrNull() ?: -1
                if (idx >= 0) {
                    log.info("SearchResp: player chose instanceId={} -> prompt index {}", chosenInstanceId, idx)
                    idx
                } else {
                    log.warn("SearchResp: instanceId={} not found in candidates, using default", chosenInstanceId)
                    DevCheck.fail { "SearchResp: instanceId=$chosenInstanceId not in candidates" }
                    prompt.request.defaultIndex
                }
            }
        }
    }

    private fun drainPendingPlayback() {
        val playback = ctx.bridge.playbackFor(counters.seatId) ?: return
        if (!playback.hasPendingMessages()) return

        for (batch in playback.drainQueue()) {
            sink.sendBundledGRE(batch)
        }
    }
}
