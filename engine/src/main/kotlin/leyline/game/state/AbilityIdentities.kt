package leyline.game.state

import forge.game.card.Card
import forge.game.spellability.SpellAbility
import forge.game.trigger.Trigger
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.AbilityIdentitySource
import leyline.bridge.types.AbilityLookup
import leyline.bridge.types.AbilityLookupMiss
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.SelectedAdditionalCost
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.catalogKeywords
import leyline.game.data.grantedKeywordAbilityGrpId
import leyline.game.data.lookupGrantedKeywordAbility
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns one match's ability identity state: per-card registries, granted-ability ordinals, the choices recorded
 * between a cast or trigger and its projection, and the [ledger] of lookup outcomes.
 *
 * Every lookup returns an [AbilityLookup] naming the source that answered or the reason none did, and records it
 * in the ledger. The nullable accessors keep the callers' existing shape.
 */
class AbilityIdentities(
    private val cardRepository: CardRepository,
    private val cardData: (Card) -> CardData?,
) {
    val ledger = AbilityIdentityLedger(RowAgreement(cardRepository))

    private val registries = ConcurrentHashMap<Int, AbilityRegistry>()
    private val grantedAbilityIds = ConcurrentHashMap<Int, GrantedAbilityIds>()

    private val selectedSpellGrpIds = ConcurrentHashMap<ForgeCardId, Int>()
    private val selectedAdditionalCostGrpIds = ConcurrentHashMap<ForgeCardId, Int>()
    private val selectedAdditionalCosts = ConcurrentHashMap<ForgeCardId, SelectedAdditionalCost>()
    private val stackIdentitiesByRuntimeId = ConcurrentHashMap<Int, ResolvedAbilityIdentity>()

    /** Chosen mode row keyed by the Forge id of the modal invocation's root ability. */
    private val selectedModalAbilityGrpIds = ConcurrentHashMap<Int, Int>()
    private val pendingTriggerAbilityGrpIds = ConcurrentHashMap<Int, Int>()
    private val pendingTriggerCleanupGrpIds = ConcurrentHashMap<Int, Int>()

    // --- Registries ---

    /**
     * Look up or build the [AbilityRegistry] for a Forge card. Rebuilt when Forge has regenerated the card's
     * spell ability instances, which invalidates definition-id rows.
     */
    fun registryFor(
        card: Card,
        data: CardData?,
    ): AbilityRegistry? {
        if (data == null) return null
        return registries.compute(card.id) { _, cached ->
            cached?.takeIf { it.sourceCardGrpId == data.grpId && it.matchesLiveSpellAbilities(card) }
                ?: buildRegistry(card, data)
        }
    }

    fun cachedRegistryCardIds(): Set<ForgeCardId> = registries.keys.mapTo(linkedSetOf(), ::ForgeCardId)

    fun grantedAbilityIdentityCardIds(): Set<ForgeCardId> = grantedAbilityIds.keys.mapTo(linkedSetOf(), ::ForgeCardId)

    fun evictRegistry(forgeCardId: Int) {
        registries.remove(forgeCardId)
    }

    /** Forget the card's granted ordinals when it leaves its zone. */
    fun retireGrantedAbilityIds(forgeCardId: Int) {
        grantedAbilityIds[forgeCardId]?.retain(emptySet())
    }

    fun clearRegistries() {
        registries.clear()
    }

    /** Registry and granted-ordinal state; cleared with the game. */
    fun clearCatalogState() {
        registries.clear()
        grantedAbilityIds.clear()
    }

    /** Recorded choices and pending trigger rows; cleared between puzzle games. */
    fun clearChoices() {
        selectedModalAbilityGrpIds.clear()
        pendingTriggerAbilityGrpIds.clear()
        pendingTriggerCleanupGrpIds.clear()
        stackIdentitiesByRuntimeId.clear()
        selectedSpellGrpIds.clear()
        selectedAdditionalCostGrpIds.clear()
        selectedAdditionalCosts.clear()
    }

    private fun buildRegistry(
        card: Card,
        data: CardData,
    ): AbilityRegistry =
        AbilityRegistry.build(
            card,
            data,
            cardRepository::grantedKeywordAbilityGrpId,
            grantedAbilityIds.computeIfAbsent(card.id) { GrantedAbilityIds() },
            ledger,
            cardRepository::catalogKeywords,
        )

    // --- Lookups ---

    /** Identity of [ability] on [card]: a spell or activated ability through the registry, or a trigger through the trigger chain. */
    fun lookup(
        card: Card,
        ability: SpellAbility,
    ): AbilityLookup<ResolvedAbilityIdentity> {
        val trigger = ability.trigger ?: return AbilityRegistry.lookupSpellAbility(ability, cardData, ::registryFor)
        val lookup = ledger.record(AbilityIdentityLedger.Site.Trigger, lookupTrigger(card, trigger))
        (lookup as? AbilityLookup.Resolved)?.let {
            ledger.checkRow(
                AbilityIdentityLedger.Site.Trigger,
                ability,
                it.value.abilityGrpId,
                it.via,
            )
        }
        return lookup
    }

    /**
     * Trigger chain: the registry, the pending trigger's cleanup row, the pending trigger's row, then the ability
     * that spawned a delayed trigger.
     */
    private fun lookupTrigger(
        card: Card,
        trigger: Trigger,
    ): AbilityLookup<ResolvedAbilityIdentity> {
        val definition = AbilityDefinitionRef.Trigger(trigger.definitionId)
        val data = cardData(card) ?: return AbilityLookup.Missing(AbilityLookupMiss.NoCardData)
        val registry = registryFor(card, data) ?: return AbilityLookup.Missing(AbilityLookupMiss.NoCardData)
        registry.resolve(definition)?.let { return AbilityLookup.Resolved(it, AbilityIdentitySource.Registry) }
        val row =
            pendingTriggerCleanupGrpIds[trigger.id]?.let { AbilityLookup.Resolved(it, AbilityIdentitySource.PendingTriggerCleanup) }
                ?: pendingTriggerAbilityGrpIds[trigger.id]?.let { AbilityLookup.Resolved(it, AbilityIdentitySource.PendingTrigger) }
                ?: spawningTriggerRow(trigger)
                ?: return AbilityLookup.Missing(AbilityLookupMiss.NoRow)
        return AbilityLookup.Resolved(ResolvedAbilityIdentity(definition, row.value), row.via)
    }

    private fun spawningTriggerRow(trigger: Trigger): AbilityLookup.Resolved<Int>? {
        val spawningAbility = trigger.spawningAbility ?: return null
        val sourceCard = spawningAbility.hostCard?.effectSource ?: spawningAbility.hostCard ?: return null
        return lookup(sourceCard, spawningAbility.rootAbility).valueOrNull()?.let {
            AbilityLookup.Resolved(it.abilityGrpId, AbilityIdentitySource.SpawningAbility)
        }
    }

    /** Row of the ability that created [trigger], for delayed triggers. */
    fun spawningTriggerAbilityGrpId(trigger: Trigger): Int? =
        ledger
            .record(
                AbilityIdentityLedger.Site.SpawningTrigger,
                spawningTriggerRow(trigger) ?: AbilityLookup.Missing(AbilityLookupMiss.NoRow),
            ).valueOrNull()

    /** Identity of a bare definition reference on [card], through its registry and then a rebuilt one. */
    fun lookup(
        card: Card,
        definition: AbilityDefinitionRef,
    ): AbilityLookup<ResolvedAbilityIdentity> {
        val lookup =
            run {
                val data = cardData(card) ?: return@run AbilityLookup.Missing(AbilityLookupMiss.NoCardData)
                val registry = registryFor(card, data) ?: return@run AbilityLookup.Missing(AbilityLookupMiss.NoCardData)
                registry.resolve(definition)?.let { AbilityLookup.Resolved(it, AbilityIdentitySource.Registry) }
                    ?: AbilityLookup.Missing(AbilityLookupMiss.NoRow)
            }
        return ledger.record(AbilityIdentityLedger.Site.Definition, lookup)
    }

    /** Identity a prompt shows for [ability]: the mode chosen for its invocation, or the ability's own identity. */
    fun lookupPrompt(
        card: Card,
        ability: SpellAbility,
    ): AbilityLookup<ResolvedAbilityIdentity> {
        val own = lookup(card, ability)
        val modalGrpId = selectedModalAbilityGrpIds[ability.rootAbility.id] ?: return ledger.record(AbilityIdentityLedger.Site.Prompt, own)
        val definition =
            ability.trigger?.let { AbilityDefinitionRef.Trigger(it.definitionId) }
                ?: AbilityDefinitionRef.SpellAbility(ability.definitionId)
        val identity = own.valueOrNull()?.copy(abilityGrpId = modalGrpId) ?: ResolvedAbilityIdentity(definition, modalGrpId)
        return ledger.record(AbilityIdentityLedger.Site.Prompt, AbilityLookup.Resolved(identity, AbilityIdentitySource.ModalChoice))
    }

    /** The granting card's row for a keyword a static granted to [ability]'s host, counted at [site]. */
    fun grantedKeywordAbilityGrpId(
        ability: SpellAbility,
        site: AbilityIdentityLedger.Site = AbilityIdentityLedger.Site.GrantedKeyword,
    ): Int? =
        cardRepository
            .lookupGrantedKeywordAbility(ability)
            ?.let { ledger.record(site, it) }
            ?.valueOrNull()

    // --- Stack identities ---

    fun recordStackIdentity(
        runtimeAbilityId: Int,
        identity: ResolvedAbilityIdentity,
    ) {
        if (runtimeAbilityId != 0) stackIdentitiesByRuntimeId[runtimeAbilityId] = identity
    }

    fun stackIdentity(runtimeAbilityId: Int): ResolvedAbilityIdentity? = stackIdentitiesByRuntimeId[runtimeAbilityId]

    fun consumeStackIdentity(runtimeAbilityId: Int): ResolvedAbilityIdentity? = stackIdentitiesByRuntimeId.remove(runtimeAbilityId)

    // --- Cast choices ---

    fun setSelectedSpellGrpId(
        cardId: ForgeCardId,
        grpId: Int?,
    ) {
        if (grpId == null) selectedSpellGrpIds.remove(cardId) else selectedSpellGrpIds[cardId] = grpId
    }

    fun consumeSelectedSpellGrpId(cardId: ForgeCardId): Int? = selectedSpellGrpIds.remove(cardId)

    fun setSelectedAdditionalCostGrpId(
        cardId: ForgeCardId,
        grpId: Int,
    ) {
        selectedAdditionalCostGrpIds[cardId] = grpId
    }

    fun consumeSelectedAdditionalCostGrpId(cardId: ForgeCardId): Int? = selectedAdditionalCostGrpIds.remove(cardId)

    fun setSelectedAdditionalCost(
        cardId: ForgeCardId,
        cost: SelectedAdditionalCost?,
    ) {
        if (cost == null) selectedAdditionalCosts.remove(cardId) else selectedAdditionalCosts[cardId] = cost
    }

    fun consumeSelectedAdditionalCost(cardId: ForgeCardId): SelectedAdditionalCost? = selectedAdditionalCosts.remove(cardId)

    // --- Pending triggers ---

    /** Record the mode row chosen for the modal invocation whose root ability has Forge id [rootAbilityId]. */
    fun recordSelectedModalAbilityGrpId(
        rootAbilityId: Int,
        abilityGrpId: Int,
    ) {
        selectedModalAbilityGrpIds[rootAbilityId] = abilityGrpId
    }

    /**
     * Row for a pending trigger: the one already bound to [triggerId], else the mode chosen for the spawning
     * invocation [spawningRootAbilityId], else [fallback]. The chosen mode binds to the first trigger that asks.
     */
    fun resolvePendingTriggerAbilityIdentity(
        triggerId: Int,
        spawningRootAbilityId: Int,
        fallback: () -> Int?,
    ): Int? =
        pendingTriggerAbilityGrpIds[triggerId]
            ?: (selectedModalAbilityGrpIds.remove(spawningRootAbilityId) ?: fallback())?.also { resolved ->
                pendingTriggerAbilityGrpIds[triggerId] = resolved
            }

    fun retainPendingTriggerAbilityIdentities(triggerIds: Set<Int>) {
        pendingTriggerAbilityGrpIds.keys.removeIf { it !in triggerIds }
        pendingTriggerCleanupGrpIds.keys.removeIf { it !in triggerIds }
    }

    fun recordPendingTriggerCleanupIdentity(
        triggerId: Int,
        cleanupAbilityGrpId: Int,
    ) {
        pendingTriggerCleanupGrpIds[triggerId] = cleanupAbilityGrpId
    }

    fun pendingTriggerCleanupAbilityGrpId(triggerId: Int): Int? = pendingTriggerCleanupGrpIds[triggerId]

    fun pendingTriggerAbilityGrpId(triggerId: Int): Int? = pendingTriggerAbilityGrpIds[triggerId]
}
