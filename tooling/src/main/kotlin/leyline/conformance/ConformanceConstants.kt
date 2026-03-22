package leyline.conformance

/** Shared constants for conformance tooling. */
object ConformanceConstants {
    /** Strip proto enum suffixes: ZoneTransfer_af5a → ZoneTransfer */
    val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")

    /** Fields that always differ between recording and engine — skip during comparison. */
    val SKIP_FIELDS = setOf(
        "gameStateId",
        "prevGameStateId",
        "msgId",
        "matchID",
        "timestamp",
        "transactionId",
        "requestId",
    )

    /** Field names that carry instance IDs — values normalized to ordinals. */
    val ID_FIELDS = setOf(
        "instanceId", "affectorId", "affectedIds", "objectInstanceIds",
        "sourceId", "itemsToSearch", "itemsSought", "targetInstanceId",
        "attackerInstanceId", "blockerInstanceId", "attackerIds",
        "targetId", "parentId", "orig_id", "new_id",
    )

    /** Parse --flag value from CLI args. */
    fun flagValue(args: List<String>, flag: String): String? {
        val idx = args.indexOf(flag)
        return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
    }

    fun flagInt(args: List<String>, flag: String): Int? = flagValue(args, flag)?.toIntOrNull()
}
