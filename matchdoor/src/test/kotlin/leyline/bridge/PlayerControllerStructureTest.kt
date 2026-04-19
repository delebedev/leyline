package leyline.bridge

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

        // The current set of 42 PCHuman overrides. Alphabetical for review stability.
        val expectedOverrides = setOf(
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

        test("override count is pinned at 42") {
            expectedOverrides.size shouldBe 42
        }

        test("PlayerController declares exactly the expected overrides") {
            val clazz = PlayerController::class.java
            // Deduplicate by (name, parameterTypes) — not by name alone — so that an
            // accidental overload addition (two methods with the same name and different
            // signatures) cannot pass silently.
            val overridingMethods = clazz.declaredMethods
                .asSequence()
                .filter { !it.isSynthetic }
                .filter { !java.lang.reflect.Modifier.isPrivate(it.modifiers) }
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isDeclaredInAnyAncestor(clazz.superclass) }
                .distinctBy { m -> m.name to m.parameterTypes.toList() }
                .toList()

            overridingMethods.map { it.name }.toSet() shouldBe expectedOverrides
            overridingMethods.size shouldBe expectedOverrides.size
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
