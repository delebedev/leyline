package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptResponseMapper
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.bundle.RequestBuilder
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

        val seatBridge = bridge.seat(counters.seatId)
        val prompt = seatBridge.prompt.getPendingPrompt()
        if (prompt == null ||
            prompt.promptId != pending.promptId ||
            !prompt.request.route.accepts(PromptResponseKind.Search)
        ) {
            return
        }
        val responseIndices = responseIndices(itemsFound, prompt)
        val submitted =
            seatBridge.prompt.submitResponse(pending.promptId, responseIndices) {
                setPendingInteraction(null)
            }
        if (!submitted) return
        bridge.awaitPriority()
        drainPendingPlayback()
        bundles.bundleBuilder.cursor.invalidate()
        sink.sendRealGameState(bridge)
        autoPass()
    }

    fun sendSearchReq(pendingPrompt: InteractivePromptBridge.PendingPrompt) {
        val bridge = ctx.bridge
        // Reveal library contents so the client can populate the search picker.
        sink.sendRealGameState(bridge, revealForSeat = counters.seatId.value)

        val req = pendingPrompt.request
        val facts = bridge.searchPromptFacts(counters.seatId, req.sourceEntityId)
        val validIds =
            req.candidateRefs.map { ref ->
                bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            }

        val msg =
            RequestBuilder.buildSearchReq(
                msgId = counters.counter.nextMsgId(),
                gsId = counters.counter.currentGsId(),
                systemSeatId = counters.seatId.value,
                sourceInstanceId = facts.sourceInstanceId,
                hostCardInstanceId = facts.hostCardInstanceId,
                searchingSeat = counters.seatId.value,
                libraryZoneId = facts.libraryZoneId,
                allLibraryIds = facts.allLibraryIds,
                validTargetIds = validIds,
                maxFind = req.max,
                allowFailToFind = req.min == 0,
                promptId = facts.promptId,
            )
        sink.sendBundledGRE(listOf(msg))
        setPendingInteraction(PendingClientInteraction.Search(pendingPrompt.promptId))
        log.info(
            "SearchReq sent: lib={} valid={} source={}, awaiting SearchResp",
            facts.allLibraryIds.size,
            validIds.size,
            facts.sourceInstanceId,
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
