package leyline.match

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer
import leyline.conformance.offerAltCost
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Disturb graveyard-cast-with-alternate-cost path.
 *
 * Disturb is a graveyard alt-cast keyword for double-faced cards. Forge
 * registers the SA via `K:Disturb:<cost>` (CardFactoryUtil:2790-2814) with
 * `setAlternativeCost(AlternativeCost.Disturb)`. The SA is built from the
 * back-face cast SA, so casting it transforms the card to back face and
 * resolves the back-face spell.
 *
 * Bridge wiring (mirrors Foretell exile-cast minus face-down stuff):
 *  - `KEYWORD_BASE_IDS["DISTURB"] = 215` resolves Galedrifter's per-card
 *    disturb ability id (145202 in Arena DB).
 *  - `addZoneCastActionsFromSnap`'s generic `altCost != null` branch sets
 *    `abilityGrpId = <disturb ability id>` on the offer.
 *  - `ActionPerformer.resolveAltCostAbilityIndex` matches
 *    `info.baseId == KEYWORD_BASE_IDS["DISTURB"]` → `AlternativeCost.Disturb`.
 *  - `TransferAnnotations` wires `altCostAbilityGrpId` into both UserActionTaken
 *    (`abilityGrpId` + `alternativeGrpId`) and the persistent CastingTimeOption
 *    (`type=13`, `alternateCostGrpId`). Same path Flashback rides.
 *
 * Card: Galedrifter (front, Creature 3/2 Flying, ManaCost {3}{U}, K:Disturb:4 U).
 * Back face: Waildrifter (Creature 2/2 Flying Spirit, exile-instead-of-graveyard).
 */
class DisturbTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Forge surfaces the Disturb alt-cost SA on a graveyard card (AlternativeCost.Disturb)") {
            val (_, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Galedrifter", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Galedrifter" }

            val disturbSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Disturb }
            disturbSa shouldNotBe null
        }

        test("ActionMapper.buildFromSnapshot offers Cast for disturb card in graveyard when {4}{U} payable") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Galedrifter", human, ZoneType.Graveyard)
                }

            val galedrifterGrpId = b.cardRepository.findGrpIdByName("Galedrifter")!!
            val disturbAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(galedrifterGrpId, KeywordAbilityIds.DISTURB)!!
            val galedrifterIid =
                b
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer
                                .getZone(ZoneType.Graveyard)
                                .cards
                                .first { it.name == "Galedrifter" }
                                .id,
                        ),
                    ).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val castOffers =
                fromSnap.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == galedrifterIid
                }
            castOffers.shouldNotBeEmpty()
            val disturbOffer = castOffers.firstOrNull { it.abilityGrpId == disturbAbilityGrpId }
            // NOT using `beAltCostOffer(disturbAbilityGrpId)` — the bridge doesn't yet
            // stamp Disturb's manaCost entries with the disturb ability grpId
            // (each ManaRequirement has abilityGrpId=0). Foretell/Plot/Warp/Escape
            // do stamp. Once the stamp lands for Disturb, switch to the matcher.
            disturbOffer shouldNotBe null
            disturbOffer!!.manaCostCount shouldBeGreaterThan 0
        }

        test("disturb card only in hand → no graveyard-cast offer (zone guard)") {
            // Disturb is graveyard-only. A disturb card in hand should not surface
            // the disturb alt-cost from graveyard rail.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Galedrifter", human, ZoneType.Hand)
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

        test("SnapshotCapture.resolveOthersideGrpId returns Waildrifter for Galedrifter (DFC linkage)") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Galedrifter", human, ZoneType.Graveyard)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .first { it.name == "Galedrifter" }
            val waildrifterGrpId =
                b.cardRepository.findGrpIdByName("Waildrifter")
                    ?: leyline.conformance.TestCardRegistry.ensureCardRegistered("Waildrifter")
            val othersideGrpId = SnapshotCapture.resolveOthersideGrpId(card, b.cardRepository)
            othersideGrpId shouldBeGreaterThan 0
            othersideGrpId shouldBe waildrifterGrpId
        }
    })
