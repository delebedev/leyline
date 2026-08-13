package leyline.game.annotations

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.SeatId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * TargetSpec persistent annotation tests — verifies targeting arrows emitted
 * via the bridge-side pending target store.
 *
 * Completed target groups are recorded after chooseTargetsFor and stored on
 * InteractivePromptBridge. The mapper computes from pending records without
 * consuming them; GameBridge.applyMutations consumes them after the persistent
 * annotation batch is committed.
 */
class TargetSpecAnnotationTest :
    BoardTest({

        test("same-row stack abilities resolve TargetSpec by exact ability id") {
            val (b, _) =
                startWithBoard { _, human, _ ->
                    addCard("Goblin Fireslinger", human, ZoneType.Battlefield)
                }
            val source =
                b
                    .getPlayer(SeatId(1))!!
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Goblin Fireslinger" }
            val abilityGrpId = 77
            val firstAbilityId = 501
            val secondAbilityId = 502
            val stack =
                StackSnapshot(
                    listOf(firstAbilityId, secondAbilityId).map { abilityId ->
                        StackEntry(
                            forgeCardId = ForgeCardId(source.id),
                            controller = SeatId(1),
                            owner = SeatId(1),
                            grpId = abilityGrpId,
                            sourceCardGrpId = 1,
                            isSpell = false,
                            targets = emptyList(),
                            forgeAbilityId = abilityId,
                        )
                    },
                )
            val snap = GsmSnapshot.forTest(stack = stack)
            val ctx =
                AnnotationContext(
                    b,
                    snap,
                    FrameIdResolver(b),
                    emptyList(),
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                )
            val spec =
                InteractivePromptBridge.PendingTarget(
                    spellForgeCardId = source.id,
                    spellName = source.name,
                    index = 1,
                    affectorInstanceIdAtRecord = 0,
                    affectees = listOf(InteractivePromptBridge.PendingTarget.TargetAffectee(targetSeatId = 2)),
                    isStackAbility = true,
                    abilityIdentity =
                        ResolvedAbilityIdentity(
                            definition = AbilityDefinitionRef.SpellAbility(1),
                            abilityGrpId = abilityGrpId,
                        ),
                    forgeAbilityId = secondAbilityId,
                )

            ctx.targetSpecStackAbilityIid(spec) shouldBe
                b.getOrAllocInstanceId(FrameIdResolver.triggerStackAbilityForgeId(secondAbilityId))
        }

        test("pending target spec emits TargetSpec persistent annotation") {
            val (b, game) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Murder", human, ZoneType.Hand)
                }

            val creature =
                b
                    .getPlayer(SeatId(1))!!
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val spell =
                b
                    .getPlayer(SeatId(1))!!
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Murder" }

            // Simulate completed chooseTargetsFor state: add the pending group.
            val target =
                InteractivePromptBridge.PendingTarget(
                    spellForgeCardId = spell.id,
                    spellName = spell.name,
                    affectees =
                        listOf(
                            InteractivePromptBridge.PendingTarget.TargetAffectee(targetForgeCardId = creature.id),
                        ),
                    index = 1,
                    affectorInstanceIdAtRecord = b.getOrAllocInstanceId(ForgeCardId(spell.id)).value,
                )
            b.seat(SeatId(1)).prompt.addPendingTargetSpec(target)

            val snapTarget1 = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snapTarget1,
                        1,
                        Board.TEST_MATCH_ID,
                        b,
                        promptFacts = b.materializePromptProjectionFacts(),
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            val targetAnn =
                gs.persistentAnnotationsList.firstOrNull { ann ->
                    AnnotationType.TargetSpec in ann.typeList
                }
            assertSoftly {
                targetAnn shouldNotBe null
                targetAnn!!.affectedIdsList.size shouldBe 1
                targetAnn.detailInt("index") shouldBe 1
                targetAnn.detailInt("abilityGrpId") shouldBeGreaterThan 0
            }
        }

        test("pending target spec is consumed only when mapper mutations apply") {
            val (b, game) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Murder", human, ZoneType.Hand)
                }

            val creature =
                b
                    .getPlayer(SeatId(1))!!
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val spell =
                b
                    .getPlayer(SeatId(1))!!
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Murder" }

            val target =
                InteractivePromptBridge.PendingTarget(
                    spellForgeCardId = spell.id,
                    spellName = spell.name,
                    affectees =
                        listOf(
                            InteractivePromptBridge.PendingTarget.TargetAffectee(targetForgeCardId = creature.id),
                        ),
                    index = 1,
                    affectorInstanceIdAtRecord = b.getOrAllocInstanceId(ForgeCardId(spell.id)).value,
                )
            b.seat(SeatId(1)).prompt.addPendingTargetSpec(target)

            val snapTarget = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val result =
                StateMapper.buildFromSnapshot(
                    snapTarget,
                    1,
                    Board.TEST_MATCH_ID,
                    b,
                    promptFacts = b.materializePromptProjectionFacts(),
                    effectFacts = b.materializeEffectProjectionFacts(),
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                )

            result.gsm.persistentAnnotationsList.any { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true
            b
                .seat(SeatId(1))
                .prompt
                .snapshotPendingTargetSpecs()
                .size shouldBe 1

            // A later identical selection must survive this older frame's commit.
            b.seat(SeatId(1)).prompt.addPendingTargetSpec(target)

            b.applyMutations(result.finalizeAnnotations().mutations)

            b
                .seat(SeatId(1))
                .prompt
                .snapshotPendingTargetSpecs()
                .size shouldBe 1
        }

        test("no pending targets emits no TargetSpec") {
            val (b, game) =
                startWithBoard { _, human, _ ->
                    addCard("Divination", human, ZoneType.Hand)
                }

            val snapTarget2 = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snapTarget2,
                        1,
                        Board.TEST_MATCH_ID,
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            gs.persistentAnnotationsList.none { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true
        }

        test("TargetSpec removed when pending list is empty on next build") {
            val (b, game) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Murder", human, ZoneType.Hand)
                }

            val creature =
                b
                    .getPlayer(SeatId(1))!!
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val spell =
                b
                    .getPlayer(SeatId(1))!!
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Murder" }

            b.seat(SeatId(1)).prompt.addPendingTargetSpec(
                InteractivePromptBridge.PendingTarget(
                    spellForgeCardId = spell.id,
                    spellName = spell.name,
                    affectees =
                        listOf(
                            InteractivePromptBridge.PendingTarget.TargetAffectee(targetForgeCardId = creature.id),
                        ),
                    index = 1,
                    affectorInstanceIdAtRecord = b.getOrAllocInstanceId(ForgeCardId(spell.id)).value,
                ),
            )

            // First GSM: pending target → TargetSpec present
            val snapTs1 = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs1 =
                StateMapper.buildFromSnapshot(
                    snapTs1,
                    1,
                    Board.TEST_MATCH_ID,
                    b,
                    promptFacts = b.materializePromptProjectionFacts(),
                    effectFacts = b.materializeEffectProjectionFacts(),
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                )
            gs1.gsm.persistentAnnotationsList.any { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true
            b.applyMutations(gs1.finalizeAnnotations().mutations)

            // Second GSM: pending consumed, no new targets → TargetSpec removed
            val snapTs2 = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 2)
            val gs2 =
                StateMapper.buildFromSnapshot(
                    snapTs2,
                    2,
                    Board.TEST_MATCH_ID,
                    b,
                    effectFacts = b.materializeEffectProjectionFacts(),
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                )
            gs2.gsm.persistentAnnotationsList.none { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true
        }
    })
