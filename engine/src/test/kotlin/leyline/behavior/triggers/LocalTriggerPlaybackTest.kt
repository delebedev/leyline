package leyline.behavior.triggers

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.after
import leyline.testkit.annotationsOfType
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

class LocalTriggerPlaybackTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Ajani's Pridemate")
            TestCardRegistry.ensureCardRegistered("Dwynen's Elite")
            TestCardRegistry.ensureCardRegistered("Novice Inspector")
            TestCardRegistry.ensureCardRegistered("Revitalize")
        }

        fun assertTriggerSplit(
            messages: List<GREToClientMessage>,
            abilityGrpId: Int? = null,
        ) {
            val triggerAbilityIids = messages.persistentAnnotationsOfType(AnnotationType.TriggeringObject).map { it.affectorId }.toSet()
            val gsms = messages.gameStateMessages()
            val created =
                gsms.firstNotNullOfOrNull { gsm ->
                    gsm.annotationsList.firstOrNull {
                        AnnotationType.AbilityInstanceCreated in it.typeList &&
                            it.affectedIdsList.singleOrNull() in triggerAbilityIids
                    }
                } ?: error(
                    "missing trigger AbilityInstanceCreated; TriggeringObject iids=$triggerAbilityIids; " +
                        "annotations=${gsms.flatMap { gsm -> gsm.annotationsList.map { it.typeList } }}",
                )
            val abilityIid = created.affectedIdsList.single()
            val triggerEnterIdx =
                gsms.indexOfFirst { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.AbilityInstanceCreated in it.typeList &&
                            it.affectedIdsList.contains(abilityIid)
                    }
                }
            val resolveIdx =
                gsms.indexOfFirst { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList &&
                            it.affectorId == abilityIid &&
                            (abilityGrpId == null || it.detailUint("grpid") == abilityGrpId)
                    }
                }

            assertSoftly("local non-interactive trigger lifecycle") {
                triggerEnterIdx shouldBeGreaterThan -1
                resolveIdx shouldBeGreaterThan triggerEnterIdx
                messages
                    .persistentAnnotationsOfType(
                        AnnotationType.TriggeringObject,
                    ).filter { it.affectorId == abilityIid }
                    .shouldNotBeEmpty()
                messages
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .filter {
                        it.affectorId == abilityIid &&
                            (abilityGrpId == null || it.detailUint("grpid") == abilityGrpId)
                    }.shouldNotBeEmpty()
            }
        }

        val ajaniPuzzle =
            """
            [metadata]
            Name:Ajani Pridemate Local Trigger
            Goal:Survive
            Turns:3
            Difficulty:Easy
            Description:Revitalize gains life, Ajani's Pridemate trigger enters, then resolves.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Revitalize
            humanbattlefield=Ajani's Pridemate;Plains;Plains
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        val dwynenPuzzle =
            """
            [metadata]
            Name:Dwynen Elite Local Token Trigger
            Goal:Survive
            Turns:3
            Difficulty:Easy
            Description:Dwynen's Elite enters with another Elf, creates an Elf Warrior token.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Dwynen's Elite
            humanbattlefield=Llanowar Elves;Forest;Forest
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        val noviceInspectorPuzzle =
            """
            [metadata]
            Name:Novice Inspector Local Investigate Trigger
            Goal:Survive
            Turns:3
            Difficulty:Easy
            Description:Novice Inspector enters, investigate trigger enters, then creates a Clue token.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Novice Inspector
            humanbattlefield=Plains
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        session("mandatory non-interactive local trigger enters before resolving", puzzle = ajaniPuzzle) {
            val post =
                after {
                    castSpellByName("Revitalize").shouldBeTrue()
                    passUntil(maxPasses = 20) {
                        human
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .first { it.name == "Ajani's Pridemate" }
                            .getCounters(CounterEnumType.P1P1) == 1
                    }.shouldBeTrue()
                }.messages

            assertTriggerSplit(post, abilityGrpId = 92970)
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .first { it.name == "Ajani's Pridemate" }
                .getCounters(CounterEnumType.P1P1) shouldBe 1
        }

        session("mandatory non-interactive token trigger enters before resolving", puzzle = dwynenPuzzle) {
            val post =
                after {
                    castSpellByName("Dwynen's Elite").shouldBeTrue()
                    passUntil(maxPasses = 20) {
                        human
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .any { "Elf Warrior" in it.name && it.isToken }
                    }.shouldBeTrue()
                }.messages

            assertTriggerSplit(post)
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { "Elf Warrior" in it.name && it.isToken }
                .shouldNotBeEmpty()
        }

        session(
            "mandatory non-interactive investigate trigger enters before resolving",
            puzzle = noviceInspectorPuzzle,
            validation =
                InvariantSelection.except(
                    "Clue token ZoneTransfer affectedIds are unresolved until token projection is fixed (leyline-g8bw)",
                    InvariantCheck.AnnotationReferences,
                ),
        ) {
            val post =
                after {
                    castSpellByName("Novice Inspector").shouldBeTrue()
                    passUntil(maxPasses = 20) {
                        human
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .any { it.name.contains("Clue", ignoreCase = true) && it.isToken }
                    }.shouldBeTrue()
                }.messages

            assertTriggerSplit(post)
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.name.contains("Clue", ignoreCase = true) && it.isToken }
                .shouldNotBeEmpty()
        }
    })
