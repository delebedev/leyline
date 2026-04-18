package leyline.game.mapper

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer
import leyline.game.snapshot.SnapshotCapture
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Verifies that [ActionMapper.buildFromSnapshot] produces the same shape as
 * [ActionMapper.buildActions] for representative board states.
 *
 * Uses [ConformanceTestBase.startWithBoard] — synchronous board setup, no game loop.
 * Cost-legality routes through the live Forge bridge, so these are ConformanceTag tests.
 */
class ActionMapperSnapshotTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // -----------------------------------------------------------------------
        // Test 1: empty hand + battlefield → Pass + FloatMana only
        // -----------------------------------------------------------------------

        test("empty hand and battlefield yields only Pass and FloatMana") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            assertSoftly {
                fromSnap.actionsList.any { it.actionType == ActionType.Pass }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.FloatMana }.shouldBeTrue()
                fromSnap.actionsList.none {
                    it.actionType == ActionType.Cast ||
                        it.actionType == ActionType.Play_add3 ||
                        it.actionType == ActionType.ActivateMana
                }.shouldBeTrue()
                fromSnap.inactiveActionsCount shouldBe 0
            }
        }

        // -----------------------------------------------------------------------
        // Test 2: land in hand → inactive Play (can't play first land in Main1 w/o priority)
        //   Note: in startWithBoard the game is devMode MAIN1 but hasn't gone through
        //   advanceToMain1, so canPlayLand may be false — we assert the action shape matches
        //   legacy buildActions.
        // -----------------------------------------------------------------------

        test("land in hand — snapshot path matches legacy buildActions") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Island", human, ZoneType.Hand)
            }

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            val legacy = ActionMapper.buildActions(1, b)

            fromSnap.actionsList.map { it.actionType } shouldBe legacy.actionsList.map { it.actionType }
            fromSnap.inactiveActionsList.map { it.actionType } shouldBe
                legacy.inactiveActionsList.map { it.actionType }
        }

        // -----------------------------------------------------------------------
        // Test 3: non-land spell in hand — Cast shape matches legacy
        // -----------------------------------------------------------------------

        test("non-land spell in hand — snapshot Cast shape matches legacy") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Llanowar Elves", human, ZoneType.Hand)
            }

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            val legacy = ActionMapper.buildActions(1, b)

            // Cast action present in same slot (actions vs inactiveActions)
            val snapHasActiveCast = fromSnap.actionsList.any { it.actionType == ActionType.Cast }
            val legacyHasActiveCast = legacy.actionsList.any { it.actionType == ActionType.Cast }
            snapHasActiveCast shouldBe legacyHasActiveCast

            val snapHasInactiveCast = fromSnap.inactiveActionsList.any { it.actionType == ActionType.Cast }
            val legacyHasInactiveCast = legacy.inactiveActionsList.any { it.actionType == ActionType.Cast }
            snapHasInactiveCast shouldBe legacyHasInactiveCast
        }

        // -----------------------------------------------------------------------
        // Test 4: untapped land on battlefield → ActivateMana matches legacy
        // -----------------------------------------------------------------------

        test("untapped land on battlefield — ActivateMana matches legacy") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Island", human, ZoneType.Battlefield)
            }

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            val legacy = ActionMapper.buildActions(1, b)

            fromSnap.actionsList.count { it.actionType == ActionType.ActivateMana } shouldBe
                legacy.actionsList.count { it.actionType == ActionType.ActivateMana }
        }

        // -----------------------------------------------------------------------
        // Test 5: affordable spell → full snapshot == legacy (count + action types)
        // -----------------------------------------------------------------------

        test("affordable Llanowar Elves — full action lists match legacy") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Llanowar Elves", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            val legacy = ActionMapper.buildActions(1, b)

            assertSoftly {
                fromSnap.actionsCount shouldBe legacy.actionsCount
                fromSnap.inactiveActionsCount shouldBe legacy.inactiveActionsCount
                fromSnap.actionsList.map { it.actionType } shouldBe legacy.actionsList.map { it.actionType }
            }
        }

        // -----------------------------------------------------------------------
        // Test 6: CardSnapshot isLand flag is set correctly
        // -----------------------------------------------------------------------

        test("CardSnapshot.isLand is true for lands, false for non-lands") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Island", human, ZoneType.Hand)
                base.addCard("Llanowar Elves", human, ZoneType.Hand)
            }

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val humanCards = game.humanPlayer.getZone(ZoneType.Hand).cards

            val islandFid = ForgeCardId(humanCards.first { it.name == "Island" }.id)
            val elvesFid = ForgeCardId(humanCards.first { it.name == "Llanowar Elves" }.id)

            snap.objects[islandFid]?.isLand shouldBe true
            snap.objects[elvesFid]?.isLand shouldBe false
        }
    })
