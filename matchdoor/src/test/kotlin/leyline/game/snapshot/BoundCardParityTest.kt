package leyline.game.snapshot

import forge.card.GamePieceType
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.types.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.game.data.KeywordAbilityIds

/**
 * Phase 0 parity gate for the BoundCard migration (S2.A in `leyline-y3pf`).
 *
 * Verifies that the per-card bound view built alongside `objects` agrees with
 * direct `cardRepository.findByGrpId` lookups: same cardinality, same grpId
 * binding, no off-by-one keying. Disposable — deleted at Phase 7 once
 * `CardSnapshot` retires and BoundCard becomes the sole per-card type.
 */
class BoundCardParityTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("boundCards has one entry per object, each matching the repository's findByGrpId") {
            val (b, game, _) =
                base.startWithBoard { _, human, opp ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    base.addCard("Llanowar Elves", human, ZoneType.Hand)
                    base.addCard("Lightning Bolt", opp, ZoneType.Hand)
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
                    bound.data.shouldNotBeNull()
                    bound.data?.grpId shouldBe cardSnap.grpId
                }
            }
        }

        test("EFFECT pieces with grpId=0 get null CardData on the bound view") {
            val (b, game, _) =
                base.startWithBoard { g, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)

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
            realBound.data.shouldNotBeNull()
            realBound.data?.grpId shouldBe realSnap.grpId
        }

        test("designations mirror CardSnapshot's per-role state") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    // Plain creature — no designations.
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            // Every BoundCard's DesignationSet must mirror the underlying
            // CardSnapshot fields verbatim — the wrapper struct is the
            // substrate for the future S3.B registry, so loss-free copying
            // is load-bearing.
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
                base.startWithBoard { _, human, _ ->
                    val bear = base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    val aura = base.addCard("Pacifism", human, ZoneType.Battlefield)
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

        test("altCosts pre-resolves Mobilize via either direct match or BaseId chain") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            // Sanity baseline: Grizzly Bears carries no alt-cost rows.
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
