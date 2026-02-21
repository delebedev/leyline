package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import wotc.mtgo.gre.external.messaging.Messages.*

// ----- Tier 1: BundleResult extraction -----

/** Extract the first GameStateMessage from a bundle result. */
val BundleBuilder.BundleResult.gsm: GameStateMessage
    get() = messages.first { it.hasGameStateMessage() }.gameStateMessage

/** Extract the first GameStateMessage, or null if absent. */
val BundleBuilder.BundleResult.gsmOrNull: GameStateMessage?
    get() = messages.firstOrNull { it.hasGameStateMessage() }?.gameStateMessage

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

// ----- Tier 1: Action filtering -----

/** Filter actions by ActionType. */
fun ActionsAvailableReq.ofType(type: ActionType): List<Action> =
    actionsList.filter { it.actionType == type }

// ----- Tier 1: Annotation lookup by type -----

/** Find the first annotation with the given type. */
fun GameStateMessage.annotation(type: AnnotationType): AnnotationInfo =
    annotationsList.first { type in it.typeList }

/** Find the first annotation with the given type, or null. */
fun GameStateMessage.annotationOrNull(type: AnnotationType): AnnotationInfo? =
    annotationsList.firstOrNull { type in it.typeList }

// ----- Tier 2: Accumulator consistency -----

/** Assert that action instanceIds and zone object refs are all valid. */
fun ClientAccumulator.assertConsistent(context: String = "") {
    val suffix = if (context.isNotEmpty()) " ($context)" else ""
    val missingActions = actionInstanceIdsMissingFromObjects()
    assertTrue(missingActions.isEmpty(), "Action instanceIds missing from objects$suffix: $missingActions")
    val missingZoneObjs = zoneObjectsMissingFromObjects()
    assertTrue(missingZoneObjs.isEmpty(), "Zone objects missing from objects$suffix: $missingZoneObjs")
}

// ----- Tier 2: Zone consistency -----

/** Assert that a zone's objectInstanceIds count matches the number of objects with that zoneId. */
fun ClientAccumulator.assertZoneCountMatchesObjects(zoneId: Int) {
    val zone = zones[zoneId] ?: return
    val zoneCount = zone.objectInstanceIdsCount
    val objCount = objects.values.count { it.zoneId == zoneId }
    assertEquals(
        zoneCount,
        objCount,
        "Zone $zoneId objectIds count ($zoneCount) should match objects with that zoneId ($objCount)",
    )
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
        assertTrue(
            gsms[i].gameStateId > gsms[i - 1].gameStateId,
            "gsIds not monotonic$suffix: ${gsms[i - 1].gameStateId} -> ${gsms[i].gameStateId}",
        )
    }
    // No self-referential prevGameStateId
    for (gsm in gsms) {
        if (gsm.prevGameStateId != 0) {
            assertNotEquals(
                gsm.prevGameStateId,
                gsm.gameStateId,
                "Self-referential prevGsId$suffix: gsId=${gsm.gameStateId}",
            )
        }
    }
    // prevGameStateId references a known gsId
    for (gsm in gsms) {
        if (gsm.prevGameStateId != 0) {
            assertTrue(
                knownGsIds.contains(gsm.prevGameStateId),
                "prevGsId ${gsm.prevGameStateId} not in known set $knownGsIds$suffix (gsId=${gsm.gameStateId})",
            )
        }
        knownGsIds.add(gsm.gameStateId)
    }
    // gsIds globally unique (no collisions from counter re-seeding)
    val allGsIds = gsms.map { it.gameStateId }
    val duplicates = allGsIds.groupBy { it }.filter { it.value.size > 1 }.keys
    assertTrue(
        duplicates.isEmpty(),
        "Duplicate gsIds$suffix: $duplicates (total ${allGsIds.size} GSMs)",
    )

    // msgIds strictly monotonic
    val msgIds = messages.map { it.msgId }
    for (i in 1 until msgIds.size) {
        assertTrue(
            msgIds[i] > msgIds[i - 1],
            "msgIds not monotonic$suffix: ${msgIds[i - 1]} -> ${msgIds[i]}",
        )
    }

    // msgIds globally unique
    val allMsgIds = msgIds
    val dupMsgIds = allMsgIds.groupBy { it }.filter { it.value.size > 1 }.keys
    assertTrue(
        dupMsgIds.isEmpty(),
        "Duplicate msgIds$suffix: $dupMsgIds",
    )
}

// ----- Tier 2: Limbo assertions -----

/** Assert that the GSM's Limbo zone contains the given instanceId in objectInstanceIds. */
fun assertLimboContains(gsm: GameStateMessage, instanceId: Int) {
    val limboZone = gsm.zonesList.firstOrNull { it.type == ZoneType.Limbo }
    assertTrue(limboZone != null, "GSM should have Limbo zone")
    assertTrue(
        limboZone!!.objectInstanceIdsList.contains(instanceId),
        "Limbo zone should contain instanceId $instanceId, got: ${limboZone.objectInstanceIdsList}",
    )
    // Real server doesn't send GameObjectInfo for Limbo objects —
    // only objectInstanceIds in the Limbo ZoneInfo.
}
