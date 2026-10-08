package leyline.testkit

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType

/** Only message order and order within one repeated wire field are comparable. */
internal data class ContractPosition(
    val messageIndex: Int,
    val wireList: String,
    val ordinal: Int,
) {
    fun precedes(other: ContractPosition): Boolean =
        messageIndex < other.messageIndex || (messageIndex == other.messageIndex && wireList == other.wireList && ordinal < other.ordinal)

    fun unorderedWith(other: ContractPosition): Boolean = messageIndex == other.messageIndex && wireList != other.wireList
}

internal data class ContractEvent(
    val position: ContractPosition,
    val type: String,
    val lane: String,
    val op: String,
    val values: Map<String, Any?>,
)

/** Snapshot disappearance ends membership without manufacturing an explicit deletion. */
internal data class ContractRowRemoval(
    val rowId: Int,
    val messageIndex: Int,
    val explicit: ContractPosition?,
) {
    fun breaksHold(
        start: ContractPosition,
        end: ContractPosition,
    ): Boolean =
        messageIndex in start.messageIndex..end.messageIndex &&
            (explicit == null || (!explicit.precedes(start) && explicit != end && !end.precedes(explicit)))
}

internal data class ContractProjection(
    val events: List<ContractEvent>,
    val removals: List<ContractRowRemoval>,
)

internal fun projectContract(messages: List<GREToClientMessage>): ContractProjection {
    val rows = mutableMapOf<Int, AnnotationInfo>()
    val activeRows = mutableSetOf<Int>()
    val objects = mutableSetOf<Int>()
    val removals = mutableListOf<ContractRowRemoval>()
    val events =
        buildList {
            messages.forEachIndexed { index, message ->
                if (message.hasGameStateMessage()) {
                    val gsm = message.gameStateMessage
                    if (gsm.type == GameStateType.Full) {
                        val present = gsm.persistentAnnotationsList.map { it.id }.toSet()
                        (activeRows - present).forEach { removals.add(ContractRowRemoval(it, index, null)) }
                        activeRows.retainAll(present)
                        objects.retainAll(gsm.gameObjectsList.map { it.instanceId }.toSet())
                    } else {
                        objects.removeAll(gsm.diffDeletedInstanceIdsList.toSet())
                    }
                    for ((ordinal, obj) in gsm.gameObjectsList.withIndex()) {
                        add(
                            ContractEvent(
                                ContractPosition(index, "gameObjects", ordinal),
                                obj.type.name,
                                "object",
                                if (objects.add(obj.instanceId)) "create" else "update",
                                mapOf("affectorId" to obj.instanceId, "raw" to obj),
                            ),
                        )
                    }
                    for ((ordinal, annotation) in gsm.annotationsList.withIndex()) {
                        addAll(annotation.events(ContractPosition(index, "annotations", ordinal), "transient", "emit"))
                    }
                    for ((ordinal, annotation) in gsm.persistentAnnotationsList.withIndex()) {
                        val op = if (activeRows.add(annotation.id)) "create" else "update"
                        rows[annotation.id] = annotation
                        addAll(annotation.events(ContractPosition(index, "persistentAnnotations", ordinal), "persistent", op))
                    }
                    for ((ordinal, id) in gsm.diffDeletedPersistentAnnotationIdsList.withIndex()) {
                        val position = ContractPosition(index, "diffDeletedPersistentAnnotationIds", ordinal)
                        activeRows.remove(id)
                        removals.add(ContractRowRemoval(id, index, position))
                        // Retain the last row to expose repeated deletions to exact-count obligations.
                        rows[id]?.let { addAll(it.events(position, "persistent", "delete")) }
                    }
                }
                val prompt =
                    when {
                        message.hasDeclareAttackersReq() -> "DeclareAttackersReq"
                        message.hasDeclareBlockersReq() -> "DeclareBlockersReq"
                        message.hasSelectTargetsReq() -> "SelectTargetsReq"
                        message.hasOptionalActionMessage() -> "OptionalActionMessage"
                        message.hasSelectNReq() -> "SelectNReq"
                        message.hasOrderReq() -> "OrderReq"
                        message.hasActionsAvailableReq() -> "ActionsAvailableReq"
                        else -> null
                    }
                if (prompt !=
                    null
                ) {
                    add(ContractEvent(ContractPosition(index, "prompt", 0), prompt, "prompt", "emit", mapOf("raw" to message)))
                }
                if (message.hasActionsAvailableReq()) {
                    for ((ordinal, action) in message.actionsAvailableReq.actionsList.withIndex()) {
                        add(ContractEvent(ContractPosition(index, "actions", ordinal), "Action", "action", "offer", mapOf("raw" to action)))
                    }
                }
            }
        }
    val correlated =
        events.map { event ->
            val message = messages[event.position.messageIndex]
            val stateId = if (message.hasGameStateMessage()) message.gameStateMessage.gameStateId else message.gameStateId
            event.copy(values = event.values + ("gameStateId" to stateId))
        }
    return ContractProjection(correlated, removals)
}

private fun AnnotationInfo.events(
    position: ContractPosition,
    lane: String,
    op: String,
): List<ContractEvent> {
    val values =
        mapOf(
            "annotationId" to id,
            "affectorId" to affectorId,
            "affectedIds" to affectedIdsList,
            "details" to
                detailsList.associate { detail ->
                    val field = detail.descriptorForType.findFieldByName("value${detail.type.name}")
                    detail.key to field?.let { detail.getField(it) }
                },
            "detailTypes" to detailsList.associate { it.key to it.type.name },
            "keys" to detailsList.map { it.key },
            "raw" to this,
        )
    return typeList.map { ContractEvent(position, it.protocolName(), lane, op, values) }
}
