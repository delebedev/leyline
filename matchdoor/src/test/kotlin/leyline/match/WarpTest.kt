package leyline.match

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.IntegrationTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.SeatId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.MatchFlowHarness
import leyline.conformance.detailInt
import leyline.conformance.humanPlayer
import leyline.game.InMemoryCardRepository
import leyline.game.data.AbilityInfo
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GrpIdResolver
import leyline.game.snapshot.SnapshotCapture
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Warp hand-cast-with-alternate-cost path.
 *
 * Scope (strict): only the cast-from-hand rail. No library-top offers, no
 * post-exile recast offers, no library-reveal flow — those shapes are not
 * attested in recordings.
 *
 * Card: Germinating Wurm (ManaCost 4G, Warp {1}{G}, ETB gain 2 life — no
 * mandatory target).
 */
private val WARP_PUZZLE =
    """
    [metadata]
    Name:Warp — Germinating Wurm cast for warp cost
    Goal:Cast Germinating Wurm from hand for its warp cost ({1}{G}).
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Germinating Wurm
    humanbattlefield=Forest;Forest
    humanlibrary=Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

@Suppress(
    // Integration tests are step-by-step: each assertion is a precondition for the next, not a parallel check.
    "MissingAssertSoftly",
    // `it!!` inside `assertSoftly(nullable) { it!! ... }` is required — the soft-assertion
    // doesn't short-circuit so smart-cast can't propagate after the null check.
    "UnnecessaryNotNullOperator",
)
class WarpTest :
    FunSpec({

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("ActionMapper offers alt-cost Cast for warp card in hand when mana available").config(tags = setOf(ConformanceTag)) {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Germinating Wurm", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)
            warpAbilityGrpId shouldNotBe null
            warpAbilityGrpId!! shouldBeGreaterThan 0

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                    cardRepository = b.cardRepository,
                )

            val castOffers =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.grpId == wurmGrpId
                }
            castOffers.shouldNotBeEmpty()
            val warpOffer = castOffers.firstOrNull { it.alternativeGrpId == warpAbilityGrpId }
            assertSoftly(warpOffer) {
                it shouldNotBe null
                it!!.abilityGrpId shouldBe 0
                it.manaCostCount shouldBeGreaterThan 0
                it.manaCostList.all { mc -> mc.abilityGrpId == warpAbilityGrpId }.shouldBeTrue()
            }
        }

        test("ActionMapper emits warp offer when production-shape CardData lacks keywordAbilityGrpIds").config(
            tags = setOf(ConformanceTag),
        ) {
            // Regression for leyline-g3zg. ExposedCardRepository does not populate
            // keywordAbilityGrpIds (no column in the Cards table), so CardData arrives
            // at the mapper with an empty keyword-name → grpId map. The fix resolves
            // the per-card warp row via the Arena DB Abilities table (BaseId=371 for
            // Warp, matched by OldSchoolManaText). This test repros that shape using
            // InMemoryCardRepository.findAbilityInfo().
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Germinating Wurm", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                    abilityRegistryLookup = { card, cardData -> b.abilityRegistryFor(card, cardData) },
                    cardRepository = b.cardRepository,
                )

            val warpOffer =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast && it.grpId == wurmGrpId && it.alternativeGrpId == warpAbilityGrpId
                }
            assertSoftly(warpOffer) {
                it shouldNotBe null
                it!!.abilityGrpId shouldBe 0
                it.manaCostCount shouldBeGreaterThan 0
                it.manaCostList.all { mc -> mc.abilityGrpId == warpAbilityGrpId }.shouldBeTrue()
            }
        }

        test(
            "resolver picks the warp ability row (BaseId=371 + cost match) and NOT the first abilityIds entry",
        ).config(tags = setOf(ConformanceTag)) {
            // Direct regression for leyline-g3zg. Production AbilityIds arrive as
            // `etbTriggerId:textId,warpId:textId` — positional resolution picks the ETB
            // trigger (first slot), which made the client show "Alternate Cost" generic
            // marker on the wrong cost in the CastingTimeOptions modal. Under the fix
            // the resolver must match by BaseId (Warp=371) AND mana cost, so the ETB
            // row (BaseId=0) cannot shadow the Warp row regardless of slot order.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Germinating Wurm", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val realWarpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            // Inject a fake ETB ability id (BaseId=0) at a leading position via the
            // cardDataLookup override below. It must be registered with AbilityInfo so
            // findAlternativeCostAbilityGrpId considers it during the BaseId scan, and
            // must be rejected in favor of the real Warp row (BaseId=371 + cost match).
            val fakeEtbId = 999001
            (b.cardRepository as InMemoryCardRepository).registerAbilityInfo(
                fakeEtbId,
                AbilityInfo(baseId = 0, manaCost = emptyList()),
            )

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId ->
                        // Prepend the ETB id so positional-first-wins would pick the wrong row.
                        b.cardRepository.findByGrpId(grpId.value)?.copy(
                            abilityIds = listOf(fakeEtbId to 0) + (b.cardRepository.findByGrpId(grpId.value)?.abilityIds ?: emptyList()),
                        )
                    },
                    abilityRegistryLookup = { card, cardData -> b.abilityRegistryFor(card, cardData) },
                    cardRepository = b.cardRepository,
                )

            val warpOffer =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast && it.grpId == wurmGrpId && it.alternativeGrpId != 0
                }
            assertSoftly(warpOffer) {
                it shouldNotBe null
                it!!.alternativeGrpId shouldBe realWarpAbilityGrpId
                it.alternativeGrpId shouldNotBe fakeEtbId
                it.abilityGrpId shouldBe 0
                it.manaCostList.all { mc -> mc.abilityGrpId == realWarpAbilityGrpId }.shouldBeTrue()
            }
        }

        test("ActionMapper.buildFromSnapshot offers alt-cost Cast for Quantum Riddler (puzzle path)").config(
            tags = setOf(ConformanceTag),
        ) {
            // Mirrors the live puzzle (try-warp.pzl): 3 Islands, Quantum Riddler in hand.
            // Base cost 3UU is unpayable (only 3 lands). Warp {1}{U} is payable.
            // Runtime bug: puzzle path produced no alt-cost Cast offer at all.
            val puzzle =
                """
                [metadata]
                Name:Try Warp
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Quantum Riddler
                humanbattlefield=Island;Island;Island
                humanlibrary=Island
                aibattlefield=
                ailibrary=Plains
                """.trimIndent()
            val (b, game, _) = base.startPuzzleAtMain1(puzzle)

            val riddlerGrpId = b.cardRepository.findGrpIdByName("Quantum Riddler")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(riddlerGrpId, KeywordAbilityIds.WARP)!!
            val riddlerIid =
                b
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer
                                .getZone(ZoneType.Hand)
                                .cards
                                .first { it.name == "Quantum Riddler" }
                                .id,
                        ),
                    ).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val warpOffer =
                fromSnap.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == riddlerIid &&
                        it.alternativeGrpId == warpAbilityGrpId
                }
            assertSoftly(warpOffer) {
                it shouldNotBe null
                it!!.abilityGrpId shouldBe 0
                it.manaCostCount shouldBeGreaterThan 0
                it.manaCostList.all { mc -> mc.abilityGrpId == warpAbilityGrpId }.shouldBeTrue()
            }
        }

        test(
            "ActionMapper.buildFromSnapshot offers alt-cost Cast for warp card in hand when mana available",
        ).config(tags = setOf(ConformanceTag)) {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Germinating Wurm", human, ZoneType.Hand)
                }

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!
            val wurmIid =
                b
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer
                                .getZone(ZoneType.Hand)
                                .cards
                                .first { it.name == "Germinating Wurm" }
                                .id,
                        ),
                    ).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val warpOffer =
                fromSnap.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == wurmIid &&
                        it.alternativeGrpId == warpAbilityGrpId
                }
            assertSoftly(warpOffer) {
                it shouldNotBe null
                it!!.abilityGrpId shouldBe 0
                it.manaCostCount shouldBeGreaterThan 0
                it.manaCostList.all { mc -> mc.abilityGrpId == warpAbilityGrpId }.shouldBeTrue()
            }
        }

        test(
            "submission path: resolveAltCostAbilityIndex picks Warp SA via findAbilityInfo (empty keywordAbilityGrpIds)",
        ).config(tags = setOf(IntegrationTag)) {
            // Regression for the submission-path bug that mirrored leyline-g3zg on the
            // emission side: ActionPerformer.resolveAltCostAbilityIndex used to read
            // CardData.keywordAbilityGrpIds (empty in prod since ExposedCardRepository
            // doesn't populate it) and silently returned null → Forge picked the base
            // SA → {4}{G} cost required with only {2}{G} available → spell returned to
            // hand. This test pins the production shape: empty keyword-map-equivalent,
            // with AbilityInfo registered the way ExposedCardRepository surfaces it.
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(WARP_PUZZLE)
                val player = h.bridge.getPlayer(SeatId(1))!!
                val repo = h.bridge.cardRepository

                val wurmGrpId = repo.findGrpIdByName("Germinating Wurm")!!
                val warpAbilityGrpId =
                    repo.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

                h.castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId).shouldBeTrue()
                h
                    .passUntil(maxPasses = 20) {
                        h.bridge
                            .getGame()!!
                            .stack.isEmpty
                    }.shouldBeTrue()

                // Cast landed (proof that the Warp SA — not the base 4G SA — was chosen):
                // with only two Forests the base cost is unpayable, so if
                // resolveAltCostAbilityIndex had fallen through to the base SA the spell
                // would have returned to hand.
                val stillInHand = player.getZone(ZoneType.Hand).cards.any { it.name == "Germinating Wurm" }
                val castLanded =
                    player.getZone(ZoneType.Battlefield).cards.any { it.name == "Germinating Wurm" } ||
                        player.getZone(ZoneType.Exile).cards.any { it.name == "Germinating Wurm" } ||
                        player.getZone(ZoneType.Graveyard).cards.any { it.name == "Germinating Wurm" }
                stillInHand.shouldBeFalse()
                castLanded.shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }

        test("hand-cast warp path: cast Germinating Wurm for {1}{G} → CastingTimeOption type=13").config(tags = setOf(IntegrationTag)) {
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(WARP_PUZZLE)
                val player = h.bridge.getPlayer(SeatId(1))!!

                val wurmGrpId = h.bridge.cardRepository.findGrpIdByName("Germinating Wurm")!!
                val warpAbilityGrpId =
                    h.bridge.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)
                        ?: error("Expected WARP keyword ability grpId on Germinating Wurm")
                warpAbilityGrpId shouldBeGreaterThan 0

                // Submit the Cast carrying alternativeGrpId = warp ability grpId
                // (same shape the client would emit against a hand-zone warp offer).
                h.castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId).shouldBeTrue()
                h
                    .passUntil(maxPasses = 20) {
                        h.bridge
                            .getGame()!!
                            .stack.isEmpty
                    }.shouldBeTrue()

                // Warp cast resolves normally: ETB → battlefield. passUntil may have
                // cascaded past the end step into Warp's exile-at-end-step replacement,
                // so treat "Wurm exists anywhere except the hand" as proof the cast landed.
                val stillInHand = player.getZone(ZoneType.Hand).cards.any { it.name == "Germinating Wurm" }
                val castLanded =
                    player.getZone(ZoneType.Battlefield).cards.any { it.name == "Germinating Wurm" } ||
                        player.getZone(ZoneType.Exile).cards.any { it.name == "Germinating Wurm" } ||
                        player.getZone(ZoneType.Graveyard).cards.any { it.name == "Germinating Wurm" }
                stillInHand.shouldBeFalse()
                castLanded.shouldBeTrue()

                val allGsms = h.allMessages.mapNotNull { msgGsm(it) }

                // (1) CastingTimeOption persistent annotation type=13 (CastThroughAbility)
                //     carrying the warp ability grpId.
                val cto =
                    allGsms
                        .flatMap { it.persistentAnnotationsList }
                        .firstOrNull { it.typeList.contains(AnnotationType.CastingTimeOption) }
                assertSoftly(cto) {
                    it shouldNotBe null
                    it!!.detailInt("type") shouldBe 13
                    it.detailInt("alternateCostGrpId") shouldBe warpAbilityGrpId
                    it.detailInt("castAbilityGrpId") shouldBe warpAbilityGrpId
                }
            } finally {
                h.shutdown()
            }
        }

        test("full-turn cast-via-regular-cost keeps Germinating Wurm on the battlefield").config(tags = setOf(IntegrationTag)) {
            val puzzle =
                """
                [metadata]
                Name:Warp — regular-cost cast stays on battlefield
                Goal:Cast Germinating Wurm for 4G; it stays post-turn.
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Germinating Wurm
                humanbattlefield=Forest;Forest;Forest;Forest;Forest
                humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(puzzle)
                val player = h.bridge.getPlayer(SeatId(1))!!

                // No alternativeGrpId → engine pays the regular 4G cost.
                h.castSpellByName("Germinating Wurm").shouldBeTrue()
                h
                    .passUntil(maxPasses = 20) {
                        h.bridge
                            .getGame()!!
                            .stack.isEmpty
                    }.shouldBeTrue()

                player
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Germinating Wurm" }
                    .shouldBeTrue()

                // Advance past end of turn 1 so the warp exile replacement would fire if it were armed.
                h.passUntilTurn(2, maxPasses = 30)

                val onBf = player.getZone(ZoneType.Battlefield).cards.any { it.name == "Germinating Wurm" }
                val inExile = player.getZone(ZoneType.Exile).cards.any { it.name == "Germinating Wurm" }
                val inGy = player.getZone(ZoneType.Graveyard).cards.any { it.name == "Germinating Wurm" }
                val inHand = player.getZone(ZoneType.Hand).cards.any { it.name == "Germinating Wurm" }
                assertSoftly {
                    onBf.shouldBeTrue()
                    inExile.shouldBeFalse()
                    inGy.shouldBeFalse()
                    inHand.shouldBeFalse()
                }
            } finally {
                h.shutdown()
            }
        }

        test("full-turn cast-via-warp-cost exiles Germinating Wurm at end of turn").config(tags = setOf(IntegrationTag)) {
            val puzzle =
                """
                [metadata]
                Name:Warp — warp-cost cast exiles at end of turn
                Goal:Cast Germinating Wurm for {1}{G}; exiled at end of turn.
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Germinating Wurm
                humanbattlefield=Forest;Forest;Forest;Forest;Forest
                humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent()
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(puzzle)
                val player = h.bridge.getPlayer(SeatId(1))!!

                val wurmGrpId = h.bridge.cardRepository.findGrpIdByName("Germinating Wurm")!!
                val warpAbilityGrpId =
                    h.bridge.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

                h.castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId).shouldBeTrue()
                h
                    .passUntil(maxPasses = 20) {
                        h.bridge
                            .getGame()!!
                            .stack.isEmpty
                    }.shouldBeTrue()

                // autoPassAndAdvance may have already cascaded past the end step,
                // firing warp's exile-at-end-step replacement. Ensure we're past turn 1.
                h.passUntilTurn(2, maxPasses = 30)

                val onBf = player.getZone(ZoneType.Battlefield).cards.any { it.name == "Germinating Wurm" }
                val inExile = player.getZone(ZoneType.Exile).cards.any { it.name == "Germinating Wurm" }
                val inGy = player.getZone(ZoneType.Graveyard).cards.any { it.name == "Germinating Wurm" }
                val inHand = player.getZone(ZoneType.Hand).cards.any { it.name == "Germinating Wurm" }
                assertSoftly {
                    inExile.shouldBeTrue()
                    onBf.shouldBeFalse()
                    inGy.shouldBeFalse()
                    inHand.shouldBeFalse()
                }
            } finally {
                h.shutdown()
            }
        }

        test("warp card in hand but insufficient mana → no alt-cost Cast offer").config(tags = setOf(ConformanceTag)) {
            // Only one Forest — can't pay {1}{G}.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Germinating Wurm", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            val hasWarpOffer =
                actions.actionsList.any { it.alternativeGrpId == warpAbilityGrpId } ||
                    actions.inactiveActionsList.any { it.alternativeGrpId == warpAbilityGrpId }
            hasWarpOffer.shouldBeFalse()
        }

        test("warp card only in library → no alt-cost Cast offer (no speculative library-top rail)").config(tags = setOf(ConformanceTag)) {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Germinating Wurm", human, ZoneType.Library)
                }
            val human = game.humanPlayer

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            val hasWarpOffer =
                actions.actionsList.any { it.alternativeGrpId == warpAbilityGrpId } ||
                    actions.inactiveActionsList.any { it.alternativeGrpId == warpAbilityGrpId }
            hasWarpOffer.shouldBeFalse()
        }

        test("warp card in graveyard → no alt-cost Cast offer").config(tags = setOf(ConformanceTag)) {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Germinating Wurm", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            val hasWarpOffer =
                actions.actionsList.any { it.alternativeGrpId == warpAbilityGrpId } ||
                    actions.inactiveActionsList.any { it.alternativeGrpId == warpAbilityGrpId }
            hasWarpOffer.shouldBeFalse()
        }
    })

private fun msgGsm(msg: GREToClientMessage) = if (msg.hasGameStateMessage()) msg.gameStateMessage else null
