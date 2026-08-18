package leyline.behavior.mechanics.firebending

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.after
import leyline.testkit.annotationsOfType
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

class FirebendingKeywordTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Jeong Jeong, the Deserter")
        }

        session(
            "Firebending attack trigger resolves to combat-persistent red mana",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Jeong Jeong, the Deserter
            humanlibrary=Mountain;Mountain;Mountain
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent(),
            turns = 5,
        ) {
            val sourceGrpId = TestCardRegistry.ensureCardRegistered("Jeong Jeong, the Deserter")
            val firebendingGrpId =
                TestCardRegistry.repo.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.FIREBENDING)
                    ?: error("Jeong Jeong Firebending ability row missing")

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val sourceIid = humanBattlefieldCreatures().first { it.second == "Jeong Jeong, the Deserter" }.first

            val post =
                after {
                    declareAttackers(listOf(sourceIid))
                    passUntil(maxPasses = 40) {
                        allMessages.gameStateMessages().any { gsm ->
                            gsm.playersList.any { player ->
                                player.systemSeatNumber == HUMAN_SEAT &&
                                    player.manaPoolList.any { it.abilityGrpId == firebendingGrpId }
                            }
                        }
                    }
                }.messages

            val aic =
                post.annotationsOfType(AnnotationType.AbilityInstanceCreated).first {
                    it.affectorId == sourceIid
                }
            val abilityIid = aic.affectedIdsList.single()
            val manaGsm =
                post.gameStateMessages().last { gsm ->
                    gsm.playersList.any { player ->
                        player.systemSeatNumber == HUMAN_SEAT &&
                            player.manaPoolList.any { it.abilityGrpId == firebendingGrpId }
                    }
                }
            val mana =
                manaGsm.playersList
                    .first { it.systemSeatNumber == HUMAN_SEAT }
                    .manaPoolList
                    .single { it.abilityGrpId == firebendingGrpId }
            val manaDetails =
                post.persistentAnnotationsOfType(AnnotationType.ManaDetails).single {
                    mana.manaId in it.affectedIdsList
                }
            val triggerEnterIdx =
                post.gameStateMessages().indexOfFirst { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.AbilityInstanceCreated in it.typeList &&
                            abilityIid in it.affectedIdsList
                    }
                }
            val resolveIdx =
                post.gameStateMessages().indexOfFirst { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList && it.affectorId == abilityIid
                    }
                }

            assertSoftly {
                aic.detailInt("source_zone") shouldBe 28
                triggerEnterIdx shouldBeGreaterThan -1
                resolveIdx shouldBeGreaterThan triggerEnterIdx
                post
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .first { it.affectorId == abilityIid }
                    .affectedIdsList shouldContain sourceIid
                post
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .first { it.affectorId == abilityIid }
                    .detailUint("grpid") shouldBe firebendingGrpId
                post
                    .annotationsOfType(AnnotationType.ResolutionComplete)
                    .first { it.affectorId == abilityIid }
                    .detailUint("grpid") shouldBe firebendingGrpId
                mana.color shouldBe ManaColor.Red_afc9
                mana.srcInstanceId shouldBe sourceIid
                mana.count shouldBe 1
                mana.specsList.single().type shouldBe ManaSpecType.DoesNotEmpty
                manaDetails.affectorId shouldBe sourceIid
                manaDetails.detailInt("ManaSpecType_DoesNotEmpty") shouldBe 14695
            }

            val cleanup =
                if (manaDetails.id in post.deletedPersistentAnnotationIds()) {
                    post
                } else {
                    after {
                        passThroughCombat(maxPasses = 20)
                    }.messages
                }

            cleanup.deletedPersistentAnnotationIds() shouldContain manaDetails.id
        }
    })
