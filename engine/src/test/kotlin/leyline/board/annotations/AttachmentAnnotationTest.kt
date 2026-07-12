package leyline.board.annotations

import forge.game.ability.AbilityKey
import forge.game.event.GameEventCardAttachment
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Attachment annotation pipeline: verifies that aura/equipment attachment
 * events produce the correct transient (AttachmentCreated) and persistent
 * (Attachment) annotations in the GSM.
 */
class AttachmentAnnotationTest :
    BoardTest({

        test("attachment produces transient and persistent annotations") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Pacifism", human, ZoneType.Hand)
                }
            val game = board.game
            val human = board.human
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val auraCard = human.getZone(ZoneType.Hand).cards.first()

            game.action.moveToPlay(auraCard, null, AbilityKey.newMap())
            val gsm =
                board.snapshotDiff {
                    game.fireEvent(GameEventCardAttachment(auraCard, null, creature))
                }

            // The manually fired attachment event doesn't move the aura through a real
            // attach, so resolve its iid by card id rather than by battlefield lookup.
            val auraIid = board.instanceId(auraCard.id)
            val creatureIid = human.battlefield.iid("Grizzly Bears")

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
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Pacifism", human, ZoneType.Hand)
                }
            val game = board.game
            val human = board.human
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val auraCard = human.getZone(ZoneType.Hand).cards.first()

            game.action.moveToPlay(auraCard, null, AbilityKey.newMap())
            val gsm =
                board.snapshotDiff {
                    game.fireEvent(GameEventCardAttachment(auraCard, creature, null))
                }

            gsm.annotationsList
                .firstOrNull {
                    AnnotationType.AttachmentCreated in it.typeList
                }.shouldBeNull()
        }
    })
