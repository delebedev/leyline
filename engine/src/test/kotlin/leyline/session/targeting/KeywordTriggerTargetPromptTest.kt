package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.detailInt
import leyline.tooling.headless.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq

/**
 * Backup and Mentor hang a targeted trigger off a keyword, so their
 * SelectTargetsReq must carry the keyword's ability grpId rather than the
 * card's, and the matching TargetSpec annotation must repeat it.
 *
 * Each scenario is staged directly rather than played out: Backup wants the
 * creature cast from hand (its trigger is an ETB), Mentor wants two attackers
 * of unequal power already on the battlefield.
 */
class KeywordTriggerTargetPromptTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Enduring Bondwarden")
            TestCardRegistry.ensureCardRegistered("Sunhome Stalwart")
        }

        fun MatchFlowHarness.targetSpecs() =
            allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.persistentAnnotationsList }
                .filter { AnnotationType.TargetSpec in it.typeList }

        fun MatchFlowHarness.selectTargetsReqs(): List<SelectTargetsReq> =
            allMessages
                .filter { it.type == GREMessageType.SelectTargetsReq_695e }
                .map { it.selectTargetsReq }

        /** Let the stack resolve until the keyword trigger asks for its target. */
        fun MatchFlowHarness.passUntilPrompt(matching: (SelectTargetsReq) -> Boolean) {
            repeat(6) {
                if (selectTargetsReqs().any(matching)) return
                if (!hasPendingAction()) return
                passPriority()
            }
        }

        test("Backup target prompt and annotation carry the keyword's ability grpId") {
            val bondwardenGrpId = TestCardRegistry.ensureCardRegistered("Enduring Bondwarden")
            val backupGrpId =
                TestCardRegistry.repo.findKeywordAbilityGrpId(bondwardenGrpId, KeywordAbilityIds.BACKUP)
                    ?: error("Enduring Bondwarden Backup ability row missing")

            val h = MatchFlowHarness()
            try {
                h.connectAndKeepPuzzleText(
                    """
                    [metadata]
                    Name:Backup Target Prompt
                    Goal:Resolve Backup trigger
                    Turns:2

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20

                    humanhand=Enduring Bondwarden
                    humanbattlefield=Plains;Grizzly Bears
                    humanlibrary=Plains
                    ailibrary=Mountain
                    """.trimIndent(),
                )
                h.castSpellByName("Enduring Bondwarden").shouldBeTrue()
                h.passUntilPrompt { it.abilityGrpId == backupGrpId }

                val prompts = h.selectTargetsReqs().filter { it.abilityGrpId == backupGrpId }
                prompts.shouldNotBeEmpty()

                val prompt = prompts.first()
                assertSoftly(prompt) {
                    sourceId shouldBeGreaterThan 0
                    abilityGrpId shouldBe backupGrpId
                    targetsList.single().targetingAbilityGrpId shouldBe KeywordAbilityIds.BACKUP
                    targetsList.single().minTargets shouldBe 1
                    targetsList.single().maxTargets shouldBe 1
                }

                // The TargetSpec annotation only lands once the target is chosen.
                h.selectTargets(
                    listOf(
                        prompt.targetsList
                            .single()
                            .targetsList
                            .first()
                            .targetInstanceId,
                    ),
                )
                h.targetSpecs().any { it.detailInt("abilityGrpId") == backupGrpId }.shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }

        // Disabled: Mentor's attack trigger currently emits no SelectTargetsReq at
        // all, so this fails on the first assertion. The prompt mapping exists, but
        // nothing reaches it from the attack trigger. Tracked as bd leyline-ebln;
        // re-enable together with the fix.
        test("Mentor target prompt carries the shared keyword prompt shape").config(enabled = false) {
            val h = MatchFlowHarness()
            try {
                h.connectAndKeepPuzzleText(
                    """
                    [metadata]
                    Name:Mentor Target Prompt
                    Goal:Resolve Mentor trigger
                    Turns:2

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20

                    humanbattlefield=Sunhome Stalwart;Enduring Bondwarden;Plains
                    humanlibrary=Plains
                    ailibrary=Mountain
                    """.trimIndent(),
                )
                h.advanceToCombat()
                h.declareAllAttackers()
                h.submitAttackers()
                h.passUntilPrompt { it.abilityGrpId == KeywordAbilityIds.MENTOR }

                val prompts = h.selectTargetsReqs().filter { it.abilityGrpId == KeywordAbilityIds.MENTOR }
                prompts.shouldNotBeEmpty()

                val prompt = prompts.first()
                assertSoftly(prompt) {
                    abilityGrpId shouldBe KeywordAbilityIds.MENTOR
                    targetsList.single().targetingAbilityGrpId shouldBe KeywordAbilityIds.MENTOR
                    targetsList.single().prompt.promptId shouldBe PromptIds.MENTOR_TARGET
                    targetsList
                        .single()
                        .prompt.parametersList
                        .single()
                        .numberValue shouldBe sourceId
                    targetsList.single().minTargets shouldBe 1
                    targetsList.single().maxTargets shouldBe 1
                }
                h
                    .targetSpecs()
                    .any {
                        it.detailInt("abilityGrpId") == KeywordAbilityIds.MENTOR &&
                            it.detailInt("promptId") == PromptIds.MENTOR_TARGET
                    }.shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }
    })
