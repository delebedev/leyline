package leyline.conformance

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq

/**
 * Matchers for alt-cost Cast offers (Foretell, Plot, Warp, Sneak, Escape,
 * Disturb, Flashback, etc.). The alt-cost-stamp invariant — the keyword's
 * per-card ability grpId is stamped on every ManaRequirement — appears in
 * every keyword test. These matchers carry the failure-message detail that
 * inline `assertSoftly` blocks otherwise have to spell out field by field.
 *
 * ```
 * // Subject = Action? (the offer the test selected via .firstOrNull { ... }).
 * foretellOffer should beAltCostOffer(foretellAbilityGrpId)
 *
 * // Subject = ActionsAvailableReq. Searches actionsList AND inactiveActionsList
 * // for any Action where alternativeGrpId or abilityGrpId == altGrpId.
 * actions shouldNot offerAltCost(foretellAbilityGrpId)
 * ```
 *
 * The single-offer matcher asserts the universal stamp:
 *   1. The offer is non-null.
 *   2. `manaCostCount > 0` (the offer carries a mana cost — empty mana list is
 *      always wrong for hand/exile/graveyard cast offers).
 *   3. Every ManaRequirement's `abilityGrpId == altGrpId`.
 *
 * It does NOT check rail-specific fields (`abilityGrpId == 0` for the
 * Alternative rail, `grpId == 0 && facetId == 0` for Escape's minimal-emit
 * shape). Tests that want those add them inline alongside the matcher — they
 * vary by keyword and aren't part of the universal invariant.
 *
 * **Subject is `Action?` on purpose.** The selector at the call site is
 * `castOffers.firstOrNull { it.alternativeGrpId == X }`, which can return
 * null. The matcher folds the null check into the failure message ("expected
 * an alt-cost offer carrying grpId=X, got null") so the test doesn't need a
 * separate `shouldNotBe null` ahead of it.
 */

fun beAltCostOffer(altGrpId: Int): Matcher<Action?> =
    Matcher { offer ->
        val passed =
            offer != null &&
                offer.manaCostCount > 0 &&
                offer.manaCostList.all { it.abilityGrpId == altGrpId }
        val explanation =
            when {
                offer == null ->
                    "expected an alt-cost offer carrying grpId=$altGrpId, got null"
                offer.manaCostCount == 0 ->
                    "alt-cost offer for grpId=$altGrpId has manaCostCount=0 (must carry a mana cost)"
                !offer.manaCostList.all { it.abilityGrpId == altGrpId } ->
                    "alt-cost offer manaCostList must all carry abilityGrpId=$altGrpId; " +
                        "saw ${offer.manaCostList.map { it.abilityGrpId }}"
                else -> "alt-cost offer matches grpId=$altGrpId"
            }
        MatcherResult(
            passed,
            { explanation },
            { "alt-cost offer should NOT match grpId=$altGrpId, but did" },
        )
    }

fun offerAltCost(altGrpId: Int): Matcher<ActionsAvailableReq> =
    Matcher { req ->
        val activeMatches =
            req.actionsList.count {
                it.alternativeGrpId == altGrpId || it.abilityGrpId == altGrpId
            }
        val inactiveMatches =
            req.inactiveActionsList.count {
                it.alternativeGrpId == altGrpId || it.abilityGrpId == altGrpId
            }
        val passed = activeMatches + inactiveMatches > 0
        MatcherResult(
            passed,
            {
                "ActionsAvailableReq should expose an offer with alt-cost grpId=$altGrpId " +
                    "(searched ${req.actionsCount} active + ${req.inactiveActionsCount} inactive, " +
                    "found 0 matching)"
            },
            {
                "ActionsAvailableReq should NOT expose an offer with alt-cost grpId=$altGrpId " +
                    "(found $activeMatches active, $inactiveMatches inactive)"
            },
        )
    }
