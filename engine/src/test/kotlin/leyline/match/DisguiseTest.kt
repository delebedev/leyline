package leyline.match

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Disguise hand cast (Hand → Stack face-down).
 *
 * Disguise is a hand-keyword whose face-down SA is registered by Forge as a
 * `Spell` with `setCastFaceDown(true)` — there is no `AlternativeCost.Disguise`
 * enum entry, so the rail predicate uses `isCastFaceDown` (mirroring how Plot
 * uses `isPlotting`). The action emit's `alternativeGrpId` carries the
 * Disguise BaseId (307) directly rather than the per-card row id (Plot/Foretell
 * pattern), because the morph-down hand SA is keyword-anchor-only — the
 * per-card ability id is the *turn-face-up* activator and rides the dedicated
 * `Special_TurnFaceUp_add3` action instead.
 *
 * The `Special_TurnFaceUp_add3` accept side and the bf face-down projection
 * are exercised end-to-end via `data/puzzles/disguise-forum-familiar.pzl` — those
 * paths require an actual face-down permanent which can't be programmatically
 * synthesized without driving the cast resolution.
 *
 * Card: Forum Familiar (Creature 1/1 White, `Disguise:1 W`).
 */
@Suppress(
    "UnnecessaryNotNullOperator",
    "WeakAssertionOnly",
)
class DisguiseTest :
    BoardTest({

        test("Forge surfaces the Disguise face-down hand SA on a hand card (isCastFaceDown=true)") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Forum Familiar", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Forum Familiar" }

            val disguiseSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.isCastFaceDown }
            disguiseSa shouldNotBe null
        }

        test(
            "ActionMapper offers Cast for disguise card in hand when {3} payable " +
                "(alternativeGrpId=DISGUISE BaseId, manaCost={3})",
        ) {
            // Forum Familiar's printed cost is {W} (1 mana). Disguise face-down
            // cast is {3} (3 mana). With 3 Plains we can pay either.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Forum Familiar", human, ZoneType.Hand)
                }

            val familiarGrpId = b.cardRepository.findGrpIdByName("Forum Familiar")!!
            val familiarIid =
                b
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer
                                .getZone(ZoneType.Hand)
                                .cards
                                .first { it.name == "Forum Familiar" }
                                .id,
                        ),
                    ).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val castOffers =
                fromSnap.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == familiarIid
                }
            castOffers.shouldNotBeEmpty()

            val disguiseOffer =
                castOffers.firstOrNull {
                    it.alternativeGrpId == KeywordAbilityIds.DISGUISE
                }
            val disguiseOffers = castOffers.filter { it.alternativeGrpId == KeywordAbilityIds.DISGUISE }
            assertSoftly {
                disguiseOffers shouldHaveSize 1
                disguiseOffer shouldNotBe null
                disguiseOffer!!.grpId shouldBe familiarGrpId
                disguiseOffer.facetId shouldBe familiarIid
                disguiseOffer.abilityGrpId shouldBe 0
                // Face-down cast cost is a flat {3} (3 generic). Generic mana
                // is enum value 7 (Messages.ManaColor.Generic).
                val genericCount =
                    disguiseOffer.manaCostList
                        .filter { it.colorList.size == 1 && it.colorList[0].number == 7 }
                        .sumOf { it.count }
                genericCount shouldBe 3
            }
        }

        test("disguise card in hand insufficient mana → no Cast offer with alternativeGrpId=DISGUISE") {
            // Disguise face-down requires {3}. 2 Plains can pay neither the
            // {W} regular cast nor the {3} disguise cast.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Forum Familiar", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            fromSnap shouldNot offerAltCost(KeywordAbilityIds.DISGUISE)
        }

        test("disguise card only in graveyard → no Cast offer with alternativeGrpId=DISGUISE") {
            // Disguise face-down cast is hand-only (the SA Forge attaches via
            // CardFactoryUtil.abilityCastFaceDown checks isInPlay()/zone, but the
            // canPlay default for Spell rejects non-hand zones). A graveyard
            // card must surface no disguise offer.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Forum Familiar", human, ZoneType.Graveyard)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            fromSnap shouldNot offerAltCost(KeywordAbilityIds.DISGUISE)
        }
    })
