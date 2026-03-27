package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer

class AbilityWordScannerTest :
    FunSpec({

        tags(UnitTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Threshold creature emits AbilityWordActive with GY count") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
            }
            val human = game.humanPlayer
            val scavenger = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Dreadwing Scavenger" }
            val iid = b.getOrAllocInstanceId(ForgeCardId(scavenger.id)).value

            val results = AbilityWordScanner.scan(
                battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                player = human,
                instanceIdResolver = { fid -> b.getOrAllocInstanceId(fid) },
                registryResolver = { card ->
                    val grpId = b.cards.findGrpIdByName(card.name) ?: 0
                    val cardData = b.cards.findByGrpId(grpId)
                    b.abilityRegistryFor(card, cardData)
                },
            )

            results shouldHaveSize 1
            val r = results[0]
            r.instanceId shouldBe iid
            r.abilityWordName shouldBe "Threshold"
            r.value shouldBe 5
            r.threshold shouldBe 7
        }

        test("no ability word cards returns empty") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer

            val results = AbilityWordScanner.scan(
                battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                player = human,
                instanceIdResolver = { fid -> b.getOrAllocInstanceId(fid) },
                registryResolver = { _ -> null },
            )

            results.shouldBeEmpty()
        }
    })
