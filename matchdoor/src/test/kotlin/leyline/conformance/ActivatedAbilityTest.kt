package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Activated ability subsystem tests (non-mana Activate_add3 actions).
 *
 * For mana abilities (ActivateMana), see LandManaTest.
 * Session-tier tests (Jade Mage, Fireslinger, Channel) to be added
 * when InteractionTest base class is built.
 */
class ActivatedAbilityTest : SubsystemTest({

    test("Activate action fields — shouldStop, instanceId, grpId, facetId") {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Battlefield)
            addCard("Gingerbrute", human, ZoneType.Battlefield)
        }

        val activate = ActionMapper.buildActions(game, 1, b).ofType(ActionType.Activate_add3)
        activate.shouldHaveSize(2) // {1}: evasion + {2},{T},Sac: gain 3 life

        assertSoftly {
            for (a in activate) {
                a.shouldStop shouldBe true
                a.instanceId shouldNotBe 0
                a.grpId shouldNotBe 0
                a.facetId shouldBe a.instanceId
            }
        }
    }
})
