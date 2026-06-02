package leyline.simclient

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

data class PromptRouteAudit(
    val requestsByKind: Map<String, Int>,
    val samplesByKind: Map<String, String>,
    val findings: List<PromptRouteFinding>,
) {
    companion object {
        val Empty = PromptRouteAudit(emptyMap(), emptyMap(), emptyList())
    }
}

data class PromptRouteFinding(
    val bucket: String,
    val routeKey: String,
    val enginePromptType: String,
    val semantic: String,
    val expectedGreType: String,
    val expectedCount: Int,
    val emittedCount: Int,
    val outcomeCounts: Map<String, Int>,
    val sampleMessage: String,
)

object PromptRouteAuditor {
    fun audit(
        history: List<InteractivePromptBridge.PromptRecord>,
        promptHistogram: Map<GREMessageType, Int>,
    ): PromptRouteAudit {
        if (history.isEmpty()) return PromptRouteAudit.Empty

        val requestsByKind = history.groupingBy { it.kindKey() }.eachCount().toSortedMap()
        val samplesByKind =
            history
                .groupBy { it.kindKey() }
                .mapValues { (_, records) -> records.first().message.compactSample() }
                .toSortedMap()
        val emittedByGreType = promptHistogram.toRouteNames()
        val expectedRoutes = history.mapNotNull { record -> record.expectedRoute()?.let { it to record } }
        val expectedByGreType = expectedRoutes.groupingBy { it.first.expectedGreType }.eachCount()
        val routeKeysByGreType =
            expectedRoutes
                .groupBy { it.first.expectedGreType }
                .mapValues { (_, routes) -> routes.map { it.first.routeKey }.toSet() }
        val findings =
            expectedRoutes
                .groupBy { it.first.routeKey }
                .values
                .mapNotNull { routeRecords ->
                    val route = routeRecords.first().first
                    val records = routeRecords.map { it.second }
                    val emitted = emittedByGreType[route.expectedGreType] ?: 0
                    val outcomes = records.groupingBy { it.outcome.name }.eachCount().toSortedMap()
                    val expectedForType = expectedByGreType.getValue(route.expectedGreType)
                    val bucket =
                        when {
                            emitted < expectedForType ->
                                classifyBucket(expectedCount = records.size, emittedCount = emitted, outcomeCounts = outcomes)
                            routeKeysByGreType.getValue(route.expectedGreType).size > 1 -> "ambiguous_route_coverage"
                            else -> return@mapNotNull null
                        }
                    PromptRouteFinding(
                        bucket = bucket,
                        routeKey = route.routeKey,
                        enginePromptType = route.enginePromptType,
                        semantic = route.semantic.name,
                        expectedGreType = route.expectedGreType,
                        expectedCount = records.size,
                        emittedCount = emitted,
                        outcomeCounts = outcomes,
                        sampleMessage = records.first().message.compactSample(),
                    )
                }.sortedWith(compareBy<PromptRouteFinding> { it.bucket }.thenBy { it.routeKey })
        return PromptRouteAudit(requestsByKind, samplesByKind, findings)
    }

    private fun InteractivePromptBridge.PromptRecord.expectedRoute(): ExpectedRoute? {
        val expectedGreType = semantic.expectedGreType(this) ?: return null
        return ExpectedRoute(
            routeKey = kindKey(),
            enginePromptType = promptType,
            semantic = semantic,
            expectedGreType = expectedGreType,
        )
    }

    private fun PromptSemantic.expectedGreType(record: InteractivePromptBridge.PromptRecord): String? =
        when (this) {
            PromptSemantic.GroupingSurveil,
            PromptSemantic.GroupingScry,
            -> "GroupReq"
            PromptSemantic.ModalChoice -> "CastingTimeOptionsReq"
            PromptSemantic.Search -> "SearchReq"
            PromptSemantic.OrderForBottom,
            PromptSemantic.OrderForTop,
            PromptSemantic.OrderGeneric,
            -> "OrderReq"
            PromptSemantic.SelectNCostSacrifice,
            PromptSemantic.SelectNCostExileFromGrave,
            PromptSemantic.SelectNCostCollectEvidence,
            PromptSemantic.EnlistCost,
            PromptSemantic.StationTapCost,
            PromptSemantic.ReturnUnblockedAttackerCost,
            -> "PayCostsReq"
            PromptSemantic.SelectNLegendRule,
            PromptSemantic.SelectNDiscard,
            PromptSemantic.RevealChoose,
            PromptSemantic.SelectNResolution,
            PromptSemantic.SelectNLibraryPutback,
            PromptSemantic.SelectNSacrificeEffect,
            PromptSemantic.MutateTopBottom,
            PromptSemantic.LearnLesson,
            PromptSemantic.StaticColorChoice,
            PromptSemantic.StaticSubtypeChoice,
            -> "SelectNReq"
            PromptSemantic.Generic ->
                when {
                    record.promptType == "order" -> "OrderReq"
                    record.promptType == "choose_cards" && record.message.isLibraryOrderPrompt() -> "OrderReq"
                    record.candidateCount > 0 -> "SelectTargetsReq"
                    else -> null
                }
        }

    private fun classifyBucket(
        expectedCount: Int,
        emittedCount: Int,
        outcomeCounts: Map<String, Int>,
    ): String =
        when {
            outcomeCounts.getOrDefault(InteractivePromptBridge.PromptCallStatus.TIMEOUT.name, 0) > 0 -> "defaulted_timeout"
            emittedCount == 0 && outcomeCounts.getOrDefault(InteractivePromptBridge.PromptCallStatus.RESPONDED.name, 0) == expectedCount ->
                "swallowed_auto_resolve"
            else -> "wrong_req"
        }

    private fun InteractivePromptBridge.PromptRecord.kindKey(): String = "$promptType|${semantic.name}"

    private fun Map<GREMessageType, Int>.toRouteNames(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for ((type, count) in this) {
            out.merge(type.routeName(), count) { a, b -> a + b }
        }
        return out.toSortedMap()
    }

    private fun GREMessageType.routeName(): String =
        when (val base = name.replace(ENUM_TAG_SUFFIX, "")) {
            "SelectNreq" -> "SelectNReq"
            else -> base
        }

    private fun String.compactSample(): String = replace(Regex("\\s+"), " ").take(160)

    private fun String.isLibraryOrderPrompt(): Boolean = contains("order", ignoreCase = true) && contains("library", ignoreCase = true)

    private data class ExpectedRoute(
        val routeKey: String,
        val enginePromptType: String,
        val semantic: PromptSemantic,
        val expectedGreType: String,
    )

    private val ENUM_TAG_SUFFIX = Regex("_[a-f0-9]{4}$")
}
