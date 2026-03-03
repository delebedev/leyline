package leyline.bridge

import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

/**
 * Tracks client auto-pass settings from [SettingsMessage].
 *
 * Updated on each `SetSettingsReq`. Thread-safe: accessed from session thread
 * (MatchSession.onSettings) and read from AutoPassEngine on the same thread.
 */
class ClientAutoPassState {
    var autoPassOption: AutoPassOption = AutoPassOption.None_a465
        private set
    var stackAutoPassOption: AutoPassOption = AutoPassOption.None_a465
        private set

    fun update(settings: SettingsMessage) {
        if (settings.autoPassOption != AutoPassOption.None_a465) {
            autoPassOption = settings.autoPassOption
        }
        if (settings.stackAutoPassOption != AutoPassOption.None_a465) {
            stackAutoPassOption = settings.stackAutoPassOption
        }
    }

    /** Should we auto-pass based on the client's current autoPassOption? */
    fun shouldAutoPass(): Boolean = when (autoPassOption) {
        AutoPassOption.ResolveAll,
        AutoPassOption.ResolveMyStackEffects,
        -> true
        else -> false
    }
}
