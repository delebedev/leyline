package leyline.bridge

import forge.game.phase.PhaseType
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

/**
 * Tracks client auto-pass settings from [SettingsMessage] and [PerformActionResp].
 *
 * Updated on each `SetSettingsReq` and `PerformActionResp`. Thread-safe: accessed
 * from session thread (MatchSession) and read from AutoPassEngine on the same thread.
 */
class ClientAutoPassState {
    var autoPassOption: AutoPassOption = AutoPassOption.None_a465
        private set
    var stackAutoPassOption: AutoPassOption = AutoPassOption.None_a465
        private set

    /**
     * Full control flag from last PerformActionResp.autoPassPriority.
     * - [AutoPassPriority.No_a099] = full control ON (always return priority)
     * - [AutoPassPriority.Yes_a099] = auto-pass OK
     * - [AutoPassPriority.None_a099] = normal (no override)
     *
     * Persists until client sends a different value.
     */
    var autoPassPriority: AutoPassPriority = AutoPassPriority.None_a099
        private set

    fun update(settings: SettingsMessage) {
        if (settings.autoPassOption != AutoPassOption.None_a465) {
            autoPassOption = settings.autoPassOption
        }
        if (settings.stackAutoPassOption != AutoPassOption.None_a465) {
            stackAutoPassOption = settings.stackAutoPassOption
        }
    }

    /** Update autoPassPriority from PerformActionResp. */
    fun updateAutoPassPriority(priority: AutoPassPriority) {
        if (priority != AutoPassPriority.None_a099) {
            autoPassPriority = priority
        }
    }

    /** True when client has full control enabled (No_a099 = always return priority). */
    val isFullControl: Boolean
        get() = autoPassPriority == AutoPassPriority.No_a099

    /**
     * Opponent-turn stops explicitly set by the client via SetSettingsReq
     * with Opponents scope. Defaults empty — no opponent-turn stops unless
     * the client toggles them. Separate from PhaseStopProfile's AI_DEFAULTS
     * which are engine-internal.
     */
    private val opponentStops = mutableSetOf<PhaseType>()

    /** Update opponent-turn stops from parsed client settings. */
    fun setOpponentStop(phase: PhaseType, enabled: Boolean) {
        if (enabled) opponentStops.add(phase) else opponentStops.remove(phase)
    }

    /** Check if the client has set an opponent-turn stop for this phase. */
    fun hasOpponentStop(phase: PhaseType): Boolean = phase in opponentStops

    /** Should we auto-pass based on the client's current autoPassOption? */
    fun shouldAutoPass(): Boolean {
        // Full control overrides everything — never auto-pass
        if (isFullControl) return false
        return when (autoPassOption) {
            AutoPassOption.ResolveAll,
            AutoPassOption.ResolveMyStackEffects,
            -> true
            else -> false
        }
    }
}
