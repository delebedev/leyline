package leyline.game

import forge.game.card.Card
import forge.game.keyword.KeywordInterface

/**
 * Maps Forge trait IDs (SpellAbility, Trigger, StaticAbility) to Arena abilityGrpId slots
 * for a single card.
 *
 * Slot layout must match [AbilityIdDeriver]:
 * - Keywords occupy the first N slots
 * - Non-mana activated abilities follow
 * - Mana abilities and unslotted intrinsic traits fall back to slot 0
 */
class AbilityRegistry private constructor(
    private val saMap: Map<Int, Int>,
    private val staticMap: Map<Int, Int>,
    private val triggerMap: Map<Int, Int>,
) {

    /** SpellAbility forge id → abilityGrpId (mana + activated). */
    fun forSpellAbility(forgeId: Int): Int? = saMap[forgeId]

    /** StaticAbility forge id → abilityGrpId. */
    fun forStaticAbility(forgeId: Int): Int? = staticMap[forgeId]

    /** Trigger forge id → abilityGrpId. */
    fun forTrigger(forgeId: Int): Int? = triggerMap[forgeId]

    companion object {

        /** Empty registry — no mappings. */
        val EMPTY = AbilityRegistry(emptyMap(), emptyMap(), emptyMap())

        /**
         * Build a registry from a live Forge [card] and its derived [cardData].
         *
         * Uses [cardData.abilityIds] for slot values, so must be called *after*
         * [AbilityIdDeriver.deriveAbilityIds].
         */
        fun build(card: Card, cardData: CardData): AbilityRegistry {
            val abilityIds = cardData.abilityIds
            if (abilityIds.isEmpty()) return EMPTY

            val fallbackGrpId = abilityIds[0].first
            val saMap = mutableMapOf<Int, Int>()
            val staticMap = mutableMapOf<Int, Int>()
            val triggerMap = mutableMapOf<Int, Int>()

            val keywordCount = mapKeywords(card, abilityIds, saMap, staticMap, triggerMap)
            mapActivatedAbilities(card, abilityIds, keywordCount, saMap)
            mapManaAbilities(card, fallbackGrpId, saMap)
            mapUnclaimedIntrinsics(card, fallbackGrpId, staticMap, triggerMap)

            return AbilityRegistry(saMap, staticMap, triggerMap)
        }

        /** Phase 1: Keywords occupy the first N slots. Returns keyword count. */
        private fun mapKeywords(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            saMap: MutableMap<Int, Int>,
            staticMap: MutableMap<Int, Int>,
            triggerMap: MutableMap<Int, Int>,
        ): Int {
            val keywordStrings = card.rules?.mainPart?.keywords?.toList() ?: emptyList()
            val liveKeywords = card.getKeywords() ?: emptyList()
            val claimed = mutableSetOf<KeywordInterface>()

            for ((slotIdx, kwText) in keywordStrings.withIndex()) {
                if (slotIdx >= abilityIds.size) break
                val grpId = abilityIds[slotIdx].first
                val matching = liveKeywords.filter { kw ->
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

        /** Phase 2: Non-mana activated abilities in slots after keywords. */
        private fun mapActivatedAbilities(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            keywordCount: Int,
            saMap: MutableMap<Int, Int>,
        ) {
            var idx = 0
            for (sa in card.spellAbilities ?: emptyList()) {
                if (!sa.isActivatedAbility || sa.isManaAbility()) continue
                if (!sa.isIntrinsic) continue
                val slotIdx = keywordCount + idx
                if (slotIdx < abilityIds.size) saMap[sa.id] = abilityIds[slotIdx].first
                idx++
            }
        }

        /** Phase 3: Mana abilities fall back to slot 0. */
        private fun mapManaAbilities(card: Card, fallbackGrpId: Int, saMap: MutableMap<Int, Int>) {
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

        private fun matchesKeywordText(kw: KeywordInterface, rulesText: String): Boolean {
            if (kw.original.equals(rulesText, ignoreCase = true)) return true
            val kwName = kw.keyword.toString()
            return rulesText.startsWith(kwName, ignoreCase = true)
        }
    }
}
