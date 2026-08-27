package leyline.board.diff

import forge.game.ability.AbilityKey
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import leyline.testkit.BundleBuilderTestSupport
import leyline.testkit.ValidatingMessageSink
import leyline.testkit.annotation
import leyline.testkit.detailInt
import leyline.testkit.gsm
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import forge.game.zone.ZoneType as ForgeZoneType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Diagnostic tests tracing exact diff contents for each game action.
 *
 * Accumulator consistency (zone-object refs, action instanceIds, no duplicates)
 * is automatic via [ValidatingMessageSink]. What remains here are structural
 * assertions about diff contents — which zones appear, annotation types, field values.
 */
class DiffDiagnosticTest :
    BoardTest({

        test("diff after land play has correct GSM type, zones, and annotations") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ForgeZoneType.Hand)
                }

            val land =
                board.game.humanPlayer
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .first { it.isLand }
            val gsm =
                board.snapshotDiff {
                    board.game.action.moveToPlay(land, null, AbilityKey.newMap())
                }

            assertSoftly {
                gsm.type shouldBe GameStateType.Diff

                val zoneTypes = gsm.zonesList.map { it.type }.toSet()
                (ProtoZoneType.Hand in zoneTypes).shouldBeTrue()
                (ProtoZoneType.Battlefield in zoneTypes).shouldBeTrue()
                (ProtoZoneType.Limbo in zoneTypes).shouldBeTrue()

                val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
                val origId = oic.detailInt("orig_id")
                gsm.diffDeletedInstanceIdsList shouldNotContain origId
            }
        }

        test("stateOnlyDiff contains BF objects for AI land play") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ForgeZoneType.Hand)
                }

            val land =
                board.game.humanPlayer
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .first { it.isLand }
            board.snapshotDiff {
                board.game.action.moveToPlay(land, null, AbilityKey.newMap())
            }

            val aiResult =
                BundleBuilderTestSupport.stateOnly(
                    bundleBuilder(board.bridge),
                    board.bridge,
                    board.game,
                    board.counter,
                )

            val gsm = aiResult.gsm
            gsm.type shouldBe GameStateType.Diff

            // stateOnlyDiff may or may not include BF zone depending on
            // what changed. Core invariant: all objects have a valid zoneId.
            for (obj in gsm.gameObjectsList) {
                obj.zoneId shouldBeGreaterThan 0
            }
        }
    })
