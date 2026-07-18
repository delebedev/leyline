package leyline.behavior.mechanics.abilitywordactive

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.allAnnotations
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.firstGameObjectByIid
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

class OpusAbilityWordLifecycleTest :
    SessionTest({
        fun castTargetedSpell(
            spellName: String,
            targetIid: Int,
        ): List<GREToClientMessage> =
            after {
                castSpellByName(spellName).shouldBeTrue()
                selectTargetsIterative(listOf(targetIid))
                submitTargets()
                passUntilResolved(maxPasses = 20)
            }.messages

        test("below-five Opus trigger has no active marker") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Tackle Artist;Mountain
                humanhand=Shock
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Forest;Forest;Forest
                """,
                name = "Opus below five",
            )
            val source = human.getZone(forge.game.zone.ZoneType.Battlefield).cards.first { it.name == "Tackle Artist" }
            val target = instanceIdOf("Grizzly Bears", ai)

            val messages = castTargetedSpell("Shock", target)

            messages.opusMarkers().shouldBeEmpty()
            source.getCounters(CounterEnumType.P1P1) shouldBe 1
        }

        test("five-plus Opus marker binds player to the exact trigger ability and is deleted on resolution") {
            startPuzzle(
                opusPuzzle(sourceCount = 1),
                name = "Opus five plus",
            )
            val source = human.getZone(forge.game.zone.ZoneType.Battlefield).cards.first { it.name == "Tackle Artist" }
            val target = instanceIdOf("Grizzly Bears", ai)

            val messages = castTargetedSpell("Unfriendly Fire", target)
            val marker = messages.opusMarkers().single()
            val abilityIid = marker.affectedIdsList.single()
            val triggeringObject =
                messages
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .single { it.affectorId == abilityIid }
            val createdAbilityIids =
                messages
                    .allAnnotations()
                    .filter { AnnotationType.AbilityInstanceCreated in it.typeList }
                    .flatMap { it.affectedIdsList }
            val triggeringSpellIid = triggeringObject.affectedIdsList.single()
            val triggeringSpell = messages.firstGameObjectByIid(triggeringSpellIid)!!

            assertSoftly {
                marker.affectorId shouldBe 1
                marker.detailsList.map { it.key }.toSet() shouldBe setOf("AbilityWordName")
                createdAbilityIids shouldContainAll listOf(abilityIid)
                triggeringObject.detailInt("source_zone") shouldBe 27
                triggeringSpell.grpId shouldBe 66309
                triggeringSpell.zoneId shouldBe 27
                messages.deletedPersistentAnnotationIds() shouldContainAll setOf(marker.id, triggeringObject.id)
                source.getCounters(CounterEnumType.P1P1) shouldBe 2
            }
        }

        test("two Opus sources retain distinct live ability identities") {
            startPuzzle(
                opusPuzzle(sourceCount = 2),
                name = "Two Opus sources",
            )
            val target = instanceIdOf("Grizzly Bears", ai)

            val messages = castTargetedSpell("Unfriendly Fire", target)
            val markers = messages.opusMarkers()
            val abilityIids = markers.flatMap { it.affectedIdsList }.toSet()
            val triggerObjects =
                messages
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .filter { it.affectorId in abilityIids }
            val triggeringSpellIids = triggerObjects.map { it.affectedIdsList.single() }.toSet()

            assertSoftly {
                markers shouldHaveSize 2
                abilityIids shouldHaveSize 2
                markers.map { it.affectorId }.toSet() shouldBe setOf(1)
                triggerObjects.map { it.affectorId }.toSet() shouldBe abilityIids
                triggeringSpellIids shouldHaveSize 1
                messages.firstGameObjectByIid(triggeringSpellIids.single())!!.grpId shouldBe 66309
                messages.deletedPersistentAnnotationIds() shouldContainAll markers.map { it.id }
            }
        }

        test("five-mana non-Opus spell without an Opus source emits no marker") {
            startPuzzle(
                opusPuzzle(sourceCount = 0),
                name = "No Opus source",
            )
            val target = instanceIdOf("Grizzly Bears", ai)

            castTargetedSpell("Unfriendly Fire", target).opusMarkers().shouldBeEmpty()
        }
    })

private fun opusPuzzle(sourceCount: Int): String {
    val sources = List(sourceCount) { "Tackle Artist" }.joinToString(";")
    val battlefield =
        listOf(
            sources,
            "Mountain",
            "Mountain",
            "Mountain",
            "Mountain",
            "Mountain",
        ).filter { it.isNotEmpty() }.joinToString(";")
    return """
        ActivePlayer=Human
        ActivePhase=Main1
        HumanLife=20
        AILife=20

        humanbattlefield=$battlefield
        humanhand=Unfriendly Fire
        humanlibrary=Mountain;Mountain;Mountain
        aibattlefield=Grizzly Bears
        ailibrary=Forest;Forest;Forest
    """
}

private fun List<GREToClientMessage>.opusMarkers() =
    persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
        .filter { it.detailString("AbilityWordName") == "Opus" }
