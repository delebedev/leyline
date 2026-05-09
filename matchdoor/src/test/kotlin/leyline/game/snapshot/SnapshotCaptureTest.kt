package leyline.game.snapshot

import forge.card.GamePieceType
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ZoneIds
import leyline.testkit.BoardTestBase
import leyline.testkit.humanPlayer

class SnapshotCaptureTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Puzzle-Goal-style EFFECT in Command zone: snapshot captures with grpId=0, no throw") {
            val (b, game, _) =
                base.startWithBoard { g, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)

                    // Mirror forge.gamemodes.puzzle.Puzzle — synthetic engine goal card.
                    // forgeId=-1 signals "not a real card"; GamePieceType.EFFECT signals
                    // "engine bookkeeping, no wire identity".
                    val goal = Card(-1, g)
                    goal.owner = human
                    goal.name = "Puzzle Goal"
                    goal.gamePieceType = GamePieceType.EFFECT
                    human.getZone(ZoneType.Command).add(goal)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            val goalFid = ForgeCardId(-1)
            snap.objects[goalFid]?.grpId shouldBe 0
            snap.objects[goalFid]?.name shouldBe "Puzzle Goal"

            // Sanity: real card still resolves to a real grpId.
            val bearsCard =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val bearsSnap = snap.objects.getValue(ForgeCardId(bearsCard.id))
            bearsSnap.grpId shouldBeGreaterThan 0
        }

        test("Effect helper with source is omitted from snapshot zones and objects") {
            val (b, game, _) =
                base.startWithBoard { g, human, _ ->
                    val source = base.addCard("Grizzly Bears", human, ZoneType.Graveyard)

                    val helper = Card(198, g)
                    helper.owner = human
                    helper.name = "Grizzly Bears's Effect"
                    helper.gamePieceType = GamePieceType.EFFECT
                    helper.setEffectSource(source)
                    human.getZone(ZoneType.Battlefield).add(helper)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val helperFid = ForgeCardId(198)

            assertSoftly {
                snap.zones.getValue(ZoneIds.BATTLEFIELD).contents shouldBe emptyList()
                snap.zones.getValue(ZoneIds.BATTLEFIELD).contents shouldNotContain helperFid
                snap.objects.keys shouldNotContain helperFid
            }
        }
    })
