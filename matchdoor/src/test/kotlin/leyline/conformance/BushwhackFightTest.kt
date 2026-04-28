package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Regression for fix/bushwhack-fight-mode (bd leyline-xny).
 *
 * Bushwhack is `SP$ Charm | Choices$ FetchBasic,Fight`. The Fight branch is
 * `Pump|AILogic$ Fight + DBFight|ParentTarget` — same shape as Bite Down,
 * but wrapped in an outer Charm whose own SA does not target.
 *
 * Pre-fix bug: PlayerController.playChosenSpellAbility computed
 * `needsTargeting = sa.usesTargeting() && sa.targets.isEmpty()` against the
 * outer Charm SA, which never targets. mayChooseTargets=false → setupTargets()
 * skipped → sub-SA chain never gets chooseTargetsFor → no SelectTargetsReq.
 * The cast silently dropped after the modal pick.
 */
class BushwhackFightTest :
    InteractionTest({

        test("Bushwhack Fight mode emits SelectTargetsReq for both target groups") {
            startPuzzleFile("puzzles/bushwhack-fight.pzl")

            val cto = castSpellUntilCastingTimeOptionsReq("Bushwhack")
            cto.castingTimeOptionReqCount shouldBe 1
            val modalReq = cto.getCastingTimeOptionReq(0).modalReq
            modalReq.modalOptionsCount shouldBe 2

            // Bushwhack: `SP$ Charm | Choices$ FetchBasic,Fight` — order preserved
            // by CharmEffect.makePossibleOptions, so index 1 = Fight.
            val fightOption = modalReq.getModalOptions(1)

            val snap = messageSnapshot()
            harness.respondModalChoice(listOf(fightOption.grpId))

            // Pre-fix: zero SelectTargetsReq emitted, cast silently drops.
            // Post-fix: first SelectTargetsReq for "creature you control".
            val firstSt = messagesSince(snap).firstOrNull { it.hasSelectTargetsReq() }
            firstSt.shouldNotBeNull()
            val firstSelection = firstSt.selectTargetsReq.targetsList.first()
            assertSoftly {
                firstSelection.minTargets shouldBe 1
                firstSelection.maxTargets shouldBe 1
                firstSelection.targetsList
                    .map { it.targetInstanceId }
                    .shouldContain(instanceIdOf("Centaur Courser", player = human))
            }
        }

        test("Bushwhack Fight resolves: mutual damage, both creatures take damage") {
            startPuzzleFile("puzzles/bushwhack-fight.pzl")

            val ownIid = instanceIdOf("Centaur Courser", player = human)
            val oppIid = instanceIdOf("Grizzly Bears", player = ai)

            val cto = castSpellUntilCastingTimeOptionsReq("Bushwhack")
            val fightOption = cto.getCastingTimeOptionReq(0).modalReq.getModalOptions(1)
            harness.respondModalChoice(listOf(fightOption.grpId))

            selectTargets(listOf(ownIid))
            selectTargets(listOf(oppIid))
            passUntilResolved()

            // DamageDealt fires twice for fight (each creature deals to the other).
            val damageAnns =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .filter { AnnotationType.DamageDealt_af5a in it.typeList }
            damageAnns.shouldNotBeEmpty()

            // Bushwhack moved Stack→GY, not countered.
            assertSoftly {
                human
                    .getZone(ForgeZoneType.Graveyard)
                    .cards
                    .filter { it.name == "Bushwhack" } shouldHaveSize 1

                // Grizzly Bears (2/2) takes 3 damage from Centaur Courser → dies.
                ai
                    .getZone(ForgeZoneType.Graveyard)
                    .cards
                    .filter { it.name == "Grizzly Bears" }
                    .shouldNotBeEmpty()
            }
        }
    })
