package leyline.mechanics.collectevidence

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detail
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

private val PUZZLE =
    """
    [metadata]
    Name:Collect Evidence - Behind the Mask
    Goal:Cast Behind the Mask and pay collect evidence.
    Turns:2
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Behind the Mask
    humangraveyard=Murder;Centaur Courser;Walking Corpse
    humanbattlefield=Island
    humanlibrary=Island;Island;Island
    aibattlefield=Runeclaw Bear
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

class CollectEvidenceLifecycleTest :
    SessionTest({
        session("Behind the Mask pays Collect Evidence through weighted PayCostsReq", puzzle = PUZZLE, validating = true) {
            val targetIid = ai.battlefield.iid("Runeclaw Bear")
            val murderIid = human.graveyard.iid("Murder")
            val courserIid = human.graveyard.iid("Centaur Courser")

            val cto =
                after { castSpellByName("Behind the Mask").shouldBeTrue() }
                    .expectOneCastingTimeOptionsReq()
            val collectEvidenceOption =
                cto.castingTimeOptionReqList.single {
                    it.castingTimeOptionType == CastingTimeOptionType.AdditionalCost
                }
            assertSoftly {
                collectEvidenceOption.grpId shouldBe COLLECT_EVIDENCE_ABILITY_GRP_ID
                cto.castingTimeOptionReqList.map { it.castingTimeOptionType } shouldContain CastingTimeOptionType.Done
            }

            after { respondToOptionalCost(collectEvidenceOption.ctoId) }
                .expectOneSelectTargetsReq()
            val payCostSlice = after { selectTargets(listOf(targetIid)) }
            val payCosts = payCostSlice.expectOnePayCostsReq()
            val payCostMessage = payCostSlice.messages.single { it.hasPayCostsReq() }
            val selection = payCosts.effectCostReq.costSelection
            val weightsById = selection.idsList.zip(selection.weightsList).toMap()

            assertSoftly {
                payCostSlice.messages
                    .last { it.hasGameStateMessage() }
                    .gameStateMessage.pendingMessageCount shouldBe 1
                payCostMessage.prompt.promptId shouldBe PromptIds.COLLECT_EVIDENCE_COST
                selection.minSel shouldBe 0
                selection.maxSel shouldBe 3
                selection.minWeight shouldBe 6
                selection.idsList shouldContain murderIid
                selection.idsList shouldContain courserIid
                weightsById[murderIid] shouldBe 3
                weightsById[courserIid] shouldBe 3
            }

            respondToEffectCost(listOf(murderIid, courserIid))
            passUntilResolved(maxPasses = 8)

            val abilityWord =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
                    .filter {
                        it.detail(DetailKeys.ABILITY_WORD_NAME)?.getValueString(0) == "CollectEvidenceCount"
                    }
            val exileNames = human.getZone(ZoneType.Exile).cards.map { it.name }
            val graveyardNames = human.getZone(ZoneType.Graveyard).cards.map { it.name }

            assertSoftly {
                abilityWord shouldHaveSize 2
                abilityWord.map { it.detailInt(DetailKeys.THRESHOLD) }.toSet() shouldBe setOf(6)
                abilityWord.map { it.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) }.toSet() shouldBe
                    setOf(COLLECT_EVIDENCE_ABILITY_GRP_ID)
                exileNames shouldContain "Murder"
                exileNames shouldContain "Centaur Courser"
                graveyardNames shouldContain "Behind the Mask"
                graveyardNames shouldContain "Walking Corpse"
            }
        }
    })

private const val COLLECT_EVIDENCE_ABILITY_GRP_ID = 170390
