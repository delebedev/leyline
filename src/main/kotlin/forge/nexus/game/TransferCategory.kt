package forge.nexus.game

/**
 * Zone transfer categories for Arena protocol annotations.
 *
 * When a card moves between zones, the annotation carries a `category` string
 * that the client uses to pick the right animation and log text.
 * These are the known values from real server captures.
 *
 * [label] is the exact string written into the protobuf `KeyValuePairInfo`.
 */
enum class TransferCategory(val label: String) {
    PlayLand("PlayLand"),
    CastSpell("CastSpell"),
    Resolve("Resolve"),
    Destroy("Destroy"),
    Exile("Exile"),
    ZoneTransfer("ZoneTransfer"),
}
