package leyline.game.snapshot

import forge.StaticData
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge

/** Current dungeon and durable completion history owned by one player. */
data class DungeonSnapshot(
    val currentGrpId: Int?,
    val currentForgeCardId: ForgeCardId?,
    val currentRoomGrpId: Int?,
    val completedGrpIds: List<Int>,
) {
    companion object {
        fun grpId(
            name: String,
            cards: CardRepository,
        ): Int {
            cards.findGrpIdByName(name)?.let { return it }
            cards.findGrpIdByNameAnyFace(name)?.let { return it }
            val script =
                StaticData
                    .instance()
                    .allTokens.rules.entries
                    .single { it.value.name == name }
                    .key
            return checkNotNull(cards.findTokenGrpIdByScript(script)) { "Dungeon '$name' has no catalog identity" }
        }

        fun capture(
            player: Player,
            bridge: GameBridge,
        ): DungeonSnapshot? {
            val current = player.getCardsIn(ZoneType.Command).singleOrNull { it.type.isDungeon }
            val completed = player.completedDungeons.map { grpId(it.name, bridge.cardRepository) }.distinct()
            if (current == null && completed.isEmpty()) return null
            val room = current?.triggers?.singleOrNull { it.overridingAbility.getParam("RoomName") == current.currentRoom }
            val data = current?.let { bridge.cardRepository.findByGrpId(grpId(it.name, bridge.cardRepository)) }
            val registry = if (current != null && data != null) bridge.abilityRegistryFor(current, data) else null
            return DungeonSnapshot(
                current?.let { grpId(it.name, bridge.cardRepository) },
                current?.let { ForgeCardId(it.id) },
                room?.let { checkNotNull(registry?.forTrigger(it.definitionId)) { "Dungeon room has no ability identity" } },
                completed,
            )
        }
    }
}
