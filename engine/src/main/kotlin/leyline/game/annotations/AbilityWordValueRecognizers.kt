package leyline.game.annotations

import forge.game.CardTraitBase
import forge.game.ability.AbilityUtils
import forge.game.card.Card
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.state.AbilityRegistry

/**
 * Projects numeric `CheckSVar` comparisons whose live value is useful to the client.
 * Forge remains authoritative for both the count expression and comparison threshold.
 */
object AbilityWordValueRecognizers {
    private val COMPARISON_THRESHOLD = Regex("(?:GE|GT|LE|LT|EQ|NE)(\\d+)")

    private enum class TraitKind {
        STATIC,
        TRIGGER,
    }

    private data class ValueFamily(
        val abilityWordName: String,
        val matches: (TraitKind, CardTraitBase, String, String) -> Boolean,
    )

    private val families =
        listOf(
            ValueFamily("Devotion") { kind, trait, expression, comparator ->
                kind == TraitKind.STATIC &&
                    trait.getParam("Mode") == "Continuous" &&
                    trait.getParam("Affected") == "Card.Self" &&
                    expression.startsWith("Count\$Devotion.") &&
                    comparator == "LT5"
            },
            ValueFamily("Descend") { kind, trait, expression, comparator ->
                kind == TraitKind.TRIGGER &&
                    trait.getParam("Mode") == "Phase" &&
                    trait.getParam("Phase") == "Upkeep" &&
                    expression == "Count\$ValidGraveyard Permanent.YouOwn" &&
                    comparator == "GE8"
            },
            ValueFamily("NumberOfSpellsCast") { kind, trait, expression, comparator ->
                kind == TraitKind.TRIGGER &&
                    trait.getParam("Mode") == "Phase" &&
                    trait.getParam("Phase") == "BeginCombat" &&
                    expression == "Count\$ThisTurnCast_Card.YouCtrl" &&
                    comparator == "GE2"
            },
        )

    fun scan(
        battlefieldCards: List<Card>,
        instanceIdResolver: (ForgeCardId) -> InstanceId,
        registryResolver: (Card) -> AbilityRegistry?,
    ): List<AbilityWordScanner.AbilityWordEntry> {
        val results = mutableListOf<AbilityWordScanner.AbilityWordEntry>()
        for (card in battlefieldCards) {
            val iid = instanceIdResolver(ForgeCardId(card.id)).value
            val registry by lazy { registryResolver(card) }

            for (staticAbility in card.staticAbilities.orEmpty()) {
                val projection = projection(card, staticAbility, TraitKind.STATIC) ?: continue
                results.add(
                    projection.toEntry(
                        iid = iid,
                        abilityGrpId = registry?.forStaticAbility(staticAbility.id),
                    ),
                )
            }
            for (trigger in card.triggers.orEmpty()) {
                val projection = projection(card, trigger, TraitKind.TRIGGER) ?: continue
                results.add(
                    projection.toEntry(
                        iid = iid,
                        abilityGrpId = registry?.forTrigger(trigger.id),
                    ),
                )
            }
        }
        return results
    }

    private data class Projection(
        val abilityWordName: String,
        val value: Int,
        val threshold: Int,
    ) {
        fun toEntry(
            iid: Int,
            abilityGrpId: Int?,
        ) = AbilityWordScanner.AbilityWordEntry(
            instanceId = iid,
            abilityWordName = abilityWordName,
            value = value,
            threshold = threshold,
            abilityGrpId = abilityGrpId?.takeIf { it > 0 },
        )
    }

    private fun projection(
        card: Card,
        trait: CardTraitBase,
        kind: TraitKind,
    ): Projection? {
        val checkSVar = trait.getParam("CheckSVar") ?: return null
        val expression = trait.getSVar(checkSVar)
        val comparator = trait.getParamOrDefault("SVarCompare", "GE1")
        val family = families.firstOrNull { it.matches(kind, trait, expression, comparator) } ?: return null
        val threshold =
            COMPARISON_THRESHOLD
                .matchEntire(comparator)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        val value = AbilityUtils.calculateAmount(card, checkSVar, trait)
        return Projection(family.abilityWordName, value, threshold)
    }
}
