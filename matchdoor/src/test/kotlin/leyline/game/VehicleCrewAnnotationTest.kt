package leyline.game

import forge.game.card.CardCollection
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase
import leyline.conformance.detailInt
import leyline.conformance.gsm
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType

/**
 * Vehicle/crew annotation tests — verifies live card type overlay in GSM objects
 * and crew-related persistent annotations (CrewedThisTurn, ModifiedType+LayeredEffect).
 */
class VehicleCrewAnnotationTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // --- Task 1: Type overlay tests (existing) ---

        test("vehicle gains Creature type when crew adds Creature to Forge card type") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                val vehicle = base.addCard("Smuggler's Copter", human, ZoneType.Battlefield)
                vehicle.addType("Creature")
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

        // --- Task 2: CrewedThisTurn builder shape test ---

        test("crewedThisTurn builder produces correct annotation shape") {
            val ann = AnnotationBuilder.crewedThisTurn(
                vehicleInstanceId = 304,
                crewSourceInstanceIds = listOf(293, 348),
            )

            ann.typeList shouldHaveSize 1
            ann.typeList shouldContain AnnotationType.CrewedThisTurn
            ann.affectorId shouldBe 304
            ann.affectedIdsList shouldBe listOf(293, 348)
            ann.detailsList shouldHaveSize 0
        }

        // --- Task 3: ModifiedType+LayeredEffect builder shape test ---

        test("modifiedTypeLayeredEffect builder produces correct annotation shape") {
            val ann = AnnotationBuilder.modifiedTypeLayeredEffect(
                instanceId = 304,
                effectId = 7004,
                sourceAbilityGrpId = 76611,
            )

            ann.typeList shouldHaveSize 2
            ann.typeList shouldContain AnnotationType.ModifiedType
            ann.typeList shouldContain AnnotationType.LayeredEffect
            ann.affectedIdsList shouldBe listOf(304)
            ann.detailInt(DetailKeys.EFFECT_ID) shouldBe 7004
            ann.detailInt(DetailKeys.SOURCE_ABILITY_GRPID) shouldBe 76611
        }

        test("modifiedTypeLayeredEffect without sourceAbilityGrpId omits that detail") {
            val ann = AnnotationBuilder.modifiedTypeLayeredEffect(
                instanceId = 200,
                effectId = 7010,
            )

            ann.typeList shouldContain AnnotationType.ModifiedType
            ann.typeList shouldContain AnnotationType.LayeredEffect
            ann.detailInt(DetailKeys.EFFECT_ID) shouldBe 7010
            ann.detailsList.none { it.key == DetailKeys.SOURCE_ABILITY_GRPID } shouldBe true
        }

        // --- Task 2+3: Pipeline integration — crew state produces persistent annotations ---

        test("crewed vehicle produces CrewedThisTurn persistent annotation in GSM") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                val vehicle = base.addCard("Smuggler's Copter", human, ZoneType.Battlefield)
                val crew1 = base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                val crew2 = base.addCard("Elvish Mystic", human, ZoneType.Battlefield)

                // Simulate crew: add Creature type + set crewedByThisTurn
                vehicle.addType("Creature")
                vehicle.addCrewedByThisTurn(CardCollection(listOf(crew1, crew2)))
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            val crewedAnn = gs.persistentAnnotationsList.firstOrNull { ann ->
                AnnotationType.CrewedThisTurn in ann.typeList
            }
            crewedAnn shouldNotBe null
            crewedAnn!!.affectedIdsList shouldHaveSize 2
        }

        test("crewed vehicle-creature produces ModifiedType+LayeredEffect persistent annotation") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                val vehicle = base.addCard("Smuggler's Copter", human, ZoneType.Battlefield)
                val crew = base.addCard("Grizzly Bears", human, ZoneType.Battlefield)

                vehicle.addType("Creature")
                vehicle.addCrewedByThisTurn(CardCollection(listOf(crew)))
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            val typeAnn = gs.persistentAnnotationsList.firstOrNull { ann ->
                AnnotationType.ModifiedType in ann.typeList &&
                    AnnotationType.LayeredEffect in ann.typeList
            }
            typeAnn shouldNotBe null
            typeAnn!!.detailInt(DetailKeys.EFFECT_ID) shouldBeGreaterThan 0
        }

        test("uncrewed vehicle produces no crew persistent annotations") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                base.addCard("Smuggler's Copter", human, ZoneType.Battlefield)
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            gs.persistentAnnotationsList.none { ann ->
                AnnotationType.CrewedThisTurn in ann.typeList
            } shouldBe true
            gs.persistentAnnotationsList.none { ann ->
                AnnotationType.ModifiedType in ann.typeList
            } shouldBe true
        }

        test("crew effect expiry removes ModifiedType persistent annotation") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                val vehicle = base.addCard("Smuggler's Copter", human, ZoneType.Battlefield)
                val crew = base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                vehicle.addType("Creature")
                vehicle.addCrewedByThisTurn(CardCollection(listOf(crew)))
            }

            // First GSM: crew active
            val gs1 = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b)
            gs1.gsm.persistentAnnotationsList.any { ann ->
                AnnotationType.ModifiedType in ann.typeList
            } shouldBe true

            // Simulate end of turn: crew expires — remove Creature type + clear crewedByThisTurn
            val vehicle = game.registeredPlayers.first()
                .getZone(ZoneType.Battlefield).cards.first { it.type.hasSubtype("Vehicle") }
            (vehicle.currentState.type as forge.card.CardType).remove("Creature")
            vehicle.resetCrewed()

            // Second GSM: crew expired
            val gs2 = StateMapper.buildFromGame(game, 2, ConformanceTestBase.TEST_MATCH_ID, b)
            gs2.gsm.persistentAnnotationsList.none { ann ->
                AnnotationType.ModifiedType in ann.typeList
            } shouldBe true
        }
    })
