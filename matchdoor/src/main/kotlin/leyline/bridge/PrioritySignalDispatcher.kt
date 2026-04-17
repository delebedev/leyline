package leyline.bridge

import com.google.common.eventbus.Subscribe
import forge.game.event.GameEventPlayerPriority

/**
 * Fires the [PrioritySignal] on Forge priority transitions.
 *
 * [GameBridge.awaitPriorityWithTimeout] primarily wakes via bridge pending-work
 * callbacks, but during an AI turn with no human interaction the waiter can
 * sit idle until the full `aiTurnWaitMs` deadline. Hooking Forge's priority
 * event is a cheap belt-and-braces: any priority change that does happen
 * signals the waiter immediately. Spurious signals are harmless — waiters
 * re-check their exit conditions before sleeping again.
 *
 * Note: this does NOT fire during CLEANUP or other phases where Forge skips
 * priority entirely. For those, `aiTurnWaitMs` is the correct knob.
 */
class PrioritySignalDispatcher(private val signal: PrioritySignal) {
    @Subscribe
    @Suppress("UNUSED_PARAMETER")
    fun onPlayerPriority(ev: GameEventPlayerPriority) {
        signal.signal("forgeEvent.playerPriority")
    }
}
