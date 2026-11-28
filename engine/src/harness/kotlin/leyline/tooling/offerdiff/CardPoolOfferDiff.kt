package leyline.tooling.offerdiff

import forge.StaticData
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.OfferDiff
import leyline.bridge.OfferDiffBucket
import leyline.bridge.OfferDiffDiagnostics
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.data.CardRepository
import leyline.tooling.headless.HeadlessResponseMode
import leyline.tooling.headless.MatchFlowHarness

/**
 * Runs [OfferDiff] over a list of cards, one game state per card and zone.
 *
 * Each state puts the card into one zone it can occupy, on the player's side at their first
 * main phase, with lands that make enough mana of every colour, creatures for
 * sacrifice, mutate and target costs, and spare cards to discard. The diff runs
 * on the one priority window that state publishes.
 */
class CardPoolOfferDiff(
    private val cardRepository: CardRepository,
    private val zones: List<ZoneType> = DEFAULT_ZONES,
) {
    /** Result for one card in one zone. */
    data class ZoneResult(
        val card: String,
        val zone: ZoneType,
        /** Forge's plays for the card in this zone, beyond the ordinary hand cast and land play. */
        val extraPlays: List<String>,
        /** Differences on the card under test. */
        val findings: List<OfferDiffDiagnostics.Finding>,
        /** Differences outside the card in this zone: the fixed cards every state carries, and the card elsewhere. */
        val baselineFindings: List<OfferDiffDiagnostics.Finding> = emptyList(),
        val failures: Map<String, Int> = emptyMap(),
        /** Why no window was compared: the card failed to load or the game threw. */
        val error: String? = null,
    ) {
        val verdict: Verdict get() = Verdict.of(this)
    }

    /**
     * One card-and-zone outcome, strongest first. A card's overall verdict is
     * the strongest over its zones.
     */
    enum class Verdict {
        Error,
        EnumerationGap,
        ProjectionDrop,
        OverOffer,
        InactiveAffordability,
        Agrees,
        NoSignal,
        ;

        companion object {
            fun of(result: ZoneResult): Verdict {
                val unnamed =
                    result.findings
                        .filter { it.key.rule == null }
                        .map { it.key.bucket }
                        .toSet()
                val buckets = result.findings.map { it.key.bucket }.toSet()
                return when {
                    result.error != null || result.failures.isNotEmpty() -> Error
                    OfferDiffBucket.EnumerationGap in unnamed -> EnumerationGap
                    OfferDiffBucket.ProjectionDrop in unnamed -> ProjectionDrop
                    OfferDiffBucket.OfferedNotInForge in unnamed -> OverOffer
                    // Every state has mana of every colour, so an inactive play points at an affordability check.
                    OfferDiffBucket.OfferedInactive in buckets -> InactiveAffordability
                    result.extraPlays.isNotEmpty() -> Agrees
                    else -> NoSignal
                }
            }
        }
    }

    fun run(
        card: String,
        zone: ZoneType,
    ): ZoneResult {
        val harness =
            MatchFlowHarness(
                seed = SEED,
                validating = false,
                cardRepositoryOverride = cardRepository,
                responseMode = HeadlessResponseMode.PolicyVisible,
                engineSettings =
                    MatchFlowHarness.headlessEngineSettings().let { it.copy(dev = it.dev.copy(offerDiff = true)) },
            )
        return try {
            harness.connect(puzzleText = puzzle(card, zone))
            val player = checkNotNull(harness.bridge.getPlayer(SeatId(1))) { "no seat-1 player" }
            // The puzzle loader skips names Forge does not know, and a permanent placed without counters it
            // needs (a planeswalker's loyalty) leaves the battlefield; either would otherwise read as no signal.
            check(player.getZone(zone).cards.any { it.name == card }) {
                "card not in $zone after setup: unknown to Forge, or it left the zone"
            }
            val extra =
                OfferDiff
                    .forgeEnumeration(player.game, player)
                    .filter { it.hostCard.name == card && it.hostCard.zone?.zoneType == zone && !isBasePlay(it) }
                    .map { describe(it) }
                    .distinct()
            val snapshot = harness.bridge.offerDiffDiagnostics.snapshot()
            // One state publishes one window; any other count means the state did not run as built.
            check(snapshot.windows == 1) { "compared ${snapshot.windows} windows, expected 1" }
            val (own, baseline) = snapshot.findings.partition { it.key.card == card && it.key.zone == zone.name }
            ZoneResult(card, zone, extra, own, baseline, snapshot.failures)
        } catch (ex: Exception) {
            ZoneResult(card, zone, emptyList(), emptyList(), error = "${ex::class.simpleName}: ${ex.message?.take(ERROR_LENGTH)}")
        } finally {
            runCatching { harness.shutdown() }
        }
    }

    fun runAll(
        cards: List<String>,
        onResult: (ZoneResult) -> Unit = {},
    ): List<ZoneResult> {
        GameBootstrap.initializeCardDatabase(quiet = true)
        return cards.flatMap { card ->
            zones.filter { canOccupy(card, it) }.map { zone -> run(card, zone).also(onResult) }
        }
    }

    /**
     * An instant or sorcery is never on the battlefield, and only a card that can
     * be a commander starts in the command zone. An unknown name is kept so it
     * reports an error.
     */
    private fun canOccupy(
        card: String,
        zone: ZoneType,
    ): Boolean {
        val rules =
            StaticData
                .instance()
                .commonCards
                .getCard(card)
                ?.rules ?: return true
        return when (zone) {
            ZoneType.Battlefield -> rules.type.isPermanent
            ZoneType.Command -> rules.canBeCommander()
            else -> true
        }
    }

    /** The ordinary cast from hand and the land play are what every card has; neither is coverage. */
    private fun isBasePlay(sa: SpellAbility): Boolean =
        sa.hostCard.isInZone(ZoneType.Hand) &&
            (sa.isLandAbility || (sa.isSpell && sa.alternativeCost == null && sa.mayPlay == null && sa.isBasicSpell))

    private fun describe(sa: SpellAbility): String {
        val variant = sa.alternativeCost?.name ?: sa.api?.name ?: OfferDiff.kindOf(sa).name
        return "${OfferDiff.kindOf(sa).name}:$variant ${sa.payCosts?.toSimpleString().orEmpty()}".trim()
    }

    companion object {
        val DEFAULT_ZONES = listOf(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command)
        private const val SEED = 7L
        private const val ERROR_LENGTH = 200
        private val LANDS =
            listOf("Plains", "Island", "Swamp", "Mountain", "Forest").flatMap { land -> List(LANDS_PER_COLOUR) { land } } +
                List(2) { "Wastes" }
        private const val LANDS_PER_COLOUR = 4

        internal fun puzzle(
            card: String,
            zone: ZoneType,
        ): String {
            fun with(target: ZoneType) =
                if (zone ==
                    target
                ) {
                    listOf(if (target == ZoneType.Command) "$card|IsCommander" else card)
                } else {
                    emptyList()
                }
            return """
                [metadata]
                Name:Offer diff
                Goal:Survive
                Turns:1
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=40
                AILife=40
                humanhand=${(with(ZoneType.Hand) + listOf("Grizzly Bears", "Island", "Mountain")).joinToString(";")}
                humanbattlefield=${(with(ZoneType.Battlefield) + listOf("Grizzly Bears", "Memnite") + LANDS).joinToString(";")}
                humangraveyard=${(with(ZoneType.Graveyard) + listOf("Grizzly Bears", "Shock")).joinToString(";")}
                ${with(ZoneType.Exile).takeIf { it.isNotEmpty() }?.let { "humanexile=${it.joinToString(";")}" }.orEmpty()}
                ${with(ZoneType.Command).takeIf { it.isNotEmpty() }?.let { "humancommand=${it.joinToString(";")}" }.orEmpty()}
                humanlibrary=Island;Island;Island;Island;Island
                aibattlefield=Grizzly Bears
                ailibrary=Island;Island;Island;Island;Island
                """.trimIndent()
        }
    }
}
