package leyline.behavior.annotations.displaycardundercard

import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.beInExileOf
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * DisplayCardUnderCard lifecycle — the annotation means "exiled card shown
 * tucked under a different source permanent".
 *
 * The two cases that define it: an exiling permanent claims the card it
 * exiled and releases it when destroyed, and a spell that exiles *itself*
 * emits nothing, because a card cannot be displayed under itself.
 */
private val SELF_EXILE_PUZZLE =
    """
    [metadata]
    Name:Flashback self-exile - no under-card
    Goal:Win
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=2

    humanhand=Think Twice
    humanbattlefield=Island;Island;Island;Island;Island;Island
    humanlibrary=Coral Merfolk;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class DisplayCardUnderCardTest :
    SessionTest({
        session(
            "Banishing Light exile emits DisplayCardUnderCard, Disenchant removes it",
            """
            [metadata]
            Name:Exile Under Card
            Goal:Win
            Turns:3
            Difficulty:Easy
            Description:DisplayCardUnderCard lifecycle test.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=1

            humanhand=Banishing Light;Disenchant
            humanbattlefield=Plains;Plains;Plains;Plains;Plains
            humanlibrary=Plains
            aibattlefield=Grizzly Bears
            ailibrary=Forest
            """.trimIndent(),
        ) {
            phase() shouldBe "MAIN1"

            // --- Phase 1: Cast Banishing Light, target Grizzly Bears ---
            val phase1 =
                after {
                    castSpellByName("Banishing Light").shouldBeTrue()

                    // The only legal target is selected by the engine.
                    passUntil(maxPasses = 10) {
                        ai.getZone(ZoneType.Battlefield).cards.none { it.name == "Grizzly Bears" }
                    }.shouldBeTrue()
                }

            // Verify Grizzly Bears is in exile
            ai
                .getZone(ZoneType.Exile)
                .cards
                .any { it.name == "Grizzly Bears" }
                .shouldBeTrue()

            // Check persistent annotations for DisplayCardUnderCard.
            // Pick the latest GSM that actually carries the persistent
            // annotation — the trailing post-content echo GSM has no
            // persistent annotations, so `gsms.last()` would hit the empty
            // echo and report 0.
            val gsms = phase1.messages.mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
            gsms.size shouldBeGreaterThan 0

            val underCardAnns =
                gsms
                    .flatMap { it.persistentAnnotationsList }
                    .filter { it.typeList.any { t -> t == AnnotationType.DisplayCardUnderCard } }

            underCardAnns.size shouldBe 1
            // Resolve Banishing Light iid (on battlefield, stable at this point)
            val banishingIid = human.battlefield.iid("Banishing Light")
            underCardAnns[0].affectorId shouldBe banishingIid
            underCardAnns[0].affectedIdsCount shouldBe 1

            // --- Phase 2: Cast Disenchant to destroy Banishing Light ---

            val phase2 =
                after {
                    castSpellUntilSelectTargetsReq("Disenchant")
                    selectTargets(listOf(banishingIid))

                    passUntil(maxPasses = 15) {
                        ai.getZone(ZoneType.Battlefield).cards.any { it.name == "Grizzly Bears" }
                    }.shouldBeTrue()
                }

            // Verify Grizzly Bears is back on battlefield
            ai
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Grizzly Bears" }
                .shouldBeTrue()

            // Verify DisplayCardUnderCard annotation is removed
            val gsms2 = phase2.messages.mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
            val lastGsm2 = gsms2.last()
            val remainingUnderCard =
                lastGsm2.persistentAnnotationsList
                    .filter { it.typeList.any { t -> t == AnnotationType.DisplayCardUnderCard } }
            remainingUnderCard.shouldBeEmpty()
        }

        session(
            "flashback self-exile emits no under-card annotation",
            puzzle = SELF_EXILE_PUZZLE,
        ) {
            // Forge sets `exiledWith` to the ChangeZone host when a graveyard-cast
            // spell exiles itself on resolution, and that host is the spell card
            // itself. Without a self-reference guard the collector reports
            // CardExiled with sourceCardId == cardId, and the annotation comes out
            // claiming the card is displayed under itself.
            //
            // Think Twice (Flashback {2}{U}) and Winternight Stories (Harmonize
            // {4}{U}) share the identical self-exile path.
            castSpellByName("Think Twice").shouldBeTrue()
            passPriority()

            val slice =
                after {
                    castFromGraveyard("Think Twice").shouldBeTrue()
                    passPriority()
                }

            "Think Twice" should beInExileOf(human)

            val selfReferential =
                slice.messages
                    .gameStateMessages()
                    .flatMap { it.persistentAnnotationsList }
                    .filter { AnnotationType.DisplayCardUnderCard in it.typeList }
                    .filter { it.affectedIdsList == listOf(it.affectorId) }

            selfReferential.shouldBeEmpty()
        }
    })
