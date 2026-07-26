package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.netty.channel.embedded.EmbeddedChannel
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.ConnectionState
import leyline.match.FamiliarSession
import leyline.match.Match
import leyline.match.MatchHandler
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import leyline.match.MatchState
import leyline.match.MatchTeardownReason
import leyline.testkit.TestCardRegistry
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class MatchRegistryTest :
    FunSpec({

        tags(UnitTag)

        // Wrap a fresh in-process Game without calling bridge.start() — avoids
        // racing the MyRandom static RNG with conformance tests that depend on
        // a stable seed-shuffle window (see Board.startGameAtMain1
        // for the lock that MatchRegistryTest can't share).
        fun stubBridge(): GameBridge =
            GameBridge(cardRepository = InMemoryCardRepository()).also {
                it.wrapGame(GameBootstrap.createGame())
            }

        test("getOrCreateMatch creates on first call, reuses on second") {
            val registry = MatchRegistry()
            var created = 0
            val m1 =
                registry.getOrCreateMatch("m1") {
                    created++
                    Match("m1", stubBridge())
                }
            val m2 =
                registry.getOrCreateMatch("m1") {
                    created++
                    Match("m1", stubBridge())
                }
            m1 shouldBeSameInstanceAs m2
            created shouldBe 1
        }

        test("getBridge returns bridge from match") {
            val registry = MatchRegistry()
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            registry.getOrCreateMatch("m1") { Match("m1", bridge) }
            registry.getBridge("m1") shouldBeSameInstanceAs bridge
        }

        test("getMatch returns match or null") {
            val registry = MatchRegistry()
            registry.getMatch("nope").shouldBeNull()
            val m = registry.getOrCreateMatch("m1") { Match("m1", stubBridge()) }
            registry.getMatch("m1") shouldBeSameInstanceAs m
        }

        test("registerSession + getPeer returns correct session") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val s1 =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = "m1",
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = stubBridge(),
                    paceDelayMs = 0,
                )
            val s2 =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(2),
                            matchId = "m1",
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = stubBridge(),
                    paceDelayMs = 0,
                )
            registry.registerSession("m1", SeatId(1), s1)
            registry.getPeer("m1", SeatId(1)).shouldBeNull()
            registry.registerSession("m1", SeatId(2), s2)
            registry.getPeer("m1", SeatId(1)) shouldBeSameInstanceAs s2
        }

        test("evictStale removes old match entries and closes them") {
            val registry = MatchRegistry()
            val old = registry.getOrCreateMatch("old-match") { Match("old-match", stubBridge()) }
            registry.getOrCreateMatch("current") { Match("current", stubBridge()) }
            val evicted = registry.evictStale("current")
            assertSoftly {
                evicted.size shouldBe 1
                evicted.single() shouldBe old
                old.state shouldBe MatchState.FINISHED
            }
        }

        // --- Match lifecycle tests ---

        test("new Match starts in WAITING state") {
            val m = Match("m1", stubBridge())
            m.state shouldBe MatchState.WAITING
        }

        test("close() transitions to FINISHED and fires callback") {
            val m = Match("m1", stubBridge())
            val observed = mutableListOf<MatchState>()
            m.onStateChanged = { observed.add(it) }
            m.close()
            m.state shouldBe MatchState.FINISHED
            observed.shouldContainExactly(MatchState.FINISHED)
        }

        test("close() is idempotent — second call is a no-op") {
            val m = Match("m1", stubBridge())
            val observed = mutableListOf<MatchState>()
            m.onStateChanged = { observed.add(it) }
            m.close()
            m.close()
            m.state shouldBe MatchState.FINISHED
            observed.shouldContainExactly(MatchState.FINISHED) // only one callback
        }

        test("start() transitions WAITING -> RUNNING") {
            val m = Match("m1", stubBridge())
            val observed = mutableListOf<MatchState>()
            m.onStateChanged = { observed.add(it) }
            m.state shouldBe MatchState.WAITING
            // start() will transition state then call bridge.start() which is a no-op on a bare GameBridge
            m.start()
            m.state shouldBe MatchState.RUNNING
            observed.shouldContainExactly(MatchState.RUNNING)
        }

        test("start() on already-RUNNING match is a no-op for state") {
            val m = Match("m1", stubBridge())
            val observed = mutableListOf<MatchState>()
            m.onStateChanged = { observed.add(it) }
            m.start()
            m.start() // second start — CAS fails, no duplicate callback
            m.state shouldBe MatchState.RUNNING
            observed.shouldContainExactly(MatchState.RUNNING)
        }

        test("start() on FINISHED match does not transition back") {
            val m = Match("m1", stubBridge())
            m.close()
            m.state shouldBe MatchState.FINISHED
            val observed = mutableListOf<MatchState>()
            m.onStateChanged = { observed.add(it) }
            m.start() // should not transition back to RUNNING
            m.state shouldBe MatchState.FINISHED
            observed shouldBe emptyList()
        }

        test("actionBridge throws for unknown seat") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            shouldThrow<IllegalStateException> {
                bridge.actionBridge(SeatId(99))
            }
        }

        test("registerSession accepts FamiliarSession via SessionOps interface") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val human =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = "m1",
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = stubBridge(),
                    paceDelayMs = 0,
                )
            val familiar = FamiliarSession(seatId = SeatId(2), matchId = "m1", sink = sink)
            registry.registerSession("m1", SeatId(1), human)
            registry.registerSession("m1", SeatId(2), familiar)
            registry.getPeer("m1", SeatId(1)) shouldBeSameInstanceAs familiar
            registry.getPeer("m1", SeatId(2)) shouldBeSameInstanceAs human
        }

        test("activeSession returns MatchSession, not FamiliarSession") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val human =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = "m1",
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = stubBridge(),
                    paceDelayMs = 0,
                )
            val familiar = FamiliarSession(seatId = SeatId(2), matchId = "m1", sink = sink)
            registry.registerSession("m1", SeatId(1), human)
            registry.registerSession("m1", SeatId(2), familiar)
            registry.activeSession() shouldBeSameInstanceAs human
        }

        test("replacing a connection detaches the displaced session generation") {
            val registry = MatchRegistry()
            val matchId = "reconnect"
            val bridge = stubBridge()
            val sink = ListMessageSink()
            val firstSession =
                MatchSession(
                    ConnectionState(SeatId(1), matchId, sink, registry),
                    bridge,
                    paceDelayMs = 0,
                )
            val secondSession =
                MatchSession(
                    ConnectionState(SeatId(1), matchId, sink, registry),
                    bridge,
                    paceDelayMs = 0,
                )
            val firstHandler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
            val secondHandler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
            val firstChannel = EmbeddedChannel(firstHandler)
            val secondChannel = EmbeddedChannel(secondHandler)

            try {
                firstHandler.session = firstSession
                registry.registerSession(matchId, SeatId(1), firstSession)
                registry.registerConnection(matchId, SeatId(1), firstHandler.connection)

                secondHandler.session = secondSession
                registry.registerSession(matchId, SeatId(1), secondSession)
                registry.registerConnection(matchId, SeatId(1), secondHandler.connection)
                firstChannel.close()

                assertSoftly {
                    firstHandler.session.shouldBeNull()
                    registry.activeSession() shouldBeSameInstanceAs secondSession
                    firstSession.connection.owner shouldBeSameInstanceAs secondSession.connection.owner
                }
            } finally {
                secondChannel.close()
                firstChannel.close()
            }
        }

        test("pre-game response queued during replacement cannot run on the displaced generation") {
            val registry = MatchRegistry()
            val matchId = "queued-replacement"
            val bridge = stubBridge()
            val sink = ListMessageSink()
            registry.getOrCreateMatch(matchId) { Match(matchId, bridge) }
            val firstSession =
                MatchSession(ConnectionState(SeatId(1), matchId, sink, registry), bridge, paceDelayMs = 0)
            val secondSession =
                MatchSession(ConnectionState(SeatId(1), matchId, sink, registry), bridge, paceDelayMs = 0)
            registry.registerSession(matchId, SeatId(1), firstSession)
            val ownerEntered = CountDownLatch(1)
            val replaceNow = CountDownLatch(1)
            val queuedThread = AtomicReference<Thread>()
            val staleResponseRan = AtomicBoolean(false)
            val entrants = Executors.newFixedThreadPool(2)

            try {
                val replacement =
                    entrants.submit {
                        firstSession.withSessionAuthority {
                            ownerEntered.countDown()
                            replaceNow.await()
                            registry.registerSession(matchId, SeatId(1), secondSession)
                        }
                    }
                ownerEntered.await(2, TimeUnit.SECONDS) shouldBe true
                val staleResponse =
                    entrants.submit {
                        queuedThread.set(Thread.currentThread())
                        firstSession.withSessionAuthority {
                            staleResponseRan.set(true)
                        }
                    }
                eventually(2.seconds) {
                    queuedThread.get()?.state shouldBe Thread.State.WAITING
                }

                replaceNow.countDown()
                replacement.get(2, TimeUnit.SECONDS)
                staleResponse.get(2, TimeUnit.SECONDS)

                assertSoftly {
                    registry.activeSession() shouldBeSameInstanceAs secondSession
                    staleResponseRan.get() shouldBe false
                    sink.messages shouldBe emptyList()
                }
            } finally {
                replaceNow.countDown()
                entrants.shutdownNow()
                registry.teardownMatch(matchId, MatchTeardownReason.Disconnect)
            }
        }

        test("stale-match eviction cancels queued owner work before cleanup") {
            val registry = MatchRegistry()
            val matchId = "stale-owner"
            val bridge = stubBridge()
            val sink = ListMessageSink()
            registry.getOrCreateMatch(matchId) { Match(matchId, bridge) }
            val session =
                MatchSession(ConnectionState(SeatId(1), matchId, sink, registry), bridge, paceDelayMs = 0)
            registry.registerSession(matchId, SeatId(1), session)
            val ownerEntered = CountDownLatch(1)
            val releaseOwner = CountDownLatch(1)
            val queuedThread = AtomicReference<Thread>()
            val queuedFailure = AtomicReference<Throwable>()
            val queuedRan = AtomicBoolean(false)
            val entrants = Executors.newFixedThreadPool(3)

            try {
                val holder =
                    entrants.submit {
                        session.withSessionAuthority {
                            ownerEntered.countDown()
                            releaseOwner.await()
                        }
                    }
                ownerEntered.await(2, TimeUnit.SECONDS) shouldBe true
                val queued =
                    entrants.submit {
                        queuedThread.set(Thread.currentThread())
                        queuedFailure.set(
                            runCatching {
                                session.withSessionAuthority { queuedRan.set(true) }
                            }.exceptionOrNull(),
                        )
                    }
                eventually(2.seconds) {
                    queuedThread.get()?.state shouldBe Thread.State.WAITING
                }

                val eviction = entrants.submit { registry.evictStale("next-match") }
                eventually(2.seconds) {
                    queuedFailure.get().shouldBeInstanceOf<CancellationException>()
                }
                queuedRan.get() shouldBe false

                releaseOwner.countDown()
                holder.get(2, TimeUnit.SECONDS)
                queued.get(2, TimeUnit.SECONDS)
                eviction.get(2, TimeUnit.SECONDS)
                registry.getMatch(matchId).shouldBeNull()
            } finally {
                releaseOwner.countDown()
                entrants.shutdownNow()
            }
        }

        test("teardown fences publication from the active owner action") {
            val registry = MatchRegistry()
            val matchId = "terminal-publication"
            val bridge = stubBridge()
            registry.getOrCreateMatch(matchId) { Match(matchId, bridge) }
            val sink = ListMessageSink()
            val session =
                MatchSession(
                    ConnectionState(SeatId(1), matchId, sink, registry),
                    bridge,
                    paceDelayMs = 0,
                )
            val handler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
            val channel = EmbeddedChannel(handler)
            val owner = registry.ownerFor(matchId)
            val ownerEntered = CountDownLatch(1)
            val publishNow = CountDownLatch(1)
            val publicationFailure = AtomicReference<Throwable?>()
            val entrants = Executors.newFixedThreadPool(2)

            try {
                val publication =
                    entrants.submit {
                        publicationFailure.set(
                            runCatching {
                                owner.reduce {
                                    ownerEntered.countDown()
                                    publishNow.await()
                                    registry.publishSessionAndConnection(
                                        matchId,
                                        SeatId(1),
                                        session,
                                        handler.connection,
                                    ) {
                                        handler.session = session
                                    }
                                }
                            }.exceptionOrNull(),
                        )
                    }
                ownerEntered.await(2, TimeUnit.SECONDS) shouldBe true

                val teardown =
                    entrants.submit {
                        registry.teardownMatch(matchId, MatchTeardownReason.Disconnect)
                    }
                eventually(2.seconds) {
                    registry.getMatch(matchId).shouldBeNull()
                    owner.isClosed() shouldBe true
                }

                publishNow.countDown()
                publication.get(2, TimeUnit.SECONDS)
                teardown.get(2, TimeUnit.SECONDS)

                assertSoftly {
                    publicationFailure.get().shouldBeInstanceOf<IllegalStateException>()
                    registry.getMatch(matchId).shouldBeNull()
                    registry.activeSession().shouldBeNull()
                    registry.getConnection(matchId, SeatId(1)).shouldBeNull()
                    handler.session.shouldBeNull()
                }
            } finally {
                publishNow.countDown()
                entrants.shutdownNow()
                channel.close()
            }
        }

        test("auto advance queued by a closing session becomes inert") {
            val registry = MatchRegistry()
            val sink = ListMessageSink()
            val bridge = stubBridge()
            val session =
                MatchSession(
                    ConnectionState(SeatId(1), "replacement", sink, registry),
                    bridge,
                    paceDelayMs = 0,
                )

            session.withSessionAuthority {
                bridge.autoAdvanceRequester?.invoke("replacement")
                session.close()
            }
            session.withSessionAuthority { }

            sink.messages shouldBe emptyList()
        }

        test("onStateChanged callback enables auto-removal from registry") {
            val registry = MatchRegistry()
            val m = registry.getOrCreateMatch("m1") { Match("m1", stubBridge()) }
            m.onStateChanged = { state ->
                if (state == MatchState.FINISHED) registry.removeMatch(m.matchId)
            }
            registry.getMatch("m1").shouldNotBeNull()
            m.close()
            registry.getMatch("m1").shouldBeNull()
        }

        test("teardownMatch removes match sessions and handlers together") {
            val registry = MatchRegistry()
            val match = registry.getOrCreateMatch("m1") { Match("m1", stubBridge()) }
            val sink = ListMessageSink()
            val session =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = "m1",
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = match.bridge,
                    paceDelayMs = 0,
                )
            val handler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)

            registry.registerSession("m1", SeatId(1), session)

            registry.teardownMatch("m1", MatchTeardownReason.Disconnect)

            assertSoftly {
                match.state shouldBe MatchState.FINISHED
                registry.getMatch("m1").shouldBeNull()
                registry.getPeer("m1", SeatId(2)).shouldBeNull()
                registry.getConnection("m1", SeatId(1)).shouldBeNull()
            }
        }

        test("channelInactive tears down state and next session can recreate match") {
            val registry = MatchRegistry()
            val matchId = "forge-match-1"
            val match = registry.getOrCreateMatch(matchId) { Match(matchId, stubBridge()) }
            val sink = ListMessageSink()
            val session =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = matchId,
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = match.bridge,
                    paceDelayMs = 0,
                )

            val handler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
            val channel = EmbeddedChannel(handler)
            handler.session = session
            registry.registerSession(matchId, SeatId(1), session)
            registry.registerConnection(matchId, SeatId(1), handler.connection)

            channel.close()

            assertSoftly {
                match.state shouldBe MatchState.FINISHED
                handler.session.shouldBeNull()
                registry.getMatch(matchId).shouldBeNull()
                registry.getConnection(matchId, SeatId(1)).shouldBeNull()
                registry.activeSession().shouldBeNull()
            }

            val recreated = registry.getOrCreateMatch(matchId) { Match(matchId, stubBridge()) }
            val replacement =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = matchId,
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = stubBridge(),
                    paceDelayMs = 0,
                )
            registry.registerSession(matchId, SeatId(1), replacement)

            assertSoftly {
                recreated.state shouldBe MatchState.WAITING
                registry.getMatch(matchId) shouldBeSameInstanceAs recreated
                registry.activeSession() shouldBeSameInstanceAs replacement
            }
        }
    })
