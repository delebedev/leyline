package leyline.headless

import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PlayerInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo

/** Client-side state derived only from emitted match-service messages. */
class HeadlessClient private constructor() {
    private val objectsById = mutableMapOf<Int, GameObjectInfo>()
    private val zonesById = mutableMapOf<Int, ZoneInfo>()
    private val playersBySeat = mutableMapOf<Int, PlayerInfo>()
    private val observedGre = mutableListOf<GREToClientMessage>()
    private val observedService = mutableListOf<MatchServiceToClientMessage>()

    var pendingActions: GREToClientMessage? = null
        private set

    val messages: List<GREToClientMessage> get() = observedGre.toList()
    val serviceMessages: List<MatchServiceToClientMessage> get() = observedService.toList()

    fun life(seatId: Int): Int? = playersBySeat[seatId]?.lifeTotal

    fun zone(zoneId: Int): ZoneInfo? = zonesById[zoneId]

    fun objectsInZone(zoneId: Int): List<GameObjectInfo> =
        zonesById[zoneId]
            ?.objectInstanceIdsList
            .orEmpty()
            .mapNotNull(objectsById::get)

    internal fun submitted() {
        pendingActions = null
    }

    internal fun observe(messages: List<MatchServiceToClientMessage>) {
        observedService += messages
        messages
            .asSequence()
            .filter { it.hasGreToClientEvent() }
            .flatMap { it.greToClientEvent.greToClientMessagesList.asSequence() }
            .forEach(::observe)
    }

    private fun observe(message: GREToClientMessage) {
        observedGre += message
        if (message.hasGameStateMessage()) {
            val state = message.gameStateMessage
            if (state.type == GameStateType.Full) {
                objectsById.clear()
                zonesById.clear()
                playersBySeat.clear()
            }
            state.diffDeletedInstanceIdsList.forEach(objectsById::remove)
            state.gameObjectsList.forEach { objectsById[it.instanceId] = it }
            state.zonesList.forEach { zonesById[it.zoneId] = it }
            state.playersList.forEach { playersBySeat[it.systemSeatNumber] = it }
        }
        if (message.hasActionsAvailableReq()) pendingActions = message
    }

    internal companion object {
        fun create() = HeadlessClient()
    }
}
