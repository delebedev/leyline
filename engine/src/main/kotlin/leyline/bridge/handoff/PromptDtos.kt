package leyline.bridge.handoff

data class CommanderReturnPromptContext(
    val oldInstanceId: Int,
    val promptInstanceId: Int,
    val originZone: CommanderZone,
    val destinationZone: CommanderZone,
    val ownerSeatId: Int,
    val transferCategory: String,
)

enum class CommanderZone { Battlefield, Graveyard, Exile, Hand, Library, Command, Limbo }
