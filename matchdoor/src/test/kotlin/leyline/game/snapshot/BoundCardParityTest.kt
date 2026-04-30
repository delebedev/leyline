package leyline.game.snapshot

import forge.card.GamePieceType
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.types.ForgeCardId
import leyline.conformance.ConformanceTestBase

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
    })
