package leyline.game.annotations

import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.state.AbilityRegistry

/**
 * Text-pattern recognizers for ability-word families that don't surface as Forge
 * `Condition$` rows. Detection is by `<AbilityWord> —` (em dash, U+2014) prefix on
 * a card's `TriggerDescription$` / `Description$` / `SpellDescription$` /
 * `PrecostDesc$` / keyword text / printed body text, since the ability-word label
 * is the canonical handle in Forge card text.
 *
 * Three registration shapes:
 *
 * - [register] — per-card emission on battlefield only. Each matching source emits
 *   its own annotation. Use when one annotation per source is the right shape
 *   (Flurry: count tracker on the battlefield permanent).
 * - [registerPerController] — per-controller-aggregated emission on battlefield only.
 *   One annotation per controller with all matching battlefield sources in
 *   `affectedIds`. Use for activated-ability or end-step ability words on resident
 *   permanents (Coven, Disappear).
 * - [registerAcrossZones] — per-controller-aggregated across hand + battlefield, plus
 *   optional per-source helper entries. Use for ETB-conditional ability words where
 *   the badge shows on the hand card pre-cast (Raid, Infusion).
 *
 * Same fixture-evidence rule as [AbilityWordScanner.CONDITIONS]: only register
 * recognizers whose emission contents are exercised by a puzzle fixture plus a
 * `ConformanceTag` test asserting the expected annotation contents.
 */
object AbilityWordTriggerRecognizers {
    private const val EM_DASH = '—'

    /** Per-card evaluator. Returns zero or more entries to emit for the given card. */
    fun interface Recognizer {
        fun evaluate(
            card: Card,
            controller: Player,
            iid: Int,
            seatIdx: Int,
            registry: AbilityRegistry?,
        ): List<AbilityWordScanner.AbilityWordEntry>
    }

    /**
     * Per-controller gate. Emits a single keyword-only entry per controller, with all
     * the controller's matching sources in `affectedIds`. Use for Coven-shape ability
     * words where the annotation aggregates across the controller's permanents.
     */
    fun interface ControllerGate {
        fun isActive(controller: Player): Boolean
    }

    /**
     * Across-zones evaluator: per-controller-aggregated keyword-only marker (gated by
     * [shouldEmitMarker]) plus optional per-source helper entries (emitted regardless
     * of marker gate, but only for sources matching the ability word). Sources are
     * collected from both hand and battlefield.
     */
    interface AcrossZonesEvaluator {
        /** Whether the per-controller keyword-only marker should be emitted. */
        fun shouldEmitMarker(controller: Player): Boolean = true

        /**
         * Per-source extra entries to emit (e.g. quantitative helper annotations).
         * Default: none. Override for words like Infusion that emit a `LifeGainedThisTurn`
         * helper alongside the marker.
         */
        fun perSourceEntries(
            card: Card,
            controller: Player,
            iid: Int,
            seatIdx: Int,
            registry: AbilityRegistry?,
        ): List<AbilityWordScanner.AbilityWordEntry> = emptyList()
    }

    private val recognizers = mutableMapOf<String, Recognizer>()
    private val controllerGates = mutableMapOf<String, ControllerGate>()
    private val acrossZonesEvaluators = mutableMapOf<String, AcrossZonesEvaluator>()

    /** Test whether `<word> —` appears in any trigger / static / activated / keyword description. */
    fun cardHasAbilityWordPrefix(
        card: Card,
        word: String,
    ): Boolean {
        val needle = "$word $EM_DASH"
        for (trigger in card.triggers ?: emptyList()) {
            val desc = trigger.getParam("TriggerDescription") ?: continue
            if (desc.startsWith(needle)) return true
        }
        for (sa in card.staticAbilities ?: emptyList()) {
            val desc = sa.getParam("Description") ?: continue
            if (desc.startsWith(needle)) return true
        }
        for (sa in card.spellAbilities ?: emptyList()) {
            // Forge expresses ability-word labels on activated abilities via `PrecostDesc$`,
            // and via `SpellDescription$` / the rendered description on spells / others.
            val precost = sa.getParam("PrecostDesc")
            if (precost != null && precost.startsWith(needle)) return true
            val desc = sa.getParam("SpellDescription") ?: sa.description ?: continue
            if (desc.startsWith(needle)) return true
        }
        for (kw in card.keywords ?: emptyList()) {
            if (kw.toString().contains(needle)) return true
        }
        // Card-text fallback covers ability words encoded on keywords whose runtime
        // representation doesn't survive zone transitions (e.g. etbCounter after ETB)
        // and any other body-text-only carriers.
        val text = card.oracleText
        if (text != null && text.contains(needle)) return true
        return false
    }

    fun scan(
        battlefieldCards: List<Card>,
        instanceIdResolver: (ForgeCardId) -> InstanceId,
        registryResolver: (Card) -> AbilityRegistry?,
        handCards: List<Card> = emptyList(),
    ): List<AbilityWordScanner.AbilityWordEntry> {
        if (recognizers.isEmpty() && controllerGates.isEmpty() && acrossZonesEvaluators.isEmpty()) {
            return emptyList()
        }
        val results = mutableListOf<AbilityWordScanner.AbilityWordEntry>()

        // Per-card recognizers (battlefield only).
        for (card in battlefieldCards) {
            val controller = card.controller ?: continue
            val iid = instanceIdResolver(ForgeCardId(card.id)).value
            val seatIdx = controller.game.registeredPlayers.indexOf(controller) + 1
            val registry = registryResolver(card)
            for ((word, recognizer) in recognizers) {
                if (!cardHasAbilityWordPrefix(card, word)) continue
                results.addAll(recognizer.evaluate(card, controller, iid, seatIdx, registry))
            }
        }

        // Per-controller gates (battlefield only): aggregate sources, emit once when gate is active.
        if (controllerGates.isNotEmpty()) {
            for ((word, gate) in controllerGates) {
                val sourcesBySeat = mutableMapOf<Int, MutableList<Int>>()
                val controllerBySeat = mutableMapOf<Int, Player>()
                for (card in battlefieldCards) {
                    if (!cardHasAbilityWordPrefix(card, word)) continue
                    val controller = card.controller ?: continue
                    val seatIdx = controller.game.registeredPlayers.indexOf(controller) + 1
                    sourcesBySeat
                        .getOrPut(seatIdx) { mutableListOf() }
                        .add(instanceIdResolver(ForgeCardId(card.id)).value)
                    controllerBySeat.putIfAbsent(seatIdx, controller)
                }
                for ((seatIdx, iids) in sourcesBySeat) {
                    val controller = controllerBySeat[seatIdx] ?: continue
                    if (!gate.isActive(controller)) continue
                    results.add(
                        AbilityWordScanner.AbilityWordEntry(
                            instanceId = seatIdx,
                            abilityWordName = word,
                            affectorId = seatIdx,
                            affectedIds = iids,
                        ),
                    )
                }
            }
        }

        // Across-zones evaluators (hand + battlefield): aggregate sources per controller for the
        // marker, then collect per-source helper entries.
        if (acrossZonesEvaluators.isNotEmpty()) {
            data class Source(
                val card: Card,
                val iid: Int,
                val controller: Player,
                val seatIdx: Int,
            )

            for ((word, evaluator) in acrossZonesEvaluators) {
                val sourcesBySeat = mutableMapOf<Int, MutableList<Source>>()
                val collect: (Card) -> Unit = collect@{ card ->
                    if (!cardHasAbilityWordPrefix(card, word)) return@collect
                    val controller = card.controller ?: return@collect
                    val seatIdx = controller.game.registeredPlayers.indexOf(controller) + 1
                    val iid = instanceIdResolver(ForgeCardId(card.id)).value
                    sourcesBySeat
                        .getOrPut(seatIdx) { mutableListOf() }
                        .add(Source(card, iid, controller, seatIdx))
                }
                battlefieldCards.forEach(collect)
                handCards.forEach(collect)

                for ((seatIdx, sources) in sourcesBySeat) {
                    val controller = sources.first().controller
                    if (evaluator.shouldEmitMarker(controller)) {
                        results.add(
                            AbilityWordScanner.AbilityWordEntry(
                                instanceId = seatIdx,
                                abilityWordName = word,
                                affectorId = seatIdx,
                                affectedIds = sources.map { it.iid },
                            ),
                        )
                    }
                    for (s in sources) {
                        val registry = registryResolver(s.card)
                        results.addAll(
                            evaluator.perSourceEntries(s.card, s.controller, s.iid, s.seatIdx, registry),
                        )
                    }
                }
            }
        }

        return results
    }

    internal fun register(
        word: String,
        recognizer: Recognizer,
    ) {
        recognizers[word] = recognizer
    }

    internal fun registerPerController(
        word: String,
        gate: ControllerGate,
    ) {
        controllerGates[word] = gate
    }

    internal fun registerAcrossZones(
        word: String,
        evaluator: AcrossZonesEvaluator,
    ) {
        acrossZonesEvaluators[word] = evaluator
    }

    init {
        // Raid — per-controller keyword-only marker across hand + battlefield.
        // Active while controller has attacked with a creature this turn. The badge
        // shows on hand cards pre-cast so the player can see the bonus is active.
        registerAcrossZones(
            "Raid",
            object : AcrossZonesEvaluator {
                override fun shouldEmitMarker(controller: Player): Boolean = controller.creaturesAttackedThisTurn.isNotEmpty()
            },
        )

        // Flurry — quantitative spell-count marker on the battlefield-resident source.
        // value = spells cast this turn by controller; threshold = 2 (the trigger fires on second spell).
        register("Flurry") { _, controller, iid, seatIdx, _ ->
            listOf(
                AbilityWordScanner.AbilityWordEntry(
                    instanceId = iid,
                    abilityWordName = "Flurry",
                    value = controller.spellsCastThisTurn,
                    threshold = 2,
                    affectorId = seatIdx,
                    affectedIds = listOf(iid),
                ),
            )
        }

        // Coven — per-controller, active when controller has 3+ creatures with different powers.
        registerPerController("Coven") { controller ->
            val powers =
                controller.creaturesInPlay
                    .map { it.netPower }
                    .toSet()
            powers.size >= 3
        }

        // Disappear — per-controller, active when a permanent left the battlefield under controller this turn (Forge Revolt).
        registerPerController("Disappear") { controller ->
            controller.hasRevolt()
        }

        // Infusion — per-controller marker across hand + battlefield (always-on while sources exist),
        // plus a per-source LifeGainedThisTurn quantitative helper. The helper rides the Infusion
        // ability id (looked up via registry from the trigger whose description carries the
        // 'Infusion —' prefix) and is omitted when no life has been gained this turn.
        registerAcrossZones(
            "Infusion",
            object : AcrossZonesEvaluator {
                override fun shouldEmitMarker(controller: Player): Boolean = true

                override fun perSourceEntries(
                    card: Card,
                    controller: Player,
                    iid: Int,
                    seatIdx: Int,
                    registry: AbilityRegistry?,
                ): List<AbilityWordScanner.AbilityWordEntry> {
                    val lifeGained = controller.lifeGainedThisTurn
                    if (lifeGained <= 0) return emptyList()
                    val infusionTrigger =
                        card.triggers?.firstOrNull { t ->
                            val desc = t.getParam("TriggerDescription") ?: return@firstOrNull false
                            desc.startsWith("Infusion $EM_DASH")
                        }
                    val abilityGrpId = infusionTrigger?.let { registry?.forTrigger(it.id)?.takeIf { id -> id > 0 } }
                    return listOf(
                        AbilityWordScanner.AbilityWordEntry(
                            instanceId = iid,
                            abilityWordName = "LifeGainedThisTurn",
                            value = lifeGained,
                            abilityGrpId = abilityGrpId,
                            affectorId = seatIdx,
                            affectedIds = listOf(iid),
                        ),
                    )
                }
            },
        )
    }
}
