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
                StaticChoiceKind.Named, StaticChoiceKind.Dungeon, StaticChoiceKind.DungeonRoom -> setOf(null)
                StaticChoiceKind.Color -> setOf(StaticList.Colors, StaticList.CardColors)
                StaticChoiceKind.Subtype -> setOf(StaticList.SubTypes)
                StaticChoiceKind.CardType -> setOf(StaticList.CardTypes)
                StaticChoiceKind.Parity -> setOf(StaticList.Parities)
            }
        check(request.staticList in allowedLists) { "StaticChoice domain does not match its route" }
        if (route.descriptor.kind == StaticChoiceKind.Named) {
            check(request.staticOptionIds == request.options.indices.map { it + 1 }) { "Named choices require aligned prompt indices" }
        }
        check(request.staticOptionIds.size == request.options.size) { "StaticChoice values must match options" }
        val enumValues = request.staticOptionIds.filterNotNull()
        check(enumValues.distinct().size == enumValues.size) { "StaticChoice values must be distinct" }
        check(enumValues.size == request.staticOptionIds.size || route.descriptor.kind in LABELLABLE_KINDS) {
            "Only type choices may offer values without an enum value"
        }
        check(request.min in 0..request.max && request.max <= request.options.size) { "Invalid StaticChoice cardinality" }
        check(request.defaultIndex in request.options.indices) { "Invalid StaticChoice default option" }
        return StaticChoiceWindowValue(
            kind = route.descriptor.kind,
            staticList = request.staticList,
            sourceForgeAbilityId = request.forgeAbilityId,
            options =
                request.staticOptionIds.mapIndexed { index, value ->
                    StaticChoiceOptionValue(
                        index,
                        value,
                        request.options[index].takeIf {
                            route.descriptor.kind == StaticChoiceKind.Named ||
                                route.descriptor.kind in LABELLABLE_KINDS
                        },
                    )
                },
            sourceForgeCardId = request.sourceEntityId?.let(::ForgeCardId),
            min = request.min,
            max = request.max,
            defaultOptionIndex = request.defaultIndex,
        )
    }

    /** Type choices may offer Forge types that have no enum value; projection decides how they are shown. */
    private val LABELLABLE_KINDS = setOf(StaticChoiceKind.Subtype, StaticChoiceKind.CardType)
}
