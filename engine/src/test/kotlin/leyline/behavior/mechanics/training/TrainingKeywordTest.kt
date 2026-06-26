package leyline.behavior.mechanics.training

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.allAnnotations
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class TrainingKeywordTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Hopeful Initiate")
            TestCardRegistry.ensureCardRegistered("Savior of Ollenbock")
            TestCardRegistry.ensureCardRegistered("Grizzly Bears")
        }

        test("Hopeful Initiate training emits marker, keyword lifecycle, and counter affector") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Hopeful Initiate;Grizzly Bears
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Training Hopeful Initiate",
                turns = 5,
                validating = true,
            )

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val hopefulIid = humanBattlefieldCreatures().first { it.second == "Hopeful Initiate" }.first
            val bearIid = humanBattlefieldCreatures().first { it.second == "Grizzly Bears" }.first

            val post =
                after {
                    declareAttackers(listOf(hopefulIid, bearIid))
                    passUntil(maxPasses = 30) {
                        allMessages.annotationsOfType(AnnotationType.CounterAdded).any { hopefulIid in it.affectedIdsList }
                    }
                }.messages

            val trainingMarker =
                post.persistentAnnotationsOfType(AnnotationType.AbilityWordActive).first {
                    it.detailString("AbilityWordName") == "Training"
                }
            val aic = post.annotationsOfType(AnnotationType.AbilityInstanceCreated).first { it.affectorId == hopefulIid }
            val abilityIid = aic.affectedIdsList.single()
            val counterAdded = post.annotationsOfType(AnnotationType.CounterAdded).first { hopefulIid in it.affectedIdsList }
            val counterState = post.persistentAnnotationsOfType(AnnotationType.Counter_803b).first { hopefulIid in it.affectedIdsList }
            val gsms = post.gameStateMessages()
            val triggerEnterIdx =
                gsms.indexOfFirst { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.AbilityInstanceCreated in it.typeList &&
                            it.affectorId == hopefulIid &&
                            it.affectedIdsList.contains(abilityIid)
                    }
                }
            val resolveIdx =
                gsms.indexOfFirst { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList && it.affectorId == abilityIid
                    }
                }

            assertSoftly {
                trainingMarker.affectorId shouldBe hopefulIid
                trainingMarker.affectedIdsList shouldContain bearIid
                aic.detailInt("source_zone") shouldBe 28
                triggerEnterIdx shouldBeGreaterThan -1
                resolveIdx shouldBeGreaterThan triggerEnterIdx
                post
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .first { it.affectorId == abilityIid }
                    .affectedIdsList shouldContain hopefulIid
                post
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .first { it.affectorId == abilityIid }
                    .detailUint("grpid") shouldBe KeywordAbilityIds.TRAINING
                post
                    .annotationsOfType(AnnotationType.ResolutionComplete)
                    .first { it.affectorId == abilityIid }
                    .detailUint("grpid") shouldBe KeywordAbilityIds.TRAINING
                counterAdded.affectorId shouldBe abilityIid
                counterAdded.detailInt("transaction_amount") shouldBe 1
                counterState.detailInt("counter_type") shouldBe 1
                counterState.detailInt("count") shouldBe 1
                post.annotationsOfType(AnnotationType.PlayerSelectingTargets).shouldBeEmpty()
                post.persistentAnnotationsOfType(AnnotationType.TargetSpec).shouldBeEmpty()
            }
        }

        test("Savior of Ollenbock trains trigger fires after the Training counter") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Savior of Ollenbock;Grizzly Bears
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Training Savior of Ollenbock",
                turns = 5,
                validating = true,
            )

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val saviorIid = humanBattlefieldCreatures().first { it.second == "Savior of Ollenbock" }.first
            val bearIid = humanBattlefieldCreatures().first { it.second == "Grizzly Bears" }.first
            val start = messageSnapshot()

            declareAttackers(listOf(saviorIid, bearIid))
            passUntil(maxPasses = 30) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(bearIid))
            passUntil(maxPasses = 30) {
                allMessages.annotationsOfType(AnnotationType.ResolutionStart).any { it.detailUint("grpid") == 146570 }
            }

            val post = messagesSince(start)
            val annotations = post.allAnnotations()
            val counterIdx =
                annotations.indexOfFirst { AnnotationType.CounterAdded in it.typeList && saviorIid in it.affectedIdsList }
            val secondaryIdx =
                annotations.indexOfFirst { AnnotationType.ResolutionStart in it.typeList && it.detailUint("grpid") == 146570 }
            val trainingStart =
                post
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .first { it.detailUint("grpid") == KeywordAbilityIds.TRAINING }

            assertSoftly {
                counterIdx shouldBeGreaterThan -1
                secondaryIdx shouldBeGreaterThan counterIdx
                trainingStart.affectedIdsList shouldContain trainingStart.affectorId
                post
                    .annotationsOfType(AnnotationType.ZoneTransfer_af5a)
                    .any { it.detailInt("zone_dest") == 29 }
                    .shouldBeTrue()
            }
        }

        test("Training markers stay distinct when two trainers share one greater attacker") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Hopeful Initiate;Hopeful Initiate;Grizzly Bears
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Training Shared Larger Attacker",
                turns = 5,
                validating = true,
            )

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val hopefulIids = humanBattlefieldCreatures().filter { it.second == "Hopeful Initiate" }.map { it.first }
            val bearIid = humanBattlefieldCreatures().first { it.second == "Grizzly Bears" }.first
            hopefulIids shouldHaveSize 2

            val post =
                after {
                    declareAttackers(hopefulIids + bearIid)
                    passUntil(maxPasses = 40) {
                        allMessages.annotationsOfType(AnnotationType.CounterAdded).count { ann ->
                            hopefulIids.any { it in ann.affectedIdsList }
                        } >= 2
                    }
                }.messages

            val trainingMarkers =
                post.persistentAnnotationsOfType(AnnotationType.AbilityWordActive).filter {
                    it.detailString("AbilityWordName") == "Training"
                }

            assertSoftly {
                trainingMarkers shouldHaveSize 2
                trainingMarkers.map { it.affectorId }.toSet() shouldBe hopefulIids.toSet()
                trainingMarkers.forEach { it.affectedIdsList shouldContain bearIid }
            }
        }

        test("Training does not trigger with only equal-power co-attackers") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Hopeful Initiate;Hopeful Initiate
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Training Equal Power Attackers",
                turns = 5,
                validating = true,
            )

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val hopefulIids = humanBattlefieldCreatures().filter { it.second == "Hopeful Initiate" }.map { it.first }
            hopefulIids shouldHaveSize 2

            val post =
                after {
                    declareAttackers(hopefulIids)
                    passThroughCombat(maxPasses = 10)
                }.messages

            assertSoftly {
                post
                    .persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
                    .filter { it.detailString("AbilityWordName") == "Training" }
                    .shouldBeEmpty()
                post
                    .annotationsOfType(AnnotationType.CounterAdded)
                    .filter { ann -> hopefulIids.any { it in ann.affectedIdsList } }
                    .shouldBeEmpty()
                post
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .filter { it.detailUint("grpid") == KeywordAbilityIds.TRAINING }
                    .shouldBeEmpty()
            }
        }
    })
