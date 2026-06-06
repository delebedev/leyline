package leyline.game.state

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.keyword.KeywordInterface
import forge.game.spellability.SpellAbility
import leyline.game.codes.SlotEntry
import leyline.game.codes.SlotKind
import leyline.game.codes.SlotLayout
import leyline.game.data.CardData
import leyline.game.data.KeywordAbilityIds

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
            val slotKinds =
                abilityIds.mapIndexed { i, _ ->
                    when {
                        cardData.abilityKinds.size == abilityIds.size -> cardData.abilityKinds[i]
                        i < keywordCount -> SlotKind.Keyword
                        else -> SlotKind.Activated
                    }
                }
            // Indices in abilityIds that are eligible for non-mana activated SAs.
            // For Arena-sourced cards: slots whose ability metadata is Activated.
            // For data without per-slot kinds (legacy / puzzle deriver): fall back
            // to "all slots after keywords are activated" — matches old behavior.
            val activatedSlotIndices =
                if (cardData.abilityKinds.size == abilityIds.size) {
                    abilityIds.indices.filter {
                        slotKinds[it] == SlotKind.Activated
                    }
                } else {
                    (keywordCount until abilityIds.size).toList()
                }
            val manaSlotIndices =
                if (cardData.abilityKinds.size == abilityIds.size) {
                    abilityIds.indices.filter {
                        slotKinds[it] == SlotKind.Mana
                    }
                } else {
                    emptyList()
                }
            mapActivatedAbilities(card, abilityIds, activatedSlotIndices, saMap)
            mapReconfigureUnattachAbilities(card, saMap)
            mapStationThresholdStatics(card, abilityIds, staticMap)
            mapManaAbilities(card, abilityIds, manaSlotIndices, fallbackGrpId, saMap)
            mapUnclaimedIntrinsicTriggers(card, cardData, abilityIds, keywordCount, triggerMap)
            mapUnclaimedIntrinsicStatics(card, cardData, abilityIds, keywordCount, staticMap)
            mapUnclaimedLegacyStatics(card, cardData, abilityIds, keywordCount, staticMap)
            mapUnclaimedIntrinsics(card, fallbackGrpId, staticMap, triggerMap)

            // Derive SlotLayout from the same data — single source of truth.
            // Use cardData.abilityKinds when available so triggers/statics interleaved
            // among Arena's slots (e.g. Kaito at slot 0) get classified correctly.
            val virtualSlots = reconfigureUnattachSlot(card)
            val activatedCount = slotKinds.count { it == SlotKind.Activated } + virtualSlots.count { it.kind == SlotKind.Activated }
            val slots =
                abilityIds.mapIndexed { i, (grpId, textId) ->
                    SlotEntry(grpId, textId, slotKinds[i])
                } + virtualSlots
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
                if (isReconfigureUnattach(sa)) {
                    saMap[sa.id] = KeywordAbilityIds.RECONFIGURE_UNATTACH
                    continue
                }
                if (idx >= activatedSlotIndices.size) {
                    idx++
                    continue
                }
                val slotIdx = activatedSlotIndices[idx]
                if (slotIdx < abilityIds.size) saMap[sa.id] = abilityIds[slotIdx].first
                idx++
            }
        }

        private fun mapReconfigureUnattachAbilities(
            card: Card,
            saMap: MutableMap<Int, Int>,
        ) {
            for (sa in card.allSpellAbilities.orEmpty()) {
                if (isReconfigureUnattach(sa)) saMap[sa.id] = KeywordAbilityIds.RECONFIGURE_UNATTACH
            }
        }

        /** Phase 3: Map mana abilities to mana slots, with legacy fallback to slot 0. */
        private fun mapManaAbilities(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            manaSlotIndices: List<Int>,
            fallbackGrpId: Int,
            saMap: MutableMap<Int, Int>,
        ) {
            var idx = 0
            for (sa in card.spellAbilities ?: emptyList()) {
                if (!sa.isManaAbility() || !sa.isIntrinsic) continue
                val slotIdx = manaSlotIndices.getOrNull(idx)
                val grpId = if (slotIdx != null && slotIdx < abilityIds.size) abilityIds[slotIdx].first else fallbackGrpId
                saMap.putIfAbsent(sa.id, grpId)
                idx++
            }
        }

        /** Map card-specific intrinsic triggers when Arena's remaining
         *  intrinsic slots line up exactly with Forge's unclaimed triggers.
         *  Mixed static+trigger layouts keep the legacy fallback until CardData
         *  carries trigger/static categories separately. */
        private fun mapUnclaimedIntrinsicTriggers(
            card: Card,
            cardData: CardData,
            abilityIds: List<Pair<Int, Int>>,
            keywordCount: Int,
            triggerMap: MutableMap<Int, Int>,
        ) {
            if (cardData.abilityKinds.size != abilityIds.size) return
            val triggers =
                card.triggers
                    ?.filter { it.isIntrinsic && it.id !in triggerMap }
                    .orEmpty()
            if (triggers.isEmpty()) return
            val intrinsicSlots =
                abilityIds.indices.filter { idx ->
                    idx >= keywordCount && cardData.abilityKinds[idx] == SlotKind.Intrinsic
                }
            if (intrinsicSlots.size != triggers.size) return
            for ((trig, slotIdx) in triggers.zip(intrinsicSlots)) {
                triggerMap[trig.id] = abilityIds[slotIdx].first
            }
        }

        /** Map card-specific static abilities when Arena's remaining
         *  intrinsic slots line up exactly with Forge's unclaimed statics. */
        private fun mapUnclaimedIntrinsicStatics(
            card: Card,
            cardData: CardData,
            abilityIds: List<Pair<Int, Int>>,
            keywordCount: Int,
            staticMap: MutableMap<Int, Int>,
        ) {
            if (cardData.abilityKinds.size != abilityIds.size) return
            val statics =
                card.staticAbilities
                    ?.filter { it.id !in staticMap }
                    .orEmpty()
            if (statics.isEmpty()) return
            val intrinsicSlots =
                abilityIds.indices.filter { idx ->
                    idx >= keywordCount && cardData.abilityKinds[idx] == SlotKind.Intrinsic
                }
            if (intrinsicSlots.size != statics.size) return
            for ((staticAbility, slotIdx) in statics.zip(intrinsicSlots)) {
                staticMap[staticAbility.id] = abilityIds[slotIdx].first
            }
        }

        /** Legacy data without per-slot categories: map statics only when the
         * remaining post-keyword slots line up exactly after activated abilities. */
        private fun mapUnclaimedLegacyStatics(
            card: Card,
            cardData: CardData,
            abilityIds: List<Pair<Int, Int>>,
            keywordCount: Int,
            staticMap: MutableMap<Int, Int>,
        ) {
            if (cardData.abilityKinds.size == abilityIds.size) return
            val statics =
                card.staticAbilities
                    ?.filter { it.id !in staticMap }
                    .orEmpty()
            if (statics.isEmpty()) return
            val activatedCount =
                card.spellAbilities
                    ?.count { it.isActivatedAbility && it.isIntrinsic && !it.isManaAbility() } ?: 0
            val staticSlots = (keywordCount until abilityIds.size).drop(activatedCount)
            if (staticSlots.size != statics.size) return
            for ((staticAbility, slotIdx) in statics.zip(staticSlots)) {
                staticMap[staticAbility.id] = abilityIds[slotIdx].first
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
            if (rulesText.startsWith("Reconfigure", ignoreCase = true)) return false
            if (kw.original.equals(rulesText, ignoreCase = true)) return true
            val kwName = kw.keyword.toString()
            return rulesText.startsWith(kwName, ignoreCase = true)
        }

        private fun reconfigureUnattachSlot(card: Card): List<SlotEntry> =
            if (card.allSpellAbilities.orEmpty().any { isReconfigureUnattach(it) }) {
                listOf(SlotEntry(KeywordAbilityIds.RECONFIGURE_UNATTACH, 0, SlotKind.Activated))
            } else {
                emptyList()
            }

        private fun isReconfigureUnattach(sa: SpellAbility): Boolean =
            sa.api == ApiType.Unattach && sa.getParam("PrecostDesc") == "Reconfigure"

        private const val STATION_THRESHOLD_ABILITY_ID_FLOOR = 60_000
    }
}
