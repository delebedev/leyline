package leyline.behavior.annotations.displaycardundercard

import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import leyline.testkit.SessionTest
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Regression: a spell exiled by its own resolution (Flashback / Harmonize "then
 * exile it") must NOT emit a self-referential DisplayCardUnderCard annotation.
 *
 * Forge sets `exiledWith` to the ChangeZone host when a graveyard-cast spell
 * exiles itself on resolution; the host is the spell card itself. Without a
 * self-reference guard, [GameEventCollector] reports CardExiled with
 * sourceCardId == cardId and [MechanicAnnotations] emits DisplayCardUnderCard
 * with affectorId == its sole affectedId. The annotation means "exiled card
 * shown tucked under a different source permanent"; a card cannot be displayed
 * under itself, so a self-exiling spell must emit no under-card annotation at
 * all.
 *
 * Think Twice (Flashback {2}{U}) and Winternight Stories (Harmonize {4}{U})
 * share the identical self-exile code path, so the simplest deterministic case
 * also covers Harmonize.
 */
private val PUZZLE =
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

class SelfExileNoUnderCardTest :
    SessionTest({
        test("flashback self-exile does not emit self-referential DisplayCardUnderCard") {
            startPuzzleRaw(PUZZLE, validating = true)

            // Cast from hand → resolves into graveyard.
            castSpellByName("Think Twice").shouldBeTrue()
            passPriority()

            // Flashback from graveyard → resolves and exiles itself. Observe the
            // frames produced while the spell self-exiles.
            val slice =
                after {
                    castFromGraveyard("Think Twice").shouldBeTrue()
                    passPriority()
                }

            // Sanity: the spell really did self-exile.
            human
                .getZone(ZoneType.Exile)
                .cards
                .any { it.name == "Think Twice" }
                .shouldBeTrue()

            // No DisplayCardUnderCard may have its affector equal to its own
            // (sole) affected id.
            val selfReferential =
                slice.messages
                    .gameStateMessages()
                    .flatMap { it.persistentAnnotationsList }
                    .filter { AnnotationType.DisplayCardUnderCard in it.typeList }
                    .filter { it.affectedIdsList == listOf(it.affectorId) }

            selfReferential.shouldBeEmpty()
        }
    })
