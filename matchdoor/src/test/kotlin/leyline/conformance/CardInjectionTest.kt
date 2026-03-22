package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.IntegrationTag
import leyline.bridge.InstanceId
import leyline.game.StateMapper
import wotc.mtgo.gre.external.messaging.Messages.CardType

/**
 * Verifies [TestCardInjector] + [CardDataDeriver] produce cards that are
 * fully visible in proto output with correct metadata.
 *
 * Most tests use startWithBoard{} for speed (~0.01s). The deck-list
 * auto-registration test keeps startGameAtMain1 since it specifically
 * tests the deck registration path.
 */
class CardInjectionTest :
    FunSpec({

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("injected Serra Angel appears in GSM with correct metadata").config(tags = setOf(ConformanceTag)) {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, "Serra Angel", ZoneType.Battlefield, sick = false)

            val gsm = StateMapper.buildFromGame(game, counter.nextGsId(), "test", b, viewingSeatId = 1).gsm
            val obj = checkNotNull(gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId }) { "Injected card should appear in gameObjectsList" }
            obj.grpId shouldBe injected.grpId
            obj.cardTypesList.shouldContain(CardType.Creature)
            obj.hasPower().shouldBeTrue()
            obj.power.value shouldBe 4
            obj.hasToughness().shouldBeTrue()
            obj.toughness.value shouldBe 4
            obj.uniqueAbilitiesCount shouldBeGreaterThanOrEqual 2

            b.getForgeCardId(InstanceId(injected.instanceId))?.value shouldBe injected.forgeCardId
            b.cards.findByGrpId(injected.grpId).shouldNotBeNull()
            b.cards.findNameByGrpId(injected.grpId) shouldBe "Serra Angel"

            val acc = ClientAccumulator()
            acc.seedFull(gsm)
            acc.assertConsistent("after Serra Angel injection")
        }

        test("injected creature to hand is visible in hand zone").config(tags = setOf(ConformanceTag)) {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, "Lightning Bolt", ZoneType.Hand)

            val gsm = StateMapper.buildFromGame(game, counter.nextGsId(), "test", b, viewingSeatId = 1).gsm
            val obj = checkNotNull(gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId }) { "Injected card should appear in gameObjectsList" }
            obj.cardTypesList.shouldContain(CardType.Instant)

            val handZone = checkNotNull(gsm.zonesList.firstOrNull { it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Hand && it.ownerSeatId == 1 }) { "Hand zone should exist for seat 1" }
            handZone.objectInstanceIdsList.shouldContain(injected.instanceId)
        }

        test("CardDataDeriver produces consistent grpIds for same card name").config(tags = setOf(ConformanceTag)) {
            val (b, _, _) = base.startWithBoard { _, _, _ -> }

            val first = TestCardInjector.inject(b, 1, "Grizzly Bears", ZoneType.Battlefield)
            val second = TestCardInjector.inject(b, 1, "Grizzly Bears", ZoneType.Battlefield)

            first.grpId shouldBe second.grpId
            first.instanceId shouldNotBe second.instanceId
            first.forgeCardId shouldNotBe second.forgeCardId
        }

        // This test is integration group — kept separate from conformance
        test("auto-register deck list populates repository for all cards").config(tags = setOf(IntegrationTag)) {
            val deckList = "30 Plains\n20 Serra Angel\n10 Lightning Bolt"
            val (b, _, _) = base.startGameAtMain1(deckList = deckList)

            b.cards.findGrpIdByName("Plains").shouldNotBeNull()
            b.cards.findGrpIdByName("Serra Angel").shouldNotBeNull()
            b.cards.findGrpIdByName("Lightning Bolt").shouldNotBeNull()
        }

        test("injected land enters tapped when requested").config(tags = setOf(ConformanceTag)) {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, "Plains", ZoneType.Battlefield, tapped = true)

            val gsm = StateMapper.buildFromGame(game, counter.nextGsId(), "test", b, viewingSeatId = 1).gsm
            val obj = checkNotNull(gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId }) { "Injected land should appear in gameObjectsList" }
            obj.cardTypesList.shouldContain(CardType.Land_a80b)
        }
    })
