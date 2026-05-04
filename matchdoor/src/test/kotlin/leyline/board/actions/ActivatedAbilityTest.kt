package leyline.board.actions

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest
import leyline.testkit.ofType
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Activated ability subsystem tests (non-mana Activate_add3 actions).
 *
 * For mana abilities (ActivateMana), see LandManaTest.
 * Session-tier tests (Jade Mage, Fireslinger, Channel) to be added
 * when SessionTest base class is built.
 */
class ActivatedAbilityTest :
    BoardTest({

        test("Activate action fields — shouldStop, instanceId, grpId, facetId") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Gingerbrute", human, ZoneType.Battlefield)
                }

            val activate = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b).ofType(ActionType.Activate_add3)
            assertSoftly {
                activate.shouldHaveSize(2) // {1}: evasion + {2},{T},Sac: gain 3 life
                for (a in activate) {
                    a.shouldStop shouldBe true
                    a.instanceId shouldNotBe 0
                    a.grpId shouldNotBe 0
                    a.facetId shouldBe a.instanceId
                }
            }
        }
    })
