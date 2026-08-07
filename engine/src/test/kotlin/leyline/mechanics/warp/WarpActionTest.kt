package leyline.mechanics.warp

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.GrpId
import leyline.game.InMemoryCardRepository
import leyline.game.data.AbilityInfo
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GrpIdResolver
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

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
@Suppress("UnnecessaryNotNullOperator")
class WarpActionTest :
    BoardTest({

        test("ActionMapper offers alt-cost Cast for warp card in hand when mana available") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Germinating Wurm", human, ZoneType.Hand)
                }
            val b = board.bridge

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)
            warpAbilityGrpId shouldNotBe null
            warpAbilityGrpId!! shouldBeGreaterThan 0

            val actions = board.actions()

            val castOffers =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.grpId == wurmGrpId
                }
            castOffers.shouldNotBeEmpty()
            val warpOffer = castOffers.firstOrNull { it.alternativeGrpId == warpAbilityGrpId }
            assertSoftly {
                warpOffer should beAltCostOffer(warpAbilityGrpId)
                warpOffer!!.abilityGrpId shouldBe 0 // alternative rail
            }
        }

        test("ActionMapper emits warp offer when production-shape CardData lacks keywordAbilityGrpIds") {
            // Regression for leyline-g3zg. ExposedCardRepository does not populate
            // keywordAbilityGrpIds (no column in the Cards table), so CardData arrives
            // at the mapper with an empty keyword-name → grpId map. The fix resolves
            // the per-card warp row via the Arena DB Abilities table (BaseId=371 for
            // Warp, matched by OldSchoolManaText). This test repros that shape using
            // InMemoryCardRepository.findAbilityInfo().
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Germinating Wurm", human, ZoneType.Hand)
                }
            val b = board.bridge
            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            val actions = board.actions()

            val warpOffer =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast && it.grpId == wurmGrpId && it.alternativeGrpId == warpAbilityGrpId
                }
            assertSoftly {
                warpOffer should beAltCostOffer(warpAbilityGrpId)
                warpOffer!!.abilityGrpId shouldBe 0 // alternative rail
            }
        }

        test(
            "resolver picks the warp ability row (BaseId=371 + cost match) and NOT the first abilityIds entry",
        ) {
            // Direct regression for leyline-g3zg. Production AbilityIds arrive as
            // `etbTriggerId:textId,warpId:textId` — positional resolution picks the ETB
            // trigger (first slot), which made the client show "Alternate Cost" generic
            // marker on the wrong cost in the CastingTimeOptions modal. Under the fix
            // the resolver must match by BaseId (Warp=371) AND mana cost, so the ETB
            // row (BaseId=0) cannot shadow the Warp row regardless of slot order.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Germinating Wurm", human, ZoneType.Hand)
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
            assertSoftly {
                warpOffer should beAltCostOffer(realWarpAbilityGrpId)
                warpOffer!!.alternativeGrpId shouldBe realWarpAbilityGrpId
                warpOffer.alternativeGrpId shouldNotBe fakeEtbId
                warpOffer.abilityGrpId shouldBe 0 // alternative rail
            }
        }

        test("ActionMapper.buildFromSnapshot offers alt-cost Cast for Quantum Riddler (puzzle path)") {
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
            val (b, game, _) = startPuzzleAtMain1(puzzle)

            val riddlerGrpId = b.cardRepository.findGrpIdByName("Quantum Riddler")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(riddlerGrpId, KeywordAbilityIds.WARP)!!
            val riddlerIid = game.humanPlayer.hand.iid("Quantum Riddler")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val warpOffer =
                fromSnap.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == riddlerIid &&
                        it.alternativeGrpId == warpAbilityGrpId
                }
            assertSoftly {
                warpOffer should beAltCostOffer(warpAbilityGrpId)
                warpOffer!!.abilityGrpId shouldBe 0 // alternative rail
            }
        }

        test(
            "ActionMapper.buildFromSnapshot offers alt-cost Cast for warp card in hand when mana available",
        ) {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Germinating Wurm", human, ZoneType.Hand)
                }

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!
            val wurmIid = game.humanPlayer.hand.iid("Germinating Wurm")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val warpOffer =
                fromSnap.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == wurmIid &&
                        it.alternativeGrpId == warpAbilityGrpId
                }
            assertSoftly {
                warpOffer should beAltCostOffer(warpAbilityGrpId)
                warpOffer!!.abilityGrpId shouldBe 0 // alternative rail
            }
        }

        test("warp card in hand but insufficient mana -> no alt-cost Cast offer") {
            // Only one Forest — can't pay {1}{G}.
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Germinating Wurm", human, ZoneType.Hand)
                }
            val b = board.bridge

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            val actions = board.actions()

            actions.actionsList.count { it.alternativeGrpId == warpAbilityGrpId } shouldBe 0
            actions shouldNot offerAltCost(warpAbilityGrpId)
        }

        test("warp card only in library -> no alt-cost Cast offer (no speculative library-top rail)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Germinating Wurm", human, ZoneType.Library)
                }
            val b = board.bridge

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            val actions = board.actions()

            actions.actionsList.count { it.alternativeGrpId == warpAbilityGrpId } shouldBe 0
            actions shouldNot offerAltCost(warpAbilityGrpId)
        }

        test("warp card in graveyard -> no alt-cost Cast offer") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Germinating Wurm", human, ZoneType.Graveyard)
                }
            val b = board.bridge

            val wurmGrpId = b.cardRepository.findGrpIdByName("Germinating Wurm")!!
            val warpAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!

            val actions = board.actions()

            actions.actionsList.count { it.alternativeGrpId == warpAbilityGrpId } shouldBe 0
            actions shouldNot offerAltCost(warpAbilityGrpId)
        }
    })
