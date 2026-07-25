package leyline.game.snapshot

import forge.card.GamePieceType
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.BoardTest

/**
 * Pins that BoundCard's per-card bound view agrees with direct
 * `cardRepository` lookups — same cardinality, same grpId binding,
 * designation fields mirror the underlying snapshot, parent linkage
 * materializes for attachments. Catches binding regressions at the
 * snapshot/projection boundary.
 */
class BoundCardParityTest :
    BoardTest({

        test("boundCards has one entry per object, each matching the repository's findByGrpId") {
            val (b, game, _) =
                startWithBoard { _, human, opp ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Llanowar Elves", human, ZoneType.Hand)
                    addCard("Lightning Bolt", opp, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            // Cardinality: every snapshot has a paired bound view, same key set.
            snap.boundCards.keys.shouldContainExactlyInAnyOrder(snap.objects.keys.toList())

            // Per-card binding: BoundCard.snapshot is the same instance, and
            // BoundCard.data agrees with what the repository would return live.
            for ((fid, cardSnap) in snap.objects) {
                val bound = snap.boundCards[fid].shouldNotBeNull()
                bound.forgeCardId shouldBe fid
                bound.snapshot shouldBe cardSnap

                val expected =
                    if (cardSnap.grpId > 0) b.cardRepository.findByGrpId(cardSnap.grpId) else null
                bound.data shouldBe expected
                if (cardSnap.grpId > 0) {
                    bound.data.shouldNotBeNull().grpId shouldBe cardSnap.grpId
                }
            }
        }

        test("EFFECT pieces with grpId=0 get null CardData on the bound view") {
            val (b, game, _) =
                startWithBoard { g, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)

                    // Synthetic engine goal — mirrors the SnapshotCapture EFFECT case.
                    val goal = Card(-1, g)
                    goal.owner = human
                    goal.name = "Puzzle Goal"
                    goal.gamePieceType = GamePieceType.EFFECT
                    human.getZone(ZoneType.Command).add(goal)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            val goalFid = ForgeCardId(-1)
            val goalBound = snap.boundCards[goalFid].shouldNotBeNull()
            goalBound.snapshot.grpId shouldBe 0
            goalBound.data.shouldBeNull()

            // Sanity — a real card next to it still binds.
            val realFid = snap.objects.keys.first { it != goalFid }
            val realSnap = snap.objects.getValue(realFid)
            realSnap.grpId shouldBeGreaterThan 0
            val realBound = snap.boundCards.getValue(realFid)
            realBound.data.shouldNotBeNull().grpId shouldBe realSnap.grpId
        }

        test("designations mirror CardSnapshot's per-role state") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Plain creature — no designations.
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            // Every BoundCard's DesignationSet must mirror the underlying
            // CardSnapshot fields verbatim — loss-free copying is load-bearing
            // for downstream pattern-matches over the role hierarchy.
            for (bound in snap.boundCards.values) {
                val cardSnap = bound.snapshot
                assertSoftly {
                    bound.designations.prepared shouldBe cardSnap.preparedRole
                    bound.designations.plotted shouldBe cardSnap.plottedRole
                    bound.designations.foretold shouldBe cardSnap.isForetold
                }
            }
        }

        test("parentLinkage resolves AttachedTo for an aura on a permanent") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    val bear = addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    val aura = addCard("Pacifism", human, ZoneType.Battlefield)
                    aura.attachToEntity(bear, null, true)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            val auraBound =
                snap.boundCards.values
                    .firstOrNull {
                        it.snapshot.name == "Pacifism"
                    }.shouldNotBeNull()
            val linkage = auraBound.parentLinkage.shouldNotBeNull()
            (linkage is ParentLinkage.AttachedTo).shouldBeTrue()

            // The carrier permanent's instanceId should match what the
            // attached card sees as its parent.
            val bearBound =
                snap.boundCards.values
                    .firstOrNull {
                        it.snapshot.name == "Grizzly Bears"
                    }.shouldNotBeNull()
            val bearIid = b.getOrAllocInstanceId(bearBound.forgeCardId).value
            linkage.parentInstanceId shouldBe bearIid

            // A non-attached card has no parent linkage.
            bearBound.parentLinkage.shouldBeNull()
        }

        test("non-Mobilize cards carry no Mobilize alt-cost row and no cleanup") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            val bearsBound =
                snap.boundCards.values
                    .firstOrNull {
                        it.snapshot.name == "Grizzly Bears"
                    }.shouldNotBeNull()
            bearsBound.altCosts
                .none {
                    it.keywordBaseId == KeywordAbilityIds.MOBILIZE
                }.shouldBeTrue()
            bearsBound.mobilizeCleanup.shouldBeNull()
        }
    })
