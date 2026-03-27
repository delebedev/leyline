package leyline.game

import forge.game.ability.AbilityUtils
import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId

/**
 * Scans battlefield permanents for ability word conditions and produces
 * [AbilityWordEntry] records for persistent annotation emission.
 *
 * Detection: checks Forge StaticAbility `Condition$` param and Trigger/SpellAbility
 * named params (`Threshold$`, `Morbid$`, etc.) from CardTraitBase.meetsCommonRequirements.
 *
 * Value computation: delegates to Player predicates (same as Forge's own condition checks).
 * AbilityGrpId resolution: delegates to AbilityRegistry.
 */
object AbilityWordScanner {

    /** One ability word annotation to emit. */
    data class AbilityWordEntry(
        val instanceId: Int,
        val abilityWordName: String,
        val value: Int? = null,
        val threshold: Int? = null,
        val abilityGrpId: Int? = null,
        /** Override affectorId (default = instanceId). */
        val affectorId: Int? = null,
    )

    private data class ConditionSpec(
        val abilityWordName: String,
        val threshold: Int? = null,
        val value: ((Player) -> Int)? = null,
    )

    /** Maps Forge Condition$ values → Arena wire shape. */
    private val CONDITIONS = mapOf(
        "Threshold" to ConditionSpec(
            "Threshold",
            threshold = 7,
            value = { p -> p.getZone(ZoneType.Graveyard).size() },
        ),
        "Metalcraft" to ConditionSpec(
            "Metalcraft",
            threshold = 3,
            value = { p -> p.getCardsIn(ZoneType.Battlefield).toList().count { it.isArtifact } },
        ),
        "Delirium" to ConditionSpec(
            "Delirium",
            threshold = 4,
            value = { p -> AbilityUtils.countCardTypesFromList(p.getCardsIn(ZoneType.Graveyard), false) },
        ),
        "Ferocious" to ConditionSpec("Ferocious"),
        "Hellbent" to ConditionSpec("Hellbent"),
        "Desert" to ConditionSpec("Desert"),
        "Blessing" to ConditionSpec("Blessing"),
    )

    /** Named params checked on Triggers (Threshold$ True, etc.) — derived from CONDITIONS. */
    private val NAMED_PARAM_CONDITIONS = CONDITIONS.keys

    /**
     * Scan battlefield permanents for ability word conditions.
     *
     * Scans all cards regardless of controller — each card's value is computed
     * relative to its controller's game state (GY count, artifact count, etc.).
     *
     * @param battlefieldCards cards currently on the battlefield (all players)
     * @param instanceIdResolver ForgeCardId → InstanceId
     * @param registryResolver Card → AbilityRegistry? (for abilityGrpId)
     */
    fun scan(
        battlefieldCards: List<Card>,
        instanceIdResolver: (ForgeCardId) -> InstanceId,
        registryResolver: (Card) -> AbilityRegistry?,
    ): List<AbilityWordEntry> {
        val results = mutableListOf<AbilityWordEntry>()
        val seen = mutableSetOf<Pair<Int, String>>() // (forgeCardId, conditionName) dedup

        for (card in battlefieldCards) {
            val controller = card.controller ?: continue
            val iid = instanceIdResolver(ForgeCardId(card.id)).value
            val registry = registryResolver(card)

            // Phase 1: StaticAbility with Condition$ param
            for (sa in card.staticAbilities ?: emptyList()) {
                val condition = sa.getParam("Condition") ?: continue
                val spec = CONDITIONS[condition] ?: continue
                if (!seen.add(card.id to condition)) continue

                results.add(
                    AbilityWordEntry(
                        instanceId = iid,
                        abilityWordName = spec.abilityWordName,
                        value = spec.value?.invoke(controller),
                        threshold = spec.threshold,
                        abilityGrpId = registry?.forStaticAbility(sa.id)?.takeIf { it > 0 },
                    ),
                )
            }

            // Phase 2: Triggers with named params (Threshold$ True, etc.)
            for (trigger in card.triggers ?: emptyList()) {
                for (paramName in NAMED_PARAM_CONDITIONS) {
                    if (!trigger.hasParam(paramName)) continue
                    if (!seen.add(card.id to paramName)) continue
                    val spec = CONDITIONS[paramName] ?: continue

                    results.add(
                        AbilityWordEntry(
                            instanceId = iid,
                            abilityWordName = spec.abilityWordName,
                            value = spec.value?.invoke(controller),
                            threshold = spec.threshold,
                            abilityGrpId = registry?.forTrigger(trigger.id)?.takeIf { it > 0 },
                        ),
                    )
                }
            }
        }

        return results
    }
}
