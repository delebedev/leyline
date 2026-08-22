package leyline.behavior.mechanics.decayed

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.AbilityInfo
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.after
import leyline.testkit.allGameObjects
import leyline.testkit.annotationTypeSet
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

private const val DECAYED_CLEANUP_GRP_ID = 147665

class DecayedKeywordTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            val grpId = TestCardRegistry.ensureCardRegistered("Rot-Curse Rakshasa")
            val repo = TestCardRegistry.repo
            val data = repo.findByGrpId(grpId)!!
            repo.registerData(
                data.copy(hiddenAbilityIds = listOf(DECAYED_CLEANUP_GRP_ID to 530690)),
                "Rot-Curse Rakshasa",
            )
            repo.registerAbilityInfo(DECAYED_CLEANUP_GRP_ID, AbilityInfo(baseId = 0, manaCost = emptyList(), category = 2))
        }

        val decayedPuzzle =
            """
            [metadata]
            Name:Decayed Rot-Curse Rakshasa
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Rot-Curse Rakshasa attacks, registers EndCombat cleanup, then sacrifices itself.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Rot-Curse Rakshasa
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        session(
            "Decayed attack trigger registers EndCombat cleanup and sacrifices the attacker",
            puzzle = decayedPuzzle,
        ) {
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val sourceIid = humanBattlefieldCreatures().first { it.second == "Rot-Curse Rakshasa" }.first

            val post =
                after {
                    declareAttackers(listOf(sourceIid))
                    passUntil(maxPasses = 40) { turn() > 1 || isGameOver() }
                }.messages

            val types = post.annotationTypeSet()
            val holders = post.allGameObjects().filter { it.type == GameObjectType.TriggerHolder }
            val tempPerms = post.persistentAnnotationsOfType(AnnotationType.TemporaryPermanent)
            val cleanupResolutions =
                post.annotationsOfType(AnnotationType.ResolutionStart).filter {
                    it.detailUint("grpid") == DECAYED_CLEANUP_GRP_ID
                }
            val cleanupAbilityIid = cleanupResolutions.singleOrNull()?.affectorId
            val decayedResolution =
                post.annotationsOfType(AnnotationType.ResolutionStart).first {
                    it.detailUint("grpid") == KeywordAbilityIds.DECAYED
                }
            val decayedAbilityIid = decayedResolution.affectorId
            val gsms = post.gameStateMessages()
            val triggerEnterIdx =
                gsms.indexOfFirst { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.AbilityInstanceCreated in it.typeList &&
                            it.affectedIdsList.contains(decayedAbilityIid)
                    }
                }
            val resolveIdx =
                gsms.indexOfFirst { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList && it.affectorId == decayedAbilityIid
                    }
                }
            val cleanupEnterIdx =
                gsms.indexOfFirst { gsm ->
                    cleanupAbilityIid != null &&
                        gsm.annotationsList.any {
                            AnnotationType.AbilityInstanceCreated in it.typeList &&
                                it.affectedIdsList.contains(cleanupAbilityIid)
                        }
                }
            val cleanupResolveIdx =
                gsms.indexOfFirst { gsm ->
                    cleanupAbilityIid != null &&
                        gsm.annotationsList.any {
                            AnnotationType.ResolutionStart in it.typeList && it.affectorId == cleanupAbilityIid
                        }
                }
            val sourceTransferIids =
                post
                    .annotationsOfType(AnnotationType.ObjectIdChanged)
                    .filter { it.detailInt("orig_id") == sourceIid }
                    .map { it.detailInt("new_id") }
                    .toSet() + sourceIid
            val sacrifices =
                post.annotationsOfType(AnnotationType.ZoneTransfer_af5a).filter { ann ->
                    ann.affectedIdsList.any { it in sourceTransferIids } &&
                        ann.detailsList.any { d -> d.key == "category" && "Sacrifice" in d.valueStringList }
                }
            val sacrificeIdx =
                gsms.indexOfFirst { gsm ->
                    gsm.annotationsList.any { ann ->
                        ann.affectedIdsList.any { it in sourceTransferIids } &&
                            ann.detailsList.any { d -> d.key == "category" && "Sacrifice" in d.valueStringList }
                    }
                }

            assertSoftly("Decayed lifecycle") {
                types shouldContain AnnotationType.AbilityInstanceCreated
                types shouldContain AnnotationType.TriggeringObject
                types shouldContain AnnotationType.ResolutionStart
                types shouldContain AnnotationType.ResolutionComplete
                types shouldContain AnnotationType.AbilityInstanceDeleted
                types shouldContain AnnotationType.TemporaryPermanent
                post.persistentAnnotationsOfType(AnnotationType.DelayedTriggerAffectees).shouldBeEmpty()
                triggerEnterIdx shouldBeGreaterThan -1
                resolveIdx shouldBeGreaterThan triggerEnterIdx
                cleanupEnterIdx shouldBeGreaterThan -1
                cleanupResolveIdx shouldBeGreaterThan cleanupEnterIdx
                sacrificeIdx shouldBeGreaterThanOrEqualTo cleanupResolveIdx

                post
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .filter { it.detailUint("grpid") == KeywordAbilityIds.DECAYED }
                    .shouldNotBeEmpty()
                post
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .filter { it.detailUint("grpid") == DECAYED_CLEANUP_GRP_ID }
                    .shouldNotBeEmpty()
                cleanupResolutions shouldHaveSize 1
                sacrifices shouldHaveSize 1
                sacrifices.single().affectorId shouldBe cleanupResolutions.single().affectorId

                holders.shouldNotBeEmpty()
                val holder = holders.first()
                holder.grpId shouldBe 5
                holder.objectSourceGrpId shouldBe KeywordAbilityIds.DECAYED
                holder.parentId shouldBe sourceIid
                holder.uniqueAbilitiesList.first().grpId shouldBe DECAYED_CLEANUP_GRP_ID

                val tempPerm = tempPerms.first { sourceIid in it.affectedIdsList }
                tempPerm.affectorId shouldBe holder.instanceId
                tempPerm.detailInt("AbilityGrpId") shouldBe DECAYED_CLEANUP_GRP_ID
            }

            val holderIids = holders.map { it.instanceId }.toSet()
            holderIids shouldHaveSize 1
            val holderIid = holderIids.single()
            val deletionCount = gsms.count { holderIid in it.diffDeletedInstanceIdsList }
            deletionCount shouldBe 1
        }
    })
