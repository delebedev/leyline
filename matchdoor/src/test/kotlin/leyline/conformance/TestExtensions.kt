package leyline.conformance

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.BundleBuilder
import wotc.mtgo.gre.external.messaging.Messages.*

// ----- Tier 1: BundleResult extraction -----

/** Extract the first GameStateMessage from a bundle result. */
val BundleBuilder.BundleResult.gsm: GameStateMessage
    get() = messages.first { it.hasGameStateMessage() }.gameStateMessage

/** Extract the first GameStateMessage, or null if absent. */
val BundleBuilder.BundleResult.gsmOrNull: GameStateMessage?
    get() = messages.firstOrNull { it.hasGameStateMessage() }?.gameStateMessage

/**
 * Merge all GSMs in a bundle into a single GSM with combined annotations.
 * Uses the last GSM as the base (has persistent annotations), prepends
 * annotations from earlier GSMs. For CastSpell triplets this reconstructs
 * the full annotation sequence: queued1 annotations + main annotations.
 */
val BundleBuilder.BundleResult.mergedGsm: GameStateMessage
    get() {
        val gsms = messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
        require(gsms.isNotEmpty()) { "No GSMs in bundle" }
        val allAnnotations = gsms.flatMap { it.annotationsList }
        val base = gsms.last()
        return base.toBuilder()
            .clearAnnotations()
            .addAllAnnotations(allAnnotations)
            .build()
    }

/** Extract the ActionsAvailableReq from a bundle result. */
val BundleBuilder.BundleResult.aar: ActionsAvailableReq
    get() = messages.first { it.hasActionsAvailableReq() }.actionsAvailableReq

/** Extract the ActionsAvailableReq, or null if absent. */
val BundleBuilder.BundleResult.aarOrNull: ActionsAvailableReq?
    get() = messages.firstOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq

// ----- Tier 1: Annotation detail access -----

/** Get the first detail with the given key, or null. */
fun AnnotationInfo.detail(key: String): KeyValuePairInfo? =
    detailsList.firstOrNull { it.key == key }

/** Shorthand: get an int32 detail value. Fails if the key is missing. */
fun AnnotationInfo.detailInt(key: String): Int =
    detail(key)?.getValueInt32(0) ?: error("No detail '$key' on annotation $typeList")

/** Shorthand: get a uint32 detail value. Fails if the key is missing. */
fun AnnotationInfo.detailUint(key: String): Int =
    detail(key)?.getValueUint32(0) ?: error("No detail '$key' on annotation $typeList")

/** Shorthand: get a string detail value. Fails if the key is missing. */
fun AnnotationInfo.detailString(key: String): String =
    detail(key)?.getValueString(0) ?: error("No detail '$key' on annotation $typeList")

/** Shorthand: get all int32 values for a multi-value detail (e.g. colors=[3, 5]). */
fun AnnotationInfo.detailIntList(key: String): List<Int> {
    val d = detail(key) ?: error("No detail '$key' on annotation $typeList")
    return (0 until d.valueInt32Count).map { d.getValueInt32(it) }
}

// ----- Tier 1: Action filtering -----

/** Filter actions by ActionType. */
fun ActionsAvailableReq.ofType(type: ActionType): List<Action> =
    actionsList.filter { it.actionType == type }

// ----- Tier 1: Annotation lookup by type -----

/** Find all annotations with the given type. */
fun GameStateMessage.annotations(type: AnnotationType): List<AnnotationInfo> =
    annotationsList.filter { type in it.typeList }

/** Find the first annotation with the given type. */
fun GameStateMessage.annotation(type: AnnotationType): AnnotationInfo =
    annotationsList.first { type in it.typeList }

/** Find the first annotation with the given type, or null. */
fun GameStateMessage.annotationOrNull(type: AnnotationType): AnnotationInfo? =
    annotationsList.firstOrNull { type in it.typeList }

/** Find the first persistent annotation with the given type. */
fun GameStateMessage.persistentAnnotation(type: AnnotationType): AnnotationInfo =
    persistentAnnotationsList.firstOrNull { type in it.typeList }
        ?: error("No persistent annotation of type $type")

/** Find the first persistent annotation with the given type, or null. */
fun GameStateMessage.persistentAnnotationOrNull(type: AnnotationType): AnnotationInfo? =
    persistentAnnotationsList.firstOrNull { type in it.typeList }

/** Check if a specific instanceId has EnteredZoneThisTurn persistent annotation. */
fun GameStateMessage.hasEnteredZoneThisTurn(instanceId: Int): Boolean =
    persistentAnnotationsList.any {
        AnnotationType.EnteredZoneThisTurn in it.typeList &&
            instanceId in it.affectedIdsList
    }

// ----- Tier 2: Accumulator consistency -----

/** Assert that action instanceIds and zone object refs are all valid. */
fun ClientAccumulator.assertConsistent(context: String = "") {
    val suffix = if (context.isNotEmpty()) " ($context)" else ""
    val missingActions = actionInstanceIdsMissingFromObjects()
    withClue("Action instanceIds missing from objects$suffix: $missingActions") {
        missingActions.shouldBeEmpty()
    }
    val missingZoneObjs = zoneObjectsMissingFromObjects()
    withClue("Zone objects missing from objects$suffix: $missingZoneObjs") {
        missingZoneObjs.shouldBeEmpty()
    }
}

// ----- Tier 2: Zone consistency -----

/** Assert that a zone's objectInstanceIds count matches the number of objects with that zoneId. */
fun ClientAccumulator.assertZoneCountMatchesObjects(zoneId: Int) {
    val zone = zones[zoneId] ?: return
    val zoneCount = zone.objectInstanceIdsCount
    val objCount = objects.values.count { it.zoneId == zoneId }
    zoneCount shouldBe objCount
}

// ----- Tier 2: gsId chain validation -----

/**
 * Assert gsId chain invariants across a sequence of GRE messages.
 * @param messages the message sequence to validate
 * @param priorGsIds gsIds from messages sent before this sequence (for prevGsId lookups)
 * @param context label for assertion messages
 */
fun assertGsIdChain(
    messages: List<GREToClientMessage>,
    priorGsIds: Set<Int> = emptySet(),
    context: String = "",
) {
    val suffix = if (context.isNotEmpty()) " ($context)" else ""
    val gsms = messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
    val knownGsIds = priorGsIds.toMutableSet()

    // gsIds strictly monotonic
    for (i in 1 until gsms.size) {
        gsms[i].gameStateId shouldBeGreaterThan gsms[i - 1].gameStateId
    }
    // No self-referential prevGameStateId
    for (gsm in gsms) {
        if (gsm.prevGameStateId != 0) {
            gsm.prevGameStateId shouldNotBe gsm.gameStateId
        }
    }
    // prevGameStateId references a known gsId
    for (gsm in gsms) {
        if (gsm.prevGameStateId != 0) {
            knownGsIds shouldContain gsm.prevGameStateId
        }
        knownGsIds.add(gsm.gameStateId)
    }
    // gsIds globally unique (no collisions from counter re-seeding)
    val allGsIds = gsms.map { it.gameStateId }
    val duplicates = allGsIds.groupBy { it }.filter { it.value.size > 1 }.keys
    duplicates.shouldBeEmpty()

    // msgIds strictly monotonic
    val msgIds = messages.map { it.msgId }
    for (i in 1 until msgIds.size) {
        msgIds[i] shouldBeGreaterThan msgIds[i - 1]
    }

    // msgIds globally unique
    val dupMsgIds = msgIds.groupBy { it }.filter { it.value.size > 1 }.keys
    dupMsgIds.shouldBeEmpty()
}

// ----- Tier 1: GRE message filtering by transfer category -----

/**
 * Find the first GRE message containing a ZoneTransfer annotation with the given category.
 */
fun List<GREToClientMessage>.firstWithTransferCategory(category: String): GREToClientMessage? =
    firstOrNull { gre ->
        gre.hasGameStateMessage() &&
            gre.gameStateMessage.annotationsList.any { ann ->
                AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                    ann.detail("category")?.getValueString(0) == category
            }
    }

// ----- Tier 1: Annotation lookup by type + affected instanceId -----

/** Find annotation by type that affects a specific instanceId. */
fun GameStateMessage.annotationAffecting(
    type: AnnotationType,
    instanceId: Int,
): AnnotationInfo? =
    annotationsList.firstOrNull {
        type in it.typeList && instanceId in it.affectedIdsList
    }

/** ZoneTransfer details extracted from annotation. */
data class ZoneTransferInfo(
    val category: String,
    val zoneSrc: Int = -1,
    val zoneDest: Int = -1,
)

/** Find the ZoneTransfer annotation for a given instanceId. */
fun GameStateMessage.findZoneTransfer(instanceId: Int): ZoneTransferInfo? {
    val ann = annotationAffecting(AnnotationType.ZoneTransfer_af5a, instanceId) ?: return null
    return ZoneTransferInfo(
        category = ann.detail("category")?.getValueString(0) ?: "",
        zoneSrc = ann.detail("zone_src")?.getValueInt32(0) ?: -1,
        zoneDest = ann.detail("zone_dest")?.getValueInt32(0) ?: -1,
    )
}

// ----- Tier 2: Limbo assertions -----

/** Assert that the GSM's Limbo zone contains the given instanceId in objectInstanceIds. */
fun assertLimboContains(gsm: GameStateMessage, instanceId: Int) {
    val limbo = checkNotNull(gsm.zonesList.firstOrNull { it.type == ZoneType.Limbo }) {
        "GSM should have Limbo zone"
    }
    limbo.objectInstanceIdsList shouldContain instanceId
}
