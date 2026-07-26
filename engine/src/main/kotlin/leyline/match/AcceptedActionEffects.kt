package leyline.match

import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.ForgeCardId
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority

/** Session state committed only when the originating priority action is accepted. */
internal data class AcceptedActionEffects(
    val autoPassPriority: AutoPassPriority,
    val selectedSpellCardId: ForgeCardId? = null,
    val selectedSpellGrpId: Int? = null,
) {
    fun apply(
        autoPassState: ClientAutoPassState,
        bridge: GameBridge,
    ) {
        if (autoPassPriority != AutoPassPriority.None_a099) {
            autoPassState.updateAutoPassPriority(autoPassPriority)
        }
        selectedSpellCardId?.let { bridge.setSelectedSpellGrpId(it, selectedSpellGrpId) }
    }
}
