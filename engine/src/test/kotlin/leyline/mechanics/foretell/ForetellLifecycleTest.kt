package leyline.mechanics.foretell

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class ForetellLifecycleTest :
    SessionTest({
        session("local face-down rows survive exile and retire on cast announcement", puzzleFile = "data/puzzles/foretell-depart-the-realm.pzl") {
            val cardGrpId = bridge.cardRepository.findGrpIdByName("Depart the Realm")!!
            val foretellAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.FORETELL)!!
            val lifecycleStart = messageSnapshot()
            castSpellByName("Depart the Realm", alternativeGrpId = foretellAbilityGrpId).shouldBeTrue()

            val faceDown = messagesSince(lifecycleStart).persistentAnnotationsOfType(AnnotationType.FaceDown).single()
            val suppressed = messagesSince(lifecycleStart).persistentAnnotationsOfType(AnnotationType.SuppressedPowerAndToughness).single()

            passUntil(maxPasses = 20) { foretellCastOffer(foretellAbilityGrpId) != null }
            val castAction = foretellCastOffer(foretellAbilityGrpId)
            check(castAction != null) { "Foretell cast offer did not appear before game end" }
            val preCastMessages = messagesSince(lifecycleStart)
            assertSoftly {
                preCastMessages.deletedPersistentAnnotationIds() shouldNotContain faceDown.id
                preCastMessages.deletedPersistentAnnotationIds() shouldNotContain suppressed.id
            }

            val castStart = messageSnapshot()
            submitAction(castAction)

            assertSoftly {
                messagesSince(castStart).deletedPersistentAnnotationIds() shouldContain faceDown.id
                messagesSince(castStart).deletedPersistentAnnotationIds() shouldContain suppressed.id
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
