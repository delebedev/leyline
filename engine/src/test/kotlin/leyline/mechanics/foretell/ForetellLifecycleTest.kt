package leyline.mechanics.foretell

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

private val FORETELL_PUZZLE =
    """
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Depart the Realm
    humanbattlefield=Island;Island
    humanlibrary=Island;Island;Island
    aibattlefield=Grizzly Bears
    ailibrary=Forest;Forest;Forest
    """.trimIndent()

class ForetellLifecycleTest :
    SessionTest({
        session("face-down row persists in exile and retires when the foretold card is cast", puzzle = FORETELL_PUZZLE, turns = 5) {
            val cardGrpId = bridge.cardRepository.findGrpIdByName("Depart the Realm")!!
            val foretellAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.FORETELL)!!
            val lifecycleStart = messageSnapshot()
            castSpellByName("Depart the Realm", alternativeGrpId = foretellAbilityGrpId).shouldBeTrue()

            val foretoldCard = human.getZone(ZoneType.Exile).cards.single()
            val foretoldIid = bridge.instanceId(foretoldCard)
            val faceDownRows = messagesSince(lifecycleStart).persistentAnnotationsOfType(AnnotationType.FaceDown)
            val faceDown =
                faceDownRows.firstOrNull {
                    it.detailInt(DetailKeys.REASON_UPPER) == AnnotationConstants.FACEDOWN_REASON_FORETELL
                } ?: error("No persistent Foretell FaceDown row: $faceDownRows")

            assertSoftly {
                faceDown.affectorId shouldBe foretoldIid
                faceDown.affectedIdsList shouldBe listOf(foretoldIid)
                faceDown.detailInt(DetailKeys.ABILITY_GRP_ID) shouldBe KeywordAbilityIds.FORETELL
            }

            passUntil(maxPasses = 20) { foretellCastOffer(foretellAbilityGrpId) != null }
            val castAction = foretellCastOffer(foretellAbilityGrpId)
            check(castAction != null) { "Foretell cast offer did not appear before game end" }
            val preCastMessages = messagesSince(lifecycleStart)
            preCastMessages.deletedPersistentAnnotationIds() shouldNotContain faceDown.id

            val castStart = messageSnapshot()
            submitAction(castAction)

            assertSoftly {
                messagesSince(castStart).deletedPersistentAnnotationIds() shouldContain faceDown.id
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .none { bridge.instanceId(it) == foretoldIid }
                    .shouldBeTrue()
            }
        }
    })

private fun MatchFlowHarness.foretellCastOffer(foretellAbilityGrpId: Int): Action? =
    allMessages
        .lastOrNull { it.hasActionsAvailableReq() }
        ?.actionsAvailableReq
        ?.actionsList
        ?.firstOrNull {
            it.actionType == ActionType.Cast &&
                it.alternativeGrpId == foretellAbilityGrpId
        }
