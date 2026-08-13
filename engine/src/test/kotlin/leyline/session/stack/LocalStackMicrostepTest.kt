package leyline.session.stack

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

class LocalStackMicrostepTest :
    SessionTest({
        fun enableStackAutoResolve() {
            harness.session.autoPassState.update(
                SettingsMessage
                    .newBuilder()
                    .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                    .build(),
            )
        }

        fun GameStateMessage.annotationTypes(): Set<AnnotationType> =
            annotationsList
                .asSequence()
                .flatMap { it.typeList.asSequence() }
                .toSet()

        fun GameStateMessage.persistentTypes(): Set<AnnotationType> =
            persistentAnnotationsList
                .asSequence()
                .flatMap { it.typeList.asSequence() }
                .toSet()

        fun GameStateMessage.hasZoneTransfer(
            category: String,
            src: Int? = null,
            dest: Int? = null,
        ): Boolean =
            annotationsList.any { ann ->
                AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                    ann.detailString("category") == category &&
                    (src == null || ann.detailsList.any { it.key == "zone_src" && it.getValueInt32(0) == src }) &&
                    (dest == null || ann.detailsList.any { it.key == "zone_dest" && it.getValueInt32(0) == dest })
            }

        fun GameStateMessage.zoneTransferCategories(): List<String> =
            annotationsList
                .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
                .map { it.detailString("category") }

        test("own-turn auto-resolved creature cast emits stack entry before resolve") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Forest
                humanhand=Llanowar Elves
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "Local cast split",
            )
            enableStackAutoResolve()

            val messages = after { castSpellByName("Llanowar Elves").shouldBe(true) }.messages
            val gsms = messages.gameStateMessages()
            val castGsm = gsms.firstOrNull { it.hasZoneTransfer("CastSpell", dest = ZoneIds.STACK) }
            val resolveGsm = gsms.firstOrNull { it.hasZoneTransfer("Resolve", src = ZoneIds.STACK, dest = ZoneIds.BATTLEFIELD) }

            val castFrame = castGsm.shouldNotBeNull()
            val resolveFrame =
                withClue(
                    gsms.joinToString("\n") { gsm ->
                        "gs=${gsm.gameStateId} anns=${gsm.annotationTypes()} cats=${gsm.zoneTransferCategories()}"
                    },
                ) {
                    resolveGsm.shouldNotBeNull()
                }
            assertSoftly {
                castFrame.gameStateId shouldBeLessThan resolveFrame.gameStateId
                castFrame.annotationTypes() shouldNotContain AnnotationType.ResolutionStart
                castFrame.annotationTypes() shouldNotContain AnnotationType.ResolutionComplete
                resolveFrame.annotationTypes() shouldContain AnnotationType.ResolutionStart
                resolveFrame.annotationTypes() shouldContain AnnotationType.ResolutionComplete
            }
        }

        test("targeted spell uses cast, targets-confirmed, resolve slots") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanhand=Lightning Bolt
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "Targeted spell split",
            )
            enableStackAutoResolve()

            val castMessages = after { castSpellByName("Lightning Bolt").shouldBe(true) }.messages
            val castFrame =
                castMessages
                    .gameStateMessages()
                    .firstOrNull { AnnotationType.PlayerSelectingTargets in it.annotationTypes() }
                    .shouldNotBeNull()
            assertSoftly {
                castFrame.hasZoneTransfer("CastSpell", dest = ZoneIds.STACK) shouldBe true
                castFrame.annotationTypes() shouldNotContain AnnotationType.UserActionTaken
                castFrame.annotationTypes() shouldNotContain AnnotationType.ManaPaid
                castFrame.annotationTypes() shouldNotContain AnnotationType.ResolutionStart
            }

            selectTargetsIterative(listOf(OPPONENT_SEAT))
            val submitSnap = messageSnapshot()
            submitTargets()
            passUntilResolved()
            val postSubmitGsms = gameStateMessagesSince(submitSnap)

            val targetsConfirmed =
                postSubmitGsms.firstOrNull { AnnotationType.PlayerSubmittedTargets in it.annotationTypes() }
            val resolveGsm =
                postSubmitGsms.firstOrNull {
                    AnnotationType.ResolutionStart in it.annotationTypes() ||
                        AnnotationType.DamageDealt_af5a in it.annotationTypes() ||
                        it.hasZoneTransfer("Resolve", src = ZoneIds.STACK)
                }

            val targetsConfirmedFrame = targetsConfirmed.shouldNotBeNull()
            val resolveFrame =
                withClue(
                    postSubmitGsms.joinToString("\n") { gsm ->
                        "gs=${gsm.gameStateId} anns=${gsm.annotationTypes()} cats=${gsm.zoneTransferCategories()}"
                    },
                ) {
                    resolveGsm.shouldNotBeNull()
                }
            assertSoftly {
                targetsConfirmedFrame.gameStateId shouldBeLessThan resolveFrame.gameStateId
                targetsConfirmedFrame.annotationTypes() shouldContain AnnotationType.UserActionTaken
                targetsConfirmedFrame.annotationTypes() shouldContain AnnotationType.ManaPaid
                targetsConfirmedFrame.annotationTypes() shouldNotContain AnnotationType.ResolutionStart
                targetsConfirmedFrame.annotationTypes() shouldNotContain AnnotationType.DamageDealt_af5a
                resolveFrame.annotationTypes() shouldContain AnnotationType.DamageDealt_af5a
            }
        }

        test("local ETB trigger enters stack before resolving") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Soul Warden;Forest
                humanhand=Llanowar Elves
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "ETB trigger split",
            )
            enableStackAutoResolve()

            val messages = after { castSpellByName("Llanowar Elves").shouldBe(true) }.messages
            val gsms = messages.gameStateMessages()
            val triggerEnter = gsms.firstOrNull { AnnotationType.TriggeringObject in it.persistentTypes() }
            triggerEnter.shouldNotBeNull()
            val triggeringObject = triggerEnter.persistentAnnotationsList.first { AnnotationType.TriggeringObject in it.typeList }
            val resolveGsm =
                gsms.firstOrNull { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList && it.affectorId == triggeringObject.affectorId
                    }
                }

            resolveGsm.shouldNotBeNull()
            assertSoftly {
                triggerEnter.annotationTypes() shouldContain AnnotationType.AbilityInstanceCreated
                triggerEnter.annotationTypes() shouldNotContain AnnotationType.ResolutionStart
                triggerEnter.annotationTypes() shouldNotContain AnnotationType.ResolutionComplete
                triggerEnter.annotationTypes() shouldNotContain AnnotationType.AbilityInstanceDeleted
                triggerEnter.gameStateId shouldBeLessThan resolveGsm.gameStateId
                resolveGsm.annotationTypes() shouldContain AnnotationType.ResolutionStart
                resolveGsm.annotationTypes() shouldContain AnnotationType.ResolutionComplete
                resolveGsm.annotationTypes() shouldContain AnnotationType.AbilityInstanceDeleted
                withClue("TriggeringObject id should be deleted when the trigger resolves") {
                    resolveGsm.diffDeletedPersistentAnnotationIdsList shouldContain triggeringObject.id
                }
            }
        }

        test("counter add and trigger resolution share one finalized lifecycle frame") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Soul Warden;Ajani's Pridemate;Forest
                humanhand=Llanowar Elves
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "Counter microstep split",
            )
            enableStackAutoResolve()

            val messages = after { castSpellByName("Llanowar Elves").shouldBe(true) }.messages
            val counterGsm =
                messages
                    .gameStateMessages()
                    .firstOrNull { AnnotationType.CounterAdded in it.annotationTypes() }

            counterGsm.shouldNotBeNull()
            val annotationTypes = counterGsm.annotationsList.mapNotNull { it.typeList.firstOrNull() }
            assertSoftly {
                annotationTypes shouldContain AnnotationType.CounterAdded
                annotationTypes shouldContain AnnotationType.ResolutionStart
                annotationTypes shouldContain AnnotationType.ResolutionComplete
                annotationTypes shouldContain AnnotationType.AbilityInstanceDeleted
                annotationTypes.take(4) shouldBe
                    listOf(
                        AnnotationType.ResolutionStart,
                        AnnotationType.ResolutionComplete,
                        AnnotationType.AbilityInstanceDeleted,
                        AnnotationType.CounterAdded,
                    )
            }
        }

        test("local activated ability enters stack before resolving") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=5

                humanbattlefield=Goblin Fireslinger
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
                name = "Activated ability split",
            )
            enableStackAutoResolve()

            activateAbility("Goblin Fireslinger").shouldBe(true)
            passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBe(true)

            val submitSnap = messageSnapshot()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntil(maxPasses = 10) { ai.life < 5 }
            val gsms = gameStateMessagesSince(submitSnap)
            val abilityEnter =
                gsms.firstOrNull { gsm ->
                    gsm.annotationsList.any { AnnotationType.AbilityInstanceCreated in it.typeList }
                }
            val abilityIid =
                abilityEnter
                    .shouldNotBeNull()
                    .annotationsList
                    .first { AnnotationType.AbilityInstanceCreated in it.typeList }
                    .affectedIdsList
                    .first()
            val resolveGsm =
                gsms.firstOrNull { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList && it.affectorId == abilityIid
                    }
                }

            val resolveFrame =
                withClue(
                    gsms.joinToString("\n") { gsm ->
                        "gs=${gsm.gameStateId} anns=${gsm.annotationTypes()} cats=${gsm.zoneTransferCategories()}"
                    },
                ) {
                    resolveGsm.shouldNotBeNull()
                }
            assertSoftly {
                abilityEnter.gameStateId shouldBeLessThan resolveFrame.gameStateId
                abilityEnter.annotationTypes() shouldNotContain AnnotationType.ResolutionStart
                abilityEnter.annotationTypes() shouldNotContain AnnotationType.ResolutionComplete
                abilityEnter.annotationTypes() shouldNotContain AnnotationType.AbilityInstanceDeleted
                resolveFrame.annotationTypes() shouldContain AnnotationType.ResolutionStart
                resolveFrame.annotationTypes() shouldContain AnnotationType.ResolutionComplete
                resolveFrame.annotationTypes() shouldContain AnnotationType.AbilityInstanceDeleted
            }
        }
    })
