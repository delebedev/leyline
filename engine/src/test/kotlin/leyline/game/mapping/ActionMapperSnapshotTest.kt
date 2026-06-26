package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.types.ForgeCardId
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTestBase
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Verifies that [ActionMapper.buildFromSnapshot] produces correct action shapes
 * for representative board states.
 *
 * Uses [BoardTestBase.startWithBoard] — synchronous board setup, no game loop.
 * Cost-legality routes through the live Forge bridge, so these are BoardTag tests.
 */
class ActionMapperSnapshotTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // -----------------------------------------------------------------------
        // Test 1: empty hand + battlefield → Pass + FloatMana only
        // -----------------------------------------------------------------------

        test("empty hand and battlefield yields only Pass and FloatMana") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            assertSoftly {
                fromSnap.actionsList.any { it.actionType == ActionType.Pass }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.FloatMana }.shouldBeTrue()
                fromSnap.actionsList
                    .none {
                        it.actionType == ActionType.Cast ||
                            it.actionType == ActionType.Play_add3 ||
                            it.actionType == ActionType.ActivateMana
                    }.shouldBeTrue()
                fromSnap.inactiveActionsCount shouldBe 0
            }
        }

        // -----------------------------------------------------------------------
        // Test 2: land in hand → Play action appears (active or inactive)
        // -----------------------------------------------------------------------

        test("land in hand — Play action present") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val hasPlay =
                fromSnap.actionsList.any { it.actionType == ActionType.Play_add3 } ||
                    fromSnap.inactiveActionsList.any { it.actionType == ActionType.Play_add3 }
            hasPlay.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 3: non-land spell in hand — Cast action present
        // -----------------------------------------------------------------------

        test("non-land spell in hand — Cast action present (active or inactive)") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Llanowar Elves", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val hasCast =
                fromSnap.actionsList.any { it.actionType == ActionType.Cast } ||
                    fromSnap.inactiveActionsList.any { it.actionType == ActionType.Cast }
            hasCast.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 4: untapped land on battlefield → ActivateMana present
        // -----------------------------------------------------------------------

        test("untapped land on battlefield — ActivateMana present") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            fromSnap.actionsList.count { it.actionType == ActionType.ActivateMana } shouldBe 1
        }

        test("battlefield activated ability carries matching uniqueAbilityId") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Tavern Swindler", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            val activate = fromSnap.actionsList.first { it.actionType == ActionType.Activate_add3 }

            assertSoftly {
                activate.abilityGrpId shouldBe 19490
                activate.uniqueAbilityId shouldBe 50
            }
        }

        test("snow-costed activated ability carries snow mana cost") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Ascendant Spirit", human, ZoneType.Battlefield)
                    base.addCard("Snow-Covered Island", human, ZoneType.Battlefield)
                    base.addCard("Snow-Covered Island", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            val activate = fromSnap.actionsList.first { it.actionType == ActionType.Activate_add3 }
            val snowMana =
                fromSnap.actionsList
                    .asSequence()
                    .filter { it.actionType == ActionType.ActivateMana }
                    .flatMap { it.manaPaymentOptionsList.asSequence() }
                    .flatMap { it.manaList.asSequence() }
                    .first { mana -> mana.specsList.any { it.type == ManaSpecType.FromSnow } }
            val autoTapMana =
                activate.autoTapSolution.autoTapActionsList.flatMap { it.manaPaymentOption.manaList }

            assertSoftly {
                activate.abilityGrpId shouldBe 139877
                activate should haveManaCost(snow = 2)
                activate.hasAutoTapSolution().shouldBeTrue()
                autoTapMana.map { it.color } shouldBe listOf(ManaColor.Blue_afc9, ManaColor.Blue_afc9)
                autoTapMana.map { mana -> mana.specsList.map { it.type } } shouldBe
                    listOf(
                        listOf(ManaSpecType.Predictive, ManaSpecType.FromSnow),
                        listOf(ManaSpecType.Predictive, ManaSpecType.FromSnow),
                    )
                snowMana.color shouldBe ManaColor.Blue_afc9
                snowMana.specsList.map { it.type } shouldBe listOf(ManaSpecType.Predictive, ManaSpecType.FromSnow)
            }
        }

        // -----------------------------------------------------------------------
        // Test 5: affordable spell → Cast in active list
        // -----------------------------------------------------------------------

        test("affordable Llanowar Elves — Cast in active actions") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Llanowar Elves", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            assertSoftly {
                fromSnap.actionsCount shouldBe 4 // ActivateMana + Cast + Pass + FloatMana
                fromSnap.actionsList.any { it.actionType == ActionType.Cast }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.ActivateMana }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.Pass }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.FloatMana }.shouldBeTrue()
            }
        }

        // -----------------------------------------------------------------------
        // Test 6: CardSnapshot isLand flag is set correctly
        // -----------------------------------------------------------------------

        test("CardSnapshot.isLand is true for lands, false for non-lands") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Hand)
                    base.addCard("Llanowar Elves", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val humanCards = game.humanPlayer.getZone(ZoneType.Hand).cards

            val islandFid = ForgeCardId(humanCards.first { it.name == "Island" }.id)
            val elvesFid = ForgeCardId(humanCards.first { it.name == "Llanowar Elves" }.id)

            snap.objects[islandFid]?.isLand shouldBe true
            snap.objects[elvesFid]?.isLand shouldBe false
        }
    })
