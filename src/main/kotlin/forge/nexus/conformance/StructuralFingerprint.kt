package forge.nexus.conformance

import kotlinx.serialization.Serializable
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * ID-agnostic structural fingerprint of a GRE message.
 *
 * Captures the "shape" of a message (types, field presence, counts) while
 * stripping instance-specific IDs (instanceId, grpId, gsId, zoneId).
 * Two fingerprints are equal iff the messages have the same structure,
 * regardless of which specific cards or zones are involved.
 *
 * Used by the conformance test suite to compare Forge-generated messages
 * against recorded real-server baselines.
 */
@Serializable
data class StructuralFingerprint(
    val greMessageType: String,
    val gsType: String?,
    val updateType: String?,
    val annotationTypes: List<String>,
    val annotationCategories: List<String>,
    val fieldPresence: Set<String>,
    val zoneCount: Int,
    val objectCount: Int,
    val actionTypes: List<String>,
    val hasPrompt: Boolean,
    val promptId: Int?,
) {
    companion object {
        /** Proto enum suffixes to strip for human-readable comparison. */
        private val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")

        /** Extract a structural fingerprint from a [GREToClientMessage]. */
        fun fromGRE(gre: GREToClientMessage): StructuralFingerprint {
            val greType = gre.type.name.stripSuffix()

            // Prompt (on the GRE envelope, not inside GameStateMessage)
            val hasPrompt = gre.hasPrompt() && gre.prompt.promptId != 0
            val promptId = if (hasPrompt) gre.prompt.promptId else null

            // GameStateMessage extraction
            var gsType: String? = null
            var updateType: String? = null
            val fieldPresence = mutableSetOf<String>()
            var zoneCount = 0
            var objectCount = 0
            val annotationTypes = mutableListOf<String>()
            val annotationCategories = mutableListOf<String>()
            val actionTypes = mutableListOf<String>()

            if (gre.hasGameStateMessage()) {
                val gs = gre.gameStateMessage
                gsType = gs.type.name.stripSuffix()
                updateType = gs.update.name.stripSuffix()

                // Field presence — which top-level fields are populated
                if (gs.hasGameInfo()) fieldPresence.add("gameInfo")
                if (gs.hasTurnInfo()) fieldPresence.add("turnInfo")
                if (gs.zonesCount > 0) fieldPresence.add("zones")
                if (gs.gameObjectsCount > 0) fieldPresence.add("objects")
                if (gs.playersCount > 0) fieldPresence.add("players")
                if (gs.timersCount > 0) fieldPresence.add("timers")
                if (gs.actionsCount > 0) fieldPresence.add("actions")
                if (gs.annotationsCount > 0) fieldPresence.add("annotations")

                zoneCount = gs.zonesCount
                objectCount = gs.gameObjectsCount

                // Annotations: types + categories from "category" detail key
                for (ann in gs.annotationsList) {
                    for (t in ann.typeList) {
                        annotationTypes.add(t.name.stripSuffix())
                    }
                    for (detail in ann.detailsList) {
                        if (detail.key == "category" && detail.valueStringCount > 0) {
                            annotationCategories.add(detail.getValueString(0))
                        }
                    }
                }

                // Embedded actions in GameStateMessage
                for (actionInfo in gs.actionsList) {
                    actionTypes.add(actionInfo.action.actionType.name.stripSuffix())
                }
            }

            // ActionsAvailableReq extraction
            if (gre.hasActionsAvailableReq()) {
                for (action in gre.actionsAvailableReq.actionsList) {
                    actionTypes.add(action.actionType.name.stripSuffix())
                }
            }

            return StructuralFingerprint(
                greMessageType = greType,
                gsType = gsType,
                updateType = updateType,
                annotationTypes = annotationTypes.sorted(),
                annotationCategories = annotationCategories.sorted(),
                fieldPresence = fieldPresence,
                zoneCount = zoneCount,
                objectCount = objectCount,
                actionTypes = actionTypes.sorted(),
                hasPrompt = hasPrompt,
                promptId = promptId,
            )
        }

        private fun String.stripSuffix(): String = replace(PROTO_SUFFIX, "")
    }
}
