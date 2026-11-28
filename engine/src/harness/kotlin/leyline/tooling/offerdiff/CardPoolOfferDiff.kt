package leyline.tooling.offerdiff

import forge.StaticData
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.NonInteractiveScope
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
        /** Forge knows the card, but state-based actions removed it from the zone before the window. */
        val notPlaceable: Boolean = false,
    ) {
        val verdict: Verdict get() = Verdict.of(this)
    }

    /**
     * One card-and-zone outcome, strongest first. A card's overall verdict is
     * the strongest over its zones that ran.
     */
    enum class Verdict {
        Error,
        EnumerationGap,
        ProjectionDrop,
        OverOffer,
        InactiveAffordability,
        Agrees,
        NoSignal,

        /** The board cannot hold the card in this zone, such as an Aura with nothing to enchant. */
        NotPlaceable,
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
                    result.notPlaceable -> NotPlaceable
                    result.error != null || result.failures.isNotEmpty() -> Error
                    OfferDiffBucket.EnumerationGap in unnamed -> EnumerationGap
                    OfferDiffBucket.ProjectionDrop in unnamed -> ProjectionDrop
                    OfferDiffBucket.OfferedNotInForge in unnamed -> OverOffer
                    // Every state has four of each basic land and two Wastes, so an inactive play points at an
                    // affordability check or a cost the board cannot meet, such as snow mana or a large X.
                    OfferDiffBucket.OfferedInactive in buckets -> InactiveAffordability
                    result.extraPlays.isNotEmpty() -> Agrees
                    else -> NoSignal
                }
            }
        }
    }

    private fun run(
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
            harness.connect(puzzleText = puzzle(card, zone, startingCounters(card)))
            val player = checkNotNull(harness.bridge.getPlayer(SeatId(1))) { "no seat-1 player" }
            if (player.getZone(zone).cards.none { it.name == card }) {
                // The puzzle loader skips names Forge does not know; a known card that is missing was removed by
                // state-based actions, such as an Aura with nothing to enchant. Fixture cards never share its name.
                check(StaticData.instance().commonCards.getCard(card) != null) { "card unknown to Forge" }
                return ZoneResult(card, zone, emptyList(), emptyList(), notPlaceable = true)
            }
            val extra =
                NonInteractiveScope
                    .bestEffort { OfferDiff.forgeEnumeration(player.game, player) }
                    .filter { it.hostCard.name == card && it.hostCard.zone?.zoneType == zone && !isBasePlay(it) }
                    .map { describe(it) }
                    .distinct()
            val snapshot = harness.bridge.offerDiffDiagnostics.snapshot()
            // One state publishes one window; any other count means the state did not run as built.
            check(snapshot.windows == 1) { "compared ${snapshot.windows} windows, expected 1" }
            val (own, baseline) = snapshot.findings.partition { it.key.card == card && it.key.zone == zone.name }
            ZoneResult(card, zone, extra, own, baseline, snapshot.failures)
        } catch (error: VirtualMachineError) {
            throw error
        } catch (ex: Throwable) {
            // A card that breaks Forge is one errored state; the rest of the pool still runs.
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
     * reports an error. The front face decides, so a modal double-faced card
     * with an instant or sorcery front never covers its permanent back face on
     * the battlefield.
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

    /**
     * The ordinary cast and land play from hand, and a commander's ordinary cast
     * from the command zone, are what every card has; none is coverage.
     */
    private fun isBasePlay(sa: SpellAbility): Boolean {
        val ordinarySpell = sa.isSpell && sa.alternativeCost == null && sa.isBasicSpell
        return when {
            sa.hostCard.isInZone(ZoneType.Hand) -> sa.isLandAbility || (ordinarySpell && sa.mayPlay == null)
            sa.hostCard.isInZone(ZoneType.Command) -> ordinarySpell
            else -> false
        }
    }

    /**
     * Counters a permanent needs to survive on the battlefield: loyalty for a
     * planeswalker, defense for a battle, and +1/+1 counters for a creature with
     * zero toughness.
     */
    private fun startingCounters(card: String): String? {
        val rules =
            StaticData
                .instance()
                .commonCards
                .getCard(card)
                ?.rules ?: return null
        val type = rules.type
        return listOfNotNull(
            "LOYALTY=$SURVIVAL_COUNTERS".takeIf { type.isPlaneswalker },
            "DEFENSE=$SURVIVAL_COUNTERS".takeIf { type.isBattle },
            "P1P1=$SURVIVAL_COUNTERS".takeIf { type.isCreature && rules.mainPart.toughness == "0" },
        ).joinToString(",").ifEmpty { null }
    }

    private fun describe(sa: SpellAbility): String {
        val variant = sa.alternativeCost?.name ?: sa.api?.name ?: OfferDiff.kindOf(sa).name
        val label = "${OfferDiff.kindOf(sa).name}:$variant ${sa.payCosts?.toSimpleString().orEmpty()}".trim()
        // The description keeps two abilities with the same API and cost apart.
        return sa.description?.takeIf { it.isNotBlank() }?.let { "$label ($it)" } ?: label
    }

    companion object {
        val DEFAULT_ZONES = listOf(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command)
        private const val SEED = 7L
        private const val ERROR_LENGTH = 200
        private val LANDS =
            listOf("Plains", "Island", "Swamp", "Mountain", "Forest").flatMap { land -> List(LANDS_PER_COLOUR) { land } } +
                List(2) { "Wastes" }
        private const val LANDS_PER_COLOUR = 4
        private const val SURVIVAL_COUNTERS = 5

        /** Same-role stand-ins for fixture cards, used when the tested card shares a fixture's name. */
        private val STAND_INS =
            mapOf(
                "Grizzly Bears" to "Runeclaw Bear",
                "Memnite" to "Ornithopter",
                "Shock" to "Lightning Bolt",
                "Plains" to "Snow-Covered Plains",
                "Island" to "Snow-Covered Island",
                "Swamp" to "Snow-Covered Swamp",
                "Mountain" to "Snow-Covered Mountain",
                "Forest" to "Snow-Covered Forest",
                "Wastes" to "Snow-Covered Wastes",
            )

        internal fun puzzle(
            card: String,
            zone: ZoneType,
            counters: String? = null,
        ): String {
            fun with(target: ZoneType): List<String> {
                if (zone != target) return emptyList()
                val tags =
                    listOfNotNull(
                        "IsCommander".takeIf { target == ZoneType.Command },
                        counters?.takeIf { target == ZoneType.Battlefield }?.let { "Counters:$it" },
                    )
                return listOf((listOf(card) + tags).joinToString("|"))
            }

            fun fixtures(vararg names: String) = names.map { if (it == card) STAND_INS[it] ?: it else it }

            val lands = LANDS.map { if (it == card) STAND_INS[it] ?: it else it }
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
                humanhand=${(with(ZoneType.Hand) + fixtures("Grizzly Bears", "Island", "Mountain")).joinToString(";")}
                humanbattlefield=${(with(ZoneType.Battlefield) + fixtures("Grizzly Bears", "Memnite") + lands).joinToString(";")}
                humangraveyard=${(with(ZoneType.Graveyard) + fixtures("Grizzly Bears", "Shock")).joinToString(";")}
                ${with(ZoneType.Exile).takeIf { it.isNotEmpty() }?.let { "humanexile=${it.joinToString(";")}" }.orEmpty()}
                ${with(ZoneType.Command).takeIf { it.isNotEmpty() }?.let { "humancommand=${it.joinToString(";")}" }.orEmpty()}
                humanlibrary=Island;Island;Island;Island;Island
                aibattlefield=${fixtures("Grizzly Bears").single()}
                ailibrary=Island;Island;Island;Island;Island
                """.trimIndent()
        }
    }
}
