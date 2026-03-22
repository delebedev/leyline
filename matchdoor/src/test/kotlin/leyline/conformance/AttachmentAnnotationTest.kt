package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.event.GameEventCardAttachment
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.game.BundleBuilder
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Attachment annotation pipeline: verifies that aura/equipment attachment
 * events produce the correct transient (AttachmentCreated) and persistent
 * (Attachment) annotations in the GSM.
 */
class AttachmentAnnotationTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("attachment produces transient and persistent annotations") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Holy Strength", human, ZoneType.Hand)
            }
            val human = game.humanPlayer
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val auraCard = human.getZone(ZoneType.Hand).cards.first()

            game.action.moveToPlay(auraCard, null, AbilityKey.newMap())
            b.snapshotFromGame(game, counter.currentGsId())
            game.fireEvent(GameEventCardAttachment(auraCard, null, creature))

            val result = BundleBuilder.stateOnlyDiff(game, b, ConformanceTestBase.TEST_MATCH_ID, ConformanceTestBase.SEAT_ID, counter)
            val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

            val auraIid = b.getOrAllocInstanceId(ForgeCardId(auraCard.id)).value
            val creatureIid = b.getOrAllocInstanceId(ForgeCardId(creature.id)).value

            val attachCreated = gsm.annotationsList.firstOrNull {
                AnnotationType.AttachmentCreated in it.typeList
            }
            attachCreated.shouldNotBeNull()
            attachCreated.affectedIdsList shouldBe listOf(creatureIid)
            attachCreated.affectorId shouldBe auraIid

            val attachPersistent = gsm.persistentAnnotationsList.firstOrNull {
                AnnotationType.Attachment in it.typeList
            }
            attachPersistent.shouldNotBeNull()
            attachPersistent.affectedIdsList shouldBe listOf(creatureIid)
            attachPersistent.affectorId shouldBe auraIid
            attachPersistent.id shouldBeGreaterThan 0
        }

        test("detach does not produce AttachmentCreated") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Holy Strength", human, ZoneType.Hand)
            }
            val human = game.humanPlayer
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val auraCard = human.getZone(ZoneType.Hand).cards.first()

            game.action.moveToPlay(auraCard, null, AbilityKey.newMap())
            b.snapshotFromGame(game, counter.currentGsId())
            game.fireEvent(GameEventCardAttachment(auraCard, creature, null))

            val result = BundleBuilder.stateOnlyDiff(game, b, ConformanceTestBase.TEST_MATCH_ID, ConformanceTestBase.SEAT_ID, counter)
            val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

            gsm.annotationsList.firstOrNull {
                AnnotationType.AttachmentCreated in it.typeList
            }.shouldBeNull()
        }
    })
