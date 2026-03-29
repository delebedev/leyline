package leyline.game

import forge.game.ability.AbilityUtils
import forge.game.card.Card
import forge.game.card.CardUtil
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
        /** Per-player entries: all permanent iids with this ability word for that player. */
        val affectedIds: List<Int> = emptyList(),
    )

    private data class ConditionSpec(
        val threshold: Int? = null,
        /** Value lambda. Second param is any battlefield card (Forge src arg). */
        val value: ((Player, Card) -> Int)? = null,
        /** When true: emit one pAnn per player (affectorId=seatId), not per permanent. */
        val perPlayer: Boolean = false,
        /** When true: suppress value/threshold from pAnn details (boolean-only wire shape). */
        val booleanOnly: Boolean = false,
    )

    /** Maps Forge Condition$ name → Arena wire shape. Key doubles as AbilityWordName. */
    private val CONDITIONS = mapOf(
        "Threshold" to ConditionSpec(threshold = 7, value = { p, _ -> p.getZone(ZoneType.Graveyard).size() }),
        "Metalcraft" to ConditionSpec(threshold = 3, value = { p, _ -> p.getCardsIn(ZoneType.Battlefield).toList().count { it.isArtifact } }),
        "Delirium" to ConditionSpec(threshold = 4, value = { p, _ -> AbilityUtils.countCardTypesFromList(p.getCardsIn(ZoneType.Graveyard), false) }),
        "Ferocious" to ConditionSpec(),
        "Hellbent" to ConditionSpec(),
        "Desert" to ConditionSpec(),
        "Blessing" to ConditionSpec(),
        "Morbid" to ConditionSpec(
            perPlayer = true,
            booleanOnly = true,
            value = { p, src ->
                CardUtil.getThisTurnEntered(ZoneType.Graveyard, ZoneType.Battlefield, "Creature", src, null, p).size
            },
        ),
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
        // Per-player accumulation: conditionName → (seatIdx → list of iids)
        val perPlayerCards = mutableMapOf<String, MutableMap<Int, MutableList<Int>>>()
        // Track a representative card per player for Forge src arg
        val perPlayerRepCard = mutableMapOf<String, MutableMap<Int, Card>>()

        for (card in battlefieldCards) {
            val controller = card.controller ?: continue
            val iid = instanceIdResolver(ForgeCardId(card.id)).value
            val registry = registryResolver(card)
            val game = controller.game
            val seatIdx = game.registeredPlayers.indexOf(controller) + 1

            // Phase 1: StaticAbility with Condition$ param
            for (sa in card.staticAbilities ?: emptyList()) {
                val condition = sa.getParam("Condition") ?: continue
                val spec = CONDITIONS[condition] ?: continue
                if (!seen.add(card.id to condition)) continue

                if (spec.perPlayer) {
                    perPlayerCards.getOrPut(condition) { mutableMapOf() }
                        .getOrPut(seatIdx) { mutableListOf() }.add(iid)
                    perPlayerRepCard.getOrPut(condition) { mutableMapOf() }
                        .putIfAbsent(seatIdx, card)
                } else {
                    results.add(
                        AbilityWordEntry(
                            instanceId = iid,
                            abilityWordName = condition,
                            value = spec.value?.invoke(controller, card),
                            threshold = spec.threshold,
                            abilityGrpId = registry?.forStaticAbility(sa.id)?.takeIf { it > 0 },
                        ),
                    )
                }
            }

            // Phase 2: Triggers with named params (Threshold$ True) or CheckSVar$ <ConditionName>
            for (trigger in card.triggers ?: emptyList()) {
                val matchedConditions = mutableSetOf<String>()
                for (paramName in NAMED_PARAM_CONDITIONS) {
                    if (trigger.hasParam(paramName)) matchedConditions.add(paramName)
                }
                // Also detect CheckSVar$ pattern (e.g. CheckSVar$ Morbid)
                val checkSVar = trigger.getParam("CheckSVar")
                if (checkSVar != null && checkSVar in CONDITIONS) {
                    matchedConditions.add(checkSVar)
                }

                for (conditionName in matchedConditions) {
                    if (!seen.add(card.id to conditionName)) continue
                    val spec = CONDITIONS[conditionName] ?: continue

                    if (spec.perPlayer) {
                        perPlayerCards.getOrPut(conditionName) { mutableMapOf() }
                            .getOrPut(seatIdx) { mutableListOf() }.add(iid)
                        perPlayerRepCard.getOrPut(conditionName) { mutableMapOf() }
                            .putIfAbsent(seatIdx, card)
                    } else {
                        results.add(
                            AbilityWordEntry(
                                instanceId = iid,
                                abilityWordName = conditionName,
                                value = spec.value?.invoke(controller, card),
                                threshold = spec.threshold,
                                abilityGrpId = registry?.forTrigger(trigger.id)?.takeIf { it > 0 },
                            ),
                        )
                    }
                }
            }
        }

        // Phase 3: emit one pAnn per player for perPlayer conditions (only when condition is true)
        for ((conditionName, bySeat) in perPlayerCards) {
            val spec = CONDITIONS[conditionName] ?: continue
            for ((seatIdx, iids) in bySeat) {
                val repCard = perPlayerRepCard[conditionName]?.get(seatIdx) ?: continue
                val controller = repCard.controller ?: continue
                val conditionValue = spec.value?.invoke(controller, repCard) ?: 1
                if (conditionValue <= 0) continue // condition not met — omit pAnn

                results.add(
                    AbilityWordEntry(
                        instanceId = seatIdx, // stable key for PersistentAnnotationStore upsert
                        abilityWordName = conditionName,
                        value = if (spec.booleanOnly) null else conditionValue,
                        threshold = if (spec.booleanOnly) null else spec.threshold,
                        affectorId = seatIdx,
                        affectedIds = iids,
                    ),
                )
            }
        }

        return results
    }
}
