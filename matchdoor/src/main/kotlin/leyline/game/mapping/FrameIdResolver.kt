package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.TransferResult
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge

/**
 * Frame-local instance-id resolver.
 *
 * Inside a single [StateMapper.buildDiff] call there are three different ways
 * to answer "what's the instance id of X right now?":
 *
 * - `bridge.getOrAllocInstanceId(forgeId)` — pre-realloc; the bridge holds the
 *   old iid for any zone-transferred card until `applyMutations` runs *after*
 *   `buildDiff` returns.
 * - `transfer.newId` from the active [TransferResult] — the post-realloc iid;
 *   only annotation builders that already loop over `transferResult.transfers`
 *   see this directly.
 * - Synthesised forge-id arithmetic (`source.value + STACK_ABILITY_ID_OFFSET`
 *   for stack-resident Ability gameObjects, `+ MANA_ABILITY_ID_OFFSET` for
 *   per-payment mana abilities).
 *
 * The resolver unifies these into one frame-scoped object. Construct after
 * [leyline.game.annotations.ZoneTransferDetector.detectZoneTransfers] returns
 * (when the realloc plan is known); pass into downstream consumers that want
 * "the iid the client will see at the end of this frame".
 *
 * Companion helpers cover the call sites that don't have a resolver instance —
 * `ZoneTransferDetector` itself runs *before* the resolver exists, and only
 * needs the offset arithmetic.
 *
 * **Threading**: pure data; no shared mutable state. Safe to construct per
 * `buildDiff` call.
 */
class FrameIdResolver(
    private val bridge: GameBridge,
    private val postReallocIids: Map<ForgeCardId, InstanceId> = emptyMap(),
) {
    /**
     * Card iid as the client will see it at the end of this frame.
     *
     * For zone-transferred cards in this frame, returns the post-realloc iid
     * planned by `ZoneTransferDetector` (committed by `applyMutations` after
     * `buildDiff` returns). For everything else, falls back to the bridge's
     * current iid (which is the post-realloc iid for un-transferred cards).
     */
    fun cardIid(forgeId: ForgeCardId): InstanceId =
        postReallocIids[forgeId] ?: bridge.getOrAllocInstanceId(forgeId)

    /**
     * Iid for the synthesised stack-resident Ability gameObject sourced from
     * [sourceForgeId]. The Ability lives in zone 27 alongside the source card
     * for triggered abilities; its iid is allocated against the
     * [stackAbilityForgeId] surrogate so it doesn't collide with the source
     * card's iid.
     */
    fun stackAbilityIid(sourceForgeId: ForgeCardId): InstanceId =
        bridge.getOrAllocInstanceId(stackAbilityForgeId(sourceForgeId))

    /**
     * Iid for the synthesised per-payment mana Ability gameObject — one per
     * land tap during cost payment. Distinct from [stackAbilityIid] because a
     * single source card can produce both kinds of Ability gameObjects in the
     * same frame.
     */
    fun manaAbilityIid(sourceForgeId: ForgeCardId): InstanceId =
        bridge.getOrAllocInstanceId(manaAbilityForgeId(sourceForgeId))

    /**
     * Every iid alive on the stack at the end of this frame — both card spells
     * (in `snap.zones[STACK].contents`) and stack-resident Ability gameObjects
     * (synthesised from `snap.stack.entries`). Drives `TriggeringObject`
     * lifecycle expiry: a row sticks while its ability iid is in this set and
     * prunes when it's no longer present.
     */
    fun stackInstanceIds(snap: GsmSnapshot): Set<Int> =
        buildSet {
            val cardIids = mutableSetOf<Int>()
            snap.zones[ZoneIds.STACK]?.contents?.forEach { fid ->
                val iid = cardIid(fid).value
                cardIids += iid
                add(iid)
            }
            for (entry in snap.stack.entries) {
                val cIid = cardIid(entry.forgeCardId).value
                if (cIid in cardIids) continue
                add(stackAbilityIid(entry.forgeCardId).value)
            }
        }

    /** All iids on the battlefield at the end of this frame. */
    fun battlefieldInstanceIds(snap: GsmSnapshot): Set<Int> =
        snap.zones[ZoneIds.BATTLEFIELD]?.contents?.map { cardIid(it).value }?.toSet().orEmpty()

    companion object {
        /** Offset added to source card forge IDs for stack-resident Ability gameObjects. */
        private const val STACK_ABILITY_ID_OFFSET = 100_000

        /** Offset added to land forge IDs for per-payment mana Ability gameObjects. */
        private const val MANA_ABILITY_ID_OFFSET = 200_000

        /**
         * Surrogate forge ID for a stack-resident Ability gameObject — used as
         * the key into [GameBridge.getOrAllocInstanceId] so the Ability's iid
         * doesn't collide with the source card's.
         */
        fun stackAbilityForgeId(sourceForgeId: ForgeCardId): ForgeCardId =
            ForgeCardId(sourceForgeId.value + STACK_ABILITY_ID_OFFSET)

        /** Surrogate forge ID for a per-payment mana Ability gameObject. */
        fun manaAbilityForgeId(sourceForgeId: ForgeCardId): ForgeCardId =
            ForgeCardId(sourceForgeId.value + MANA_ABILITY_ID_OFFSET)

        /** True if [forgeId] falls in the stack-ability surrogate range. */
        fun isStackAbilityForgeId(forgeId: ForgeCardId): Boolean =
            forgeId.value in STACK_ABILITY_ID_OFFSET until MANA_ABILITY_ID_OFFSET

        /** Inverse of [stackAbilityForgeId] — recover the source card's forge ID. */
        fun stackAbilitySourceForgeId(abilityForgeId: ForgeCardId): ForgeCardId =
            ForgeCardId(abilityForgeId.value - STACK_ABILITY_ID_OFFSET)

        /** Build the post-realloc map a [FrameIdResolver] needs from a [TransferResult]. */
        fun postReallocIids(transferResult: TransferResult): Map<ForgeCardId, InstanceId> =
            transferResult.transfers
                .mapNotNull { transfer ->
                    transfer.forgeCardId?.let { fid -> fid to InstanceId(transfer.newId) }
                }.toMap()
    }
}
