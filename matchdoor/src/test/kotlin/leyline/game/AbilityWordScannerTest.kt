package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer

class AbilityWordScannerTest :
    FunSpec({

        tags(ConformanceTag)

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

        test("Morbid card with dead creature emits per-player AbilityWordEntry") {
            val (b, game, _) = base.startWithBoard { _, human, ai ->
                base.addCard("Cackling Prowler", human, ZoneType.Battlefield)
                base.addCard("Grizzly Bears", ai, ZoneType.Battlefield)
            }
            val human = game.humanPlayer
            val prowler = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Cackling Prowler" }
            val prowlerIid = b.getOrAllocInstanceId(ForgeCardId(prowler.id)).value

            // Kill the AI creature to trigger morbid condition
            val ai = game.registeredPlayers.find { it != human }!!
            val bear = ai.getZone(ZoneType.Battlefield).cards.first()
            game.action.moveToGraveyard(bear, null)

            val allBf = game.registeredPlayers.flatMap { it.getZone(ZoneType.Battlefield).cards.toList() }
            val results = AbilityWordScanner.scan(
                battlefieldCards = allBf,
                instanceIdResolver = { fid -> b.getOrAllocInstanceId(fid) },
                registryResolver = { card ->
                    val grpId = b.cards.findGrpIdByName(card.name) ?: 0
                    b.abilityRegistryFor(card, b.cards.findByGrpId(grpId))
                },
            )

            val morbidEntry = results.firstOrNull { it.abilityWordName == "Morbid" }
            morbidEntry.shouldNotBeNull()
            morbidEntry.affectorId shouldBe 1 // human seatId
            morbidEntry.affectedIds shouldContain prowlerIid
            morbidEntry.value shouldBe null // boolean-only
            morbidEntry.threshold shouldBe null // boolean-only
        }

        test("Morbid card with no creature death emits no entry") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Cackling Prowler", human, ZoneType.Battlefield)
            }

            val allBf = game.registeredPlayers.flatMap { it.getZone(ZoneType.Battlefield).cards.toList() }
            val results = AbilityWordScanner.scan(
                battlefieldCards = allBf,
                instanceIdResolver = { fid -> b.getOrAllocInstanceId(fid) },
                registryResolver = { _ -> null },
            )

            results.filter { it.abilityWordName == "Morbid" }.shouldBeEmpty()
        }

        test("Morbid and Threshold on same scan does not interfere") {
            val (b, game, _) = base.startWithBoard { _, human, ai ->
                base.addCard("Cackling Prowler", human, ZoneType.Battlefield)
                base.addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
                base.addCard("Grizzly Bears", ai, ZoneType.Battlefield)
            }
            val human = game.humanPlayer
            val ai = game.registeredPlayers.find { it != human }!!
            val bear = ai.getZone(ZoneType.Battlefield).cards.first()
            game.action.moveToGraveyard(bear, null)

            val allBf = game.registeredPlayers.flatMap { it.getZone(ZoneType.Battlefield).cards.toList() }
            val results = AbilityWordScanner.scan(
                battlefieldCards = allBf,
                instanceIdResolver = { fid -> b.getOrAllocInstanceId(fid) },
                registryResolver = { card ->
                    val grpId = b.cards.findGrpIdByName(card.name) ?: 0
                    b.abilityRegistryFor(card, b.cards.findByGrpId(grpId))
                },
            )

            results.count { it.abilityWordName == "Morbid" } shouldBe 1
            results.count { it.abilityWordName == "Threshold" } shouldBe 1
        }

        test("no ability word cards returns empty") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer

            val results = AbilityWordScanner.scan(
                battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                instanceIdResolver = { fid -> b.getOrAllocInstanceId(fid) },
                registryResolver = { _ -> null },
            )

            results.shouldBeEmpty()
        }
    })
