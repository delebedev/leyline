package leyline.game.mapping

import forge.game.Game
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.staticability.StaticAbility
import forge.game.staticability.StaticAbilityCantAttackBlock
import forge.game.staticability.StaticAbilityMode
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.codes.QualificationType
import leyline.game.data.CardData
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.CombatQualificationSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Snapshot-derived Qualification pAnns for combat restrictions and evasion. */
object CombatQualificationScanner {
    private data class RowKey(
        val sourceCardId: ForgeCardId,
        val affectedCardId: ForgeCardId,
        val grpId: Int,
        val qualificationType: QualificationType,
    )

    private data class Row(
        val sourceCardId: ForgeCardId,
        val affectedCardId: ForgeCardId,
        val grpId: GrpId,
        val qualificationType: QualificationType,
        val cantBlockCardIds: MutableSet<ForgeCardId> = linkedSetOf(),
        val cantBeBlockedByCardIds: MutableSet<ForgeCardId> = linkedSetOf(),
    )

    fun scan(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.combatQualifications
            .map { value ->
                val sourceIid = frameIds.cardIid(value.sourceCardId)
                val affectedIid = frameIds.cardIid(value.affectedCardId)
                AnnotationBuilder.qualification(
                    affectorId = sourceIid,
                    instanceId = affectedIid,
                    grpId = GrpId(value.grpId),
                    qualificationType = value.qualificationType,
                    sourceParent = sourceIid,
                    cantBlockObjects = value.cantBlockCardIds.map { frameIds.cardIid(it).value }.sorted(),
                    cantBeBlockedByObjects = value.cantBeBlockedByCardIds.map { frameIds.cardIid(it).value }.sorted(),
                )
            }.sortedWith(
                compareBy<AnnotationInfo> { it.affectedIdsList.firstOrNull() ?: 0 }
                    .thenBy { annotation ->
                        annotation.detailsList
                            .first { it.key == leyline.game.codes.DetailKeys.QUALIFICATION_TYPE }
                            .getValueInt32(0)
                    }.thenBy { annotation ->
                        annotation.detailsList
                            .first { it.key == leyline.game.codes.DetailKeys.GRPID }
                            .getValueInt32(0)
                    },
            )

    internal fun capture(
        game: Game,
        boundCards: Map<ForgeCardId, BoundCard>,
        bridge: GameBridge,
    ): List<CombatQualificationSnapshot> {
        if (!liveBattlefieldMatchesSnapshot(game, boundCards)) return emptyList()
        val creatures = battlefieldCreatures(game)
        if (creatures.isEmpty()) return emptyList()

        val rows = linkedMapOf<RowKey, Row>()
        for (source in game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).sortedBy { it.id }) {
            for (staticAbility in source.staticAbilities.orEmpty()) {
                if (staticAbility.keyword != null) continue
                if (staticAbility.checkConditions(StaticAbilityMode.CantAttack)) {
                    scanCantAttack(staticAbility, creatures, game, boundCards, bridge, rows)
                }
                if (staticAbility.checkConditions(StaticAbilityMode.CantBlock)) {
                    scanCantBlock(staticAbility, creatures, boundCards, bridge, rows)
                }
                if (staticAbility.checkConditions(StaticAbilityMode.CantBlockBy)) {
                    scanCantBlockBy(staticAbility, creatures, boundCards, bridge, rows)
                }
            }
        }

        return rows.values
            .map { row ->
                CombatQualificationSnapshot(
                    sourceCardId = row.sourceCardId,
                    affectedCardId = row.affectedCardId,
                    grpId = row.grpId.value,
                    qualificationType = row.qualificationType,
                    cantBlockCardIds = row.cantBlockCardIds.toSet(),
                    cantBeBlockedByCardIds = row.cantBeBlockedByCardIds.toSet(),
                )
            }
    }

    private fun scanCantAttack(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        game: Game,
        boundCards: Map<ForgeCardId, BoundCard>,
        bridge: GameBridge,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (creature in creatures) {
            val targets = attackTargets(game, creature)
            if (targets.isEmpty()) continue
            if (targets.any { StaticAbilityCantAttackBlock.applyCantAttackAbility(staticAbility, creature, it) }) {
                addRow(staticAbility, creature, QualificationType.CantAttack, boundCards, bridge, rows)
            }
        }
    }

    private fun scanCantBlock(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        boundCards: Map<ForgeCardId, BoundCard>,
        bridge: GameBridge,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (blocker in creatures) {
            if (StaticAbilityCantAttackBlock.applyCantBlockAbility(staticAbility, blocker)) {
                addRow(staticAbility, blocker, QualificationType.CantBlock, boundCards, bridge, rows)
            }
        }
    }

    private fun scanCantBlockBy(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        boundCards: Map<ForgeCardId, BoundCard>,
        bridge: GameBridge,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (attacker in creatures) {
            if (appliesWithoutSpecificBlocker(staticAbility, attacker)) {
                addRow(staticAbility, attacker, QualificationType.CantBeBlocked, boundCards, bridge, rows)
                continue
            }

            for (blocker in creatures) {
                if (blocker.controller == attacker.controller) continue
                if (!StaticAbilityCantAttackBlock.applyCantBlockByAbility(staticAbility, attacker, blocker)) continue

                if (isBlockerCentric(staticAbility, blocker)) {
                    val row =
                        addRow(staticAbility, blocker, QualificationType.CantBlock, boundCards, bridge, rows)
                            ?: continue
                    row.cantBlockCardIds.add(ForgeCardId(attacker.id))
                } else {
                    val row =
                        addRow(staticAbility, attacker, QualificationType.CantBeBlocked, boundCards, bridge, rows)
                            ?: continue
                    row.cantBeBlockedByCardIds.add(ForgeCardId(blocker.id))
                }
            }
        }
    }

    private fun addRow(
        staticAbility: StaticAbility,
        affected: Card,
        qualificationType: QualificationType,
        boundCards: Map<ForgeCardId, BoundCard>,
        bridge: GameBridge,
        rows: MutableMap<RowKey, Row>,
    ): Row? {
        val sourceParent = sourceParent(staticAbility)
        val sourceCardId = ForgeCardId(sourceParent.id)
        val affectedCardId = ForgeCardId(affected.id)
        val grpId = abilityGrpId(staticAbility, sourceParent, boundCards, bridge) ?: return null
        val key = RowKey(sourceCardId, affectedCardId, grpId.value, qualificationType)
        return rows.getOrPut(key) {
            Row(
                sourceCardId = sourceCardId,
                affectedCardId = affectedCardId,
                grpId = grpId,
                qualificationType = qualificationType,
            )
        }
    }

    private fun abilityGrpId(
        staticAbility: StaticAbility,
        sourceParent: Card,
        boundCards: Map<ForgeCardId, BoundCard>,
        bridge: GameBridge,
    ): GrpId? {
        val host = staticAbility.hostCard
        val hostData = cardData(host, boundCards, bridge)
        val hostRegistry = bridge.abilityRegistryFor(host, hostData)
        hostRegistry?.forStaticAbility(staticAbility.definitionId)?.let { return GrpId(it) }

        val sourceAbility = host.getEffectSourceAbility()
        if (sourceAbility != null) {
            val sourceData = cardData(sourceParent, boundCards, bridge)
            val sourceRegistry = bridge.abilityRegistryFor(sourceParent, sourceData)
            sourceRegistry?.forSpellAbility(sourceAbility.definitionId)?.let { return GrpId(it) }
        }

        return null
    }

    private fun cardData(
        card: Card,
        boundCards: Map<ForgeCardId, BoundCard>,
        bridge: GameBridge,
    ): CardData? =
        boundCards[ForgeCardId(card.id)]?.data
            ?: bridge.cardRepository.findGrpIdByName(card.name)?.let { bridge.cardRepository.findByGrpId(it) }

    private fun sourceParent(staticAbility: StaticAbility): Card = staticAbility.hostCard.getEffectSource() ?: staticAbility.hostCard

    private fun battlefieldCreatures(game: Game): List<Card> = battlefieldCards(game).filter { it.isCreature }.sortedBy { it.id }

    private fun battlefieldCards(game: Game): List<Card> =
        game.players.flatMap { player -> player.getZone(ZoneType.Battlefield).cards }.sortedBy { it.id }

    private fun liveBattlefieldMatchesSnapshot(
        game: Game,
        boundCards: Map<ForgeCardId, BoundCard>,
    ): Boolean {
        val liveIds = battlefieldCards(game).map { it.id }.toSet()
        val snapIds =
            boundCards.values
                .filter { it.snapshot.isOnBattlefield }
                .map { it.forgeCardId.value }
                .toSet()
        return liveIds == snapIds
    }

    private fun attackTargets(
        game: Game,
        attacker: Card,
    ): List<GameEntity> = game.players.filter { it.isOpponentOf(attacker.controller) }

    private fun appliesWithoutSpecificBlocker(
        staticAbility: StaticAbility,
        attacker: Card,
    ): Boolean {
        if (staticAbility.hasParam("ValidBlocker")) return false
        if (staticAbility.hasParam("ValidBlockerRelative")) return false
        if (staticAbility.hasParam("ValidDefender")) return false
        if (staticAbility.hasParam("ValidAttackerRelative")) return false
        return staticAbility.matchesValidParam("ValidAttacker", attacker)
    }

    private fun isBlockerCentric(
        staticAbility: StaticAbility,
        blocker: Card,
    ): Boolean {
        val host = staticAbility.hostCard
        val source = host.getEffectSource()
        return host.id == blocker.id || source?.id == blocker.id
    }
}
