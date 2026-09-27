package leyline.bridge.coord

import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoiceOptionValue
import leyline.bridge.handoff.StaticChoiceWindowValue
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/** Engine-thread capture for one immutable static enum SelectN window. */
internal object StaticChoiceWindowCapture {
    fun initial(request: PromptRequest): StaticChoiceWindowValue {
        val route = request.route as? ResolvedPromptRoute.StaticChoice ?: error("StaticChoice route required")
        val allowedLists =
            when (route.descriptor.kind) {
                StaticChoiceKind.Color -> setOf(StaticList.Colors, StaticList.CardColors)
                StaticChoiceKind.Subtype -> setOf(StaticList.SubTypes)
                StaticChoiceKind.Parity -> setOf(StaticList.Parities)
            }
        check(request.staticList in allowedLists) { "StaticChoice domain does not match its route" }
        check(request.staticOptionIds.size == request.options.size) { "StaticChoice values must match options" }
        check(request.staticOptionIds.distinct().size == request.staticOptionIds.size) { "StaticChoice values must be distinct" }
        check(request.min in 0..request.max && request.max <= request.options.size) { "Invalid StaticChoice cardinality" }
        check(request.defaultIndex in request.options.indices) { "Invalid StaticChoice default option" }
        return StaticChoiceWindowValue(
            kind = route.descriptor.kind,
            staticList = checkNotNull(request.staticList),
            options =
                request.staticOptionIds.mapIndexed { index, value ->
                    StaticChoiceOptionValue(index, value)
                },
            sourceForgeCardId = request.sourceEntityId?.let(::ForgeCardId),
            min = request.min,
            max = request.max,
            defaultOptionIndex = request.defaultIndex,
        )
    }
}
