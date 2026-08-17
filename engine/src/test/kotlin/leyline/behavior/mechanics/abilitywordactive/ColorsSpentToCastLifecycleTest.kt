package leyline.behavior.mechanics.abilitywordactive

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.allAnnotations
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailIntList
import leyline.testkit.detailString
import leyline.testkit.firstGameObjectByIid
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class ColorsSpentToCastLifecycleTest :
    SessionTest({
        session(
            "one paid color follows the cast spell through the triggered ability lifecycle",
            puzzle = colorsPuzzle("Plains;Plains;Plains;Plains;Plains;Plains"),
        ) {
            val target = instanceIdOf("Soul Warden", ai)

            castSpellByName("Sundering Archaic").shouldBeTrue()
            passUntil(maxPasses = 20) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()

            val lifecycleMessages = allMessages
            val spellMarker = lifecycleMessages.colorsMarkerFor(GameObjectType.Card)
            val spellIid = spellMarker.affectedIdsList.single()
            val spellObject = lifecycleMessages.firstGameObjectByIid(spellIid)!!

            assertSoftly {
                lifecycleMessages
                    .colorsMarkers()
                    .filter { marker ->
                        lifecycleMessages.firstGameObjectByIid(marker.affectedIdsList.single())?.type == GameObjectType.Card
                    }.distinctBy { it.id } shouldHaveSize 1
                spellMarker.affectorId shouldBe spellIid
                spellMarker.affectedIdsList shouldBe listOf(spellIid)
                spellMarker.detailIntList("colors") shouldBe listOf(1)
                spellObject.grpId shouldBe 102462
                spellObject.zoneId shouldBe 27
            }

            val resolutionMessages =
                after {
                    selectTargetsIterative(listOf(target))
                    submitTargets()
                    passUntilResolved(maxPasses = 20)
                    repeat(3) { passPriority() }
                }.messages
            val completeLifecycle = allMessages
            val triggerMarker =
                completeLifecycle
                    .colorsMarkers()
                    .filter { it.id != spellMarker.id }
                    .distinctBy { it.id }
                    .single()
            val abilityIid = triggerMarker.affectorId
            val triggeringObject =
                completeLifecycle
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .last { it.affectorId == abilityIid }
            val createdAbilityIids =
                completeLifecycle
                    .allAnnotations()
                    .filter { AnnotationType.AbilityInstanceCreated in it.typeList }
                    .flatMap { it.affectedIdsList }

            assertSoftly {
                triggerMarker.affectedIdsList shouldBe listOf(abilityIid)
                triggerMarker.detailIntList("colors") shouldBe listOf(1)
                createdAbilityIids shouldContain abilityIid
                completeLifecycle.deletedPersistentAnnotationIds() shouldContain spellMarker.id
                completeLifecycle.deletedPersistentAnnotationIds() shouldContain triggerMarker.id
                completeLifecycle.deletedPersistentAnnotationIds() shouldContain triggeringObject.id
                resolutionMessages.colorsMarkers() shouldContain triggerMarker
                ai.getZone(forge.game.zone.ZoneType.Exile).cards.map { it.name } shouldContain "Soul Warden"
            }
        }

        session(
            "multiple actually paid colors are preserved exactly",
            puzzle = colorsPuzzle("Plains;Mountain;Mountain;Mountain;Mountain;Mountain"),
        ) {
            val castMessages = after { castSpellByName("Sundering Archaic").shouldBeTrue() }.messages
            val marker = castMessages.colorsMarkers().last()

            assertSoftly {
                castMessages.colorsMarkers().distinctBy { it.id } shouldHaveSize 1
                marker.detailIntList("colors") shouldBe listOf(1, 4)
                marker.detailsList.map { it.key }.toSet() shouldBe setOf("AbilityWordName", "colors")
                marker.affectedIdsList shouldHaveSize 1
                marker.affectorId shouldBe marker.affectedIdsList.single()
            }
        }

        session(
            "generic spell paid with multiple colors emits no ColorsSpentToCast marker",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Plains;Mountain;Mountain;Mountain;Mountain
                humanhand=Unfriendly Fire
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Forest;Forest;Forest
                """,
        ) {
            val target = instanceIdOf("Grizzly Bears", ai)

            val messages =
                after {
                    castSpellByName("Unfriendly Fire").shouldBeTrue()
                    selectTargetsIterative(listOf(target))
                    submitTargets()
                    passUntilResolved(maxPasses = 20)
                }.messages

            messages.colorsMarkers().shouldHaveSize(0)
        }
    })

private fun colorsPuzzle(mana: String): String =
    """
        ActivePlayer=Human
        ActivePhase=Main1
        HumanLife=20
        AILife=20

        humanbattlefield=$mana
        humanhand=Sundering Archaic
        humanlibrary=Plains;Plains;Plains
        aibattlefield=Soul Warden;Soul Warden
        ailibrary=Forest;Forest;Forest
    """

private fun List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>.colorsMarkers() =
    persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
        .filter { it.detailString("AbilityWordName") == "ColorsSpentToCast" }

private fun List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>.colorsMarkerFor(type: GameObjectType) =
    colorsMarkers().lastOrNull { marker ->
        firstGameObjectByIid(marker.affectedIdsList.single())?.type == type
    } ?: error(
        "missing $type ColorsSpentToCast marker; markers=" +
            colorsMarkers().joinToString { marker ->
                val iid = marker.affectedIdsList.single()
                "id=${marker.id}/iid=$iid/object=${firstGameObjectByIid(iid)?.type}/colors=${marker.detailIntList("colors")}"
            },
    )
