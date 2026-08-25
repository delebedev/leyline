package leyline.behavior.cards

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.after
import leyline.testkit.settingsMessage
import wotc.mtgo.gre.external.messaging.Messages.*

private val PRINCE_FLICKER_PUZZLE =
    """
    [metadata]
    Name:Prince Flicker
    Goal:Win
    Turns:10
    Difficulty:Easy
    Description:Exile another creature with Charming Prince and return it at the next end step.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Plains;Plains;Grizzly Bears
    humanhand=Charming Prince
    humanlibrary=Plains;Plains;Plains;Plains;Plains
    aibattlefield=Mountain
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

/**
 * Modal ETB flow tests using [SessionTest] + puzzle files.
 *
 * Trufflesnout (2G, 2/2 Boar) has 2 ETB modal choices:
 *   - Mode 0: Put a +1/+1 counter on Trufflesnout
 *   - Mode 1: You gain 4 life
 *
 * Charming Prince (1W, 1/1 Human Noble) has 3 ETB modal choices:
 *   - Mode 0: Scry 2
 *   - Mode 1: You gain 3 life
 *   - Mode 2: Exile another target creature you own, return at next end step
 *
 * Tests verify that casting these produces a CastingTimeOptionsReq with
 * affectedId/affectorId pointing at the ability instanceId (not the card),
 * as the protocol requires.
 */
class ModalETBFlowTest :
    SessionTest({

        // Trufflesnout modal ability ids — match `trufflesnout.yaml` fixture.
        val parentAbilityGrpId = 137979
        val counterModeGrpId = 60590
        val lifeModeGrpId = 121504

        // Charming Prince modal ability ids — match `charming_prince.yaml` fixture.
        val princeAbilityGrpId = 136341
        val princeLifeModeGrpId = 26167
        val princeFlickerModeGrpId = 136340
        val princeReturnGrpId = 136220

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            val repo = TestCardRegistry.repo
            val grpId = TestCardRegistry.ensureCardRegistered("Charming Prince")
            val data = repo.findByGrpId(grpId)!!
            repo.registerData(
                data.copy(hiddenAbilityIds = listOf(princeReturnGrpId to 897_415)),
                "Charming Prince",
            )
            repo.registerAbilityInfo(
                princeReturnGrpId,
                leyline.game.data.AbilityInfo(baseId = 0, manaCost = emptyList(), category = 2),
            )
        }

        session("modal ETB emits CastingTimeOptionsReq", puzzleFile = "puzzles/modal-etb.pzl") {
            val req = castSpellUntilCastingTimeOptionsReq("Trufflesnout")
            req.castingTimeOptionReqCount shouldBe 1

            val option = req.getCastingTimeOptionReq(0)
            option.castingTimeOptionType shouldBe CastingTimeOptionType.Modal_a7b4
            // ETB trigger: grpId is the ability grpId, not the card grpId
            assertSoftly {
                option.grpId shouldBe parentAbilityGrpId
                option.ctoId shouldBe 2
                option.hasModalReq().shouldBeTrue()
            }

            val modalReq = option.modalReq
            assertSoftly {
                modalReq.abilityGrpId shouldBe parentAbilityGrpId
                modalReq.minSel shouldBe 1
                modalReq.maxSel shouldBe 1
                modalReq.modalOptionsCount shouldBe 2
                modalReq.getModalOptions(0).grpId shouldBe counterModeGrpId
                modalReq.getModalOptions(1).grpId shouldBe lifeModeGrpId
            }
        }

        session("modal choice resolves life gain", puzzleFile = "puzzles/modal-etb.pzl") {
            val startLife = human.life

            castSpellUntilCastingTimeOptionsReq("Trufflesnout")

            // Choose life gain mode (index 1 → lifeModeGrpId)
            respondModalChoice(listOf(lifeModeGrpId))

            // Verify life gain
            (human.life - startLife) shouldBe 4
        }

        session("modal choice resolves +1/+1 counter", puzzleFile = "puzzles/modal-etb.pzl") {
            castSpellUntilCastingTimeOptionsReq("Trufflesnout")

            // Choose counter mode (index 0 → counterModeGrpId)
            respondModalChoice(listOf(counterModeGrpId))

            // Find Trufflesnout on battlefield — should have a +1/+1 counter
            val trufflesnout =
                human
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .firstOrNull { it.name == "Trufflesnout" }
            trufflesnout.shouldNotBeNull()
            trufflesnout.getCounters(CounterEnumType.P1P1) shouldBeGreaterThan 0
        }

        session(
            "Charming Prince ETB modal uses ability instanceId, not card instanceId",
            puzzleFile = "puzzles/prince-etb.pzl",
        ) {
            val req = castSpellUntilCastingTimeOptionsReq("Charming Prince")
            req.castingTimeOptionReqCount shouldBe 1

            val option = req.getCastingTimeOptionReq(0)
            option.castingTimeOptionType shouldBe CastingTimeOptionType.Modal_a7b4

            // Protocol: grpId is the ability grpId (136341), not the card grpId
            option.grpId shouldBe princeAbilityGrpId

            // Protocol: affectedId/affectorId reference the ability on the
            // stack, not the card. The ability instanceId is allocated against
            // [FrameIdResolver.triggerStackAbilityForgeId] keyed on the
            // trigger SA id and lives in the stack-ability surrogate range —
            // [FrameIdResolver.isStackAbilityForgeId] guards iids vs source
            // card iids on the same battlefield. Asserting an exact iid here
            // is brittle: the SA id Forge assigns depends on internal
            // allocation order, and the SA isn't on the stack at CTO emission
            // time (still in the cost-prep phase).
            val princeCardIid = human.battlefield.iid("Charming Prince")

            assertSoftly {
                option.affectedId shouldNotBe princeCardIid
                option.affectorId shouldNotBe princeCardIid
                option.affectedId shouldBe option.affectorId
                // Protocol: ctoId=2 for both spell-time and ETB modals
                option.ctoId shouldBe 2
                // Protocol: playerIdToPrompt is set
                option.playerIdToPrompt shouldBe 1
            }

            // Modal options should be correct. Charming Prince has 3 ETB modes;
            // the third mode (Exile another creature you own) is legality-filtered
            // when no second creature is on the battlefield (puzzle setup), so it
            // surfaces in `excludedOptions[]` rather than `modalOptions[]`. The
            // total mode count is still 3.
            val modalReq = option.modalReq
            assertSoftly {
                modalReq.abilityGrpId shouldBe princeAbilityGrpId
                modalReq.minSel shouldBe 1
                modalReq.maxSel shouldBeGreaterThan 0
                (modalReq.modalOptionsCount + modalReq.excludedOptionsCount) shouldBe 3
            }
        }

        session("ETB modal GSM has ability on stack and pendingMessageCount", puzzleFile = "puzzles/modal-etb.pzl") {
            val msgs = after { castSpellUntilCastingTimeOptionsReq("Trufflesnout") }.messages

            // Find the GSM that accompanies the CTO
            val ctoIdx = msgs.indexOfFirst { it.type == GREMessageType.CastingTimeOptionsReq_695e }
            ctoIdx shouldBeGreaterThan 0 // GSM must come before CTO

            val gsm = msgs[ctoIdx - 1].gameStateMessage
            gsm.shouldNotBeNull()

            // Must have pendingMessageCount=1 (signals CTO follows)
            gsm.pendingMessageCount shouldBe 1

            // Must have the ability on the stack
            val stackZone = gsm.zonesList.find { it.type == ZoneType.Stack }
            stackZone.shouldNotBeNull()
            stackZone.objectInstanceIdsList.shouldNotBeEmpty()

            // Must have a GameObjectType_Ability in the game objects
            val abilityObj = gsm.gameObjectsList.find { it.type == GameObjectType.Ability }
            abilityObj.shouldNotBeNull()
            abilityObj.zoneId shouldBe stackZone.zoneId

            // The CTO affectedId must match the ability instanceId
            val cto = msgs[ctoIdx].castingTimeOptionsReq
            val affectedId = cto.getCastingTimeOptionReq(0).affectedId
            abilityObj.instanceId shouldBe affectedId
        }

        session("ETB ability object has correct parentId and objectSourceGrpId", puzzleFile = "puzzles/modal-etb.pzl") {
            val trufflesnoutGrpId = TestCardRegistry.repo.findGrpIdByName("Trufflesnout")!!

            val msgs = after { castSpellUntilCastingTimeOptionsReq("Trufflesnout") }.messages

            val ctoIdx = msgs.indexOfFirst { it.type == GREMessageType.CastingTimeOptionsReq_695e }
            val gsm = msgs[ctoIdx - 1].gameStateMessage
            val abilityObj = gsm.gameObjectsList.first { it.type == GameObjectType.Ability }

            // parentId = source card instanceId on the battlefield
            val cardInstanceId = human.battlefield.iid("Trufflesnout")

            assertSoftly {
                abilityObj.parentId shouldBe cardInstanceId
                abilityObj.objectSourceGrpId shouldBe trufflesnoutGrpId
                abilityObj.grpId shouldBe parentAbilityGrpId
            }
        }

        session(
            "synthesized ability cleaned up after modal resolves",
            puzzleFile = "puzzles/modal-etb.pzl",
        ) {
            castSpellUntilCastingTimeOptionsReq("Trufflesnout")

            val msgs = after { respondModalChoice(listOf(lifeModeGrpId)) }.messages

            val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            gsms.shouldNotBeEmpty()

            // Empty echoes don't count; cleanup must be an explicit stack-zone
            // replacement or object deletion in the post-response batch.
            val cleaned =
                gsms.any { gs ->
                    val stackZone = gs.zonesList.find { it.type == ZoneType.Stack }
                    val stackExplicitlyEmpty = stackZone != null && stackZone.objectInstanceIdsList.isEmpty()
                    val abilityDeleted = gs.diffDeletedInstanceIdsList.isNotEmpty()
                    stackExplicitlyEmpty || abilityDeleted
                }
            cleaned shouldBe true
        }

        session("Charming Prince gain 3 life mode resolves", puzzleFile = "puzzles/prince-etb.pzl") {
            val startLife = human.life

            castSpellUntilCastingTimeOptionsReq("Charming Prince")
            respondModalChoice(listOf(princeLifeModeGrpId))

            (human.life - startLife) shouldBe 3
        }

        session(
            "Charming Prince flicker exposes and retires pending trigger visuals",
            puzzle = PRINCE_FLICKER_PUZZLE,
        ) {
            val targetIid = human.battlefield.iid("Grizzly Bears")

            castSpellUntilCastingTimeOptionsReq("Charming Prince")
            respondModalChoice(listOf(princeFlickerModeGrpId))
            selectTargets(listOf(targetIid))
            passUntilResolved()

            val gameStates = allMessages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            val holder =
                gameStates
                    .flatMap { it.gameObjectsList }
                    .last {
                        it.type == GameObjectType.TriggerHolder &&
                            it.objectSourceGrpId == princeFlickerModeGrpId
                    }
            val persistent = gameStates.flatMap { it.persistentAnnotationsList }
            val delayedAffectees =
                persistent.last {
                    AnnotationType.DelayedTriggerAffectees in it.typeList && it.affectorId == holder.instanceId
                }
            val exiledIid = delayedAffectees.affectedIdsList.single()
            val displayCardUnderCard =
                persistent.last {
                    AnnotationType.DisplayCardUnderCard in it.typeList && it.affectorId == holder.instanceId
                }
            assertSoftly {
                holder.zoneId shouldBe leyline.game.mapping.ZoneIds.LIMBO
                holder.uniqueAbilitiesList.map { it.grpId } shouldContain princeReturnGrpId
                delayedAffectees.also { ann ->
                    ann.affectedIdsList shouldContain exiledIid
                    ann.detailsList.none { it.key == "removes_from_zone" }.shouldBeTrue()
                }
                persistent
                    .last {
                        AnnotationType.TemporaryPermanent in it.typeList && it.affectorId == holder.instanceId
                    }.affectedIdsList shouldContain exiledIid
                displayCardUnderCard.affectedIdsList shouldContain exiledIid
            }

            bridge.priorityPolicy.submit(
                settingsMessage {
                    addStops(
                        Stop
                            .newBuilder()
                            .setStopType(StopType.EndStep_ad1f)
                            .setAppliesTo(SettingScope.Team_ac6e)
                            .setStatus(SettingStatus.Set)
                            .build(),
                    )
                },
            )
            passUntil(maxPasses = 30) {
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .any { it.type == GameObjectType.Ability && it.grpId == princeReturnGrpId }
            }.shouldBeTrue()
            val firingState =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .last { state ->
                        state.gameObjectsList.any { it.type == GameObjectType.Ability && it.grpId == princeReturnGrpId }
                    }
            val returnAbility =
                firingState.gameObjectsList.single { it.type == GameObjectType.Ability && it.grpId == princeReturnGrpId }
            assertSoftly {
                firingState.diffDeletedInstanceIdsList shouldContain holder.instanceId
                firingState.persistentAnnotationsList
                    .single { it.id == delayedAffectees.id }
                    .affectorId shouldBe returnAbility.instanceId
                firingState.persistentAnnotationsList
                    .single { it.id == displayCardUnderCard.id }
                    .affectorId shouldBe returnAbility.instanceId
                firingState.persistentAnnotationsList
                    .none {
                        AnnotationType.TemporaryPermanent in it.typeList && exiledIid in it.affectedIdsList
                    }.shouldBeTrue()
            }

            assertSoftly {
                passUntil(maxPasses = 30) {
                    human.getZone(forge.game.zone.ZoneType.Battlefield).cards.any { it.name == "Grizzly Bears" }
                }.shouldBeTrue()
                val resolutionState =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .map { it.gameStateMessage }
                        .last { state ->
                            state.annotationsList.any { annotation ->
                                AnnotationType.ZoneTransfer_af5a in annotation.typeList &&
                                    annotation.detailsList.any {
                                        it.key == "zone_src" &&
                                            it.getValueInt32(0) == leyline.game.mapping.ZoneIds.EXILE
                                    } &&
                                    annotation.detailsList.any {
                                        it.key == "zone_dest" &&
                                            it.getValueInt32(0) == leyline.game.mapping.ZoneIds.BATTLEFIELD
                                    }
                            }
                        }
                resolutionState.diffDeletedPersistentAnnotationIdsList shouldContain displayCardUnderCard.id
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .any { holder.instanceId in it.gameStateMessage.diffDeletedInstanceIdsList }
                    .shouldBeTrue()
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .any { displayCardUnderCard.id in it.gameStateMessage.diffDeletedPersistentAnnotationIdsList }
                    .shouldBeTrue()
            }
        }
    })
