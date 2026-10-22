package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

        fun session(
            registry: MatchRegistry,
            match: Match,
        ): MatchSession =
            MatchSession(ConnectionState(SeatId(1), match.matchId, ListMessageSink(), registry), match.bridge, paceDelayMs = 0)

        fun reauthenticate(
            connection: MatchConnection,
            tentativeMatchId: String? = null,
        ) {
            connection.receive(
                ClientToMatchServiceMessage
                    .newBuilder()
                    .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.AuthenticateRequest_f487)
                    .setPayload(
                        AuthenticateRequest
                            .newBuilder()
                            .setClientId("reauth-player")
                            .build()
                            .toByteString(),
                    ).build(),
            )
            if (tentativeMatchId == null) return
            connection.receive(
                ClientToMatchServiceMessage
                    .newBuilder()
                    .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487)
                    .setPayload(
                        ClientToMatchDoorConnectRequest
                            .newBuilder()
                            .setMatchId(tentativeMatchId)
                            .setClientToGreMessageBytes(
                                ClientToGREMessage
                                    .newBuilder()
                                    .setSystemSeatId(2)
                                    .build()
                                    .toByteString(),
                            ).build()
                            .toByteString(),
                    ).build(),
            )
        }

        test("one registration publishes a replacement to both registry and connection observers") {
            val registry = MatchRegistry()
            val match = registry.getOrCreateMatch("registration") { Match("registration", stubBridge()) }
            val handler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
            val channel = EmbeddedChannel(handler)
            val original = session(registry, match)
            val registration = registry.registerSession(match.matchId, SeatId(1), original, handler.connection)
            val replacement = MatchSession(original.connection, match.bridge, paceDelayMs = 0)

            registry.replaceSession(original, { replacement }) { installed ->
                assertSoftly {
                    installed shouldBeSameInstanceAs replacement
                    registration.session shouldBeSameInstanceAs replacement
                    registry.activeHumanSession() shouldBeSameInstanceAs replacement
                    registry.getPeer(match.matchId, SeatId(2)) shouldBeSameInstanceAs replacement
                    handler.connection.session shouldBeSameInstanceAs replacement
                    registry.getConnection(match.matchId, SeatId(1)) shouldBeSameInstanceAs handler.connection
                }
                installed
            } shouldBeSameInstanceAs replacement

            var rebuilt = false
            shouldThrow<IllegalStateException> {
                registry.replaceSession(original, {
                    rebuilt = true
                    original
                }) { it }
            }
            assertSoftly {
                rebuilt shouldBe false
                registration.session shouldBeSameInstanceAs replacement
            }
            registry.teardownMatch(match.matchId, MatchTeardownReason.Disconnect)
            channel.close()
        }

        listOf("disconnect", "failure").forEach { cleanup ->
            listOf("attached", "reauthenticated", "retargeted").forEach { phase ->
                test("fresh attachment survives obsolete $cleanup while the old connection is $phase") {
                    val registry = MatchRegistry()
                    val match = registry.getOrCreateMatch("reconnect") { Match("reconnect", stubBridge()) }
                    val original = session(registry, match)
                    val oldHandler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
                    val oldChannel = EmbeddedChannel(oldHandler)
                    val oldConnection = oldHandler.connection
                    val oldRegistration = registry.registerSession(match.matchId, SeatId(1), original, oldConnection)
                    val unrelated = registry.getOrCreateMatch("tentative") { Match("tentative", stubBridge()) }
                    val unrelatedSession = FamiliarSession(SeatId(2), unrelated.matchId, ListMessageSink())
                    registry.registerSession(unrelated.matchId, SeatId(2), unrelatedSession)
                    if (phase != "attached") {
                        reauthenticate(oldConnection, unrelated.matchId.takeIf { phase == "retargeted" })
                    }
                    val replacement = session(registry, match)
                    val newHandler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
                    val newChannel = EmbeddedChannel(newHandler)
                    val newConnection = newHandler.connection
                    val newRegistration = registry.registerSession(match.matchId, SeatId(1), replacement, newConnection)
                    var rebuilt = false

                    shouldThrow<IllegalStateException> {
                        registry.replaceSession(original, {
                            rebuilt = true
                            original
                        }) { it }
                    }
                    oldConnection.submitGREMessage(ClientToGREMessage.newBuilder().setType(ClientMessageType.ConcedeReq_097b).build())
                    if (cleanup == "disconnect") {
                        oldConnection.disconnected()
                    } else {
                        oldConnection.failed(IllegalStateException("Authored obsolete failure"))
                    }

                    assertSoftly {
                        rebuilt shouldBe false
                        registry.isCurrent(oldRegistration) shouldBe false
                        registry.isCurrent(newRegistration) shouldBe true
                        if (phase == "attached") {
                            oldConnection.session shouldBeSameInstanceAs original
                        } else {
                            oldConnection.session.shouldBeNull()
                        }
                        newConnection.session shouldBeSameInstanceAs replacement
                        registry.activeHumanSession() shouldBeSameInstanceAs replacement
                        registry.getConnection(match.matchId, SeatId(1)) shouldBeSameInstanceAs newConnection
                        registry.getMatch(match.matchId) shouldBeSameInstanceAs match
                        match.state shouldBe MatchState.WAITING
                        registry.getMatch(unrelated.matchId) shouldBeSameInstanceAs unrelated
                        registry.getPeer(unrelated.matchId, SeatId(1)) shouldBeSameInstanceAs unrelatedSession
                        unrelated.state shouldBe MatchState.WAITING
                    }
                    registry.teardownMatch(match.matchId, MatchTeardownReason.Disconnect)
                    registry.teardownMatch(unrelated.matchId, MatchTeardownReason.Disconnect)
                    oldChannel.close()
                    newChannel.close()
                }
            }

            test("retargeted $cleanup closes its original attachment without touching tentative identity") {
                val registry = MatchRegistry()
                val match = registry.getOrCreateMatch("original") { Match("original", stubBridge()) }
                val original = session(registry, match)
                val handler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
                val channel = EmbeddedChannel(handler)
                val registration = registry.registerSession(match.matchId, SeatId(1), original, handler.connection)
                val unrelated = registry.getOrCreateMatch("tentative") { Match("tentative", stubBridge()) }
                val unrelatedSession = FamiliarSession(SeatId(2), unrelated.matchId, ListMessageSink())
                registry.registerSession(unrelated.matchId, SeatId(2), unrelatedSession)
                reauthenticate(handler.connection, unrelated.matchId)

                if (cleanup == "disconnect") {
                    handler.connection.disconnected()
                } else {
                    handler.connection.failed(IllegalStateException("Authored reconnect failure"))
                }

                assertSoftly {
                    registry.getMatch(match.matchId).shouldBeNull()
                    registry.isCurrent(registration) shouldBe false
                    registry.getConnection(match.matchId, SeatId(1)).shouldBeNull()
                    match.state shouldBe MatchState.FINISHED
                    registry.getMatch(unrelated.matchId) shouldBeSameInstanceAs unrelated
                    registry.getPeer(unrelated.matchId, SeatId(1)) shouldBeSameInstanceAs unrelatedSession
                    unrelated.state shouldBe MatchState.WAITING
                }
                registry.teardownMatch(unrelated.matchId, MatchTeardownReason.Disconnect)
                channel.close()
            }
        }

        test("teardown waits for admitted reconstruction and detaches the installed session") {
            val registry = MatchRegistry()
            val match = registry.getOrCreateMatch("replacement-race") { Match("replacement-race", stubBridge()) }
            val original = session(registry, match)
            val handler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
            val channel = EmbeddedChannel(handler)
            val registration = registry.registerSession(match.matchId, SeatId(1), original, handler.connection)
            val creating = CountDownLatch(1)
            val release = CountDownLatch(1)
            val tearingDown = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val replacement =
                    executor.submit<MatchSession> {
                        registry.replaceSession(
                            original,
                            create = {
                                creating.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                MatchSession(original.connection, match.bridge, paceDelayMs = 0)
                            },
                            publish = { it },
                        )
                    }
                creating.await(2, TimeUnit.SECONDS) shouldBe true
                val teardown =
                    executor.submit {
                        tearingDown.countDown()
                        registry.teardownMatch(match.matchId, MatchTeardownReason.Disconnect)
                    }
                tearingDown.await(2, TimeUnit.SECONDS) shouldBe true
                release.countDown()
                val installed = replacement.get(5, TimeUnit.SECONDS)
                teardown.get(5, TimeUnit.SECONDS)
                assertSoftly {
                    registration.session shouldBeSameInstanceAs installed
                    registry.isCurrent(registration) shouldBe false
                    registry.activeHumanSession().shouldBeNull()
                    handler.connection.session.shouldBeNull()
                    match.state shouldBe MatchState.FINISHED
                }
            } finally {
                release.countDown()
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS) shouldBe true
                registry.teardownMatch(match.matchId, MatchTeardownReason.Disconnect)
                channel.close()
            }
        }

        test("detached generation cannot replace or clear a connection rebound during resource cleanup") {
            val registry = MatchRegistry()
            val match = registry.getOrCreateMatch("cleanup-race") { Match("cleanup-race", stubBridge()) }
            val original = session(registry, match)
            val handler = MatchHandler(registry = registry, cardRepository = TestCardRegistry.repo)
            val channel = EmbeddedChannel(handler)
            registry.registerSession(match.matchId, SeatId(1), original, handler.connection)
            val closing = CountDownLatch(1)
            val release = CountDownLatch(1)
            match.onStateChanged = {
                if (it == MatchState.FINISHED) {
                    closing.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val teardown = executor.submit { registry.teardownMatch(match.matchId, MatchTeardownReason.Disconnect) }
                closing.await(2, TimeUnit.SECONDS) shouldBe true
                handler.connection.session.shouldBeNull()
                val nextMatch = registry.getOrCreateMatch(match.matchId) { Match(match.matchId, stubBridge()) }
                val next = session(registry, nextMatch)
                registry.registerSession(next.matchId, next.seatId, next, handler.connection)
                var rebuilt = false
                shouldThrow<IllegalStateException> {
                    registry.replaceSession(original, {
                        rebuilt = true
                        original
                    }) { it }
                }
                release.countDown()
                teardown.get(5, TimeUnit.SECONDS)
                assertSoftly {
                    rebuilt shouldBe false
                    registry.getMatch(match.matchId) shouldBeSameInstanceAs nextMatch
                    registry.activeHumanSession() shouldBeSameInstanceAs next
                    handler.connection.session shouldBeSameInstanceAs next
                    registry.getConnection(match.matchId, SeatId(1)) shouldBeSameInstanceAs handler.connection
                    nextMatch.state shouldBe MatchState.WAITING
                }
            } finally {
                release.countDown()
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS) shouldBe true
                registry.teardownMatch(match.matchId, MatchTeardownReason.Disconnect)
                channel.close()
            }
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

        test("Familiar registration preserves the active seat-1 human session") {
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
            assertSoftly {
                registry.getPeer("m1", SeatId(1)) shouldBeSameInstanceAs familiar
                registry.getPeer("m1", SeatId(2)) shouldBeSameInstanceAs human
                registry.activeHumanSession() shouldBeSameInstanceAs human
            }
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
            registry.registerSession(matchId, SeatId(1), session, handler.connection)

            channel.close()

            assertSoftly {
                match.state shouldBe MatchState.FINISHED
                handler.connection.session.shouldBeNull()
                registry.getMatch(matchId).shouldBeNull()
                registry.getConnection(matchId, SeatId(1)).shouldBeNull()
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
            }
        }
    })
