package leyline.game.state

import forge.game.card.Card
import forge.game.keyword.KeywordInterface
import leyline.game.codes.SlotEntry
import leyline.game.codes.SlotKind
import leyline.game.codes.SlotLayout
import leyline.game.data.CardData

/**
 * Maps Forge trait IDs (SpellAbility, Trigger, StaticAbility) to client
 * abilityGrpId slots for a single card.
 *
 * Slot ordering follows [CardData.abilityIds] verbatim — the same shape
 * the client's `Cards.AbilityIds` column produces. Mana abilities and
 * unslotted intrinsic traits fall back to slot 0.
 */
class AbilityRegistry private constructor(
    private val saMap: Map<Int, Int>,
    private val staticMap: Map<Int, Int>,
    private val triggerMap: Map<Int, Int>,
    val slotLayout: SlotLayout = SlotLayout.Companion.EMPTY,
) {
    /** SpellAbility forge id → abilityGrpId (mana + activated). */
    fun forSpellAbility(forgeId: Int): Int? = saMap[forgeId]

    /** StaticAbility forge id → abilityGrpId. */
    fun forStaticAbility(forgeId: Int): Int? = staticMap[forgeId]

    /** Trigger forge id → abilityGrpId. */
    fun forTrigger(forgeId: Int): Int? = triggerMap[forgeId]

    companion object {
        /** Empty registry — no mappings. */
        val EMPTY = AbilityRegistry(emptyMap(), emptyMap(), emptyMap(), SlotLayout.Companion.EMPTY)

        /**
         * Build a registry from a live Forge [card] and its [cardData].
         * Uses [cardData.abilityIds] verbatim as the slot layout.
         */
        fun build(
            card: Card,
            cardData: CardData,
        ): AbilityRegistry {
            val abilityIds = cardData.abilityIds
            if (abilityIds.isEmpty()) return EMPTY

            val fallbackGrpId = abilityIds[0].first
            val saMap = mutableMapOf<Int, Int>()
            val staticMap = mutableMapOf<Int, Int>()
            val triggerMap = mutableMapOf<Int, Int>()

            val keywordCount = mapKeywords(card, abilityIds, saMap, staticMap, triggerMap)
            // Indices in abilityIds that are eligible for activated SAs.
            // For Arena-sourced cards: slots whose `Abilities.Category=1`.
            // For data without per-slot kinds (legacy / puzzle deriver): fall back
            // to "all slots after keywords are activated" — matches old behavior.
            val activatedSlotIndices =
                if (cardData.abilityKinds.size == abilityIds.size) {
                    abilityIds.indices.filter {
                        it >= keywordCount && cardData.abilityKinds[it] == SlotKind.Activated
                    }
                } else {
                    (keywordCount until abilityIds.size).toList()
                }
            mapActivatedAbilities(card, abilityIds, activatedSlotIndices, saMap)
            mapStationThresholdStatics(card, abilityIds, staticMap)
            mapManaAbilities(card, fallbackGrpId, saMap)
            mapUnclaimedIntrinsics(card, fallbackGrpId, staticMap, triggerMap)

            // Derive SlotLayout from the same data — single source of truth.
            // Use cardData.abilityKinds when available so triggers/statics interleaved
            // among Arena's slots (e.g. Kaito at slot 0) get classified correctly.
            val activatedCount = activatedSlotIndices.size.coerceAtLeast(0)
            val slots =
                abilityIds.mapIndexed { i, (grpId, textId) ->
                    val kind =
                        when {
                            i < keywordCount -> SlotKind.Keyword
                            cardData.abilityKinds.size == abilityIds.size ->
                                cardData.abilityKinds[i]
                            else -> SlotKind.Activated
                        }
                    SlotEntry(grpId, textId, kind)
                }
            val layout = SlotLayout(keywordCount, activatedCount, slots)

            return AbilityRegistry(saMap, staticMap, triggerMap, layout)
        }

        /**
         * Station threshold rows are per-card static ability ids (e.g. 60002,
         * 60024) interleaved with granted core ability rows. Forge exposes the
         * threshold striations as static abilities whose descriptions start with
         * `STATION N+`; Arena's per-threshold rows are the matching high synthetic
         * ids in card slot order.
         */
        private fun mapStationThresholdStatics(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            staticMap: MutableMap<Int, Int>,
        ) {
            val stationStatics =
                card.staticAbilities
                    ?.filter { it.getParam("Description")?.startsWith("STATION ") == true }
                    .orEmpty()
            if (stationStatics.isEmpty()) return

            val thresholdRows = abilityIds.map { it.first }.filter { it >= STATION_THRESHOLD_ABILITY_ID_FLOOR }
            for ((staticAbility, grpId) in stationStatics.zip(thresholdRows)) {
                staticMap[staticAbility.id] = grpId
            }
        }

        /** Phase 1: Keywords occupy the first N slots. Returns keyword count. */
        private fun mapKeywords(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            saMap: MutableMap<Int, Int>,
            staticMap: MutableMap<Int, Int>,
            triggerMap: MutableMap<Int, Int>,
        ): Int {
            val keywordStrings =
                card.rules
                    ?.mainPart
                    ?.keywords
                    ?.toList() ?: emptyList()
            val liveKeywords = card.getKeywords() ?: emptyList()
            val claimed = mutableSetOf<KeywordInterface>()

            for ((slotIdx, kwText) in keywordStrings.withIndex()) {
                if (slotIdx >= abilityIds.size) break
                val grpId = abilityIds[slotIdx].first
                val matching =
                    liveKeywords.filter { kw ->
                        kw !in claimed && kw.isIntrinsic && matchesKeywordText(kw, kwText)
                    }
                for (kw in matching) {
                    claimed.add(kw)
                    for (sa in kw.abilities) saMap[sa.id] = grpId
                    for (trig in kw.triggers) triggerMap[trig.id] = grpId
                    for (st in kw.staticAbilities) staticMap[st.id] = grpId
                }
            }
            return keywordStrings.size
        }

        /**
         * Phase 2: Map non-mana activated SAs to abilityGrpIds by walking
         * [activatedSlotIndices] in order. Each indexed slot is presumed
         * to be an activated ability per upstream classification (Arena's
         * `Abilities.Category=1` for production data; positional fallback
         * for puzzle/test paths). Skipping non-activated slots prevents
         * the off-by-one bug where Arena's interleaved trigger/static
         * slots would shift activated SAs onto the wrong grpIds.
         */
        private fun mapActivatedAbilities(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            activatedSlotIndices: List<Int>,
            saMap: MutableMap<Int, Int>,
        ) {
            var idx = 0
            for (sa in card.spellAbilities ?: emptyList()) {
                if (!sa.isActivatedAbility || sa.isManaAbility()) continue
                if (!sa.isIntrinsic) continue
                if (idx >= activatedSlotIndices.size) {
                    idx++
                    continue
                }
                val slotIdx = activatedSlotIndices[idx]
                if (slotIdx < abilityIds.size) saMap[sa.id] = abilityIds[slotIdx].first
                idx++
            }
        }

        /** Phase 3: Mana abilities fall back to slot 0. */
        private fun mapManaAbilities(
            card: Card,
            fallbackGrpId: Int,
            saMap: MutableMap<Int, Int>,
        ) {
            for (sa in card.spellAbilities ?: emptyList()) {
                if (!sa.isManaAbility() || !sa.isIntrinsic) continue
                saMap.putIfAbsent(sa.id, fallbackGrpId)
            }
        }

        /** Phase 4: Unclaimed intrinsic statics and triggers fall back to slot 0. */
        private fun mapUnclaimedIntrinsics(
            card: Card,
            fallbackGrpId: Int,
            staticMap: MutableMap<Int, Int>,
            triggerMap: MutableMap<Int, Int>,
        ) {
            for (st in card.staticAbilities ?: emptyList()) {
                if (!st.isIntrinsic) continue
                staticMap.putIfAbsent(st.id, fallbackGrpId)
            }
            for (trig in card.triggers ?: emptyList()) {
                if (!trig.isIntrinsic) continue
                triggerMap.putIfAbsent(trig.id, fallbackGrpId)
            }
        }

        private fun matchesKeywordText(
            kw: KeywordInterface,
            rulesText: String,
        ): Boolean {
            if (kw.original.equals(rulesText, ignoreCase = true)) return true
            val kwName = kw.keyword.toString()
            return rulesText.startsWith(kwName, ignoreCase = true)
        }

        private const val STATION_THRESHOLD_ABILITY_ID_FLOOR = 60_000
    }
}
