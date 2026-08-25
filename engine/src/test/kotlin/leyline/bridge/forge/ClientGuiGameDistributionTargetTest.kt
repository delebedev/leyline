package leyline.bridge.forge

import forge.game.card.CardView
import forge.game.player.PlayerView
import forge.trackable.Tracker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

class ClientGuiGameDistributionTargetTest :
    FunSpec({
        tags(UnitTag)

        test("player and card views with the same id remain distinct distribution targets") {
            val gui =
                ClientGuiGame(
                    InteractivePromptBridge(timeoutMs = 0),
                    playerViewSeatOf = { SeatId(it.id).value },
                )
            val tracker = Tracker()

            gui.distributionTargetRef(PlayerView(1, tracker)) shouldBe DistributionTargetRef.Player(SeatId(1))
            gui.distributionTargetRef(CardView(1, tracker)) shouldBe DistributionTargetRef.Card(ForgeCardId(1))
        }
    })
