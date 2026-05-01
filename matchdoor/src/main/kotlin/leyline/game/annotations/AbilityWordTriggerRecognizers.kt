package leyline.game.annotations

import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.state.AbilityRegistry

/**
 * Text-pattern recognizers for ability-word families that don't surface as Forge
 * `Condition$` rows. Detection is by `<AbilityWord> —` (em dash, U+2014) prefix on
 * a card's `TriggerDescription$` / `Description$` / keyword text, since the
 * ability-word label is the canonical handle in Forge card text.
 *
 * Per-word evaluators register through [register] and decide whether to emit an
 * [AbilityWordScanner.AbilityWordEntry] given the card, its controller, and a
 * computed instance id + seat index. Each evaluator owns its own gating
 * (e.g. attacked-this-turn for Raid, life-gained-this-turn for Infusion).
 *
 * Same fixture-evidence rule as [AbilityWordScanner.CONDITIONS]: only register
 * recognizers whose emission contents are exercised by a puzzle fixture plus a
 * `ConformanceTag` test asserting the expected annotation shape.
 */
object AbilityWordTriggerRecognizers {
    private const val EM_DASH = '—'

    /** Per-word evaluator. Returns zero or more entries to emit for the given card. */
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

    private val recognizers = mutableMapOf<String, Recognizer>()
    private val controllerGates = mutableMapOf<String, ControllerGate>()

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
        return false
    }

    fun scan(
        battlefieldCards: List<Card>,
        instanceIdResolver: (ForgeCardId) -> InstanceId,
        registryResolver: (Card) -> AbilityRegistry?,
    ): List<AbilityWordScanner.AbilityWordEntry> {
        if (recognizers.isEmpty() && controllerGates.isEmpty()) return emptyList()
        val results = mutableListOf<AbilityWordScanner.AbilityWordEntry>()

        // Per-card recognizers.
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

        // Per-controller gates: aggregate sources per controller, emit once when active.
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

    init {
        // Raid — keyword-only marker. Active while controller has attacked with a creature this turn.
        register("Raid") { _, controller, iid, seatIdx, _ ->
            if (controller.creaturesAttackedThisTurn.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    AbilityWordScanner.AbilityWordEntry(
                        instanceId = iid,
                        abilityWordName = "Raid",
                        affectorId = seatIdx,
                        affectedIds = listOf(iid),
                    ),
                )
            }
        }

        // Flurry — quantitative spell-count marker. Always emitted while a Flurry source is in play.
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

        // Infusion — keyword marker plus a LifeGainedThisTurn quantitative helper.
        // The marker is always-on while a source is in play. The helper rides the Infusion
        // ability id (looked up via registry from the trigger whose description carries the
        // 'Infusion —' prefix) and is omitted when no life has been gained this turn.
        register("Infusion") { card, controller, iid, seatIdx, registry ->
            val out = mutableListOf<AbilityWordScanner.AbilityWordEntry>()
            out.add(
                AbilityWordScanner.AbilityWordEntry(
                    instanceId = iid,
                    abilityWordName = "Infusion",
                    affectorId = seatIdx,
                    affectedIds = listOf(iid),
                ),
            )
            val lifeGained = controller.lifeGainedThisTurn
            if (lifeGained > 0) {
                val infusionTrigger =
                    card.triggers?.firstOrNull { t ->
                        val desc = t.getParam("TriggerDescription") ?: return@firstOrNull false
                        desc.startsWith("Infusion $EM_DASH")
                    }
                val abilityGrpId = infusionTrigger?.let { registry?.forTrigger(it.id)?.takeIf { id -> id > 0 } }
                out.add(
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
            out
        }
    }
}
