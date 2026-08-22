package leyline.testkit

import forge.game.card.Card
import forge.game.player.Player
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.InvariantChecker
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/** Submit a test action through the production action-window claim path. */
fun GameBridge.submitTestAction(
    actionId: String,
    action: PlayerAction,
): Boolean {
    val actionBridge = actionBridge(seating.humanSeat)
    val pending = actionBridge.getPending()?.takeIf { it.actionId == actionId } ?: return false
    if (pending.state.kind == PendingActionKind.SYNC_ONLY && action == PlayerAction.PassPriority) {
        return actionBridge.completeSyncPass(actionId)
    }
    val offer =
        cutCoordinator.actions
            .actionOffersForTest(actionId)
            .singleOrNull { sameTestCommand(it, action) }
            ?: return false
    val claim = cutCoordinator.claimPriorityResponse(actionId, pending.promptGameStateId ?: 0, offer.action, defer = false) ?: return false
    return cutCoordinator.completeActionClaim(claim.actionClaim)
}

@Suppress("CyclomaticComplexMethod")
private fun sameTestCommand(
    offer: leyline.bridge.handoff.GameActionBridge.ActionOffer,
    requested: PlayerAction,
): Boolean {
    val offered = offer.command
    return when {
        offered is PlayerAction.CastSpell && requested is PlayerAction.CastSpell ->
            offered.cardId == requested.cardId &&
                (requested.abilityId == null || offered.abilityId == requested.abilityId) &&
                offered.targets == requested.targets
        offered is PlayerAction.ActivateAbility && requested is PlayerAction.ActivateAbility ->
            offered.cardId == requested.cardId && offered.abilityId == requested.abilityId && offered.targets == requested.targets
        offered is PlayerAction.ActivateMana && requested is PlayerAction.ActivateMana ->
            offered.cardId == requested.cardId &&
                (requested.abilityId == null || offered.abilityId == requested.abilityId) &&
                offered.selectedColor == requested.selectedColor
        offered is PlayerAction.PlayLand && requested is PlayerAction.PlayLand -> offered.cardId == requested.cardId
        offered is PlayerAction.DeclareAttackers && requested is PlayerAction.DeclareAttackers -> offered == requested
        offered is PlayerAction.DeclareBlockers && requested is PlayerAction.DeclareBlockers -> offered == requested
        offered is PlayerAction.PassPriority && requested is PlayerAction.PassPriority -> offer.action.actionType == ActionType.Pass
        offered is PlayerAction.EndTurn && requested is PlayerAction.EndTurn -> true
        else -> false
    }
}

// ----- Zone shorthand properties (package-level, complementing MatchFlowHarness) -----

/** Battlefield zone of this player as a probe handle. */
val Player.battlefield: PlayerZone get() = PlayerZone(this, ForgeZoneType.Battlefield)

/** Hand zone of this player as a probe handle. */
val Player.hand: PlayerZone get() = PlayerZone(this, ForgeZoneType.Hand)

/** Graveyard zone of this player as a probe handle. */
val Player.graveyard: PlayerZone get() = PlayerZone(this, ForgeZoneType.Graveyard)

/** Exile zone of this player as a probe handle. */
val Player.exile: PlayerZone get() = PlayerZone(this, ForgeZoneType.Exile)

/** Library zone of this player as a probe handle. */
val Player.library: PlayerZone get() = PlayerZone(this, ForgeZoneType.Library)

// ----- Zone card access -----

/** Find a card in the zone by name. */
fun PlayerZone.card(name: String): Card =
    player.getZone(zone).cards.firstOrNull { it.name == name }
        ?: error("No '$name' on ${player.name}'s ${zone.name}. Present: ${player.getZone(zone).cards.map { it.name }}")

// ----- Tier 0: Content-addressed GSM lookup -----
//
// Bundles can end with a trailing post-content echo (an empty GSM that
// carries no annotations / objects / persistent annotations). Ordinal
// `gsms.last()` and `last { hasGameStateMessage() }` then pick the empty
// echo and assertions against "the GSM we just produced" silently start
// matching against nothing — false-pass for absence checks, throws for
// presence checks. Use the helpers below to address GSMs by their
// content (the persistent annotation, the gameObject, an arbitrary
// predicate) instead of position.

/** Last GSM from a list of GREs that satisfies [predicate], or null. */
fun List<GREToClientMessage>.lastGsmMatching(predicate: (GameStateMessage) -> Boolean): GameStateMessage? =
    this
        .asReversed()
        .firstOrNull { it.hasGameStateMessage() && predicate(it.gameStateMessage) }
        ?.gameStateMessage

/** Last GSM in the list that satisfies [predicate], or null. */
fun List<GameStateMessage>.lastMatching(predicate: (GameStateMessage) -> Boolean): GameStateMessage? =
    this.asReversed().firstOrNull(predicate)

/** Last GSM that carries a persistent annotation of the given type, or null. */
fun List<GameStateMessage>.lastWithPersistentAnnotation(type: AnnotationType): GameStateMessage? =
    this.asReversed().firstOrNull { gs ->
        gs.persistentAnnotationsList.any { type in it.typeList }
    }

/** Last GSM whose `gameObjects` contains the given instanceId, or null. */
fun List<GameStateMessage>.lastWithGameObject(instanceId: Int): GameStateMessage? =
    this.asReversed().firstOrNull { gs ->
        gs.gameObjectsList.any { it.instanceId == instanceId }
    }

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
        return base
            .toBuilder()
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
fun AnnotationInfo.detail(key: String): KeyValuePairInfo? = detailsList.firstOrNull { it.key == key }

/** True if this annotation has a detail with the given key. Prefer over
 *  `detailsList.any { it.key == key }` for negative assertions. */
fun AnnotationInfo.hasDetail(key: String): Boolean = detailsList.any { it.key == key }

/** Shorthand: get an int32 detail value. Fails if the key is missing. */
fun AnnotationInfo.detailInt(key: String): Int = detail(key)?.getValueInt32(0) ?: error("No detail '$key' on annotation $typeList")

/** Shorthand: get a numeric detail value historically asserted as uint32. */
fun AnnotationInfo.detailUint(key: String): Int =
    detail(key)?.let { d ->
        when {
            d.valueUint32Count > 0 -> d.getValueUint32(0)
            d.valueInt32Count > 0 -> d.getValueInt32(0)
            else -> error("Detail '$key' is not numeric on annotation $typeList")
        }
    } ?: error("No detail '$key' on annotation $typeList")

/** Shorthand: get a string detail value. Fails if the key is missing. */
fun AnnotationInfo.detailString(key: String): String = detail(key)?.getValueString(0) ?: error("No detail '$key' on annotation $typeList")

/** Shorthand: get all int32 values for a multi-value detail (e.g. colors=[3, 5]). */
fun AnnotationInfo.detailIntList(key: String): List<Int> {
    val d = detail(key) ?: error("No detail '$key' on annotation $typeList")
    return (0 until d.valueInt32Count).map { d.getValueInt32(it) }
}

// ----- Tier 1: Action filtering -----

/** Filter actions by ActionType. */
fun ActionsAvailableReq.ofType(type: ActionType): List<Action> = actionsList.filter { it.actionType == type }

// ----- Tier 1: Annotation lookup by type -----

/** Find all annotations with the given type. */
fun GameStateMessage.annotations(type: AnnotationType): List<AnnotationInfo> = annotationsList.filter { type in it.typeList }

/** Find the first annotation with the given type. */
fun GameStateMessage.annotation(type: AnnotationType): AnnotationInfo =
    annotationsList.firstOrNull { type in it.typeList }
        ?: error("No $type annotation. Present: ${annotationsList.flatMap { it.typeList }.distinct()}")

/**
 * Find the only annotation of [type] in an already-extracted list.
 *
 * Mirrors the [GameStateMessage] overload for the many sites that hold a plain
 * list — a persistent-annotation slice, a filtered frame — rather than a whole
 * message. Names the types that were present, which the bare `first { }` it
 * replaces cannot.
 */
fun List<AnnotationInfo>.annotation(type: AnnotationType): AnnotationInfo =
    firstOrNull { type in it.typeList }
        ?: error("No $type annotation. Present: ${flatMap { it.typeList }.distinct()}")

/** Find the first annotation with the given type, or null. */
fun GameStateMessage.annotationOrNull(type: AnnotationType): AnnotationInfo? = annotationsList.firstOrNull { type in it.typeList }

/** Find the first persistent annotation with the given type. */
fun GameStateMessage.persistentAnnotation(type: AnnotationType): AnnotationInfo =
    persistentAnnotationsList.firstOrNull { type in it.typeList }
        ?: error("No $type persistent annotation. Present: ${persistentAnnotationsList.flatMap { it.typeList }.distinct()}")

/** Find the first persistent annotation with the given type, or null. */
fun GameStateMessage.persistentAnnotationOrNull(type: AnnotationType): AnnotationInfo? =
    persistentAnnotationsList.firstOrNull { type in it.typeList }

/** Check if a specific instanceId has EnteredZoneThisTurn persistent annotation. */
fun GameStateMessage.hasEnteredZoneThisTurn(instanceId: Int): Boolean =
    persistentAnnotationsList.any {
        AnnotationType.EnteredZoneThisTurn in it.typeList &&
            instanceId in it.affectedIdsList
    }

/** Find the first annotation with the given type in a list or iterable. */
fun Iterable<AnnotationInfo>.annotation(type: AnnotationType): AnnotationInfo =
    firstOrNull { type in it.typeList }
        ?: error("No $type annotation. Present: ${flatMap { it.typeList }.distinct()}")

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
 * Assert hard gsId chain facts across a sequence of GRE messages:
 * monotonicity, uniqueness, and no self-reference.
 *
 * @param messages the message sequence to validate
 * @param priorGsIds gsIds from messages sent before this sequence
 * @param context label for assertion messages
 */
fun assertGsIdChain(
    messages: List<GREToClientMessage>,
    priorGsIds: Set<Int> = emptySet(),
    context: String = "",
) {
    val suffix = if (context.isNotEmpty()) " ($context)" else ""
    val violations = InvariantChecker.validateGsIdChain(messages, priorGsIds)
    withClue("gsId chain violations$suffix: ${violations.joinToString { it.message }}") {
        violations.shouldBeEmpty()
    }
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
    val affectorId: Int = 0,
)

/** Find the ZoneTransfer annotation for a given instanceId. */
fun GameStateMessage.findZoneTransfer(instanceId: Int): ZoneTransferInfo? {
    val ann = annotationAffecting(AnnotationType.ZoneTransfer_af5a, instanceId) ?: return null
    return ZoneTransferInfo(
        category = ann.detail("category")?.getValueString(0).orEmpty(),
        zoneSrc = ann.detail("zone_src")?.getValueInt32(0) ?: -1,
        zoneDest = ann.detail("zone_dest")?.getValueInt32(0) ?: -1,
        affectorId = ann.affectorId,
    )
}

// ----- Tier 2: Limbo assertions -----

/** Assert that the GSM's Limbo zone contains the given instanceId in objectInstanceIds. */
fun assertLimboContains(
    gsm: GameStateMessage,
    instanceId: Int,
) {
    val limbo =
        checkNotNull(gsm.zonesList.firstOrNull { it.type == ZoneType.Limbo }) {
            "GSM should have Limbo zone"
        }
    limbo.objectInstanceIdsList shouldContain instanceId
}
