package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.UnitTag
import leyline.game.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.Match
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import leyline.match.MatchState

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

        test("getMatch returns match or null") {
            val registry = MatchRegistry()
            registry.getMatch("nope").shouldBeNull()
            val m = registry.getOrCreateMatch("m1") { Match("m1", GameBridge()) }
            registry.getMatch("m1") shouldBeSameInstanceAs m
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

        test("evictStale removes old match entries and closes them") {
            val registry = MatchRegistry()
            val old = registry.getOrCreateMatch("old-match") { Match("old-match", GameBridge()) }
            registry.getOrCreateMatch("current") { Match("current", GameBridge()) }
            val evicted = registry.evictStale("current")
            evicted.size shouldBe 1
            old.state shouldBe MatchState.FINISHED
        }

        // --- Match lifecycle tests ---

        test("new Match starts in WAITING state") {
            val m = Match("m1", GameBridge())
            m.state shouldBe MatchState.WAITING
        }

        test("close() transitions to FINISHED and fires callback") {
            val m = Match("m1", GameBridge())
            val observed = mutableListOf<MatchState>()
            m.onStateChanged = { observed.add(it) }
            m.close()
            m.state shouldBe MatchState.FINISHED
            observed.shouldContainExactly(MatchState.FINISHED)
        }

        test("close() is idempotent — second call is a no-op") {
            val m = Match("m1", GameBridge())
            val observed = mutableListOf<MatchState>()
            m.onStateChanged = { observed.add(it) }
            m.close()
            m.close()
            m.state shouldBe MatchState.FINISHED
            observed.shouldContainExactly(MatchState.FINISHED) // only one callback
        }

        test("start() transitions WAITING -> RUNNING") {
            val m = Match("m1", GameBridge())
            val observed = mutableListOf<MatchState>()
            m.onStateChanged = { observed.add(it) }
            m.state shouldBe MatchState.WAITING
            // start() will transition state then call bridge.start() which is a no-op on a bare GameBridge
            m.start()
            m.state shouldBe MatchState.RUNNING
            observed.shouldContainExactly(MatchState.RUNNING)
        }

        test("onStateChanged callback enables auto-removal from registry") {
            val registry = MatchRegistry()
            val m = registry.getOrCreateMatch("m1") { Match("m1", GameBridge()) }
            m.onStateChanged = { state ->
                if (state == MatchState.FINISHED) registry.removeMatch(m.matchId)
            }
            registry.getMatch("m1").shouldNotBeNull()
            m.close()
            registry.getMatch("m1").shouldBeNull()
        }
    })
