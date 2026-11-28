package leyline.bridge

import forge.card.CardStateName
import forge.game.spellability.LandAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class OfferDiffTest :
    BoardTest({
        test("a window that offers every play Forge lists reports no difference") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                    addCard("Mountain", human, ZoneType.Hand)
                }
            board.offerDiff().shouldBeEmpty()
        }

        test("a play Forge lists that the client cannot afford is offered inactive under a named rule") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Shivan Dragon", human, ZoneType.Hand)
                }
            val dragon = board.offerDiff().filter { it.card == "Shivan Dragon" }
            assertSoftly {
                dragon.map { it.bucket } shouldBe listOf(OfferDiffBucket.OfferedInactive)
                dragon.single().rule shouldBe OfferDiffRule.InactiveUnaffordable
            }
        }

        test("a Jump-start cast is a named cost variant of the spell Forge lists once") {
            val board =
                startWithBoard { _, human, _ ->
                    repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Radical Idea", human, ZoneType.Graveyard)
                    addCard("Island", human, ZoneType.Hand)
                }
            val idea = board.offerDiff().filter { it.card == "Radical Idea" }
            assertSoftly {
                idea.map { it.bucket } shouldBe listOf(OfferDiffBucket.OfferedNotInForge)
                idea.single().optionalCosts shouldBe listOf("Jumpstart")
                idea.single().rule shouldBe OfferDiffRule.CostVariantSplit
            }
        }

        test("a spell whose additional costs are all unpayable is a named enumeration gap") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Dusk Rose Reliquary", human, ZoneType.Hand)
                }
            val reliquary = board.offerDiff().filter { it.card == "Dusk Rose Reliquary" }
            reliquary.map { it.bucket to it.rule } shouldBe
                listOf(OfferDiffBucket.EnumerationGap to OfferDiffRule.NoPayableAdditionalCost)
        }

        test("a play missing from the candidates is an enumeration gap and one the projection drops is a projection drop") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val forge = OfferDiff.forgeEnumeration(board.game, board.human)
            val bolt = forge.filter { it.hostCard.name == "Lightning Bolt" }
            bolt shouldHaveSize 1

            val notCandidates = OfferDiff.classify(OfferDiff.diff(forge, emptyList(), emptyList(), emptySet(), canExecute = { true }))
            val dropped = OfferDiff.classify(OfferDiff.diff(forge, forge, emptyList(), emptySet(), canExecute = { true }))
            assertSoftly {
                notCandidates.filter { it.card == "Lightning Bolt" }.map { it.bucket to it.rule } shouldContain
                    (OfferDiffBucket.EnumerationGap to null)
                dropped.filter { it.card == "Lightning Bolt" }.map { it.bucket to it.rule } shouldContain
                    (OfferDiffBucket.ProjectionDrop to null)
            }
        }

        test("a dropped ability is not hidden by another ability of the same API type on the card") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Adarkar Wastes", human, ZoneType.Battlefield)
                }
            val forge = OfferDiff.forgeEnumeration(board.game, board.human).filter { it.hostCard.name == "Adarkar Wastes" }
            val (kept, dropped) = forge
            val offer =
                ActionOffer(
                    Action.getDefaultInstance(),
                    PlayerAction.ActivateMana(ForgeCardId(kept.hostCard.id), ability = kept),
                )
            val entries = OfferDiff.classify(OfferDiff.diff(forge, forge, listOf(offer), emptySet(), canExecute = { true }))
            assertSoftly {
                forge shouldHaveSize 2
                entries.map { Triple(it.bucket, it.description, it.rule) } shouldBe
                    listOf(Triple(OfferDiffBucket.ProjectionDrop, dropped.description, null))
            }
        }

        test("a dropped back-face land play is reported though the front face is offered") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Brightclimb Pathway", human, ZoneType.Hand)
                }
            val forge = OfferDiff.forgeEnumeration(board.game, board.human).filter { it.isLandAbility }
            val front = forge.first { it.cardState?.stateName == CardStateName.Original }
            val offer =
                ActionOffer(
                    Action.newBuilder().setActionType(ActionType.Play_add3).build(),
                    PlayerAction.PlayLand(ForgeCardId(front.hostCard.id)),
                )
            val entries = OfferDiff.classify(OfferDiff.diff(forge, forge, listOf(offer), emptySet(), canExecute = { true }))
            assertSoftly {
                forge shouldHaveSize 2
                entries.map { Triple(it.bucket, it.kind, it.rule) } shouldBe
                    listOf(Triple(OfferDiffBucket.ProjectionDrop, OfferDiff.Kind.Land, null))
            }
        }

        test("a land play offered after the land drop is used is offered but not in Forge") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }
            val forest = board.human.hand.card("Forest")
            val land = LandAbility(forest, forest.currentState).also { it.activatingPlayer = board.human }
            board.human.addLandPlayedThisTurn()
            val forge = OfferDiff.forgeEnumeration(board.game, board.human)
            val offer =
                ActionOffer(
                    Action.newBuilder().setActionType(ActionType.Play_add3).build(),
                    PlayerAction.PlayLand(ForgeCardId(forest.id)),
                )
            val entries = OfferDiff.classify(OfferDiff.diff(forge, listOf(land), listOf(offer), emptySet(), canExecute = { true }))
            assertSoftly {
                forge.filter { it.isLandAbility }.shouldBeEmpty()
                entries.map { Triple(it.bucket, it.card, it.rule) } shouldBe
                    listOf(Triple(OfferDiffBucket.OfferedNotInForge, "Forest", null))
            }
        }

        test("a spell whose required additional costs are all unaffordable is offered inactive under a named rule") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Kinsbaile Aspirant", human, ZoneType.Hand)
                }
            board.offerDiff().filter { it.card == "Kinsbaile Aspirant" }.map { it.bucket to it.rule } shouldBe
                listOf(OfferDiffBucket.OfferedInactive to OfferDiffRule.InactiveUnaffordable)
        }

        test("an unaffordable alternative cast without its own inactive action is a named projection drop") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Quantum Riddler", human, ZoneType.Hand)
                }
            board.offerDiff().filter { it.card == "Quantum Riddler" }.map { Triple(it.bucket, it.alternativeCost, it.rule) } shouldBe
                listOf(
                    Triple(OfferDiffBucket.OfferedInactive, null, OfferDiffRule.InactiveUnaffordable),
                    Triple(OfferDiffBucket.ProjectionDrop, "Warp", OfferDiffRule.UnaffordableNotShown),
                )
        }
    })
