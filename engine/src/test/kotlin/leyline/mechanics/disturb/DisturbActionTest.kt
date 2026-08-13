package leyline.mechanics.disturb

import forge.game.ability.AbilityKey
import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.FrameEventLog
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/**
 * Disturb graveyard-cast-with-alternate-cost path — zone guard and DFC
 * back-face state-mapping.
 *
 * Disturb is a graveyard alt-cast keyword for double-faced cards. Forge
 * registers the SA via `K:Disturb:<cost>` (CardFactoryUtil:2790-2814) with
 * `setAlternativeCost(AlternativeCost.Disturb)`. The SA is built from the
 * back-face cast SA, so casting it transforms the card to back face and
 * resolves the back-face spell.
 *
 * The Forge-surfaces and ActionMapper-offers-Cast happy-path tests for
 * Disturb live in `leyline.mechanics.AltCostOfferTest` alongside the other
 * alt-cost keywords. This file keeps the zone guard and the DisturbBack
 * synthetic-object lifecycle tests, which don't fit that shared shape.
 *
 * Card: Galedrifter (front, Creature 3/2 Flying, ManaCost {3}{U}, K:Disturb:4 U).
 * Back face: Waildrifter (Creature 2/2 Flying Spirit, exile-instead-of-graveyard).
 */
class DisturbActionTest :
    BoardTest({

        test("disturb card only in hand → no graveyard-cast offer (zone guard)") {
            // Disturb is graveyard-only. A disturb card in hand should not surface
            // the disturb alt-cost from graveyard rail.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Galedrifter", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val galedrifterGrpId = b.cardRepository.findGrpIdByName("Galedrifter")!!
            val disturbAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(galedrifterGrpId, KeywordAbilityIds.DISTURB)!!

            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Galedrifter" }
            val handDisturbSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Disturb }
            // Forge restricts the Disturb SA to the graveyard zone — should not be
            // surfaced from hand.
            handDisturbSa shouldBe null

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            fromSnap shouldNot offerAltCost(disturbAbilityGrpId)
        }

        test("StateMapper emits DisturbBack face object for graveyard disturb card") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Galedrifter", human, ZoneType.Graveyard)
                }
            val galedrifterGrpId = b.cardRepository.findGrpIdByName("Galedrifter")!!
            val waildrifterGrpId = b.cardRepository.findGrpIdByNameAnyFace("Waildrifter")!!
            val galedrifterFid =
                ForgeCardId(
                    game.humanPlayer
                        .getZone(ZoneType.Graveyard)
                        .cards
                        .first { it.name == "Galedrifter" }
                        .id,
                )
            val galedrifterIid = b.getOrAllocInstanceId(galedrifterFid).value
            val disturbBackIid = b.getOrAllocInstanceId(FrameIdResolver.disturbBackForgeId(galedrifterFid)).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        "test",
                        b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                    ).gsm
            val source = gsm.gameObjectsList.first { it.instanceId == galedrifterIid }
            val disturbBack = gsm.gameObjectsList.first { it.instanceId == disturbBackIid }
            val graveyard = gsm.zonesList.first { it.zoneId == ZoneIds.P1_GRAVEYARD }

            assertSoftly {
                source.type shouldBe GameObjectType.Card
                source.grpId shouldBe galedrifterGrpId
                source.othersideGrpId shouldBe waildrifterGrpId
                disturbBack.type shouldBe GameObjectType.DisturbBack
                disturbBack.grpId shouldBe waildrifterGrpId
                disturbBack.parentId shouldBe galedrifterIid
                disturbBack.zoneId shouldBe ZoneIds.P1_GRAVEYARD
                disturbBack.othersideGrpId shouldBe galedrifterGrpId
                graveyard.objectInstanceIdsList shouldContain galedrifterIid
                graveyard.objectInstanceIdsList shouldNotContain disturbBackIid
            }
        }

        test("StateMapper deletes DisturbBack face object when source leaves player zone") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Galedrifter", human, ZoneType.Graveyard)
                }
            val galedrifter =
                game.humanPlayer
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .first { it.name == "Galedrifter" }
            val galedrifterFid = ForgeCardId(galedrifter.id)
            val disturbBackIid = b.getOrAllocInstanceId(FrameIdResolver.disturbBackForgeId(galedrifterFid)).value

            val prev = SnapshotCapture.run(game, b, "test", 1)
            val full =
                StateMapper
                    .buildFromSnapshot(
                        prev,
                        1,
                        "test",
                        b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                    ).gsm
            full.gameObjectsList.map { it.instanceId } shouldContain disturbBackIid
            full.gameObjectsList.count { it.instanceId == disturbBackIid } shouldBe 1

            game.action.moveToPlay(galedrifter, null, AbilityKey.newMap())
            val cur = SnapshotCapture.run(game, b, "test", 2)
            val diff =
                StateMapper
                    .buildDiff(
                        prev,
                        cur,
                        FrameEventLog.EMPTY,
                        2,
                        "test",
                        b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                    ).gsm

            assertSoftly {
                diff.diffDeletedInstanceIdsList shouldContain disturbBackIid
                diff.gameObjectsList.map { it.instanceId } shouldNotContain disturbBackIid
                diff.gameObjectsList.count { it.instanceId == disturbBackIid } shouldBe 0
            }
        }

        test("SnapshotCapture.resolveOthersideGrpId returns Waildrifter for Galedrifter (DFC linkage)") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Galedrifter", human, ZoneType.Graveyard)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .first { it.name == "Galedrifter" }
            val waildrifterGrpId =
                b.cardRepository.findGrpIdByName("Waildrifter")
                    ?: TestCardRegistry.ensureCardRegistered("Waildrifter")
            val othersideGrpId = SnapshotCapture.resolveOthersideGrpId(card, b.cardRepository)
            othersideGrpId shouldBeGreaterThan 0
            othersideGrpId shouldBe waildrifterGrpId
        }

        test("SnapshotCapture.resolveOthersideGrpId falls back to any-face lookup for Lunarch Veteran") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Lunarch Veteran", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Lunarch Veteran" }
            val luminousPhantomGrpId =
                b.cardRepository.findGrpIdByNameAnyFace("Luminous Phantom")
                    ?: TestCardRegistry.ensureCardRegistered("Luminous Phantom")

            val othersideGrpId = SnapshotCapture.resolveOthersideGrpId(card, b.cardRepository)

            luminousPhantomGrpId shouldBeGreaterThan 0
            othersideGrpId shouldBe luminousPhantomGrpId
        }
    })
