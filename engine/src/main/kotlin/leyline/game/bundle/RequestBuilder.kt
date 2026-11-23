package leyline.game.bundle

import forge.game.Game
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.combat.AttackConstraints
import forge.game.combat.CombatUtil
import forge.game.cost.CostExert
import forge.game.player.Player
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds outbound interactive request protos (targeting, selectN, combat).
 *
 * Pure proto construction from game state — no session state, no sending.
 * [leyline.match.CombatHandler] and [leyline.match.TargetingHandler]
 * handle the inbound responses.
 */
@Suppress("LargeClass") // One object mirrors the interactive request proto surface.
object RequestBuilder {
    private val log = LoggerFactory.getLogger(RequestBuilder::class.java)

    /** Build the [SearchReq] fields for a library search.
     *
     *  [sourceInstanceId] — `searchReq.sourceId`.
     *
     */
    @Suppress("LongParameterList")
    fun buildSearchRequest(
        sourceInstanceId: Int,
        libraryZoneId: Int,
        allLibraryIds: List<Int>,
        validTargetIds: List<Int>,
        maxFind: Int = 1,
        allowFailToFind: Boolean = true,
    ): SearchReq {
        val searchReq =
            SearchReq
                .newBuilder()
                .setMaxFind(maxFind)
                .addZonesToSearch(libraryZoneId)
                .addAllItemsToSearch(allLibraryIds)
                .addAllItemsSought(validTargetIds)
                .setSourceId(sourceInstanceId)
        if (allowFailToFind) {
            searchReq.setAllowFailToFind(AllowFailToFind.Any)
        }
        return searchReq.build()
    }

    private fun playerDamageRecipient(seatId: SeatId): DamageRecipient =
        DamageRecipient
            .newBuilder()
            .setType(DamageRecType.Player_a0e5)
            .setPlayerSystemSeatId(seatId.value)
            .build()

    private fun planeswalkerDamageRecipient(
        card: Card,
        bridge: GameBridge,
    ): DamageRecipient =
        DamageRecipient
            .newBuilder()
            .setType(DamageRecType.PlanesWalker)
            .setPlaneswalkerInstanceId(bridge.instanceId(card))
            .build()

    private fun legalAttackDamageRecipients(
        attacker: Player,
        card: Card,
        bridge: GameBridge,
    ): List<DamageRecipient> =
        buildList {
            for (defender in CombatUtil.getAllPossibleDefenders(attacker)) {
                if (!CombatUtil.canAttack(card, defender)) continue
                when (defender) {
                    is Player -> bridge.seatOf(defender)?.let { add(playerDamageRecipient(it)) }
                    is Card -> add(planeswalkerDamageRecipient(defender, bridge))
                }
            }
        }

    private fun attackDefender(
        player: Player,
        recipient: DamageRecipient,
        bridge: GameBridge,
    ): GameEntity? =
        when (recipient.type) {
            DamageRecType.Player_a0e5 -> bridge.getPlayer(SeatId(recipient.playerSystemSeatId))
            DamageRecType.PlanesWalker ->
                CombatUtil.getAllPossibleDefenders(player).firstOrNull { defender ->
                    defender is Card && bridge.instanceId(defender) == recipient.planeswalkerInstanceId
                }
            else -> null
        }

    private fun AttackConstraints.hasRestrictions(player: Player): Boolean =
        globalRestrictions.max != null ||
            globalRestrictions.defenderMax.isNotEmpty() ||
            restrictions.any { (card, restriction) -> card.controller == player && restriction.types.isNotEmpty() }

    private fun AttackConstraints.hasRequirements(player: Player): Boolean =
        requirements.any { (card, requirement) -> card.controller == player && requirement.hasRequirement() } ||
            playerRequirements[player]?.isEmpty == false

    /**
     * Forge's acceptance rule for a complete attack, applied to the accumulated
     * selection: no restriction is broken, and no more requirements are broken than
     * the best legal attack breaks. Forge applies the same rule to the committed
     * combat in `CombatUtil.validateAttackers`.
     */
    private fun AttackConstraints.accepts(selection: Map<Card, GameEntity>): Boolean {
        val violations = countViolations(selection)
        return violations == 0 || (violations > 0 && violations <= legalAttackers.right)
    }

    private fun buildAttackerOption(
        instanceId: Int,
        legalRecipients: List<DamageRecipient>,
        alternativeGrpId: Int = 0,
    ): Attacker.Builder =
        Attacker
            .newBuilder()
            .setAttackerInstanceId(instanceId)
            .addAllLegalDamageRecipients(legalRecipients)
            .apply {
                if (alternativeGrpId != 0) setAlternativeGrpId(alternativeGrpId)
            }

    /**
     * Build [DeclareAttackersReq] listing the [attacker]'s creatures that can legally attack.
     * Each attacker includes legal player and permanent damage recipients.
     *
     * @param attacker the attacking player. The seat receiving the request may be another
     *   player who chooses this player's attackers.
     *
     * @param committedAttackerIds instanceIds of attackers already selected (echo-back).
     *   Committed attackers get [selectedDamageRecipient] set to their chosen recipient.
     * @param committedAttackAlternatives selected attack alternative per attacker; 0 means normal attack.
     *   Initial request passes empty set (no pre-selection).
     */
    fun buildDeclareAttackersReq(
        attacker: Player,
        bridge: GameBridge,
        committedAttackerIds: Set<Int> = emptySet(),
        committedAttackAlternatives: Map<Int, Int> = emptyMap(),
        committedDamageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): DeclareAttackersReq {
        val builder = DeclareAttackersReq.newBuilder()
        val selection = linkedMapOf<Card, GameEntity>()

        for (card in attacker.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canAttack(card)) continue

            val instanceId = bridge.instanceId(card)
            val alternatives =
                buildList {
                    add(0)
                    if (card.staticAbilities.any { it.hasAttackCost(card, CostExert::class.java) }) add(KeywordAbilityIds.EXERT)
                    if (card.hasKeyword("Enlist")) add(KeywordAbilityIds.ENLIST)
                }
            val isCommitted = instanceId in committedAttackerIds
            val selectedAlternativeGrpId = committedAttackAlternatives[instanceId] ?: 0
            val legalRecipients = legalAttackDamageRecipients(attacker, card, bridge)
            if (legalRecipients.isEmpty()) continue
            val selectedRecipient = committedDamageRecipients[instanceId] ?: legalRecipients.first()
            if (isCommitted) attackDefender(attacker, selectedRecipient, bridge)?.let { selection[card] = it }

            for (alternative in alternatives) {
                val option = buildAttackerOption(instanceId, legalRecipients, alternative)
                if (isCommitted && selectedAlternativeGrpId == alternative) {
                    option.setSelectedDamageRecipient(selectedRecipient)
                }
                builder.addAttackers(option)
                // Qualified options describe legality without echoing the selection.
                builder.addQualifiedAttackers(buildAttackerOption(instanceId, legalRecipients, alternative))
            }
        }
        val constraints =
            bridge
                .getGame()
                ?.phaseHandler
                ?.combat
                ?.attackConstraints
        if (constraints == null) {
            builder.setCanSubmitAttackers(true)
        } else {
            builder.setHasRestrictions(constraints.hasRestrictions(attacker))
            builder.setHasRequirements(constraints.hasRequirements(attacker))
            builder.setCanSubmitAttackers(constraints.accepts(selection))
        }
        // Conformance: client expects an empty manaCost entry entry.
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info(
            "buildDeclareAttackersReq: attacker={} attackers={} committed={}",
            attacker.name,
            builder.attackersCount,
            committedAttackerIds.size,
        )
        return builder.build()
    }

    /**
     * Build [DeclareBlockersReq] listing the [defender]'s creatures that can legally block.
     *
     * @param defender the defending player. The seat receiving the request may be another
     *   player who chooses how this player's creatures block.
     *
     * @param blockerAssignments committed blocker→attacker assignments (instanceIds).
     *   Committed blockers get `selectedAttackerInstanceIds` set and `attackerInstanceIds`
     *   cleared. Uncommitted blockers get `attackerInstanceIds` (available targets).
     */
    fun buildDeclareBlockersReq(
        game: Game,
        defender: Player,
        bridge: GameBridge,
        blockerAssignments: Map<Int, Int> = emptyMap(),
    ): DeclareBlockersReq {
        val combat = game.phaseHandler.combat ?: return DeclareBlockersReq.getDefaultInstance()
        val builder = DeclareBlockersReq.newBuilder()

        for (card in defender.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canBlock(card, combat)) continue

            // Per-attacker legality: only list attackers this creature can legally block
            // (handles flying/reach, menace, protection, etc.)
            val legalAttackers = combat.attackers.filter { CombatUtil.canBlock(it, card) }
            if (legalAttackers.isEmpty()) continue

            val instanceId = bridge.instanceId(card)
            val blocker =
                Blocker
                    .newBuilder()
                    .setBlockerInstanceId(instanceId)
                    .setMaxAttackers(1)

            val assignedAttacker = blockerAssignments[instanceId]
            if (assignedAttacker != null) {
                blocker.addSelectedAttackerInstanceIds(assignedAttacker)
            } else {
                val legalAttackerIds = legalAttackers.map(bridge::instanceId)
                blocker.addAllAttackerInstanceIds(legalAttackerIds)
            }
            builder.addBlockers(blocker)
        }
        val assignedCounts = blockerAssignments.values.groupingBy { it }.eachCount()
        for (attacker in combat.attackers) {
            val minimum = CombatUtil.getMinNumBlockersForAttacker(attacker, defender)
            if (minimum <= 1) continue
            builder.setHasRestrictions(true)
            val instanceId = bridge.instanceId(attacker)
            val count = assignedCounts[instanceId] ?: 0
            if (count in 1 until minimum) {
                builder.addBlockWarnings(
                    BlockWarning
                        .newBuilder()
                        .setInstanceId(instanceId)
                        .setType(BlockWarningType.InsufficientBlockers)
                        .setWarningPromptId(PromptIds.INSUFFICIENT_BLOCKERS),
                )
            }
        }
        // Conformance: client expects empty manaCost
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info(
            "buildDeclareBlockersReq: defender={} blockers={} assigned={}",
            defender.name,
            builder.blockersCount,
            blockerAssignments.size,
        )
        return builder.build()
    }
}
