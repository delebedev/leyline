package forge.nexus.conformance

import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.Target as ProtoTarget

/**
 * Kotlin DSL for building proto messages in tests.
 *
 * **Inbound** (client → server): [performAction], [declareAttackersResp], etc.
 * **Outbound fixtures** (server → client): [greMessage], [actionsMessage] — for
 * unit tests that hand-build GRE messages without an engine.
 *
 * Production outbound messages use [forge.nexus.game.BundleBuilder].
 *
 * ## Usage
 *
 * ```kotlin
 * val msg = performAction {
 *     actionType = ActionType.Play_add3
 *     instanceId = 123
 *     grpId = 456
 * }
 *
 * val attack = declareAttackersResp(attackers = listOf(iid1, iid2))
 * val done   = submitAttackersReq()
 * val target = selectTargetsResp(targets = listOf(iid))
 * ```
 *
 * ## Extending with a new message type
 *
 * 1. Identify the [ClientMessageType] enum value for the outer message.
 * 2. Identify the payload builder (e.g. `FooResp.newBuilder()`).
 * 3. Add a top-level function here following the pattern:
 *    ```kotlin
 *    fun fooResp(
 *        // variable fields as params with defaults
 *        field: Type = default,
 *        seatId: Int = 1,
 *    ): ClientToGREMessage = clientMessage(ClientMessageType.FooResp_097b) {
 *        setFooResp(FooResp.newBuilder().apply {
 *            // build payload
 *        })
 *    }
 *    ```
 * 4. Keep the function name matching the proto payload (camelCase).
 *    Use Kotlin named arguments and defaults — avoid overloads.
 */

// ---------------------------------------------------------------------------
// Core builder
// ---------------------------------------------------------------------------

/** Base builder — sets type and applies [block] to the message builder. */
inline fun clientMessage(
    type: ClientMessageType,
    block: ClientToGREMessage.Builder.() -> Unit = {},
): ClientToGREMessage = ClientToGREMessage.newBuilder()
    .setType(type)
    .apply(block)
    .build()

// ---------------------------------------------------------------------------
// PerformActionResp — Play, Cast, Pass, Activate
// ---------------------------------------------------------------------------

/**
 * Build a [PerformActionResp] wrapping a single [Action].
 *
 * ```kotlin
 * performAction {
 *     actionType = ActionType.Cast
 *     instanceId = 42
 *     grpId = 12345
 * }
 * ```
 */
fun performAction(block: Action.Builder.() -> Unit): ClientToGREMessage =
    clientMessage(ClientMessageType.PerformActionResp_097b) {
        setPerformActionResp(
            PerformActionResp.newBuilder()
                .addActions(Action.newBuilder().apply(block)),
        )
    }

// ---------------------------------------------------------------------------
// Attackers — two-phase protocol
// ---------------------------------------------------------------------------

/**
 * [DeclareAttackersResp] — iterative attacker selection or "Attack All".
 *
 * ```kotlin
 * declareAttackersResp(attackers = listOf(iid1, iid2))          // select specific
 * declareAttackersResp(autoDeclare = true, autoDeclareTarget = 2) // Attack All → seat 2
 * ```
 */
fun declareAttackersResp(
    attackers: List<Int> = emptyList(),
    autoDeclare: Boolean = false,
    autoDeclareTarget: Int? = null,
): ClientToGREMessage = clientMessage(ClientMessageType.DeclareAttackersResp_097b) {
    setDeclareAttackersResp(
        DeclareAttackersResp.newBuilder().apply {
            if (autoDeclare) {
                setAutoDeclare(true)
                autoDeclareTarget?.let {
                    setAutoDeclareDamageRecipient(
                        DamageRecipient.newBuilder()
                            .setType(DamageRecType.Player_a0e5)
                            .setPlayerSystemSeatId(it),
                    )
                }
            }
            for (iid in attackers) {
                addSelectedAttackers(Attacker.newBuilder().setAttackerInstanceId(iid))
            }
        },
    )
}

/** [SubmitAttackersReq] — type-only "Done" button, no payload. */
fun submitAttackersReq(seatId: Int = 1): ClientToGREMessage =
    clientMessage(ClientMessageType.SubmitAttackersReq) {
        setSystemSeatId(seatId)
    }

// ---------------------------------------------------------------------------
// Blockers — two-phase protocol
// ---------------------------------------------------------------------------

/**
 * [DeclareBlockersResp] — iterative blocker assignments.
 *
 * @param assignments blockerInstanceId → attackerInstanceId(s) it blocks
 */
fun declareBlockersResp(
    assignments: Map<Int, Int> = emptyMap(),
): ClientToGREMessage = clientMessage(ClientMessageType.DeclareBlockersResp_097b) {
    setDeclareBlockersResp(
        DeclareBlockersResp.newBuilder().apply {
            for ((blockerIid, attackerIid) in assignments) {
                addSelectedBlockers(
                    Blocker.newBuilder()
                        .setBlockerInstanceId(blockerIid)
                        .addSelectedAttackerInstanceIds(attackerIid),
                )
            }
        },
    )
}

/** [SubmitBlockersReq] — type-only "Done" button. */
fun submitBlockersReq(seatId: Int = 1): ClientToGREMessage =
    clientMessage(ClientMessageType.SubmitBlockersReq) {
        setSystemSeatId(seatId)
    }

// ---------------------------------------------------------------------------
// Targeting
// ---------------------------------------------------------------------------

/**
 * [SelectTargetsResp] — select targets by instanceId.
 *
 * All targets get [SelectAction.Select_a1ad] (the standard "select" action).
 */
fun selectTargetsResp(targets: List<Int>): ClientToGREMessage =
    clientMessage(ClientMessageType.SelectTargetsResp_097b) {
        setSelectTargetsResp(
            SelectTargetsResp.newBuilder().setTarget(
                TargetSelection.newBuilder().apply {
                    for (iid in targets) {
                        addTargets(
                            ProtoTarget.newBuilder()
                                .setTargetInstanceId(iid)
                                .setLegalAction(SelectAction.Select_a1ad),
                        )
                    }
                },
            ),
        )
    }

/**
 * [CancelActionReq] — cancel the current pending action (e.g. back out of targeting).
 *
 * Empty message — the type field (`CancelActionReq_097b = 5`) is the sole signal.
 */
fun cancelActionReq(): ClientToGREMessage =
    clientMessage(ClientMessageType.CancelActionReq_097b) {
        setCancelActionReq(CancelActionReq.newBuilder())
    }

// ===========================================================================
// Outbound GRE fixtures — for unit tests that hand-build server messages
// ===========================================================================

/**
 * Build a [GREToClientMessage] wrapping a [GameStateMessage].
 *
 * ```kotlin
 * val gre = greMessage(msgId = 1, gsId = 5) {
 *     setType(GameStateType.Full)
 *     addGameObjects(...)
 * }
 * ```
 */
fun greMessage(
    msgId: Int = 1,
    gsId: Int = 0,
    seatIds: List<Int> = listOf(1),
    gsm: GameStateMessage.Builder.() -> Unit = {},
): GREToClientMessage {
    val gs = GameStateMessage.newBuilder().setGameStateId(gsId).apply(gsm).build()
    return GREToClientMessage.newBuilder()
        .setType(GREMessageType.GameStateMessage_695e)
        .setMsgId(msgId)
        .setGameStateId(gsId)
        .addAllSystemSeatIds(seatIds)
        .setGameStateMessage(gs)
        .build()
}

/** Wrap a pre-built [GameStateMessage] in a [GREToClientMessage]. */
fun greMessage(
    msgId: Int = 1,
    gsm: GameStateMessage,
    seatIds: List<Int> = listOf(1),
): GREToClientMessage = GREToClientMessage.newBuilder()
    .setType(GREMessageType.GameStateMessage_695e)
    .setMsgId(msgId)
    .setGameStateId(gsm.gameStateId)
    .addAllSystemSeatIds(seatIds)
    .setGameStateMessage(gsm)
    .build()

/** Build a [GREToClientMessage] wrapping an [ActionsAvailableReq]. */
fun actionsMessage(
    msgId: Int = 1,
    gsId: Int = 0,
    seatIds: List<Int> = listOf(1),
    actions: ActionsAvailableReq.Builder.() -> Unit,
): GREToClientMessage = GREToClientMessage.newBuilder()
    .setType(GREMessageType.ActionsAvailableReq_695e)
    .setMsgId(msgId)
    .setGameStateId(gsId)
    .addAllSystemSeatIds(seatIds)
    .setActionsAvailableReq(ActionsAvailableReq.newBuilder().apply(actions))
    .build()
