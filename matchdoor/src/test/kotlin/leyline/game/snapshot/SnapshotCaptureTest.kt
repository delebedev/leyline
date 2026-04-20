package leyline.game.snapshot

import forge.card.GamePieceType
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.types.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer

class SnapshotCaptureTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
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

            // Would previously throw: DevCheck.fail in ObjectMapper.resolveGrpId
            // ("grpId=0 for 'Puzzle Goal' (forgeId=-1): not in client card DB").
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
    })
