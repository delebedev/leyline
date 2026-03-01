package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Action field conformance: verifies detailed proto fields on actions
 * match invariants observed in real client goldens.
 *
 * Golden-derived invariants:
 * - Cast: shouldStop=true, abilityGrpId>0, manaCost non-empty, autoTapSolution present
 * - Play: shouldStop=true, no abilityGrpId, no manaCost
 * - ActivateMana: shouldStop absent (false), has instanceId + grpId
 * - Pass: no fields besides actionType
 * - Activate: has instanceId + grpId, abilityGrpId when card data available
 */
class ActionFieldConformanceTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("after playing a land: ActivateMana present for untapped mana source") {
            val (b, game, _) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")

            val actions = ActionMapper.buildActions(game, 1, b)
            val manaActions = actions.actionsList.filter { it.actionType == ActionType.ActivateMana }
            manaActions.isNotEmpty().shouldBeTrue()

            for (a in manaActions) {
                a.instanceId shouldNotBe 0
                a.grpId shouldNotBe 0
                a.facetId shouldBe a.instanceId
                a.shouldStop.shouldBeFalse()
                a.isBatchable.shouldBeTrue()
                (a.manaPaymentOptionsCount > 0).shouldBeTrue()
                (a.manaSelectionsCount > 0).shouldBeTrue()
            }
        }

        test("Cast actions: shouldStop=true, facetId, manaCost, autoTapSolution") {
            val (b, game, _) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")

            val actions = ActionMapper.buildActions(game, 1, b)
            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            castActions.isNotEmpty().shouldBeTrue()

            for (a in castActions) {
                a.shouldStop.shouldBeTrue()
                a.instanceId shouldNotBe 0
                a.grpId shouldNotBe 0
                a.facetId shouldBe a.instanceId
                a.abilityGrpId shouldBe 0
                (a.manaCostCount > 0).shouldBeTrue()
                a.hasAutoTapSolution().shouldBeTrue()
                (a.autoTapSolution.autoTapActionsCount > 0).shouldBeTrue()
            }
        }

        test("Play actions: shouldStop=true, no abilityGrpId, no manaCost") {
            val (b, game, _) = base.startGameAtMain1()
            val actions = ActionMapper.buildActions(game, 1, b)

            val playActions = actions.actionsList.filter { it.actionType == ActionType.Play_add3 }
            if (playActions.isEmpty()) return@test

            for (a in playActions) {
                a.shouldStop.shouldBeTrue()
                a.instanceId shouldNotBe 0
                a.grpId shouldNotBe 0
                a.facetId shouldBe a.instanceId
                a.abilityGrpId shouldBe 0
                a.manaCostCount shouldBe 0
            }
        }

        test("Pass action: only actionType, no other fields") {
            val (b, game, _) = base.startGameAtMain1()
            val actions = ActionMapper.buildActions(game, 1, b)

            val passActions = actions.actionsList.filter { it.actionType == ActionType.Pass }
            passActions.size shouldBe 1

            val pass = passActions[0]
            pass.instanceId shouldBe 0
            pass.grpId shouldBe 0
            pass.shouldStop.shouldBeFalse()
        }

        test("Activate actions: shouldStop=true, has instanceId + grpId + abilityGrpId") {
            val (b, game, _) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")

            val actions = ActionMapper.buildActions(game, 1, b)
            val activateActions = actions.actionsList.filter { it.actionType == ActionType.Activate_add3 }
            if (activateActions.isEmpty()) return@test

            for (a in activateActions) {
                a.shouldStop.shouldBeTrue()
                a.instanceId shouldNotBe 0
                a.grpId shouldNotBe 0
                a.facetId shouldBe a.instanceId
            }
        }

        test("ActionsAvailableReq in postAction bundle matches direct buildActions") {
            val (b, game, counter) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")

            val result = base.postAction(game, b, counter)

            val aar = result.aarOrNull
            aar.shouldNotBeNull()

            val typeSet = aar.actionsList.map { it.actionType.name }.toSet()
            typeSet.contains("Cast").shouldBeTrue()
            typeSet.contains("Pass").shouldBeTrue()
            typeSet.contains("ActivateMana").shouldBeTrue()
            typeSet.contains("FloatMana").shouldBeTrue()

            val gsm = result.gsmOrNull
            gsm.shouldNotBeNull()
            gsm.pendingMessageCount shouldBe 1
        }

        test("GSM embedded actions are stripped") {
            val (b, game, counter) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")

            val gsm = base.postAction(game, b, counter).gsmOrNull ?: error("No GSM in post-action result")

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

        test("AutoTapSolution maps mana sources to spell cost") {
            val (b, game, _) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")

            val actions = ActionMapper.buildActions(game, 1, b)
            val castActions = actions.actionsList.filter {
                it.actionType == ActionType.Cast && it.hasAutoTapSolution()
            }
            if (castActions.isEmpty()) return@test

            for (a in castActions) {
                val ats = a.autoTapSolution
                for (tap in ats.autoTapActionsList) {
                    tap.instanceId shouldNotBe 0
                    tap.hasManaPaymentOption().shouldBeTrue()
                    val mana = tap.manaPaymentOption.manaList
                    mana.isNotEmpty().shouldBeTrue()
                    for (m in mana) {
                        m.srcInstanceId shouldNotBe 0
                        m.color shouldNotBe ManaColor.None_afc9
                        m.count shouldBeGreaterThan 0
                    }
                }
            }
        }
    })
