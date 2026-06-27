package leyline.board.annotations

import forge.game.ability.AbilityKey
import forge.game.event.GameEventCardAttachment
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.types.ForgeCardId
import leyline.game.seedDiffBaseline
import leyline.testkit.BoardTestBase
import leyline.testkit.gsm
import leyline.testkit.gsmOrNull
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Attachment annotation pipeline: verifies that aura/equipment attachment
 * events produce the correct transient (AttachmentCreated) and persistent
 * (Attachment) annotations in the GSM.
 */
class AttachmentAnnotationTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("attachment produces transient and persistent annotations") {
            val (b, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    base.addCard("Pacifism", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val auraCard = human.getZone(ZoneType.Hand).cards.first()

            game.action.moveToPlay(auraCard, null, AbilityKey.newMap())
            b.seedDiffBaseline(game, counter.currentGsId())
            game.fireEvent(GameEventCardAttachment(auraCard, null, creature))

            val result = base.bundleBuilder(b).stateOnlyDiff(game, counter)
            val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

            val auraIid = b.getOrAllocInstanceId(ForgeCardId(auraCard.id)).value
            val creatureIid = b.getOrAllocInstanceId(ForgeCardId(creature.id)).value

            val attachCreated =
                gsm.annotationsList.firstOrNull {
                    AnnotationType.AttachmentCreated in it.typeList
                }
            assertSoftly {
                attachCreated.shouldNotBeNull()
                attachCreated.affectedIdsList shouldBe listOf(creatureIid)
                attachCreated.affectorId shouldBe auraIid
            }

            val attachPersistent =
                gsm.persistentAnnotationsList.firstOrNull {
                    AnnotationType.Attachment in it.typeList
                }
            assertSoftly {
                attachPersistent.shouldNotBeNull()
                attachPersistent.affectedIdsList shouldBe listOf(creatureIid)
                attachPersistent.affectorId shouldBe auraIid
                attachPersistent.id shouldBeGreaterThan 0
            }
        }

        test("detach does not produce AttachmentCreated") {
            val (b, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    base.addCard("Pacifism", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val auraCard = human.getZone(ZoneType.Hand).cards.first()

            game.action.moveToPlay(auraCard, null, AbilityKey.newMap())
            b.seedDiffBaseline(game, counter.currentGsId())
            game.fireEvent(GameEventCardAttachment(auraCard, creature, null))

            val result = base.bundleBuilder(b).stateOnlyDiff(game, counter)
            val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

            gsm.annotationsList
                .firstOrNull {
                    AnnotationType.AttachmentCreated in it.typeList
                }.shouldBeNull()
        }
    })
