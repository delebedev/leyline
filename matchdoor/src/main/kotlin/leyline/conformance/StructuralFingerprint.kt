package leyline.conformance

import kotlinx.serialization.Serializable
import leyline.game.DetailKeys
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
    val gsType: String? = null,
    val updateType: String? = null,
    val annotationTypes: List<String> = emptyList(),
    val annotationCategories: List<String> = emptyList(),
    val fieldPresence: Set<String> = emptySet(),
    val zoneCount: Int = 0,
    val objectCount: Int = 0,
    val actionTypes: List<String> = emptyList(),
    val hasPrompt: Boolean = false,
    val promptId: Int? = null,
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
                        if (detail.key == DetailKeys.CATEGORY && detail.valueStringCount > 0) {
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

/** Read/write golden fingerprint sequences as JSON. */
object GoldenSequence {
    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        encodeDefaults = false
    }

    fun toJson(sequence: List<StructuralFingerprint>): String =
        json.encodeToString(sequence)

    fun fromJson(text: String): List<StructuralFingerprint> =
        json.decodeFromString(text)

    fun fromFile(file: java.io.File): List<StructuralFingerprint> =
        fromJson(file.readText())
}
