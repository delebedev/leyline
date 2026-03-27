package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.CardType

/**
 * Vehicle/crew annotation tests — verifies live card type overlay in GSM objects.
 *
 * When Forge's continuous effects change a card's type (e.g. crew making a vehicle
 * into an artifact creature), the proto must reflect the live state, not just the
 * static DB types.
 */
class VehicleCrewAnnotationTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("vehicle gains Creature type when crew adds Creature to Forge card type") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                val vehicle = base.addCard("Smuggler's Copter", human, ZoneType.Battlefield)
                // Simulate crew effect: Forge adds Creature to the card's type
                vehicle.addType("Creature")
                // Crew also grants P/T — Forge handles this via the card's netPower/netToughness
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            val vehicleObj = gs.gameObjectsList.first { obj ->
                obj.zoneId == ZoneIds.BATTLEFIELD &&
                    obj.cardTypesList.contains(CardType.Artifact_a80b)
            }

            vehicleObj.cardTypesList shouldContain CardType.Creature
            vehicleObj.cardTypesList shouldContain CardType.Artifact_a80b
        }

        test("non-vehicle artifact does not gain Creature type") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                base.addCard("Sol Ring", human, ZoneType.Battlefield)
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            val artifactObj = gs.gameObjectsList.first { obj ->
                obj.zoneId == ZoneIds.BATTLEFIELD &&
                    obj.cardTypesList.contains(CardType.Artifact_a80b)
            }

            artifactObj.cardTypesList shouldNotContain CardType.Creature
        }

        test("creature on battlefield has correct type without overlay changes") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            val creatureObj = gs.gameObjectsList.first { obj ->
                obj.zoneId == ZoneIds.BATTLEFIELD &&
                    obj.cardTypesList.contains(CardType.Creature)
            }

            creatureObj.cardTypesList shouldContain CardType.Creature
            creatureObj.power.value shouldBe 2
            creatureObj.toughness.value shouldBe 2
        }
    })
