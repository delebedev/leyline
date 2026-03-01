package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.game.GameBridge
import leyline.server.ListMessageSink
import leyline.server.MatchRegistry
import leyline.server.MatchSession

class MatchRegistryTest :
    FunSpec({

        test("getOrCreateBridge creates on first call, reuses on second") {
            val registry = MatchRegistry()
            var created = 0
            val b1 = registry.getOrCreateBridge("m1") {
                created++
                GameBridge()
            }
            val b2 = registry.getOrCreateBridge("m1") {
                created++
                GameBridge()
            }
            b1 shouldBeSameInstanceAs b2
            created shouldBe 1
        }

        test("registerSession + getPeer returns correct session") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val s1 = MatchSession(seatId = 1, matchId = "m1", sink = sink, registry = registry, paceDelayMs = 0)
            val s2 = MatchSession(seatId = 2, matchId = "m1", sink = sink, registry = registry, paceDelayMs = 0)
            registry.registerSession("m1", 1, s1)
            registry.getPeer("m1", 1).shouldBeNull()
            registry.registerSession("m1", 2, s2)
            registry.getPeer("m1", 1) shouldBeSameInstanceAs s2
        }

        test("evictStale removes old match entries") {
            val registry = MatchRegistry()
            registry.getOrCreateBridge("old-match") { GameBridge() }
            registry.getOrCreateBridge("current") { GameBridge() }
            val evicted = registry.evictStale("current")
            evicted.size shouldBe 1
        }
    })
