package leyline.debug

import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Structured client proto message logger. One line per inbound/outbound message.
 *
 * Logger: `forge.web.arenatap` — set to WARN in logback.xml to silence.
 * Mirrors [forge.web.WsTap] for the client protocol.
 */
object Tap {
    private val log = LoggerFactory.getLogger("forge.web.arenatap")

    // --- Inbound (client → server) ---

    fun inbound(type: ClientToMatchServiceMessageType, requestId: Int) {
        if (!log.isDebugEnabled) return
        log.debug("[Client←] {}", type.name.removeSuffix("_f487"))
    }

    fun inboundGRE(type: ClientMessageType, seatId: Int, gsId: Int) {
        if (!log.isDebugEnabled) return
        log.debug("[Client←] GRE {} seat={} gsId={}", type.name.removeSuffix("_097b"), seatId, gsId)
    }

    fun inboundAction(action: Action) {
        if (!log.isDebugEnabled) return
        val type = action.actionType.name.removeSuffix("_add3")
        if (action.instanceId != 0) {
            log.debug("[Client←] action {} instanceId={} grpId={}", type, action.instanceId, action.grpId)
        } else {
            log.debug("[Client←] action {}", type)
        }
    }

    // --- Outbound (server → client) ---

    fun outboundState(gs: GameStateMessage) {
        if (!log.isDebugEnabled) return
        val ti = gs.turnInfo
        log.debug(
            "[Client→] state gsId={} type={} phase={} turn={} active={} priority={} zones={} objects={}",
            gs.gameStateId, gs.type, ti.phase.name.removeSuffix("_a549"),
            ti.turnNumber, ti.activePlayer, ti.priorityPlayer,
            gs.zonesCount, gs.gameObjectsCount,
        )
    }

    fun outboundActions(req: ActionsAvailableReq) {
        if (!log.isDebugEnabled) return
        val counts = req.actionsList.groupBy { it.actionType }
            .map { (t, v) -> "${t.name.removeSuffix("_add3")}=${v.size}" }
            .joinToString(" ")
        log.debug("[Client→] actions {}", counts)
    }

    fun outboundTemplate(label: String) {
        if (!log.isDebugEnabled) return
        log.debug("[Client→] template {}", label)
    }

    fun actionResult(actionType: ActionType, instanceId: Int, forgeCardId: Int?, success: Boolean) {
        if (!log.isDebugEnabled) return
        val type = actionType.name.removeSuffix("_add3")
        if (forgeCardId != null) {
            log.debug("[Client⚡] {} instanceId={}→forgeId={} ok={}", type, instanceId, forgeCardId, success)
        } else {
            log.debug("[Client⚡] {} instanceId={} unmapped", type, instanceId)
        }
    }
}
