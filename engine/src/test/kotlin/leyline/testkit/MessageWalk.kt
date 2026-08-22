package leyline.testkit

import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Walkers for `List<GREToClientMessage>` — the message stream produced by
 * `harness.allMessages` / `messagesSince(snap)`. These flat-map the common
 * shapes (transient annotations, persistent annotations, game objects)
 * across all GSMs in the slice.
 *
 * GSMs are differential: a persistent annotation added in an earlier diff
 * doesn't republish in later diffs, and a game object's static fields
 * (type, isCopy, parentId, abilities) appear only in the introducing GSM.
 * The "first*" lookups handle that — they walk every GSM and return the
 * canonical introduction.
 *
 * Use these instead of inline `messages.filter { it.hasGameStateMessage() }
 * .flatMap { it.gameStateMessage.annotationsList }` chains. Same shape, one
 * fewer place to typo.
 */

fun List<GREToClientMessage>.gameStateMessages(): List<GameStateMessage> =
    mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }

fun List<GREToClientMessage>.allAnnotations(): List<AnnotationInfo> = gameStateMessages().flatMap { it.annotationsList }

fun List<GREToClientMessage>.allPersistentAnnotations(): List<AnnotationInfo> = gameStateMessages().flatMap { it.persistentAnnotationsList }

fun List<GREToClientMessage>.deletedPersistentAnnotationIds(): Set<Int> =
    gameStateMessages()
        .flatMap { it.diffDeletedPersistentAnnotationIdsList }
        .toSet()

fun List<GREToClientMessage>.allGameObjects(): List<GameObjectInfo> = gameStateMessages().flatMap { it.gameObjectsList }

fun List<GREToClientMessage>.allActions(): List<Action> =
    mapNotNull { if (it.hasActionsAvailableReq()) it.actionsAvailableReq else null }
        .flatMap { it.actionsList }

fun List<GREToClientMessage>.annotationsOfType(type: AnnotationType): List<AnnotationInfo> = allAnnotations().filter { type in it.typeList }

fun List<GREToClientMessage>.persistentAnnotationsOfType(type: AnnotationType): List<AnnotationInfo> =
    allPersistentAnnotations().filter { type in it.typeList }

/**
 * Set of every annotation type emitted (transient + persistent) across the
 * slice. Cheap broad-shape check — useful for keyword tests asserting that
 * a slice contains all of, e.g., `AbilityInstanceCreated`,
 * `ResolutionStart`, `TokenCreated`, `EnteredZoneThisTurn`.
 */
fun List<GREToClientMessage>.annotationTypeSet(): Set<AnnotationType> =
    (allAnnotations() + allPersistentAnnotations())
        .flatMap { it.typeList }
        .toSet()

/**
 * The introducing [GameObjectInfo] for [iid] — the first GSM that carried a
 * GameObject with that instanceId. Static fields (type, isCopy, parentId,
 * abilities) live there; later diffs reference by iid only.
 *
 * Returns null if the iid never appeared.
 */
fun List<GREToClientMessage>.firstGameObjectByIid(iid: Int): GameObjectInfo? =
    gameStateMessages()
        .firstNotNullOfOrNull { gsm -> gsm.gameObjectsList.firstOrNull { it.instanceId == iid } }

/**
 * The first GSM that introduced [iid] in its `gameObjectsList`. Pair with
 * [firstGameObjectByIid] when you need both the GSM (e.g. for `update` /
 * `gsId`) and the object.
 */
fun List<GREToClientMessage>.firstGsmIntroducing(iid: Int): GameStateMessage? =
    gameStateMessages()
        .firstOrNull { gsm -> gsm.gameObjectsList.any { it.instanceId == iid } }
