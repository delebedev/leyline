package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.UnitTag
import leyline.game.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.Match
import leyline.match.MatchRegistry
import leyline.match.MatchSession

class MatchRegistryTest :
    FunSpec({

        tags(UnitTag)

        test("getOrCreateMatch creates on first call, reuses on second") {
            val registry = MatchRegistry()
            var created = 0
            val m1 = registry.getOrCreateMatch("m1") {
                created++
                Match("m1", GameBridge())
            }
            val m2 = registry.getOrCreateMatch("m1") {
                created++
                Match("m1", GameBridge())
            }
            m1 shouldBeSameInstanceAs m2
            created shouldBe 1
        }

        test("getBridge returns bridge from match") {
            val registry = MatchRegistry()
            val bridge = GameBridge()
            registry.getOrCreateMatch("m1") { Match("m1", bridge) }
            registry.getBridge("m1") shouldBeSameInstanceAs bridge
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
            registry.getOrCreateMatch("old-match") { Match("old-match", GameBridge()) }
            registry.getOrCreateMatch("current") { Match("current", GameBridge()) }
            val evicted = registry.evictStale("current")
            evicted.size shouldBe 1
        }
    })
