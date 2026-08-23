package leyline.game.generator

import forge.deck.DeckSection
import forge.gamemodes.limited.IBoosterDraft
import forge.item.PaperCard
import forge.model.FModel
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.DraftConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * [BoosterDraftDriver] backed by Forge's `BoosterDraft` engine.
 *
 * Holds one [HeadlessBoosterDraft] per active session. Translates between
 * Forge `PaperCard` and Arena `grpId` via a name-to-grpId lookup.
 *
 * Falls back to FDN when [start] is given a set with no Forge booster template
 * (matches the pre-existing `DraftPackGenerator` behaviour so QuickDraft keeps
 * working for sets we haven't templated yet).
 *
 * **Concurrency:**
 * - Public methods are `@Synchronized` because Netty FD handlers run on event-loop
 *   threads — the same player's quick re-entry, or two separate accounts in
 *   simultaneous drafts, would otherwise race the session map.
 * - [HeadlessBoosterDraft.init] writes `IBoosterDraft.LAND_SET_CODE[0]` — a
 *   process-global static array. [start] guards the write with a single-flight
 *   check that errors if a different set is already in flight; same-set
 *   re-init (different player, same QuickDraft event) is allowed.
 */
class ForgeBoosterDraftDriver(
    private val findGrpIdByName: (String) -> Int?,
    private val draftConfig: DraftConfig = DraftConfig(),
) : BoosterDraftDriver {
    private val log = LoggerFactory.getLogger(ForgeBoosterDraftDriver::class.java)

    init {
        // Forge's card DB is process-global and idempotent on subsequent calls.
        // Initialize once at driver construction so the FD path doesn't re-enter
        // the (cheap-but-non-trivial) initializer per `start()`.
        GameBootstrap.initializeCardDatabase()
    }

    private data class Active(
        val draft: HeadlessBoosterDraft,
        val setCode: String,
        var packIndex: Int,
        var pickIndex: Int,
    )

    private val sessions = ConcurrentHashMap<String, Active>()

    @Synchronized
    override fun start(
        sessionKey: String,
        setCode: String,
    ): List<Int> {
        check(!sessions.containsKey(sessionKey)) { "Draft session $sessionKey already started" }

        val effectiveSet = resolveSet(setCode)
        // LAND_SET_CODE[0] is shared global state — refuse to clobber if another
        // active session is using a different set.
        val mismatched = sessions.values.firstOrNull { it.setCode != effectiveSet }
        check(mismatched == null) {
            "Cannot start draft for $effectiveSet — concurrent session ${mismatched!!.setCode} in flight " +
                "(LAND_SET_CODE[0] is process-global)"
        }
        val strategy =
            when (draftConfig.picker) {
                "model" -> DraftPickStrategies.modelBacked(effectiveSet, draftConfig.modelDir)
                else -> DraftPickStrategies.default()
            }
        val draft = HeadlessBoosterDraft(effectiveSet, strategy)
        sessions[sessionKey] = Active(draft, effectiveSet, packIndex = 0, pickIndex = 0)
        return packToGrpIds(draft.currentPackPaperCards())
    }

    @Synchronized
    override fun pick(
        sessionKey: String,
        grpId: Int,
    ): PickResult {
        val active = sessions[sessionKey] ?: error("No active draft session: $sessionKey")
        val draft = active.draft
        // Other concurrent sessions may have rewritten LAND_SET_CODE[0] since this
        // session started. Restore ours before any pack-and-pass logic that hits it.
        IBoosterDraft.LAND_SET_CODE[0] = FModel.getMagicDb().getEditions().get(active.setCode)

        val pack = draft.currentPackPaperCards()
        val card =
            pack.firstOrNull { findGrpIdByName(it.name) == grpId }
                ?: error("Card grpId=$grpId not in current pack (session=$sessionKey)")

        val accepted = draft.chooseLocally(card)
        check(accepted) { "Forge rejected pick: card=${card.name} session=$sessionKey" }

        val complete = !draft.hasNextChoice()
        val nextPack = if (complete) emptyList() else draft.currentPackPaperCards()
        // Forge's `getRound()` is 1-indexed (incremented in startRound() on each pack
        // rotation). The QuickDraft client uses a 0-indexed packNumber.
        val nextPackIndex = if (complete) active.packIndex else (draft.round - 1)

        val nextPickIndex =
            if (complete) {
                active.pickIndex
            } else if (nextPackIndex == active.packIndex) {
                active.pickIndex + 1
            } else {
                0
            }

        active.packIndex = nextPackIndex
        active.pickIndex = nextPickIndex

        return PickResult(
            packNumber = nextPackIndex,
            pickNumber = nextPickIndex,
            nextPack = packToGrpIds(nextPack),
            complete = complete,
        )
    }

    @Synchronized
    override fun complete(sessionKey: String): PodResult {
        val active = sessions[sessionKey] ?: error("No active draft session: $sessionKey")
        val draft = active.draft
        IBoosterDraft.LAND_SET_CODE[0] = FModel.getMagicDb().getEditions().get(active.setCode)
        val playerPool = packToGrpIds(draft.localPlayerPool())
        val botDecks =
            draft.computerDeckMains().map { deck ->
                val main = deck.getOrCreate(DeckSection.Main)
                val grpIds = mutableListOf<Int>()
                val unmapped = mutableListOf<String>()
                for (entry in main) {
                    val grpId = findGrpIdByName(entry.key.name)
                    if (grpId != null) {
                        repeat(entry.value) { grpIds.add(grpId) }
                    } else {
                        unmapped.add(entry.key.name)
                    }
                }
                if (unmapped.isNotEmpty()) {
                    log.error("Bot deck: no grpId for {}", unmapped)
                    throw UnmappedCardNamesException(unmapped)
                }
                grpIds.toList()
            }
        sessions.remove(sessionKey)
        return PodResult(playerPool = playerPool, botDecks = botDecks)
    }

    @Synchronized
    override fun discardAll() {
        sessions.clear()
    }

    private fun packToGrpIds(pack: List<PaperCard>): List<Int> {
        val out = mutableListOf<Int>()
        val unmapped = mutableListOf<String>()
        for (card in pack) {
            val grpId = findGrpIdByName(card.name)
            if (grpId != null) {
                out.add(grpId)
            } else {
                unmapped.add(card.name)
            }
        }
        if (unmapped.isNotEmpty()) {
            log.error("Draft pack: no grpId for {}", unmapped)
            throw UnmappedCardNamesException(unmapped)
        }
        return out
    }

    private fun resolveSet(setCode: String): String {
        val boosters = FModel.getMagicDb().getBoosters()
        if (boosters.get(setCode) != null) return setCode
        log.warn("No booster template for '{}', falling back to FDN", setCode)
        return "FDN"
    }
}
