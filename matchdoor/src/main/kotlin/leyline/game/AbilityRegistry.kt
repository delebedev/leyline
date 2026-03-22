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

            // --- Phase 1: Keywords (first N slots) ---
            val keywordStrings = card.rules?.mainPart?.keywords?.toList() ?: emptyList()
            val liveKeywords = card.getKeywords() ?: emptyList()

            // Match each keyword string from rules to its live KeywordInterface objects.
            // Multiple KeywordInterface can share the same Keyword enum (e.g., Protection from X, Y).
            // Track which live keywords we've already claimed.
            val claimed = mutableSetOf<KeywordInterface>()

            for ((slotIdx, kwText) in keywordStrings.withIndex()) {
                if (slotIdx >= abilityIds.size) break
                val grpId = abilityIds[slotIdx].first

                // Find live keywords matching this rules text.
                // KeywordInterface.getOriginal() returns the full text like "Flying" or "Bushido 2".
                val matching = liveKeywords.filter { kw ->
                    kw !in claimed && kw.isIntrinsic && matchesKeywordText(kw, kwText)
                }

                for (kw in matching) {
                    claimed.add(kw)
                    // Register sub-traits: abilities (SpellAbility), triggers, statics
                    for (sa in kw.abilities) {
                        saMap[sa.id] = grpId
                    }
                    for (trig in kw.triggers) {
                        triggerMap[trig.id] = grpId
                    }
                    for (st in kw.staticAbilities) {
                        staticMap[st.id] = grpId
                    }
                }
            }

            // --- Phase 2: Non-mana activated abilities (slots after keywords) ---
            val activatedSlotStart = keywordStrings.size
            var activatedIdx = 0
            for (sa in card.spellAbilities ?: emptyList()) {
                if (!sa.isActivatedAbility || sa.isManaAbility()) continue
                if (!sa.isIntrinsic) continue

                val slotIdx = activatedSlotStart + activatedIdx
                if (slotIdx < abilityIds.size) {
                    saMap[sa.id] = abilityIds[slotIdx].first
                }
                activatedIdx++
            }

            // --- Phase 3: Mana abilities → slot 0 fallback ---
            for (sa in card.spellAbilities ?: emptyList()) {
                if (!sa.isManaAbility()) continue
                if (!sa.isIntrinsic) continue
                saMap.putIfAbsent(sa.id, fallbackGrpId)
            }

            // --- Phase 4: Unclaimed intrinsic statics and triggers → slot 0 ---
            for (st in card.staticAbilities ?: emptyList()) {
                if (!st.isIntrinsic) continue
                staticMap.putIfAbsent(st.id, fallbackGrpId)
            }
            for (trig in card.triggers ?: emptyList()) {
                if (!trig.isIntrinsic) continue
                triggerMap.putIfAbsent(trig.id, fallbackGrpId)
            }

            return AbilityRegistry(saMap, staticMap, triggerMap)
        }

        /**
         * Match a live [KeywordInterface] to a rules keyword text string.
         *
         * Rules keywords are full text like "Flying", "Bushido 2", "Protection from red".
         * We compare against [KeywordInterface.getOriginal] which stores the same format.
         */
        private fun matchesKeywordText(kw: KeywordInterface, rulesText: String): Boolean {
            // Direct match on original text (most keywords)
            if (kw.original.equals(rulesText, ignoreCase = true)) return true
            // Fallback: match by keyword enum display name prefix
            // Handles cases where rules text has extra params
            val kwName = kw.keyword.toString()
            return rulesText.startsWith(kwName, ignoreCase = true)
        }
    }
}
