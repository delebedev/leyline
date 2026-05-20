package leyline.bridge.forge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.forge.PlayerController

/**
 * Pins the [leyline.bridge.forge.PlayerController] override surface.
 *
 * Forge dispatches through single inheritance — the class must keep hosting every
 * override. Any addition or removal is a spec change that must update this test and
 * the override table in `matchdoor/CLAUDE.md` in the same commit.
 *
 * See [leyline.bridge.forge.PlayerController]'s KDoc for the pattern this guardrail supports.
 */
class PlayerControllerStructureTest :
    FunSpec({

        tags(UnitTag)

        // The current set of 44 PCHuman overrides. Alphabetical for review stability.
        val expectedOverrides =
            setOf(
                "announceRequirements",
                "applyManaToCost",
                "arrangeForScry",
                "arrangeForSurveil",
                "assignCombatDamage",
                "chooseBinary",
                "chooseCardsForConvokeOrImprovise",
                "chooseCardsForCost",
                "chooseCardsForEffect",
                "chooseCardsToDiscardFrom",
                "chooseCardsToDiscardToMaximumHandSize",
                "chooseCardsToDiscardUnlessType",
                "chooseCardsToRevealFromHand",
                "chooseColor",
                "chooseEntitiesForEffect",
                "chooseModeForAbility",
                "chooseNumber",
                "chooseNumberForKeywordCost",
                "chooseOptionalCosts",
                "choosePermanentsToDestroy",
                "choosePermanentsToSacrifice",
                "chooseSingleEntityForEffect",
                "chooseSpellAbilityToPlay",
                "chooseStartingPlayer",
                "confirmAction",
                "confirmPayment",
                "confirmReplacementEffect",
                "confirmStaticApplication",
                "confirmTrigger",
                "declareAttackers",
                "declareBlockers",
                "getCostDecisionMaker",
                "isAI",
                "mulliganKeepHand",
                "orderMoveToZoneList",
                "payCostToPreventEffect",
                "payManaCost",
                "playChosenSpellAbility",
                "playSaFromPlayEffect",
                "playSpellAbilityNoStack",
                "reveal",
                "selectTargetsInteractively",
                "tuckCardsViaMulligan",
                "willPutCardOnTop",
            )

        test("override count is pinned at 44") {
            expectedOverrides.size shouldBe 44
        }

        test("PlayerController declares exactly the expected overrides") {
            val clazz = PlayerController::class.java
            // Deduplicate by (name, parameterTypes) — not by name alone — so that an
            // accidental overload addition (two methods with the same name and different
            // signatures) cannot pass silently.
            val overridingMethods =
                clazz.declaredMethods
                    .asSequence()
                    .filter { !it.isSynthetic }
                    .filter {
                        !java.lang.reflect.Modifier
                            .isPrivate(it.modifiers)
                    }.filter {
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                    }.filter { it.isDeclaredInAnyAncestor(clazz.superclass) }
                    .distinctBy { m -> m.name to m.parameterTypes.toList() }
                    .toList()

            overridingMethods.map { it.name }.toSet() shouldBe expectedOverrides
            // chooseNumber has three overloads (range, range+params, list-of-values),
            // and chooseCardsToDiscardFrom has two overloads (with/without visible cards),
            // so the (name, paramTypes) count exceeds the unique-name count by 3.
            overridingMethods.size shouldBe expectedOverrides.size + 3
        }
    })

private fun java.lang.reflect.Method.isDeclaredInAnyAncestor(start: Class<*>?): Boolean {
    var cls: Class<*>? = start
    while (cls != null) {
        if (cls.declaredMethods.any { it.name == name && it.parameterTypes.contentEquals(parameterTypes) }) {
            return true
        }
        cls = cls.superclass
    }
    return false
}
