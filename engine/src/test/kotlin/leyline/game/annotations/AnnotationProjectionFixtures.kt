package leyline.game.annotations

import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.GameBridge

internal fun annotationContext(
    bridge: GameBridge,
    snapshot: GsmSnapshot = GsmSnapshot.forTest(),
    events: List<GameEvent> = emptyList(),
    transferResult: TransferResult? = null,
): AnnotationContext {
    val editor = bridge.projectionStateSnapshot().editor()
    return AnnotationContext(
        editor = editor,
        environment = bridge.stateProjectionEnvironment,
        snap = snapshot,
        frameIds = FrameIdResolver(editor.identities),
        events = events,
        abilityExhaustionFacts = AbilityExhaustionFacts(),
        transferResult = transferResult,
    )
}
