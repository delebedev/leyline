package leyline.game.mapper

import forge.ai.ComputerUtilMana
import forge.game.Game
import forge.game.card.Card
import forge.game.card.CardLists
import forge.game.card.CardPredicates
import forge.game.player.Player
import forge.game.spellability.LandAbility
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.bridge.chooseCastAbility
import leyline.game.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Maps Forge playable actions to Arena [Action] / [ActionsAvailableReq] protos.
 *
 * Depends on [IdMapping] (instanceId allocation) and [PlayerLookup] (seat → player).
 * Extracted from [StateMapper] for independent testability.
 */
object ActionMapper {

    private val log = LoggerFactory.getLogger(ActionMapper::class.java)

    private const val INITIAL_MANA_ID = 10

    fun buildActions(game: Game, seatId: Int, bridge: GameBridge): ActionsAvailableReq =
        buildActionList(seatId, bridge, checkLegality = true)

    /**
     * Naive action list: Cast for all non-lands, Play for all lands in hand,
     * ActivateMana for untapped permanents — no canPlay/canPay checks.
     * Real server embeds human's potential actions during AI turn regardless of phase.
     */
    fun buildNaiveActions(seatId: Int, bridge: GameBridge): ActionsAvailableReq =
        buildActionList(seatId, bridge, checkLegality = false)

    /**
     * Shared action list builder.
     *
     * @param checkLegality true → full legality checks (canPlayLand, canPayManaCost,
     *   activated ability canPlay, autoTapSolution, inactive land actions).
     *   false → naive mode (everything playable, no autoTap, no Activate abilities).
     */
    internal fun buildActionList(
        seatId: Int,
        bridge: GameBridge,
        checkLegality: Boolean,
    ): ActionsAvailableReq {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return passOnlyActions()
        val builder = ActionsAvailableReq.newBuilder()

        // Battlefield permanents: ActivateMana + Activate
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            // Naive mode only cares about ActivateMana — skip tapped cards entirely
            if (!checkLegality && card.isTapped) continue

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val grpId = bridge.cards.findGrpIdByName(card.name) ?: GameBridge.FALLBACK_GRPID

            // ActivateMana — untapped permanents with mana abilities
            if (!card.isTapped && card.manaAbilities.isNotEmpty()) {
                builder.addActions(buildActivateManaAction(card, instanceId, grpId, bridge))
            }

            // Activate — non-mana activated abilities (only with legality checks)
            if (checkLegality) {
                for (ability in card.spellAbilities) {
                    ability.setActivatingPlayer(player)
                    if (!ability.isActivatedAbility) continue
                    if (ability.isManaAbility()) continue
                    if (!ability.canPlay()) continue
                    val cardData = bridge.cards.findByGrpId(grpId)
                    val actionBuilder = Action.newBuilder()
                        .setActionType(ActionType.Activate_add3)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Activate_add3))
                    if (cardData != null) {
                        // TODO: correlate loop index with abilityIds entries for multi-ability cards
                        val abilityEntry = cardData.abilityIds.firstOrNull()
                        if (abilityEntry != null) actionBuilder.setAbilityGrpId(abilityEntry.first)
                    }
                    builder.addActions(actionBuilder)
                }
            }
        }

        // Hand cards: Lands + Spells
        val handCards = player.getZone(ForgeZoneType.Hand).cards

        // Lands: playable → actions, not playable → inactiveActions (legality only)
        for (card in CardLists.filter(handCards, CardPredicates.LANDS)) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val grpId = bridge.cards.findGrpIdByName(card.name) ?: GameBridge.FALLBACK_GRPID
            val canPlay = if (checkLegality) {
                val landAbility = LandAbility(card, card.currentState)
                landAbility.activatingPlayer = player
                player.canPlayLand(card, false, landAbility)
            } else {
                false
            }
            if (canPlay) {
                builder.addActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Play_add3)),
                )
            } else {
                // Greyed-out: land can't be played (already played one this turn)
                builder.addInactiveActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setGrpId(grpId)
                        .setInstanceId(instanceId)
                        .setFacetId(instanceId),
                )
            }
        }

        // Non-land spells
        for (card in CardLists.filter(handCards, CardPredicates.NON_LANDS)) {
            if (checkLegality) {
                val sa = chooseCastAbility(card, player) ?: continue
                val canPay = try {
                    ComputerUtilMana.canPayManaCost(sa, player, 0, false)
                } catch (_: Exception) {
                    false
                }
                if (!canPay) continue
            }
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val grpId = bridge.cards.findGrpIdByName(card.name) ?: GameBridge.FALLBACK_GRPID
            val actionBuilder = Action.newBuilder()
                .setActionType(ActionType.Cast)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))
            val cardData = bridge.cards.findByGrpId(grpId)
            if (cardData != null) {
                for ((color, count) in cardData.manaCost) {
                    actionBuilder.addManaCost(
                        ManaRequirement.newBuilder().addColor(color).setCount(count),
                    )
                }
                if (checkLegality) {
                    val autoTap = buildAutoTapSolution(cardData.manaCost, player, bridge)
                    if (autoTap != null) actionBuilder.setAutoTapSolution(autoTap)
                }
            }
            builder.addActions(actionBuilder)
        }

        // Pass + FloatMana always available
        builder.addActions(Action.newBuilder().setActionType(ActionType.Pass))
        builder.addActions(Action.newBuilder().setActionType(ActionType.FloatMana))

        // Logging
        val manaCount = builder.actionsList.count { it.actionType == ActionType.ActivateMana }
        val landCount = builder.actionsList.count { it.actionType == ActionType.Play_add3 }
        val castCount = builder.actionsList.count { it.actionType == ActionType.Cast }
        if (checkLegality) {
            val activateCount = builder.actionsList.count { it.actionType == ActionType.Activate_add3 }
            log.info("buildActions: seat={} mana={} activate={} lands={} casts={} total={}", seatId, manaCount, activateCount, landCount, castCount, builder.actionsCount)
        } else {
            log.info("buildNaiveActions: seat={} mana={} lands={} casts={} total={}", seatId, manaCount, landCount, castCount, builder.actionsCount)
        }

        return builder.build()
    }

    /** Build an ActivateMana action for an untapped permanent with mana abilities. */
    private fun buildActivateManaAction(card: Card, instanceId: Int, grpId: Int, bridge: GameBridge): Action {
        val cardData = bridge.cards.findByGrpId(grpId)
        val abilityGrpId = cardData?.abilityIds?.firstOrNull()?.first ?: 0
        val sa = card.manaAbilities.first()
        val produced = sa.manaPart?.origProduced ?: ""
        val manaColor = producedToManaColor(produced) ?: ManaColor.Generic

        val actionBuilder = Action.newBuilder()
            .setActionType(ActionType.ActivateMana)
            .setInstanceId(instanceId)
            .setGrpId(grpId)
            .setFacetId(instanceId)
            .setIsBatchable(true)
        if (abilityGrpId != 0) actionBuilder.setAbilityGrpId(abilityGrpId)

        actionBuilder.addManaPaymentOptions(
            ManaPaymentOption.newBuilder().addMana(
                ManaInfo.newBuilder()
                    .setManaId(10)
                    .setColor(manaColor)
                    .setSrcInstanceId(instanceId)
                    .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                    .setAbilityGrpId(abilityGrpId)
                    .setCount(1),
            ),
        )

        actionBuilder.addManaSelections(
            ManaSelection.newBuilder()
                .setInstanceId(instanceId)
                .setAbilityGrpId(abilityGrpId)
                .addOptions(
                    ManaSelectionOption.newBuilder().addMana(
                        ManaColorCount.newBuilder().setColor(manaColor).setCount(1),
                    ),
                ),
        )

        return actionBuilder.build()
    }

    internal fun passOnlyActions(): ActionsAvailableReq =
        ActionsAvailableReq.newBuilder()
            .addActions(Action.newBuilder().setActionType(ActionType.Pass))
            .build()

    /**
     * Greedy auto-tap solver: maps mana cost requirements to untapped mana sources.
     * Returns null if no complete solution found (spell still castable via manual tap).
     */
    private fun buildAutoTapSolution(
        manaCost: List<Pair<ManaColor, Int>>,
        player: Player,
        bridge: GameBridge,
    ): AutoTapSolution? {
        if (manaCost.isEmpty()) return null

        data class ManaSource(val card: Card, val instanceId: Int, val color: ManaColor, val abilityGrpId: Int)

        // Collect untapped mana sources with their produced color
        val sources = mutableListOf<ManaSource>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue
            for (sa in card.manaAbilities) {
                sa.setActivatingPlayer(player)
                if (!sa.canPlay()) continue
                val produced = sa.manaPart?.origProduced ?: continue
                val manaColor = producedToManaColor(produced) ?: continue
                val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
                val grpId = bridge.cards.findGrpIdByName(card.name) ?: GameBridge.FALLBACK_GRPID
                val abilityGrpId = bridge.cards.findByGrpId(grpId)?.abilityIds?.firstOrNull()?.first ?: 0
                sources.add(ManaSource(card, instanceId, manaColor, abilityGrpId))
            }
        }

        // Greedy match: colored requirements first, then generic
        val used = mutableSetOf<Int>() // indices into sources
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>() // (source, paying color)
        val coloredReqs = manaCost.filter { it.first != ManaColor.Generic }
        val genericReqs = manaCost.filter { it.first == ManaColor.Generic }

        // Match colored requirements
        for ((reqColor, reqCount) in coloredReqs) {
            var remaining = reqCount
            for ((idx, src) in sources.withIndex()) {
                if (remaining <= 0) break
                if (idx in used) continue
                if (src.color == reqColor) {
                    used.add(idx)
                    matched.add(src to reqColor)
                    remaining--
                }
            }
            if (remaining > 0) return null // can't fulfill colored requirement
        }

        // Match generic requirements (any color)
        for ((_, reqCount) in genericReqs) {
            var remaining = reqCount
            for ((idx, src) in sources.withIndex()) {
                if (remaining <= 0) break
                if (idx in used) continue
                used.add(idx)
                matched.add(src to src.color)
                remaining--
            }
            if (remaining > 0) return null
        }

        // Build AutoTapSolution matching real server format:
        // Each AutoTapAction has manaPaymentOption with full ManaInfo
        val builder = AutoTapSolution.newBuilder()
        var manaIdCounter = INITIAL_MANA_ID
        for ((src, payingColor) in matched) {
            val manaId = manaIdCounter++
            builder.addAutoTapActions(
                AutoTapAction.newBuilder()
                    .setInstanceId(src.instanceId)
                    .setAbilityGrpId(src.abilityGrpId)
                    .setManaPaymentOption(
                        ManaPaymentOption.newBuilder().addMana(
                            ManaInfo.newBuilder()
                                .setManaId(manaId)
                                .setColor(payingColor)
                                .setSrcInstanceId(src.instanceId)
                                .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                                .setAbilityGrpId(src.abilityGrpId)
                                .setCount(1),
                        ),
                    ),
            )
        }
        return builder.build()
    }

    /** Map Forge's produced-mana string (e.g. "G", "W", "Any") to proto ManaColor. */
    internal fun producedToManaColor(produced: String): ManaColor? = when (produced.uppercase().trim()) {
        "W" -> ManaColor.White_afc9
        "U" -> ManaColor.Blue_afc9
        "B" -> ManaColor.Black_afc9
        "R" -> ManaColor.Red_afc9
        "G" -> ManaColor.Green_afc9
        "C" -> ManaColor.Colorless_afc9
        "ANY" -> ManaColor.Generic
        else -> null
    }

    /**
     * Strip an Action down to the minimal format used inside GSM embedded actions.
     * Real server: Cast=instanceId+manaCost, Play=instanceId, ActivateMana=instanceId+abilityGrpId.
     * No grpId, facetId, shouldStop, autoTapSolution.
     */
    fun stripActionForGsm(action: Action): Action {
        val b = Action.newBuilder().setActionType(action.actionType)
        when (action.actionType) {
            ActionType.Cast -> {
                b.setInstanceId(action.instanceId)
                b.addAllManaCost(action.manaCostList)
            }
            ActionType.Play_add3 -> b.setInstanceId(action.instanceId)
            ActionType.ActivateMana, ActionType.Activate_add3 -> {
                b.setInstanceId(action.instanceId)
                if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            }
            ActionType.Pass, ActionType.FloatMana -> {} // empty
            else -> b.setInstanceId(action.instanceId)
        }
        return b.build()
    }
}
