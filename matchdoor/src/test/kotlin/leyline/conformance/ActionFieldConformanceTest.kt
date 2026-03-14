package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Action field conformance: verifies proto fields on actions match
 * invariants observed in real client goldens.
 *
 * Uses [startWithBoard] (~0.01s) with [assertSoftly] — all field
 * checks report independently on failure.
 */
class ActionFieldConformanceTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("action fields before land play — Play and Pass shapes") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Hand)
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
            }

            val actions = ActionMapper.buildActions(game, 1, b)

            assertSoftly {
                // Play actions: shouldStop=true, no abilityGrpId, no manaCost
                val playActions = actions.actionsList.filter { it.actionType == ActionType.Play_add3 }
                playActions.shouldNotBeEmpty()
                for (a in playActions) {
                    a.shouldStop.shouldBeTrue()
                    a.instanceId shouldNotBe 0
                    a.grpId shouldNotBe 0
                    a.facetId shouldBe a.instanceId
                    a.abilityGrpId shouldBe 0
                    a.manaCostCount shouldBe 0
                }

                // Pass action: only actionType, no other fields
                val passActions = actions.actionsList.filter { it.actionType == ActionType.Pass }
                passActions.size shouldBe 1
                val pass = passActions[0]
                pass.instanceId shouldBe 0
                pass.grpId shouldBe 0
                pass.shouldStop.shouldBeFalse()
            }
        }

        test("action fields after land play — ActivateMana, Cast, Activate, AutoTap, bundle") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                // Forest on BF for mana, Forest in hand to play, Grizzly Bears to cast
                base.addCard("Forest", human, ZoneType.Battlefield)
                base.addCard("Forest", human, ZoneType.Hand)
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
            }

            val player = b.getPlayer(leyline.bridge.SeatId(1))!!
            val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(land, null, AbilityKey.newMap())
            }

            val actions = ActionMapper.buildActions(game, 1, b)

            assertSoftly {
                // ActivateMana: present for untapped mana source
                val manaActions = actions.actionsList.filter { it.actionType == ActionType.ActivateMana }
                manaActions.shouldNotBeEmpty()
                for (a in manaActions) {
                    a.instanceId shouldNotBe 0
                    a.grpId shouldNotBe 0
                    a.facetId shouldBe a.instanceId
                    a.shouldStop.shouldBeFalse()
                    a.isBatchable.shouldBeTrue()
                    a.manaPaymentOptionsCount shouldBeGreaterThan 0
                    a.manaSelectionsCount shouldBeGreaterThan 0
                }

                // Cast: shouldStop=true, manaCost, autoTapSolution
                val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
                castActions.shouldNotBeEmpty()
                for (a in castActions) {
                    a.shouldStop.shouldBeTrue()
                    a.instanceId shouldNotBe 0
                    a.grpId shouldNotBe 0
                    a.facetId shouldBe a.instanceId
                    a.abilityGrpId shouldBe 0
                    a.manaCostCount shouldBeGreaterThan 0
                    a.hasAutoTapSolution().shouldBeTrue()
                    a.autoTapSolution.autoTapActionsCount shouldBeGreaterThan 0
                }

                // Activate (if present): shouldStop=true, instanceId + grpId
                val activateActions = actions.actionsList.filter { it.actionType == ActionType.Activate_add3 }
                for (a in activateActions) {
                    a.shouldStop.shouldBeTrue()
                    a.instanceId shouldNotBe 0
                    a.grpId shouldNotBe 0
                    a.facetId shouldBe a.instanceId
                }

                // AutoTapSolution: maps mana sources to spell cost
                val castWithTap = castActions.filter { it.hasAutoTapSolution() }
                for (a in castWithTap) {
                    for (tap in a.autoTapSolution.autoTapActionsList) {
                        tap.instanceId shouldNotBe 0
                        tap.hasManaPaymentOption().shouldBeTrue()
                        val mana = tap.manaPaymentOption.manaList
                        mana.shouldNotBeEmpty()
                        for (m in mana) {
                            m.srcInstanceId shouldNotBe 0
                            m.color shouldNotBe ManaColor.None_afc9
                            m.count shouldBeGreaterThan 0
                        }
                    }
                }

                // ActionsAvailableReq bundle matches
                val result = base.postAction(game, b, counter)
                val aar = result.aarOrNull.shouldNotBeNull()
                val typeSet = aar.actionsList.map { it.actionType.name }.toSet()
                ("Cast" in typeSet).shouldBeTrue()
                ("Pass" in typeSet).shouldBeTrue()
                ("ActivateMana" in typeSet).shouldBeTrue()

                val gsm = result.gsmOrNull.shouldNotBeNull()
                gsm.pendingMessageCount shouldBe 1

                // GSM embedded actions are stripped (no grpId, facetId, autoTap)
                for (actionInfo in gsm.actionsList) {
                    val a = actionInfo.action
                    when (a.actionType) {
                        ActionType.Cast -> {
                            a.instanceId shouldNotBe 0
                            a.grpId shouldBe 0
                            a.facetId shouldBe 0
                            a.shouldStop.shouldBeFalse()
                            a.hasAutoTapSolution().shouldBeFalse()
                        }
                        ActionType.ActivateMana -> {
                            a.instanceId shouldNotBe 0
                            a.grpId shouldBe 0
                            a.facetId shouldBe 0
                        }
                        ActionType.Pass, ActionType.FloatMana -> {
                            a.instanceId shouldBe 0
                        }
                        else -> {}
                    }
                }
            }
        }
    })
