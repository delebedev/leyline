package leyline.mechanics.altcost

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.AlternativeCost
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.FrameIdResolver
import leyline.game.snapshot.SnapshotCapture
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Table-driven coverage for the alt-cost Cast offer shape shared by Cleave,
 * Impending, Overload, Flashback, Jump-start, Disturb, and Escape: Forge
 * surfaces the keyword's alternative spell ability on the source card, and
 * ActionMapper.buildFromSnapshot stamps it as a Cast offer carrying the
 * keyword's per-card ability grpId.
 *
 * Foretell, Plot, and Sneak ride a structurally different rail (hand-keyword
 * KeywordInstance rather than AlternativeCost, buildActionList as the primary
 * entry point) and keep their own dedicated test files.
 */
private data class AltCostOfferRow(
    val mechanic: String,
    val sourceCard: String,
    val sourceZone: ZoneType,
    val keywordId: Int,
    val seedForgeSurfaces: (game: Game, human: Player, ai: Player) -> Unit,
    val seedOffer: (game: Game, human: Player, ai: Player) -> Unit,
    val forgeSurfaces: (SpellAbility) -> Boolean,
    val assertForgeSurfaces: (sa: SpellAbility?, abilities: List<SpellAbility>) -> Unit = { sa, _ -> sa shouldNotBe null },
    val assertOffer: (
        castOffers: List<Action>,
        board: GameBridge,
        sourceCard: Card,
        sourceGrpId: Int,
        sourceIid: Int,
        keywordAbilityGrpId: Int,
    ) -> Unit,
)

class AltCostOfferTest :
    BoardTest({
        val rows =
            listOf(
                AltCostOfferRow(
                    mechanic = "Cleave",
                    sourceCard = "Path of Peril",
                    sourceZone = ZoneType.Hand,
                    keywordId = KeywordAbilityIds.CLEAVE,
                    seedForgeSurfaces = { _, human, _ ->
                        addCard("Plains", human, ZoneType.Battlefield)
                        addCard("Swamp", human, ZoneType.Battlefield)
                        addCard("Plains", human, ZoneType.Battlefield)
                        addCard("Swamp", human, ZoneType.Battlefield)
                        addCard("Plains", human, ZoneType.Battlefield)
                        addCard("Swamp", human, ZoneType.Battlefield)
                        addCard("Path of Peril", human, ZoneType.Hand)
                    },
                    seedOffer = { _, human, _ ->
                        addCard("Plains", human, ZoneType.Battlefield)
                        addCard("Swamp", human, ZoneType.Battlefield)
                        addCard("Plains", human, ZoneType.Battlefield)
                        addCard("Swamp", human, ZoneType.Battlefield)
                        addCard("Plains", human, ZoneType.Battlefield)
                        addCard("Swamp", human, ZoneType.Battlefield)
                        addCard("Path of Peril", human, ZoneType.Hand)
                    },
                    forgeSurfaces = { it.hasParam("PrecostDesc") && it.getParam("PrecostDesc") == "Cleave" },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
                        val cleaveOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            plainOffers shouldHaveSize 1
                            cleaveOffer should beAltCostOffer(keywordAbilityGrpId)
                            cleaveOffer!!.grpId shouldBe sourceGrpId
                            cleaveOffer.facetId shouldBe sourceIid
                            cleaveOffer.abilityGrpId shouldBe 0
                            cleaveOffer should haveManaCost(generic = 4, white = 1, black = 1)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Impending",
                    sourceCard = "Overlord of the Mistmoors",
                    sourceZone = ZoneType.Hand,
                    keywordId = KeywordAbilityIds.IMPENDING,
                    seedForgeSurfaces = { _, human, _ ->
                        repeat(4) { addCard("Plains", human, ZoneType.Battlefield) }
                        addCard("Overlord of the Mistmoors", human, ZoneType.Hand)
                    },
                    seedOffer = { _, human, _ ->
                        repeat(7) { addCard("Plains", human, ZoneType.Battlefield) }
                        addCard("Overlord of the Mistmoors", human, ZoneType.Hand)
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Impending },
                    assertForgeSurfaces = { sa, abilities ->
                        val regularSa = abilities.firstOrNull { it.alternativeCost == null }
                        assertSoftly {
                            sa shouldNotBe null
                            sa!!.isImpending shouldBe true
                            regularSa shouldNotBe null
                        }
                    },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
                        val impendingOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            plainOffers shouldHaveSize 1
                            impendingOffer should beAltCostOffer(keywordAbilityGrpId)
                            impendingOffer!!.grpId shouldBe sourceGrpId
                            impendingOffer.facetId shouldBe sourceIid
                            impendingOffer.abilityGrpId shouldBe 0
                            impendingOffer should haveManaCost(generic = 2, white = 2)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Overload",
                    sourceCard = "Mizzium Mortars",
                    sourceZone = ZoneType.Hand,
                    keywordId = KeywordAbilityIds.OVERLOAD,
                    seedForgeSurfaces = { _, human, ai ->
                        repeat(6) { addCard("Mountain", human, ZoneType.Battlefield) }
                        addCard("Mizzium Mortars", human, ZoneType.Hand)
                        addCard("Serra Angel", ai, ZoneType.Battlefield)
                    },
                    seedOffer = { _, human, ai ->
                        repeat(6) { addCard("Mountain", human, ZoneType.Battlefield) }
                        addCard("Mizzium Mortars", human, ZoneType.Hand)
                        addCard("Serra Angel", ai, ZoneType.Battlefield)
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Overload },
                    assertForgeSurfaces = { sa, abilities ->
                        val regularSa = abilities.firstOrNull { it.alternativeCost == null }
                        assertSoftly {
                            sa shouldNotBe null
                            sa!!.usesTargeting() shouldBe false
                            regularSa shouldNotBe null
                            regularSa!!.usesTargeting() shouldBe true
                        }
                    },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
                        val overloadOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            plainOffers shouldHaveSize 1
                            overloadOffer should beAltCostOffer(keywordAbilityGrpId)
                            overloadOffer!!.grpId shouldBe sourceGrpId
                            overloadOffer.facetId shouldBe sourceIid
                            overloadOffer.abilityGrpId shouldBe 0
                            overloadOffer should haveManaCost(generic = 3, red = 3)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Evoke",
                    sourceCard = "Mulldrifter",
                    sourceZone = ZoneType.Hand,
                    keywordId = KeywordAbilityIds.EVOKE,
                    seedForgeSurfaces = { _, human, _ ->
                        repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Mulldrifter", human, ZoneType.Hand)
                    },
                    seedOffer = { _, human, _ ->
                        repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Mulldrifter", human, ZoneType.Hand)
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Evoke },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
                        val evokeOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            plainOffers shouldHaveSize 0
                            evokeOffer should beAltCostOffer(keywordAbilityGrpId)
                            evokeOffer!!.grpId shouldBe sourceGrpId
                            evokeOffer.facetId shouldBe sourceIid
                            evokeOffer.abilityGrpId shouldBe 0
                            evokeOffer should haveManaCost(generic = 2, blue = 1)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Dash",
                    sourceCard = "Zurgo Bellstriker",
                    sourceZone = ZoneType.Hand,
                    keywordId = KeywordAbilityIds.DASH,
                    seedForgeSurfaces = { _, human, _ ->
                        repeat(2) { addCard("Mountain", human, ZoneType.Battlefield) }
                        addCard("Zurgo Bellstriker", human, ZoneType.Hand)
                    },
                    seedOffer = { _, human, _ ->
                        repeat(2) { addCard("Mountain", human, ZoneType.Battlefield) }
                        addCard("Zurgo Bellstriker", human, ZoneType.Hand)
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Dash },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
                        val dashOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            plainOffers shouldHaveSize 1
                            dashOffer should beAltCostOffer(keywordAbilityGrpId)
                            dashOffer!!.grpId shouldBe sourceGrpId
                            dashOffer.facetId shouldBe sourceIid
                            dashOffer.abilityGrpId shouldBe 0
                            dashOffer should haveManaCost(generic = 1, red = 1)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Emerge",
                    sourceCard = "Wretched Gryff",
                    sourceZone = ZoneType.Hand,
                    keywordId = KeywordAbilityIds.EMERGE,
                    seedForgeSurfaces = { _, human, _ ->
                        repeat(4) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Walking Corpse", human, ZoneType.Battlefield)
                        addCard("Wretched Gryff", human, ZoneType.Hand)
                    },
                    seedOffer = { _, human, _ ->
                        repeat(4) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Walking Corpse", human, ZoneType.Battlefield)
                        addCard("Wretched Gryff", human, ZoneType.Hand)
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Emerge },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
                        val emergeOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            plainOffers shouldHaveSize 0
                            emergeOffer should beAltCostOffer(keywordAbilityGrpId)
                            emergeOffer!!.grpId shouldBe sourceGrpId
                            emergeOffer.facetId shouldBe sourceIid
                            emergeOffer.abilityGrpId shouldBe 0
                            emergeOffer should haveManaCost(generic = 5, blue = 1)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Spectacle",
                    sourceCard = "Spawn of Mayhem",
                    sourceZone = ZoneType.Hand,
                    keywordId = KeywordAbilityIds.SPECTACLE,
                    seedForgeSurfaces = { _, human, ai ->
                        ai.setLifeLostThisTurn(1)
                        repeat(4) { addCard("Swamp", human, ZoneType.Battlefield) }
                        addCard("Spawn of Mayhem", human, ZoneType.Hand)
                    },
                    seedOffer = { _, human, ai ->
                        ai.setLifeLostThisTurn(1)
                        repeat(4) { addCard("Swamp", human, ZoneType.Battlefield) }
                        addCard("Spawn of Mayhem", human, ZoneType.Hand)
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Spectacle },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        val plainOffers = castOffers.filter { it.alternativeGrpId == 0 }
                        val spectacleOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            plainOffers shouldHaveSize 1
                            spectacleOffer should beAltCostOffer(keywordAbilityGrpId)
                            spectacleOffer!!.grpId shouldBe sourceGrpId
                            spectacleOffer.facetId shouldBe sourceIid
                            spectacleOffer.abilityGrpId shouldBe 0
                            spectacleOffer should haveManaCost(generic = 1, black = 2)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Flashback",
                    sourceCard = "Think Twice",
                    sourceZone = ZoneType.Graveyard,
                    keywordId = KeywordAbilityIds.FLASHBACK,
                    seedForgeSurfaces = { _, human, _ ->
                        repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Think Twice", human, ZoneType.Graveyard)
                    },
                    seedOffer = { _, human, _ ->
                        repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Think Twice", human, ZoneType.Graveyard)
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Flashback },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        castOffers.shouldNotBeEmpty()
                        val flashbackOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            flashbackOffer should beAltCostOffer(keywordAbilityGrpId)
                            flashbackOffer!!.grpId shouldBe sourceGrpId
                            flashbackOffer.facetId shouldBe sourceIid
                            flashbackOffer.alternativeGrpId shouldBe keywordAbilityGrpId
                            flashbackOffer.abilityGrpId shouldBe 0
                            flashbackOffer.alternativeSourceZcid shouldBe sourceIid
                            flashbackOffer should haveManaCost(generic = 2, blue = 1)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Jump-start",
                    sourceCard = "Radical Idea",
                    sourceZone = ZoneType.Graveyard,
                    keywordId = KeywordAbilityIds.JUMP_START,
                    seedForgeSurfaces = { _, human, _ ->
                        repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Radical Idea", human, ZoneType.Graveyard)
                        addCard("Coral Merfolk", human, ZoneType.Hand)
                    },
                    seedOffer = { _, human, _ ->
                        repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Radical Idea", human, ZoneType.Graveyard)
                        addCard("Coral Merfolk", human, ZoneType.Hand)
                    },
                    forgeSurfaces = { it.isJumpstart },
                    assertOffer = { castOffers, _, _, sourceGrpId, sourceIid, keywordAbilityGrpId ->
                        castOffers.shouldNotBeEmpty()
                        val jumpStartOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            jumpStartOffer should beAltCostOffer(keywordAbilityGrpId)
                            jumpStartOffer!!.grpId shouldBe sourceGrpId
                            jumpStartOffer.facetId shouldBe sourceIid
                            jumpStartOffer.alternativeGrpId shouldBe keywordAbilityGrpId
                            jumpStartOffer.abilityGrpId shouldBe keywordAbilityGrpId
                            jumpStartOffer.alternativeSourceZcid shouldBe sourceIid
                            jumpStartOffer should haveManaCost(generic = 1, blue = 1)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Disturb",
                    sourceCard = "Galedrifter",
                    sourceZone = ZoneType.Graveyard,
                    keywordId = KeywordAbilityIds.DISTURB,
                    seedForgeSurfaces = { _, human, _ ->
                        repeat(5) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Galedrifter", human, ZoneType.Graveyard)
                    },
                    seedOffer = { _, human, _ ->
                        repeat(5) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Galedrifter", human, ZoneType.Graveyard)
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Disturb },
                    assertOffer = { castOffers, board, sourceCard, _, sourceIid, keywordAbilityGrpId ->
                        val waildrifterGrpId = board.cardRepository.findGrpIdByNameAnyFace("Waildrifter")!!
                        val disturbBackIid =
                            board.getOrAllocInstanceId(FrameIdResolver.disturbBackForgeId(ForgeCardId(sourceCard.id))).value
                        castOffers.shouldNotBeEmpty()
                        val disturbOffer = castOffers.firstOrNull { it.alternativeGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            disturbOffer should beAltCostOffer(keywordAbilityGrpId)
                            disturbOffer!!.grpId shouldBe waildrifterGrpId
                            disturbOffer.facetId shouldBe disturbBackIid
                            disturbOffer.alternativeSourceZcid shouldBe sourceIid
                            disturbOffer.abilityGrpId shouldBe keywordAbilityGrpId
                            disturbOffer.alternativeGrpId shouldBe keywordAbilityGrpId
                            disturbOffer should haveManaCost(generic = 4, blue = 1)
                        }
                    },
                ),
                AltCostOfferRow(
                    mechanic = "Escape",
                    sourceCard = "Glimpse of Freedom",
                    sourceZone = ZoneType.Graveyard,
                    keywordId = KeywordAbilityIds.ESCAPE,
                    seedForgeSurfaces = { _, human, _ ->
                        repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Glimpse of Freedom", human, ZoneType.Graveyard)
                        repeat(5) { addCard("Plains", human, ZoneType.Graveyard) }
                    },
                    seedOffer = { _, human, _ ->
                        repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                        addCard("Glimpse of Freedom", human, ZoneType.Graveyard)
                        repeat(5) { addCard("Plains", human, ZoneType.Graveyard) }
                    },
                    forgeSurfaces = { it.alternativeCost == AlternativeCost.Escape },
                    assertOffer = { castOffers, _, _, _, _, keywordAbilityGrpId ->
                        castOffers.shouldNotBeEmpty()
                        // Minimal-emit shape (Escape-specific): NO grpId, NO facetId on the offer.
                        val escapeOffer = castOffers.firstOrNull { it.abilityGrpId == keywordAbilityGrpId }
                        assertSoftly {
                            escapeOffer should beAltCostOffer(keywordAbilityGrpId)
                            escapeOffer!!.grpId shouldBe 0
                            escapeOffer.facetId shouldBe 0
                            escapeOffer.alternativeGrpId shouldBe keywordAbilityGrpId
                        }
                    },
                ),
            )

        for (row in rows) {
            test("Forge surfaces ${row.mechanic} as an alternative spell ability") {
                val (_, game, _) = startWithBoard(row.seedForgeSurfaces)
                val human = game.humanPlayer
                val card = human.getZone(row.sourceZone).cards.first { it.name == row.sourceCard }

                val abilities = getAllCastableAbilities(card, human)
                val sa = abilities.firstOrNull(row.forgeSurfaces)
                row.assertForgeSurfaces(sa, abilities)
            }

            val zoneLabel = if (row.sourceZone == ZoneType.Hand) "hand" else "graveyard"
            test("ActionMapper offers canonical ${row.mechanic} alt-cost Cast from $zoneLabel") {
                val (b, game, _) = startWithBoard(row.seedOffer)
                val human = game.humanPlayer
                val card = human.getZone(row.sourceZone).cards.first { it.name == row.sourceCard }
                val sourceIid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value
                val sourceGrpId = b.cardRepository.findGrpIdByName(row.sourceCard)!!
                val keywordAbilityGrpId = b.cardRepository.findKeywordAbilityGrpId(sourceGrpId, row.keywordId)!!

                val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
                val castOffers =
                    actions.actionsList.filter {
                        it.actionType == ActionType.Cast && it.instanceId == sourceIid
                    }

                row.assertOffer(castOffers, b, card, sourceGrpId, sourceIid, keywordAbilityGrpId)
            }
        }
    })
