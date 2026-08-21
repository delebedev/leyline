package leyline.tooling.headless

import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.types.SeatId
import leyline.game.bundle.PROMPT_GRE_TYPES
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Failure diagnostics for a session-tier spec.
 *
 * A failing session assertion otherwise reports `expected true, got false`
 * about a whole game engine. This renders what a reader of a CI log needs to
 * tell "the spell never resolved" from "the card was never in hand": turn and
 * phase, whose priority it is, both seats' zones with instance ids, the prompt
 * the engine is waiting on, and the tail of the emitted message stream.
 *
 * Every section is guarded independently. A game torn down mid-failure must
 * still print whatever remains readable, and diagnostics must never replace
 * the original failure with one of their own.
 */
private const val DEFAULT_MESSAGE_TAIL = 15

private const val STACK_DESCRIPTION_LIMIT = 120

private val DIAGNOSTIC_ZONES =
    listOf(
        ZoneType.Battlefield,
        ZoneType.Hand,
        ZoneType.Graveyard,
        ZoneType.Exile,
        ZoneType.Stack,
        ZoneType.Command,
    )

/**
 * Render the harness's current state under [label] and print it.
 *
 * Returns the same text so a spec can assert on it.
 */
fun MatchFlowHarness.dumpDiagnostics(
    label: String,
    messageTail: Int = DEFAULT_MESSAGE_TAIL,
): String {
    val text = renderDiagnostics(label, messageTail)
    println(text)
    return text
}

/** [dumpDiagnostics] without the print — for tests that assert on the rendering. */
fun MatchFlowHarness.renderDiagnostics(
    label: String,
    messageTail: Int = DEFAULT_MESSAGE_TAIL,
): String =
    buildString {
        appendLine("┌─ session diagnostics: $label")
        section("game") { describeGame(it) }
        section("seats") { describeSeats(it) }
        section("waiting on") { describePending(it) }
        section("last $messageTail messages") { describeMessageTail(it, messageTail) }
        append("└─ end diagnostics: $label")
    }

private inline fun StringBuilder.section(
    title: String,
    render: (StringBuilder) -> Unit,
) {
    appendLine("│ $title")
    val body =
        runCatching { buildString { render(this) } }
            .getOrElse { "unreadable (${it::class.simpleName}: ${it.message})" }
    body
        .trimEnd()
        .ifBlank { "(none)" }
        .lineSequence()
        .forEach { appendLine("│   $it") }
}

private fun MatchFlowHarness.describeGame(out: StringBuilder) {
    val game = currentGame()
    if (game == null) {
        out.append("game not initialised")
        return
    }
    val phases = game.phaseHandler
    out.appendLine("turn ${phases?.turn ?: "?"} | phase ${phases?.phase?.name ?: "?"} | gameOver ${game.isGameOver}")
    out.appendLine("activePlayer ${label(phases?.playerTurn)} | priorityPlayer ${label(phases?.priorityPlayer)}")
    val stack = game.stack
    if (stack == null || stack.isEmpty) {
        out.appendLine("stack: empty")
    } else {
        out.appendLine("stack (${stack.size()}):")
        stack.forEach { item ->
            val description = item.stackDescription.orEmpty().take(STACK_DESCRIPTION_LIMIT)
            out.appendLine("  ${item.sourceCard?.name ?: "?"} — $description")
        }
    }
}

/**
 * The game the harness is driving. The bridge drops its reference once the
 * match closes, so fall back to a seat handle — a spec that fails right after
 * the game ends still needs its final board printed.
 */
private fun MatchFlowHarness.currentGame(): forge.game.Game? =
    runCatching { bridge.getGame() }.getOrNull()
        ?: runCatching { seatPlayerOrNull(SeatId(1))?.game }.getOrNull()

/** Puzzle-seeded players often have a blank name; fall back to the Forge id. */
private fun label(player: Player?): String = player?.name?.ifBlank { null } ?: player?.id?.let { "player#$it" } ?: "?"

private fun MatchFlowHarness.describeSeats(out: StringBuilder) {
    for (seat in listOf(SeatId(1), SeatId(2))) {
        val player = seatPlayerOrNull(seat) ?: continue
        val library = runCatching { player.getZone(ZoneType.Library)?.size() ?: 0 }.getOrDefault(0)
        out.appendLine("seat ${seat.value} ${label(player)}: ${player.life} life, $library in library")
        for (zone in DIAGNOSTIC_ZONES) {
            val cards =
                runCatching {
                    player
                        .getZone(zone)
                        ?.cards
                        ?.toList()
                        .orEmpty()
                }.getOrDefault(emptyList())
            if (cards.isEmpty()) continue
            out.appendLine("  $zone: ${cards.joinToString(", ") { describeCard(it, player) }}")
        }
    }
}

private fun MatchFlowHarness.describeCard(
    card: Card,
    owner: Player,
): String {
    val iid = runCatching { bridge.instanceId(card) }.getOrNull()
    val marks =
        buildList {
            if (card.isCreature) add("${card.netPower}/${card.netToughness}")
            if (card.isTapped) add("tapped")
            if (card.isCreature && card.isSick) add("summoningSick")
            val damage = runCatching { card.damage }.getOrDefault(0)
            if (damage > 0) add("damage=$damage")
            runCatching { card.counters }.getOrNull()?.entrySet()?.forEach { entry -> add("${entry.element}=${entry.count}") }
            val controller = runCatching { card.controller }.getOrNull()
            if (controller != null && controller != owner) add("controlledBy=${label(controller)}")
        }
    val suffix = if (marks.isEmpty()) "" else " [${marks.joinToString(" ")}]"
    return "${card.name}#${iid ?: "?"}$suffix"
}

private fun MatchFlowHarness.describePending(out: StringBuilder) {
    val pending = runCatching { bridge.actionBridge(SeatId(1)).getPending() }.getOrNull()
    if (pending == null) {
        out.appendLine("no open action window for seat 1")
    } else {
        val state = pending.state
        out.appendLine("action window ${state.kind} at turn ${state.turn} ${state.phase}")
        out.appendLine("activePlayerId ${state.activePlayerId} | priorityPlayerId ${state.priorityPlayerId}")
    }
    val prompt = allMessages.lastOrNull { it.type in PROMPT_GRE_TYPES }
    out.appendLine(if (prompt == null) "no prompt emitted yet" else "latest prompt ${describeMessage(prompt)}")
    val interaction = runCatching { bridge.cutCoordinator.currentBlockingInteraction() }.getOrNull()
    if (interaction != null) out.appendLine("blocking interaction ${interaction.interaction::class.simpleName}")
}

private fun MatchFlowHarness.describeMessageTail(
    out: StringBuilder,
    tail: Int,
) {
    val messages = allMessages.takeLast(tail)
    if (messages.isEmpty()) {
        out.append("no messages emitted")
        return
    }
    val firstIndex = allMessages.size - messages.size
    messages.forEachIndexed { index, message ->
        out.appendLine("${firstIndex + index}: ${describeMessage(message)}")
    }
}

private fun describeMessage(message: GREToClientMessage): String {
    val head = "${message.type} msgId=${message.msgId} gsId=${message.gameStateId}"
    val detail = describeMessagePayload(message)
    return if (detail.isBlank()) head else "$head — $detail"
}

private fun describeMessagePayload(message: GREToClientMessage): String =
    when {
        message.hasGameStateMessage() -> {
            val gsm = message.gameStateMessage
            val annotations =
                gsm.annotationsList
                    .flatMap { it.typeList }
                    .distinct()
                    .joinToString(",") { it.name }
                    .ifBlank { "none" }
            "${gsm.type} update=${gsm.update} annotations=$annotations"
        }
        message.hasActionsAvailableReq() ->
            "actions=" +
                message.actionsAvailableReq.actionsList
                    .joinToString(",") { it.actionType.name }
                    .ifBlank { "none" }
        message.hasSelectTargetsReq() ->
            "targetSlots=${message.selectTargetsReq.targetsCount} " +
                "sourceId=${message.selectTargetsReq.sourceId}"
        message.hasSelectNReq() ->
            "min=${message.selectNReq.minSel} max=${message.selectNReq.maxSel} " +
                "context=${message.selectNReq.context} ids=${message.selectNReq.idsList}"
        message.hasGroupReq() -> "context=${message.groupReq.context} ids=${message.groupReq.instanceIdsList}"
        message.hasOrderReq() -> "context=${message.orderReq.orderingContext} ids=${message.orderReq.idsList}"
        message.hasCastingTimeOptionsReq() -> "options=${message.castingTimeOptionsReq.castingTimeOptionReqCount}"
        message.hasDeclareAttackersReq() -> "declareAttackers"
        message.hasDeclareBlockersReq() -> "declareBlockers"
        message.hasOptionalActionMessage() -> "optionalTypes=${message.optionalActionMessage.optionalActionTypesList}"
        message.hasNumericInputReq() -> "numericInput"
        message.hasIllegalRequestMessage() -> "illegal=${message.illegalRequestMessage.reason}"
        else -> ""
    }
