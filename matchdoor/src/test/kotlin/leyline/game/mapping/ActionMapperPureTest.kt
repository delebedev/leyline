package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.types.GrpId
import leyline.game.snapshot.GrpIdResolver
import leyline.testkit.BoardTestBase
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import leyline.testkit.mana
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Pure tests for [ActionMapper.buildActionList] — the overload with function params.
 *
 * Uses [BoardTestBase.startWithBoard] to set up board state without a full
 * game loop. The key point: [ActionMapper.buildActionList] itself holds no
 * [leyline.game.state.GameBridge] reference — the bridge only provides the lambdas.
 */
@Suppress("WeakAssertionOnly")
class ActionMapperPureTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // -----------------------------------------------------------------------
        // Test 1: Pass action always present
        // -----------------------------------------------------------------------

        test("buildActionList includes Pass action on empty board") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val human = game.humanPlayer

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = false,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            val hasPass = actions.actionsList.any { it.actionType == ActionType.Pass }
            hasPass.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 2: Land in hand → inactiveActions (naive mode: no canPlayLand check)
        // -----------------------------------------------------------------------

        test("buildActionList includes Play for lands in hand (inactiveActions in naive mode)") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = false,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            // In naive mode lands are always non-playable → inactiveActions
            val hasPlay = actions.inactiveActionsList.any { it.actionType == ActionType.Play_add3 }
            hasPlay.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 3: Non-land spell in hand → Cast in actions
        // -----------------------------------------------------------------------

        test("buildActionList includes Cast for non-land spells in hand") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Llanowar Elves", human, ZoneType.Hand)
                }
            val human = game.humanPlayer

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = false,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            val hasCast = actions.actionsList.any { it.actionType == ActionType.Cast }
            hasCast.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 4: Untapped land on battlefield → ActivateMana in actions
        // -----------------------------------------------------------------------

        test("buildActionList includes ActivateMana for untapped lands on battlefield") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = false,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            val hasActivateMana = actions.actionsList.any { it.actionType == ActionType.ActivateMana }
            hasActivateMana.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 5: Unaffordable Cast → inactiveActions (legality mode)
        // -----------------------------------------------------------------------

        test("unaffordable Cast goes to inactiveActions with manaCost") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    // Spell in hand, no lands — can't pay
                    base.addCard("Llanowar Elves", human, ZoneType.Hand) // costs {G}
                }
            val human = game.humanPlayer

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            // Cast should be inactive, not active
            actions.actionsList.none { it.actionType == ActionType.Cast }.shouldBeTrue()
            val inactive = actions.inactiveActionsList.filter { it.actionType == ActionType.Cast }
            inactive.size shouldBe 1
            inactive.first() should haveManaCost(green = 1)
        }

        // -----------------------------------------------------------------------
        // Test 6: Affordable Cast stays in actions
        // -----------------------------------------------------------------------

        test("affordable Cast stays in actions") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Llanowar Elves", human, ZoneType.Hand) // costs {G}
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            actions.actionsList.any { it.actionType == ActionType.Cast }.shouldBeTrue()
            actions.inactiveActionsList.none { it.actionType == ActionType.Cast }.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 7: Unaffordable Activate → inactiveActions
        // -----------------------------------------------------------------------

        test("unaffordable Activate goes to inactiveActions") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    // Permanent with mana-costed activated ability, no mana available
                    base.addCard("Prismari Command", human, ZoneType.Hand)
                    // Sorcerer Class has {3}{U}{R}: level 2 — a mana-costed activate
                    base.addCard("Sorcerer Class", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            // Activate should be in inactiveActions (can't pay), not actions
            actions.actionsList.none { it.actionType == ActionType.Activate_add3 }.shouldBeTrue()
            actions.inactiveActionsList.any { it.actionType == ActionType.Activate_add3 }.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 8: Affordable Activate stays in actions
        // -----------------------------------------------------------------------

        test("affordable Activate stays in actions") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Sorcerer Class", human, ZoneType.Battlefield)
                    // Enough mana for {3}{U}{R}
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Mountain", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer

            val actions =
                ActionMapper.buildActionList(
                    player = human,
                    seatId = 1,
                    checkLegality = true,
                    idResolver = { forgeCardId -> b.getOrAllocInstanceId(forgeCardId) },
                    grpIdResolver = { card -> GrpId(GrpIdResolver.resolve(card, b.cardRepository)) },
                    cardDataLookup = { grpId -> b.cardRepository.findByGrpId(grpId.value) },
                )

            actions.actionsList.any { it.actionType == ActionType.Activate_add3 }.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // stripActionForGsm — GSM actions carry fewer fields than ActionsAvailableReq
        // -----------------------------------------------------------------------

        fun stripped(
            type: ActionType,
            block: Action.Builder.() -> Unit = {},
        ): Action =
            ActionMapper.stripActionForGsm(
                Action
                    .newBuilder()
                    .setActionType(type)
                    .apply(block)
                    .build(),
            )

        test("stripActionForGsm preserves manaCost on Cast") {
            val s =
                stripped(ActionType.Cast) {
                    instanceId = 100
                    grpId = 75570
                    facetId = 100
                    shouldStop = true
                    addManaCost(mana(ManaColor.Generic, 1))
                    addManaCost(mana(ManaColor.Green_afc9, 1))
                }

            assertSoftly {
                s.instanceId shouldBe 100
                s should haveManaCost(generic = 1, green = 1)
                s.grpId shouldBe 0
                s.shouldStop shouldBe false
            }
        }

        test("stripActionForGsm preserves alt-cost cast identity") {
            val s =
                stripped(ActionType.Cast) {
                    instanceId = 100
                    grpId = 75570
                    facetId = 100
                    abilityGrpId = 12345
                    sourceId = 100
                    alternativeGrpId = 12345
                    alternativeSourceZcid = 100
                    addManaCost(
                        ManaRequirement
                            .newBuilder()
                            .addColor(ManaColor.Blue_afc9)
                            .setCount(1)
                            .setAbilityGrpId(12345),
                    )
                }

            assertSoftly {
                s.instanceId shouldBe 100
                s.abilityGrpId shouldBe 12345
                s.sourceId shouldBe 100
                s.alternativeGrpId shouldBe 12345
                s.alternativeSourceZcid shouldBe 100
                s.manaCostList.single().abilityGrpId shouldBe 12345
                s.grpId shouldBe 0
                s.facetId shouldBe 0
            }
        }

        test("stripActionForGsm preserves manaCost on CastAdventure") {
            val s =
                stripped(ActionType.CastAdventure) {
                    instanceId = 200
                    grpId = 80000
                    addManaCost(mana(ManaColor.White_afc9, 1))
                }

            assertSoftly {
                s.instanceId shouldBe 200
                s should haveManaCost(white = 1)
                s.grpId shouldBe 0
            }
        }

        test("stripActionForGsm strips everything except instanceId on Play") {
            val s =
                stripped(ActionType.Play_add3) {
                    instanceId = 100
                    grpId = 91309
                    shouldStop = true
                }

            assertSoftly {
                s.instanceId shouldBe 100
                s.grpId shouldBe 0
                s.manaCostList.shouldBeEmpty()
            }
        }

        test("stripActionForGsm keeps abilityGrpId on ActivateMana") {
            val s =
                stripped(ActionType.ActivateMana) {
                    instanceId = 100
                    abilityGrpId = 1005
                    grpId = 75570
                }

            assertSoftly {
                s.instanceId shouldBe 100
                s.abilityGrpId shouldBe 1005
                s.grpId shouldBe 0
            }
        }

        test("stripActionForGsm produces empty action for Pass") {
            val s = stripped(ActionType.Pass)

            s.actionType shouldBe ActionType.Pass
            s.instanceId shouldBe 0
        }
    })
