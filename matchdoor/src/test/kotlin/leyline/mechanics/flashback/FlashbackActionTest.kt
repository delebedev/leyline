package leyline.mechanics.flashback

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import leyline.BoardTag
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTestBase
import leyline.testkit.beAltCostOffer
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Flashback graveyard-cast-with-alternate-cost path.
 *
 * Flashback is a graveyard alt-cost keyword: cast a card from your graveyard
 * for its flashback cost, then exile it after it resolves. Forge registers the
 * SA via `K:Flashback:<cost>` with `setAlternativeCost(AlternativeCost.Flashback)`.
 *
 * Bridge wiring (mirrors Escape — minimal-emit shape):
 *  - `KeywordAbilityIds.FLASHBACK = 35` resolves Faithless Looting's per-card
 *    flashback ability id (5301 in Arena DB).
 *  - `CastRails.fromGraveyard` includes a FLASHBACK row — Cast offer carries
 *    no grpId/facetId, only abilityGrpId+alternativeGrpId set to the per-card
 *    flashback row, with mana cost slots echoing alternativeGrpId.
 *  - `ActionPerformer.resolveAltCostAbilityIndex` matches
 *    `info.baseId == KeywordAbilityIds.FLASHBACK` → `AlternativeCost.Flashback`.
 *  - `CastingTimeOption type=13` fires post-cast via the standard
 *    `AppliedTransfer.altCostAbilityGrpId` → `emitCastingTimeOptions` chain.
 *
 * Card: Faithless Looting (Sorcery {R}, "Draw 2 then discard 2", Flashback {2}{R}).
 */
@Suppress("UnnecessaryNotNullOperator")
class FlashbackActionTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Forge surfaces the Flashback alt-cost SA on a graveyard sorcery with payable cost") {
            val (_, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Faithless Looting", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Faithless Looting" }

            val flashbackSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Flashback }
            flashbackSa shouldNotBe null
        }

        test("ActionMapper.buildFromSnapshot offers minimal-emit Cast for flashback card in graveyard") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Faithless Looting", human, ZoneType.Graveyard)
                }

            val lootingGrpId = b.cardRepository.findGrpIdByName("Faithless Looting")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(lootingGrpId, KeywordAbilityIds.FLASHBACK)!!
            val lootingIid =
                b
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer
                                .getZone(ZoneType.Graveyard)
                                .cards
                                .first { it.name == "Faithless Looting" }
                                .id,
                        ),
                    ).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val castOffers =
                fromSnap.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == lootingIid
                }
            castOffers.shouldNotBeEmpty()
            val flashbackOffer = castOffers.firstOrNull { it.abilityGrpId == flashbackAbilityGrpId }
            assertSoftly {
                flashbackOffer should beAltCostOffer(flashbackAbilityGrpId)
                // Minimal-emit shape (Flashback mirrors Escape): NO grpId, NO facetId.
                flashbackOffer!!.grpId shouldBe 0
                flashbackOffer.facetId shouldBe 0
                flashbackOffer.alternativeGrpId shouldBe flashbackAbilityGrpId
                flashbackOffer.abilityGrpId shouldBe flashbackAbilityGrpId
            }
        }

        test("flashback card only in hand → no graveyard-cast offer (zone guard)") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Faithless Looting", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val lootingGrpId = b.cardRepository.findGrpIdByName("Faithless Looting")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(lootingGrpId, KeywordAbilityIds.FLASHBACK)!!

            // Looting from hand surfaces only the regular Cast SA, not Flashback.
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Faithless Looting" }
            val handFlashbackSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Flashback }
            handFlashbackSa shouldBe null

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            fromSnap shouldNot offerAltCost(flashbackAbilityGrpId)
        }
    })
