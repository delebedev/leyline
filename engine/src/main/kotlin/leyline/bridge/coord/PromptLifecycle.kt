package leyline.bridge.coord

import leyline.game.PendingPromptCut

/** Common lifecycle owned by the match prompt-runtime inventory. */
internal interface PromptLifecycle {
    fun current(): Any?

    fun terminate(cause: Throwable)

    fun reset()
}

/** A prompt lifecycle that can retain an exact cut when delivery terminalizes the match. */
internal interface PromptTerminalCutOwner : PromptLifecycle {
    val terminalPriority: PromptTerminalPriority

    fun claimTerminalCutLocked(): PendingPromptCut<*>?
}

/** Inner prompt cuts take precedence over the outer windows that led to them. */
internal enum class PromptTerminalPriority {
    OneShotPayCosts,
    ManaSourcePayment,
    Search,
    Distribution,
    Order,
    Grouping,
    CardSelect,
    StaticChoice,
    ModalChoice,
    RevealChoice,
    Blocking,
}
