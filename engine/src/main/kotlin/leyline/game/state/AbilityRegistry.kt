package leyline.game.state

import forge.game.ability.AbilityFactory
import forge.game.ability.AbilityUtils
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardState
import forge.game.card.CardTraitChanges
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordInterface
import forge.game.spellability.SpellAbility
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.AbilityIdentitySource
import leyline.bridge.types.AbilityKeywordFamily
import leyline.bridge.types.AbilityLookup
import leyline.bridge.types.AbilityLookupMiss
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.game.codes.SlotEntry
import leyline.game.codes.SlotKind
import leyline.game.codes.SlotLayout
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardData
import leyline.game.data.KeywordAbilityIds
import leyline.game.data.normalizedKeyword
import leyline.game.data.resolutionGrantedAbilityVariables

/**
 * Maps stable Forge definition IDs (SpellAbility, Trigger, StaticAbility) to client
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
    private val keywordFamilies: Map<AbilityDefinitionRef, AbilityKeywordFamily>,
    private val hiddenAbilityIds: List<Pair<Int, Int>> = emptyList(),
    val sourceCardGrpId: Int,
    val slotLayout: SlotLayout = SlotLayout.Companion.EMPTY,
    private val grantedKeywordAbilityGrpId: (SpellAbility) -> Int? = { null },
    private val keywordSlotIndices: Map<Int, Int> = emptyMap(),
    private val grantedIds: GrantedAbilityIds = GrantedAbilityIds(),
    private val builtSpellAbilityDefinitions: Set<Int> = emptySet(),
    private val ledger: AbilityIdentityLedger? = null,
) {
    /**
     * True while [card] still exposes the spell ability definitions this registry was built from.
     *
     * Forge regenerates a card's [SpellAbility] instances on zone changes, so cached
     * definition-id rows go stale even though the printed slots are unchanged.
     */
    fun matchesLiveSpellAbilities(card: Card): Boolean {
        val live = card.spellAbilities
        if (live.size != builtSpellAbilityDefinitions.size) return false
        return live.all { it.definitionId in builtSpellAbilityDefinitions }
    }

    /** Resolve a live or copied SpellAbility through its stable definition identity. */
    fun forSpellAbility(ability: SpellAbility): Int? = lookupRow(ability).valueOrNull()

    /** The row of a live or copied SpellAbility, and which source on this card answered. */
    private fun lookupRow(ability: SpellAbility): AbilityLookup<Int> {
        if (ability.originalAbility != null) return lookupRow(ability.originalAbility)
        if (ability.grantorStatic != null) {
            return grantedAbilityGrpId(ability)?.let { AbilityLookup.Resolved(it, AbilityIdentitySource.ContinuousGrant) }
                ?: AbilityLookup.Missing(AbilityLookupMiss.NoRow)
        }
        grantedKeywordAbilityGrpId(ability)?.let { return AbilityLookup.Resolved(it, AbilityIdentitySource.GrantedKeyword) }
        return forSpellAbility(ability.definitionId)?.let { AbilityLookup.Resolved(it, AbilityIdentitySource.Registry) }
            ?: AbilityLookup.Missing(AbilityLookupMiss.NoRow)
    }

    /** SpellAbility definition ID → abilityGrpId (mana + activated). */
    fun forSpellAbility(definitionId: Int): Int? = resolve(AbilityDefinitionRef.SpellAbility(definitionId))?.abilityGrpId

    /** StaticAbility definition ID → abilityGrpId. */
    fun forStaticAbility(definitionId: Int): Int? = resolve(AbilityDefinitionRef.StaticAbility(definitionId))?.abilityGrpId

    /** Trigger definition ID → abilityGrpId. */
    fun forTrigger(definitionId: Int): Int? = resolve(AbilityDefinitionRef.Trigger(definitionId))?.abilityGrpId

    fun resolve(definition: AbilityDefinitionRef): ResolvedAbilityIdentity? {
        val abilityGrpId =
            when (definition) {
                is AbilityDefinitionRef.SpellAbility -> saMap[definition.definitionId]
                is AbilityDefinitionRef.Trigger -> triggerMap[definition.definitionId]
                is AbilityDefinitionRef.StaticAbility -> staticMap[definition.definitionId]
            } ?: return null
        return ResolvedAbilityIdentity(definition, abilityGrpId, keywordFamilies[definition])
    }

    /** Resolve a regenerated intrinsic trigger when this card exposes one trigger identity. */
    internal fun resolveSoleIntrinsicTrigger(definition: AbilityDefinitionRef.Trigger): ResolvedAbilityIdentity? =
        triggerMap.values
            .distinct()
            .singleOrNull()
            ?.let { ResolvedAbilityIdentity(definition, it) }

    /** Resolve an ability added by a continuous `AddAbility` effect. */
    fun grantedAbilityGrpId(ability: SpellAbility): Int? {
        val grantor = ability.grantorStatic ?: return null
        if (hiddenAbilityIds.size == 1) return hiddenAbilityIds.single().first
        val variables =
            grantor.hostCard.staticAbilities
                .orEmpty()
                .flatMap { it.getParam("AddAbility")?.split(" & ").orEmpty() }
                .distinct()
        // Continuous rows lead the hidden layout; resolution-grant rows may follow them.
        val resolutionRows =
            grantor.cardState
                ?.let(::resolutionGrantVariables)
                .orEmpty()
                .count { it !in variables }
        if (variables.size != hiddenAbilityIds.size && variables.size + resolutionRows != hiddenAbilityIds.size) return null
        val matching =
            variables.withIndex().filter { (_, variable) ->
                val script = AbilityUtils.getSVar(grantor, variable)
                script.isNotEmpty() && AbilityFactory.getMapParams(script) == ability.originalMapParams
            }
        return matching.singleOrNull()?.let { hiddenAbilityIds[it.index].first }
    }

    /**
     * Resolve an ability that an Animate or AnimateAll effect defined on this card added when it resolved.
     *
     * Hidden rows list continuous `AddAbility` grants first, then resolution grants in variable-name order.
     * A layout that does not match that shape resolves nothing rather than guessing a row.
     */
    private fun resolutionGrantedAbilityGrpId(ability: SpellAbility): Int? {
        val state = resolutionGrantState(ability) ?: return null
        val svars = state.sVars
        val staticVariables =
            state.staticAbilities
                .flatMap { it.getParam("AddAbility")?.split(" & ").orEmpty() }
                .distinct()
        val resolutionVariables = resolutionGrantVariables(state)
        val variables = (staticVariables + resolutionVariables).distinct()
        if (variables.size != hiddenAbilityIds.size) return null
        val variable =
            resolutionVariables.singleOrNull { AbilityFactory.getMapParams(svars.getValue(it)) == ability.originalMapParams }
                ?: return null
        return hiddenAbilityIds[variables.indexOf(variable)].first
    }

    companion object {
        private const val INITIAL_UNIQUE_ABILITY_ID = 50

        /** Copied abilities use the original definition's catalog, rather than the copying effect's catalog. */
        private fun identitySource(ability: SpellAbility): Card =
            ability.originalAbility?.let(::identitySource) ?: ability.grantorStatic?.hostCard ?: ability.hostCard

        /** Resolve the original definition's catalog without retaining a live ability. */
        fun resolveSpellAbility(
            ability: SpellAbility,
            cardDataLookup: (Card) -> CardData?,
            registryLookup: (Card, CardData?) -> AbilityRegistry?,
        ): ResolvedAbilityIdentity? = lookupSpellAbility(ability, cardDataLookup, registryLookup).valueOrNull()

        /**
         * Look up the original definition's identity and record the outcome in the answering registry's ledger.
         * A source without catalog data has no registry and so no ledger; that miss is returned but not counted.
         */
        fun lookupSpellAbility(
            ability: SpellAbility,
            cardDataLookup: (Card) -> CardData?,
            registryLookup: (Card, CardData?) -> AbilityRegistry?,
        ): AbilityLookup<ResolvedAbilityIdentity> {
            val original = generateSequence(ability) { it.originalAbility }.last()
            val definition = AbilityDefinitionRef.SpellAbility(original.definitionId)
            resolutionGrantState(original)?.card?.let { donor ->
                val donorRegistry = registryLookup(donor, cardDataLookup(donor))
                donorRegistry?.resolutionGrantedAbilityGrpId(original)?.let {
                    return donorRegistry.recordSpellAbility(
                        AbilityLookup.Resolved(ResolvedAbilityIdentity(definition, it), AbilityIdentitySource.ResolutionGrant),
                    )
                }
            }
            val source = identitySource(ability)
            val registry = registryLookup(source, cardDataLookup(source)) ?: return AbilityLookup.Missing(AbilityLookupMiss.NoCardData)
            val row =
                when (val lookup = registry.lookupRow(ability)) {
                    is AbilityLookup.Missing -> return registry.recordSpellAbility(lookup)
                    is AbilityLookup.Resolved -> lookup
                }
            val identity =
                registry.resolve(definition)?.takeIf { it.abilityGrpId == row.value }
                    ?: ResolvedAbilityIdentity(definition, row.value)
            registry.ledger?.checkRow(AbilityIdentityLedger.Site.SpellAbility, ability, row.value, row.via)
            return registry.recordSpellAbility(AbilityLookup.Resolved(identity, row.via))
        }

        private fun AbilityRegistry.recordSpellAbility(
            lookup: AbilityLookup<ResolvedAbilityIdentity>,
        ): AbilityLookup<ResolvedAbilityIdentity> = ledger?.record(AbilityIdentityLedger.Site.SpellAbility, lookup) ?: lookup

        /** Immutable identity observed while the caller owns the live Forge graph. */
        data class ActivatedIdentity(
            val definition: AbilityDefinitionRef.SpellAbility,
            val abilityGrpId: Int,
            val uniqueAbilityId: Int?,
            val grant: EffectProjectionFacts.GrantedAbilityEntry?,
        )

        /** Resolve definition, catalog row, recipient ordinal, and current grant lifetime together. */
        fun resolveActivated(
            card: Card,
            ability: SpellAbility,
            cardDataLookup: (Card) -> CardData?,
            registryLookup: (Card, CardData?) -> AbilityRegistry?,
            fallbackAbilityGrpId: Int = 0,
        ): ActivatedIdentity {
            val resolved = resolveSpellAbility(ability, cardDataLookup, registryLookup)
            val abilityGrpId = resolved?.abilityGrpId ?: fallbackAbilityGrpId
            val cardData = cardDataLookup(card)
            val grants = traitGrants(card)
            val grantIndex =
                grants.indexOfFirst { it.first.id == ability.id }.takeIf { it >= 0 }
                    ?: grants
                        .withIndex()
                        .singleOrNull {
                            it.value.first.definitionId == ability.definitionId && it.value.first.grantorStatic == ability.grantorStatic
                        }?.index
            val recipientRegistry = registryLookup(card, cardData)
            val grantKeys =
                grants.map { (granted, timestamp, staticId) ->
                    GrantedAbilityIds.Key(timestamp, staticId, generateSequence(granted) { it.originalAbility }.last().definitionId)
                }
            recipientRegistry?.grantedIds?.retain(grantKeys.toSet())
            val original = generateSequence(ability) { it.originalAbility }.last()
            val occurrence =
                if (abilityGrpId != 0 && cardData != null && grantIndex != null) {
                    recipientRegistry?.grantedIds?.idFor(grantKeys[grantIndex])?.let {
                        AbilityLookup.Resolved(it, AbilityIdentitySource.GrantOrdinal)
                    }
                } else {
                    recipientRegistry
                        ?.takeIf { abilityGrpId != 0 }
                        ?.keywordSlotIndices
                        ?.get(original.definitionId)
                        ?.takeIf { cardData?.abilityIds?.getOrNull(it)?.first == abilityGrpId }
                        ?.let { AbilityLookup.Resolved(INITIAL_UNIQUE_ABILITY_ID + it, AbilityIdentitySource.KeywordSlot) }
                        ?: occurrenceFor(cardData, abilityGrpId, abilityGrpId == fallbackAbilityGrpId)
                } ?: AbilityLookup.Missing(if (abilityGrpId == 0) AbilityLookupMiss.NoRow else AbilityLookupMiss.NoOccurrence)
            recipientRegistry?.ledger?.record(AbilityIdentityLedger.Site.Activated, occurrence)
            val uniqueAbilityId = occurrence.valueOrNull()
            val grant =
                grantIndex?.let { index ->
                    val (_, timestamp, staticId) = grants[index]
                    val grantor = ability.grantorStatic?.hostCard
                    if (cardData == null || uniqueAbilityId == null || grantor == null) {
                        null
                    } else {
                        EffectProjectionFacts.GrantedAbilityEntry(
                            ForgeCardId(card.id),
                            timestamp,
                            staticId,
                            abilityGrpId,
                            uniqueAbilityId,
                            ForgeCardId(grantor.id),
                        )
                    }
                }
            return ActivatedIdentity(AbilityDefinitionRef.SpellAbility(original.definitionId), abilityGrpId, uniqueAbilityId, grant)
        }

        /** Current grant facts; effect allocation and retirement remain with EffectTracker. */
        fun grantedAbilityEntries(
            card: Card,
            cardDataLookup: (Card) -> CardData?,
            registryLookup: (Card, CardData?) -> AbilityRegistry?,
        ): List<EffectProjectionFacts.GrantedAbilityEntry> {
            val grants = traitGrants(card)
            if (grants.isEmpty()) registryLookup(card, cardDataLookup(card))?.grantedIds?.retain(emptySet())
            return grants.mapNotNull { (granted, _, _) -> resolveActivated(card, granted, cardDataLookup, registryLookup).grant }
        }

        /**
         * Activated abilities added to [card] by a continuous `AddAbility` effect or by a resolved Animate effect,
         * with the trait timestamp and static id that own each grant's lifetime.
         */
        private fun traitGrants(card: Card): List<Triple<SpellAbility, Long, Long>> =
            card.changedCardTraits.cellSet().flatMap { cell ->
                (cell.value as? CardTraitChanges)
                    ?.getAbilities()
                    .orEmpty()
                    .filter { it.isActivatedAbility && (it.grantorStatic != null || resolutionGrantState(it) != null) }
                    .map { Triple(it, cell.rowKey, cell.columnKey) }
            }

        /**
         * The defining card state of an ability that a resolved effect added, such as Animate's `Abilities`.
         * Forge rebinds the host to the recipient but keeps the state whose variables define the ability.
         */
        private fun resolutionGrantState(ability: SpellAbility): CardState? =
            ability.cardState?.takeIf {
                !ability.isIntrinsic && ability.originalAbility == null && ability.grantorStatic == null && ability.keyword == null
            }

        /** Resolution-granted ability variables defined on [state], in the name order the catalog publishes them. */
        private fun resolutionGrantVariables(state: CardState): List<String> {
            val svars = state.sVars
            return (state.intrinsicSpellAbilities.map { it.originalMapParams } + svars.values.map(AbilityFactory::getMapParams))
                .flatMap(::resolutionGrantedAbilityVariables)
                .filter { it in svars }
                .distinct()
                .sorted()
        }

        internal fun uniqueAbilityIdFor(
            cardData: CardData?,
            abilityGrpId: Int,
            fallbackWhenUnmapped: Boolean = false,
        ): Int? = occurrenceFor(cardData, abilityGrpId, fallbackWhenUnmapped)?.value

        private fun occurrenceFor(
            cardData: CardData?,
            abilityGrpId: Int,
            fallbackWhenUnmapped: Boolean,
        ): AbilityLookup.Resolved<Int>? {
            if (abilityGrpId == 0) return null
            if (cardData == null) return AbilityLookup.Resolved(INITIAL_UNIQUE_ABILITY_ID, AbilityIdentitySource.CallerFallback)
            val index = cardData.abilityIds.indexOfFirst { it.first == abilityGrpId }
            return when {
                index >= 0 -> AbilityLookup.Resolved(INITIAL_UNIQUE_ABILITY_ID + index, AbilityIdentitySource.PrintedSlot)
                fallbackWhenUnmapped -> AbilityLookup.Resolved(INITIAL_UNIQUE_ABILITY_ID, AbilityIdentitySource.CallerFallback)
                else -> null
            }
        }

        /** Empty registry — no mappings. */
        val EMPTY =
            AbilityRegistry(emptyMap(), emptyMap(), emptyMap(), emptyMap(), sourceCardGrpId = 0, slotLayout = SlotLayout.Companion.EMPTY)

        /**
         * Build a registry from a live Forge [card] and its [cardData].
         * Uses [cardData.abilityIds] verbatim as the slot layout.
         */
        fun build(
            card: Card,
            cardData: CardData,
            grantedKeywordAbilityGrpId: (SpellAbility) -> Int? = { null },
            grantedIds: GrantedAbilityIds = GrantedAbilityIds(),
            ledger: AbilityIdentityLedger? = null,
            rowKeywords: (Int) -> Set<String> = { emptySet() },
        ): AbilityRegistry {
            val abilityIds = cardData.abilityIds
            val builtSpellAbilityDefinitions = card.spellAbilities.mapTo(mutableSetOf()) { it.definitionId }
            val grantedKeywordIds =
                card.spellAbilities
                    .mapNotNull { ability ->
                        grantedKeywordAbilityGrpId(ability)?.let { ability.definitionId to it }
                    }.toMap()
            if (abilityIds.isEmpty()) {
                return AbilityRegistry(
                    grantedKeywordIds,
                    emptyMap(),
                    emptyMap(),
                    emptyMap(),
                    sourceCardGrpId = cardData.grpId,
                    grantedKeywordAbilityGrpId = grantedKeywordAbilityGrpId,
                    grantedIds = grantedIds,
                    builtSpellAbilityDefinitions = builtSpellAbilityDefinitions,
                    ledger = ledger,
                )
            }

            val fallbackGrpId = abilityIds[0].first
            val saMap = mutableMapOf<Int, Int>()
            val staticMap = mutableMapOf<Int, Int>()
            val triggerMap = mutableMapOf<Int, Int>()
            val keywordFamilies = mutableMapOf<AbilityDefinitionRef, AbilityKeywordFamily>()
            val keywordSlotIndices = mutableMapOf<Int, Int>()

            val keywordRows =
                if (card.type.isDungeon) {
                    check(card.triggers.size == abilityIds.size) { "Dungeon room definitions must match catalog rows" }
                    card.triggers.zip(abilityIds).forEach { (trigger, row) -> triggerMap[trigger.definitionId] = row.first }
                    KeywordRows(0, emptySet())
                } else {
                    mapKeywords(card, abilityIds, rowKeywords, saMap, staticMap, triggerMap, keywordFamilies, keywordSlotIndices)
                }
            val keywordCount = keywordRows.declarations
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
            mapUnclaimedIntrinsicTriggers(card, cardData, abilityIds, keywordRows.slots, triggerMap)
            mapUnclaimedIntrinsicStatics(card, cardData, abilityIds, keywordRows.slots, staticMap)
            mapUnclaimedLegacyStatics(card, cardData, abilityIds, keywordCount, staticMap)
            mapUnclaimedIntrinsics(card, fallbackGrpId, staticMap, triggerMap)
            saMap.putAll(grantedKeywordIds)

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

            return AbilityRegistry(
                saMap,
                staticMap,
                triggerMap,
                keywordFamilies,
                cardData.hiddenAbilityIds,
                cardData.grpId,
                layout,
                grantedKeywordAbilityGrpId,
                keywordSlotIndices,
                grantedIds,
                builtSpellAbilityDefinitions,
                ledger,
            )
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
                staticMap[staticAbility.definitionId] = grpId
            }
        }

        /**
         * Phase 1: map each declared intrinsic keyword to the catalog row that names the same keyword, in
         * declaration order among equal keywords. A keyword the catalog names no row for takes its declaration's
         * position, when that row names no keyword. Returns the declaration count.
         */
        @Suppress("LongParameterList")
        private fun mapKeywords(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            rowKeywords: (Int) -> Set<String>,
            saMap: MutableMap<Int, Int>,
            staticMap: MutableMap<Int, Int>,
            triggerMap: MutableMap<Int, Int>,
            keywordFamilies: MutableMap<AbilityDefinitionRef, AbilityKeywordFamily>,
            keywordSlotIndices: MutableMap<Int, Int>,
        ): KeywordRows {
            val declarationCards =
                listOfNotNull(
                    card,
                    card.copiedPermanent,
                    card.cloneStates
                        .maxByOrNull { it.key }
                        ?.value
                        ?.origin,
                )
            val declaredFace =
                declarationCards
                    .asSequence()
                    .mapNotNull { it.rules }
                    .flatMap { it.allFaces.asSequence() }
                    .firstOrNull { it.name == card.currentState.name }
                    ?: card.rules?.mainPart
            val keywordStrings = declaredFace?.keywords?.toList().orEmpty()
            val bindings = bindKeywordDeclarations(keywordStrings.take(abilityIds.size), card.getKeywords().orEmpty())
            val slots = keywordSlots(bindings, abilityIds, rowKeywords)
            val declarationCount = keywordStrings.size

            for ((declaration, kw) in bindings) {
                val slotIdx = slots[declaration] ?: continue
                val grpId = abilityIds[slotIdx].first
                val family = keywordFamily(kw)
                for (sa in kw.abilities) {
                    saMap[sa.definitionId] = grpId
                    keywordSlotIndices[sa.definitionId] = slotIdx
                    family?.let { keywordFamilies[AbilityDefinitionRef.SpellAbility(sa.definitionId)] = it }
                }
                for (trig in kw.triggers) {
                    triggerMap[trig.definitionId] = grpId
                    family?.let { keywordFamilies[AbilityDefinitionRef.Trigger(trig.definitionId)] = it }
                }
                for (st in kw.staticAbilities) {
                    staticMap[st.definitionId] = grpId
                    family?.let { keywordFamilies[AbilityDefinitionRef.StaticAbility(st.definitionId)] = it }
                }
            }
            // Declarations without a bound row keep their position, as when keywords lead the catalog.
            val claimed = (0 until minOf(declarationCount, abilityIds.size)).mapTo(mutableSetOf()) { slots[it] ?: it }
            return KeywordRows(declarationCount, claimed)
        }

        /** Declared keyword count, and the catalog rows those declarations occupy. */
        private data class KeywordRows(
            val declarations: Int,
            val slots: Set<Int>,
        )

        /** Catalog row index for each bound declaration: by keyword definition first, then by unclaimed position. */
        private fun keywordSlots(
            bindings: Map<Int, KeywordInterface>,
            abilityIds: List<Pair<Int, Int>>,
            rowKeywords: (Int) -> Set<String>,
        ): Map<Int, Int> {
            val named = abilityIds.map { rowKeywords(it.first) }
            val slots = mutableMapOf<Int, Int>()
            for ((declaration, keyword) in bindings.toSortedMap()) {
                val name = normalizedKeyword(keyword.keyword.toString())
                abilityIds.indices
                    .firstOrNull { it !in slots.values && name in named[it] }
                    ?.let { slots[declaration] = it }
            }
            for (declaration in bindings.keys.sorted()) {
                if (declaration in slots || declaration in slots.values || named[declaration].isNotEmpty()) continue
                slots[declaration] = declaration
            }
            return slots
        }

        private fun bindKeywordDeclarations(
            declarations: List<String>,
            liveKeywords: Iterable<KeywordInterface>,
        ): Map<Int, KeywordInterface> {
            val remaining = liveKeywords.filter { it.isIntrinsic }.toMutableList()
            val bindings = mutableMapOf<Int, KeywordInterface>()
            // Identical declarations pair by occurrence order before any normalized family matching.
            for ((text, slots) in declarations.withIndex().groupBy { it.value.lowercase() }) {
                val exact = remaining.filter { it.original.equals(text, ignoreCase = true) }
                for ((slot, keyword) in slots.zip(exact)) {
                    bindings[slot.index] = keyword
                    remaining.remove(keyword)
                }
            }
            // Calculated keywords may change their original text; only an unambiguous remaining pair can bind.
            for ((slot, text) in declarations.withIndex()) {
                if (slot in bindings) continue
                val keyword = remaining.singleOrNull { matchesKeywordText(it, text) } ?: continue
                val matchingSlots = declarations.indices.filter { it !in bindings && matchesKeywordText(keyword, declarations[it]) }
                if (matchingSlots.size != 1) continue
                bindings[slot] = keyword
                remaining.remove(keyword)
            }
            return bindings
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
            val candidates = activationCandidates(card)
            // Some catalogs classify a mana-producing chain by its non-mana root.
            // Use those activated rows only when the complete candidate layout agrees.
            val includesDeferredMana =
                activatedSlotIndices.size ==
                    candidates.count {
                        it.isActivatedAbility && !isReconfigureUnattach(it) && (!it.isManaAbility() || it.manaPart == null)
                    }
            var idx = 0
            for (sa in candidates) {
                if (!sa.isActivatedAbility || (sa.isManaAbility() && !(includesDeferredMana && sa.manaPart == null))) continue
                if (isReconfigureUnattach(sa)) {
                    saMap[sa.definitionId] = KeywordAbilityIds.RECONFIGURE_UNATTACH
                    continue
                }
                if (idx >= activatedSlotIndices.size) {
                    idx++
                    continue
                }
                val slotIdx = activatedSlotIndices[idx]
                if (slotIdx < abilityIds.size) saMap[sa.definitionId] = abilityIds[slotIdx].first
                idx++
            }
        }

        private fun activationCandidates(card: Card): List<SpellAbility> {
            val abilities = card.spellAbilities.toMutableList()
            val seen = abilities.map { it.definitionId }.toMutableSet()
            for (sa in card.allSpellAbilities.orEmpty()) {
                if (sa.definitionId in seen) continue
                if (sa.isActivatedAbility && (!sa.isManaAbility() || sa.manaPart == null)) {
                    abilities.add(sa)
                    seen.add(sa.definitionId)
                }
            }
            return abilities
        }

        private fun mapReconfigureUnattachAbilities(
            card: Card,
            saMap: MutableMap<Int, Int>,
        ) {
            for (sa in card.allSpellAbilities.orEmpty()) {
                if (isReconfigureUnattach(sa)) saMap[sa.definitionId] = KeywordAbilityIds.RECONFIGURE_UNATTACH
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
            for (sa in card.manaAbilities ?: emptyList()) {
                if (!sa.isManaAbility() || !sa.isIntrinsic) continue
                if (sa.manaPart == null && sa.definitionId in saMap) continue
                val generatedGrpId = BasicLandAbilities.byTypeDerivedManaAbility(card, sa)
                if (generatedGrpId != null) {
                    saMap.putIfAbsent(sa.definitionId, generatedGrpId)
                    continue
                }
                val slotIdx = manaSlotIndices.getOrNull(idx)
                val grpId = if (slotIdx != null && slotIdx < abilityIds.size) abilityIds[slotIdx].first else fallbackGrpId
                saMap.putIfAbsent(sa.definitionId, grpId)
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
            keywordSlots: Set<Int>,
            triggerMap: MutableMap<Int, Int>,
        ) {
            if (cardData.abilityKinds.size != abilityIds.size) return
            val triggers =
                card.triggers
                    ?.filter { it.isIntrinsic && it.definitionId !in triggerMap }
                    .orEmpty()
            if (triggers.isEmpty()) return
            val intrinsicSlots =
                abilityIds.indices.filter { idx ->
                    idx !in keywordSlots &&
                        if (cardData.abilityCategories.size == abilityIds.size) {
                            cardData.abilityCategories[idx] == TRIGGER_CATEGORY
                        } else {
                            cardData.abilityKinds[idx] == SlotKind.Intrinsic
                        }
                }
            if (intrinsicSlots.size != triggers.size) return
            for ((trig, slotIdx) in triggers.zip(intrinsicSlots)) {
                triggerMap[trig.definitionId] = abilityIds[slotIdx].first
            }
        }

        /** Map card-specific static abilities when Arena's remaining
         *  intrinsic slots line up exactly with Forge's unclaimed statics. */
        private fun mapUnclaimedIntrinsicStatics(
            card: Card,
            cardData: CardData,
            abilityIds: List<Pair<Int, Int>>,
            keywordSlots: Set<Int>,
            staticMap: MutableMap<Int, Int>,
        ) {
            if (cardData.abilityKinds.size != abilityIds.size) return
            val statics =
                card.staticAbilities
                    ?.filter { it.definitionId !in staticMap }
                    .orEmpty()
            if (statics.isEmpty()) return
            val intrinsicSlots =
                abilityIds.indices.filter { idx ->
                    idx !in keywordSlots &&
                        if (cardData.abilityCategories.size == abilityIds.size) {
                            cardData.abilityCategories[idx] >= STATIC_CATEGORY_FLOOR
                        } else {
                            cardData.abilityKinds[idx] == SlotKind.Intrinsic
                        }
                }
            if (intrinsicSlots.size != statics.size) return
            for ((staticAbility, slotIdx) in statics.zip(intrinsicSlots)) {
                staticMap[staticAbility.definitionId] = abilityIds[slotIdx].first
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
                    ?.filter { it.definitionId !in staticMap }
                    .orEmpty()
            if (statics.isEmpty()) return
            val activatedCount =
                card.spellAbilities
                    ?.count { it.isActivatedAbility && it.isIntrinsic && !it.isManaAbility() } ?: 0
            val staticSlots = (keywordCount until abilityIds.size).drop(activatedCount)
            if (staticSlots.size != statics.size) return
            for ((staticAbility, slotIdx) in statics.zip(staticSlots)) {
                staticMap[staticAbility.definitionId] = abilityIds[slotIdx].first
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
                staticMap.putIfAbsent(st.definitionId, fallbackGrpId)
            }
            for (trig in card.triggers ?: emptyList()) {
                if (!trig.isIntrinsic) continue
                triggerMap.putIfAbsent(trig.definitionId, fallbackGrpId)
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

        private fun keywordFamily(keyword: KeywordInterface): AbilityKeywordFamily? = KEYWORD_FAMILIES[keyword.keyword]

        private fun reconfigureUnattachSlot(card: Card): List<SlotEntry> =
            if (card.allSpellAbilities.orEmpty().any { isReconfigureUnattach(it) }) {
                listOf(SlotEntry(KeywordAbilityIds.RECONFIGURE_UNATTACH, 0, SlotKind.Activated))
            } else {
                emptyList()
            }

        private fun isReconfigureUnattach(sa: SpellAbility): Boolean =
            sa.api == ApiType.Unattach && sa.getParam("PrecostDesc") == "Reconfigure"

        private const val STATION_THRESHOLD_ABILITY_ID_FLOOR = 60_000
        internal const val TRIGGER_CATEGORY = 2
        private const val STATIC_CATEGORY_FLOOR = 3

        private val KEYWORD_FAMILIES =
            mapOf(
                Keyword.BACKUP to AbilityKeywordFamily.Backup,
                Keyword.MENTOR to AbilityKeywordFamily.Mentor,
            )
    }
}
