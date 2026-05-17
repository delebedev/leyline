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
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.codes.QualificationType
import leyline.game.data.CardData
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Snapshot-derived Qualification pAnns for combat restrictions and evasion. */
object CombatQualificationScanner {
    private data class RowKey(
        val affectorId: Int,
        val affectedId: Int,
        val grpId: Int,
        val qualificationType: QualificationType,
    )

    private data class Row(
        val affectorId: InstanceId,
        val affectedId: InstanceId,
        val sourceParent: InstanceId,
        val grpId: GrpId,
        val qualificationType: QualificationType,
        val cantBlockObjects: MutableSet<Int> = linkedSetOf(),
        val cantBeBlockedByObjects: MutableSet<Int> = linkedSetOf(),
    )

    fun scan(
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        val game = bridge.getGame() ?: return emptyList()
        val creatures = battlefieldCreatures(game)
        if (creatures.isEmpty()) return emptyList()

        val rows = linkedMapOf<RowKey, Row>()
        for (source in game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (staticAbility in source.staticAbilities.orEmpty()) {
                if (staticAbility.keyword != null) continue
                if (staticAbility.checkConditions(StaticAbilityMode.CantAttack)) {
                    scanCantAttack(staticAbility, creatures, game, snap, bridge, frameIds, rows)
                }
                if (staticAbility.checkConditions(StaticAbilityMode.CantBlock)) {
                    scanCantBlock(staticAbility, creatures, snap, bridge, frameIds, rows)
                }
                if (staticAbility.checkConditions(StaticAbilityMode.CantBlockBy)) {
                    scanCantBlockBy(staticAbility, creatures, snap, bridge, frameIds, rows)
                }
            }
        }

        return rows.values
            .sortedWith(compareBy<Row> { it.affectedId.value }.thenBy { it.qualificationType.wireValue }.thenBy { it.grpId.value })
            .map { row ->
                AnnotationBuilder.qualification(
                    affectorId = row.affectorId,
                    instanceId = row.affectedId,
                    grpId = row.grpId,
                    qualificationType = row.qualificationType,
                    sourceParent = row.sourceParent,
                    cantBlockObjects = row.cantBlockObjects.toList().sorted(),
                    cantBeBlockedByObjects = row.cantBeBlockedByObjects.toList().sorted(),
                )
            }
    }

    private fun scanCantAttack(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        game: Game,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (creature in creatures) {
            val targets = attackTargets(game, creature)
            if (targets.isEmpty()) continue
            if (targets.any { StaticAbilityCantAttackBlock.applyCantAttackAbility(staticAbility, creature, it) }) {
                addRow(staticAbility, creature, QualificationType.CantAttack, snap, bridge, frameIds, rows)
            }
        }
    }

    private fun scanCantBlock(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (blocker in creatures) {
            if (StaticAbilityCantAttackBlock.applyCantBlockAbility(staticAbility, blocker)) {
                addRow(staticAbility, blocker, QualificationType.CantBlock, snap, bridge, frameIds, rows)
            }
        }
    }

    private fun scanCantBlockBy(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (attacker in creatures) {
            if (appliesWithoutSpecificBlocker(staticAbility, attacker)) {
                addRow(staticAbility, attacker, QualificationType.CantBeBlocked, snap, bridge, frameIds, rows)
                continue
            }

            for (blocker in creatures) {
                if (blocker.controller == attacker.controller) continue
                if (!StaticAbilityCantAttackBlock.applyCantBlockByAbility(staticAbility, attacker, blocker)) continue

                if (isBlockerCentric(staticAbility, blocker)) {
                    val row = addRow(staticAbility, blocker, QualificationType.CantBlock, snap, bridge, frameIds, rows) ?: continue
                    row.cantBlockObjects.add(instanceId(attacker, frameIds).value)
                } else {
                    val row = addRow(staticAbility, attacker, QualificationType.CantBeBlocked, snap, bridge, frameIds, rows) ?: continue
                    row.cantBeBlockedByObjects.add(instanceId(blocker, frameIds).value)
                }
            }
        }
    }

    private fun addRow(
        staticAbility: StaticAbility,
        affected: Card,
        qualificationType: QualificationType,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        rows: MutableMap<RowKey, Row>,
    ): Row? {
        val sourceParent = sourceParent(staticAbility)
        val sourceParentIid = instanceId(sourceParent, frameIds)
        val affectedIid = instanceId(affected, frameIds)
        val grpId = abilityGrpId(staticAbility, sourceParent, snap, bridge) ?: return null
        val key = RowKey(sourceParentIid.value, affectedIid.value, grpId.value, qualificationType)
        return rows.getOrPut(key) {
            Row(
                affectorId = sourceParentIid,
                affectedId = affectedIid,
                sourceParent = sourceParentIid,
                grpId = grpId,
                qualificationType = qualificationType,
            )
        }
    }

    private fun abilityGrpId(
        staticAbility: StaticAbility,
        sourceParent: Card,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): GrpId? {
        val host = staticAbility.hostCard
        val hostData = cardData(host, snap, bridge)
        val hostRegistry = bridge.abilityRegistryFor(host, hostData)
        hostRegistry?.forStaticAbility(staticAbility.id)?.let { return GrpId(it) }

        val sourceAbility = host.getEffectSourceAbility()
        if (sourceAbility != null) {
            val sourceData = cardData(sourceParent, snap, bridge)
            val sourceRegistry = bridge.abilityRegistryFor(sourceParent, sourceData)
            sourceRegistry?.forSpellAbility(sourceAbility.id)?.let { return GrpId(it) }
        }

        return null
    }

    private fun cardData(
        card: Card,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): CardData? =
        snap.boundCards[ForgeCardId(card.id)]?.data
            ?: bridge.cardRepository.findGrpIdByName(card.name)?.let { bridge.cardRepository.findByGrpId(it) }

    private fun sourceParent(staticAbility: StaticAbility): Card = staticAbility.hostCard.getEffectSource() ?: staticAbility.hostCard

    private fun instanceId(
        card: Card,
        frameIds: FrameIdResolver,
    ): InstanceId = frameIds.cardIid(ForgeCardId(card.id))

    private fun battlefieldCreatures(game: Game): List<Card> =
        game.players.flatMap { player -> player.getZone(ZoneType.Battlefield).cards }.filter { it.isCreature }

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
