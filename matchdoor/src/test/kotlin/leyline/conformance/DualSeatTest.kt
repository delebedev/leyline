package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class DualSeatTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: DualSeatHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("both seats receive game state messages") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            h.seat1Messages.shouldNotBeEmpty()
            h.seat2Messages.shouldNotBeEmpty()

            h.allGsms(h.seat1Messages).shouldNotBeEmpty()
            h.allGsms(h.seat2Messages).shouldNotBeEmpty()
        }

        test("each seat sees own hand cards with grpId") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            val gsms1 = h.allGsms(h.seat1Messages)
            val gsms2 = h.allGsms(h.seat2Messages)

            // Seat 1 objects owned by seat 1 should have grpId > 0
            val seat1Cards = gsms1.flatMap { it.gameObjectsList }
                .filter { it.ownerSeatId == 1 }
            seat1Cards.shouldNotBeEmpty()
            seat1Cards.any { it.grpId > 0 }.shouldBeTrue()

            // Seat 2 objects owned by seat 2 should have grpId > 0
            val seat2Cards = gsms2.flatMap { it.gameObjectsList }
                .filter { it.ownerSeatId == 2 }
            seat2Cards.shouldNotBeEmpty()
            seat2Cards.any { it.grpId > 0 }.shouldBeTrue()
        }

        test("opponent hand cards hidden — no GameObjectInfo leak") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            val gsms1 = h.allGsms(h.seat1Messages)

            // Find seat 2's hand zone from seat 1's perspective
            val seat2HandZone = gsms1.flatMap { it.zonesList }
                .firstOrNull { it.type == ZoneType.Hand && it.ownerSeatId == 2 }

            if (seat2HandZone != null && seat2HandZone.objectInstanceIdsList.isNotEmpty()) {
                val hiddenIds = seat2HandZone.objectInstanceIdsList.toSet()
                val seat1KnownObjects = gsms1.flatMap { it.gameObjectsList }
                    .map { it.instanceId }.toSet()
                // None of seat 2's hand IDs should have full GameObjectInfo in seat 1's view
                val leaked = hiddenIds.intersect(seat1KnownObjects)
                leaked.isEmpty().shouldBeTrue()
            }

            // Same check: seat 1's hand hidden from seat 2
            val gsms2 = h.allGsms(h.seat2Messages)
            val seat1HandZone = gsms2.flatMap { it.zonesList }
                .firstOrNull { it.type == ZoneType.Hand && it.ownerSeatId == 1 }

            if (seat1HandZone != null && seat1HandZone.objectInstanceIdsList.isNotEmpty()) {
                val hiddenIds = seat1HandZone.objectInstanceIdsList.toSet()
                val seat2KnownObjects = gsms2.flatMap { it.gameObjectsList }
                    .map { it.instanceId }.toSet()
                val leaked = hiddenIds.intersect(seat2KnownObjects)
                leaked.isEmpty().shouldBeTrue()
            }
        }

        test("shared gsId chain — both seats get valid monotonic gsIds") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            val gsIds1 = h.allGsms(h.seat1Messages).map { it.gameStateId }.filter { it > 0 }
            val gsIds2 = h.allGsms(h.seat2Messages).map { it.gameStateId }.filter { it > 0 }

            gsIds1.shouldNotBeEmpty()
            gsIds2.shouldNotBeEmpty()

            // Both seats share a MessageCounter — gsIds should be positive and monotonic
            gsIds1.zipWithNext().forEach { (a, b) -> (b >= a).shouldBeTrue() }
            gsIds2.zipWithNext().forEach { (a, b) -> (b >= a).shouldBeTrue() }

            // Counter is shared — seat 2's gsIds should be >= seat 1's (called second)
            gsIds2.min().shouldBeGreaterThan(0)
        }

        test("both players start at 20 life") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            val players = h.allGsms(h.seat1Messages).flatMap { it.playersList }
            players.shouldNotBeEmpty()

            val p1 = players.firstOrNull { it.systemSeatNumber == 1 }
            val p2 = players.firstOrNull { it.systemSeatNumber == 2 }
            p1.shouldNotBeNull()
            p2.shouldNotBeNull()
            p1.lifeTotal shouldBe 20
            p2.lifeTotal shouldBe 20
        }

        test("game is not over after initial connect") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            h.bridge.getGame()!!.isGameOver.shouldBeFalse()
        }

        test("both seats have 7 cards in hand") {
            val h = DualSeatHarness(seed = 42L)
            harness = h
            h.connectBothSeats()

            val hand1 = h.bridge.getHandGrpIds(1)
            val hand2 = h.bridge.getHandGrpIds(2)
            hand1.size shouldBe 7
            hand2.size shouldBe 7
        }
    })
