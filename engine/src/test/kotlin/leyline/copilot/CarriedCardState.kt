package leyline.copilot

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.snapshot.CombatRole
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

internal const val ROUND_TRIP_SEAT = 1

/**
 * A board named for the shape it holds. Puzzle ids are local cross-references;
 * [reach] plays any step the puzzle format cannot express. Every board states
 * both life totals because a session puzzle without them ends before its first
 * priority window.
 */
internal class RoundTripBoard(
    val name: String,
    val puzzle: String,
    val reach: suspend MatchFlowHarness.() -> Unit = {},
)

/**
 * Projects [sourceBridge]'s game for [ROUND_TRIP_SEAT], hydrates the projection,
 * and asserts the hydrated game carries the same per-card state.
 */
internal fun assertRoundTripPreservesCarriedState(sourceBridge: GameBridge) {
    val source = carriedCardState(sourceBridge)
    val gsm =
        StateMapper
            .buildFromSnapshot(source.snapshot, 0, "roundtrip", sourceBridge, viewingSeatId = ROUND_TRIP_SEAT)
            .gsm
    val hydrated =
        SnapshotHydration.hydrateWithReport(
            gsm = gsm,
            consultSeat = ROUND_TRIP_SEAT,
            cardRepository = sourceBridge.cardRepository,
        )
    try {
        source.cards.keys.shouldNotBeEmpty()
        carriedCardState(hydrated.bridge).cards shouldBe source.cards
    } finally {
        hydrated.bridge.teardownResources()
    }
}

/**
 * Card state that hydration claims to carry, keyed by instance id. Libraries,
 * the opponent's hand and the stack are excluded because hydration replaces
 * or omits them by design.
 */
internal data class CarriedCardState(
    val snapshot: GsmSnapshot,
    val cards: Map<Int, CarriedCard>,
)

internal data class CarriedCard(
    val zone: ZoneType,
    val name: String,
    val grpId: Int,
    val controller: Int,
    val isToken: Boolean,
    val tapped: Boolean,
    val hasSickness: Boolean,
    val damage: Int,
    val netPower: Int?,
    val netToughness: Int?,
    val cardTypes: List<Int>,
    val subtypes: List<Int>,
    val counters: Map<String, Int>,
    val attachedToInstanceId: Int?,
    val combatRole: CombatRole?,
    val hasManaAbilities: Boolean,
    val hasNonManaActivatedAbilities: Boolean,
)

internal fun carriedCardState(bridge: GameBridge): CarriedCardState {
    val game = bridge.getGame().shouldNotBeNull()
    val snapshot = GsmSnapshot.capture(game, bridge, "roundtrip", 0)
    val cards =
        snapshot.zones.values
            .filter { zone ->
                zone.type in setOf(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile) ||
                    (zone.type == ZoneType.Hand && zone.owner?.value == ROUND_TRIP_SEAT)
            }.flatMap { zone -> zone.contents.map { zone.type to it } }
            .associate { (zoneType, forgeCardId) ->
                val card = snapshot.objects.getValue(forgeCardId)
                val instanceId = bridge.peekInstanceId(forgeCardId).shouldNotBeNull().value
                instanceId to
                    CarriedCard(
                        zone = zoneType,
                        name = card.name,
                        grpId = card.grpId,
                        controller = card.controller.value,
                        isToken = card.isToken,
                        tapped = card.tapped,
                        hasSickness = card.hasSickness,
                        damage = card.damage,
                        netPower = card.netPower,
                        netToughness = card.netToughness,
                        cardTypes = card.liveCardTypeNumbers.sorted(),
                        subtypes = card.liveSubtypeNumbers.sorted(),
                        counters =
                            bridge
                                .findCard(forgeCardId)
                                ?.counters
                                ?.entrySet()
                                .orEmpty()
                                .associate { it.element.name to it.count },
                        attachedToInstanceId = card.attachedToInstanceId,
                        combatRole = card.combatRole,
                        hasManaAbilities = card.hasManaAbilities,
                        hasNonManaActivatedAbilities = card.hasNonManaActivatedAbilities,
                    )
            }
    return CarriedCardState(snapshot, cards)
}
