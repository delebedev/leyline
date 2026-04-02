package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Action field conformance for non-land, non-mana action types.
 *
 * Land/mana action fields (Play, ActivateMana, Cast+autoTap) are in [LandManaTest].
 */
class ActionFieldConformanceTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Activate action fields — non-mana activated ability") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                // Gingerbrute: {1}: can't be blocked except by haste creatures
                base.addCard("Forest", human, ZoneType.Battlefield)
                base.addCard("Gingerbrute", human, ZoneType.Battlefield)
            }

            val actions = ActionMapper.buildActions(game, 1, b)
            val activateActions = actions.actionsList.filter { it.actionType == ActionType.Activate_add3 }
            activateActions.shouldNotBeEmpty()

            assertSoftly {
                for (a in activateActions) {
                    a.shouldStop.shouldBeTrue()
                    a.instanceId shouldNotBe 0
                    a.grpId shouldNotBe 0
                    a.facetId shouldBe a.instanceId
                }
            }
        }
    })
