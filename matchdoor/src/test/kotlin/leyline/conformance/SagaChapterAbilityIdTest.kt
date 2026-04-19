package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag

/**
 * Verifies saga chapter triggers are enumerated into [CardData.chapterAbilityGrpIds]
 * with one distinct synthetic grpId per chapter number. This is what lets
 * [ZoneMapper.resolveChapterAbilityGrpId] emit the chapter-specific grpId on the
 * stack Ability gameObject instead of the saga's own grpId.
 *
 * Production (ExposedCardRepository) relies on the card DB's SQLite `Cards.AbilityIds`
 * column having the chapter grpIds at the leading positions — the resolver's
 * fallback path handles that. This test exercises the AbilityIdDeriver path used
 * by tests and puzzles.
 */
class SagaChapterAbilityIdTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("tribute to horobi: 3 chapter grpIds, distinct, non-zero")
            .config(tags = setOf(ConformanceTag)) {
                val cardName = "Tribute to Horobi"
                val (b, _, _) = base.startWithBoard { _, _, _ -> }

                val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
                // Re-derive from the live card (has player context) — mirrors the
                // planeswalker pattern in AbilityGrpIdConformanceTest.
                val cardData = CardDataDeriver.fromForgeCard(injected.card)
                TestCardRegistry.repo.registerData(cardData, cardName)

                cardData.chapterAbilityGrpIds shouldHaveSize 3
                cardData.chapterAbilityGrpIds.toSet() shouldHaveSize 3 // all distinct
                cardData.chapterAbilityGrpIds.forEach { it shouldNotBe 0 }
                // Chapter grpIds must differ from the saga's own grpId — that's the
                // whole point of the L2 fix.
                cardData.chapterAbilityGrpIds.forEach { it shouldNotBe cardData.grpId }
            }

        test("non-saga card has empty chapterAbilityGrpIds")
            .config(tags = setOf(ConformanceTag)) {
                val cardName = "Grizzly Bears"
                val (b, _, _) = base.startWithBoard { _, _, _ -> }

                val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
                val cardData = CardDataDeriver.fromForgeCard(injected.card)

                cardData.chapterAbilityGrpIds shouldBe emptyList()
            }

        // ------------------------------------------------------------------
        // Full-integration trigger-to-stack coverage is intentionally DEFERRED.
        //
        // Ideally a third test would: addCard(saga) → addCounter(LORE) → assert
        // the resulting GSM contains a stack Ability gameObject with grpId ==
        // chapterAbilityGrpIds[0]. But `addCard` + zone.add bypasses Forge's
        // card-ETB flow that registers the card's triggers into TriggerHandler,
        // so the CounterAdded trigger is defined on the saga but never active
        // in the game. `addCounterInternal(..., fireEvents=true)` followed by
        // `triggerHandler.runWaitingTriggers()` still leaves stack.size==0.
        //
        // Real engine flow (`game.action.moveToPlay(card)` or a full puzzle
        // bootstrap) wires triggers, but that's heavier than this test needs
        // and re-creates the puzzle-tier dependency we're trying to avoid.
        //
        // This gap is covered end-to-end by the Phase 3 transform puzzle
        // (saga-transform-tribute.pzl + SagaTransformPuzzleTest) in the full
        // session-tier MatchFlowHarness where Forge's normal cast → ETB →
        // chapter-trigger flow runs naturally.
        // ------------------------------------------------------------------
    })
