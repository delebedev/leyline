package leyline.game

import forge.card.GamePieceType
import forge.game.card.CardView
import forge.game.event.GameEventLandPlayed
import forge.game.event.GameEventShuffle
import forge.game.player.PlayerView
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.event.DamageSourceKind
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.ZoneMove
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import java.util.concurrent.ConcurrentLinkedQueue

class GamePlaybackFramePlanTest :
    BoardTest({

        @Suppress("UNCHECKED_CAST")
        fun collectorQueue(bridge: leyline.game.state.GameBridge): ConcurrentLinkedQueue<GameEvent> {
            val field = checkNotNull(bridge.eventCollector).javaClass.getDeclaredField("frame")
            field.isAccessible = true
            return field.get(bridge.eventCollector) as ConcurrentLinkedQueue<GameEvent>
        }

        @Suppress("UNCHECKED_CAST")
        fun collectorZoneMoves(bridge: leyline.game.state.GameBridge): ConcurrentLinkedQueue<ZoneMove> {
            val field = checkNotNull(bridge.eventCollector).javaClass.getDeclaredField("zoneMoves")
            field.isAccessible = true
            return field.get(bridge.eventCollector) as ConcurrentLinkedQueue<ZoneMove>
        }

        test("playback retries reserved input and preserves a later suffix") {
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Giant Growth", human, ZoneType.Hand)
                }
            val playback =
                GamePlayback(
                    bridge = bridge,
                    matchId = "test-match",
                    seatId = 1,
                    counter = counter,
                    delayMultiplier = 0.0,
                    captureLocalActions = true,
                )
            val baseline = bridge.bundleCursor.lastSent
            val counterBefore = counter.snapshot()
            val land =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single { it.isLand }
            val revealed =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single { !it.isLand }
            game.humanPlayer.playLand(land, null)
            bridge.promptBridge(SeatId(1)).recordReveal(
                listOf(ForgeCardId(revealed.id)),
                ownerSeatId = SeatId(1),
                viewerSeatId = SeatId(2),
            )
            val captureEvent = GameEventLandPlayed(PlayerView.get(game.humanPlayer), CardView.get(land))
            var failedInput: FrameEventLog? = null
            bridge.diffListener = { _, _, events, _, _ ->
                failedInput = events
                error("induced playback commit failure")
            }

            playback.receiveGameEvent(captureEvent)

            val retainedInput = bridge.reserveBundleFrame(1).events
            assertSoftly {
                playback.drainQueue().shouldBeEmpty()
                retainedInput.events shouldBe failedInput?.events
                retainedInput.zoneMoves shouldBe failedInput?.zoneMoves
                retainedInput.events.filterIsInstance<GameEvent.LandPlayed>() shouldHaveSize 1
                retainedInput.events.filterIsInstance<GameEvent.CardsRevealed>() shouldHaveSize 1
                retainedInput.zoneMoves shouldHaveSize 1
                bridge.bundleCursor.lastSent shouldBe baseline
                counter.snapshot() shouldBe counterBefore
            }

            var retryInput: FrameEventLog? = null
            bridge.diffListener = { _, _, events, _, _ ->
                retryInput = events
                game.fireEvent(GameEventShuffle(game.humanPlayer))
            }
            playback.receiveGameEvent(captureEvent)
            bridge.diffListener = null

            val suffix = bridge.reserveBundleFrame(1).events
            assertSoftly {
                retryInput?.events shouldBe failedInput?.events
                retryInput?.zoneMoves shouldBe failedInput?.zoneMoves
                playback.drainQueue() shouldHaveSize 1
                suffix.events.filterIsInstance<GameEvent.LibraryShuffled>() shouldHaveSize 1
                suffix.events.filterIsInstance<GameEvent.LandPlayed>().shouldBeEmpty()
                suffix.events.filterIsInstance<GameEvent.CardsRevealed>().shouldBeEmpty()
                suffix.zoneMoves.shouldBeEmpty()
            }
        }

        test("split playback failure leaves the whole window available for retry") {
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val playback =
                GamePlayback(
                    bridge = bridge,
                    matchId = "test-match",
                    seatId = 1,
                    counter = counter,
                    delayMultiplier = 0.0,
                    captureLocalActions = true,
                )
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val events =
                listOf(
                    GameEvent.PhaseChanged(
                        SeatId(1),
                        Phase.Combat_a549.number,
                        Step.FirstStrikeDamage_a2cb.number,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(source.id),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                    GameEvent.PhaseChanged(
                        SeatId(1),
                        Phase.Combat_a549.number,
                        Step.CombatDamage_a2cb.number,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(source.id),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                )
            collectorQueue(bridge).addAll(events)
            val baseline = bridge.bundleCursor.lastSent
            val counterBefore = counter.snapshot()
            val captureEvent = GameEventLandPlayed(PlayerView.get(game.humanPlayer), CardView.get(source))
            var observationCount = 0
            bridge.diffListener = { _, _, _, _, _ ->
                observationCount += 1
                if (observationCount == 2) error("induced final split failure")
            }

            playback.receiveGameEvent(captureEvent)

            val retained = bridge.reserveBundleFrame(1).events
            assertSoftly {
                observationCount shouldBe 2
                playback.drainQueue().shouldBeEmpty()
                counter.snapshot() shouldBe counterBefore
                bridge.bundleCursor.lastSent shouldBe baseline
                retained.events shouldBe events
            }

            var retryObservationCount = 0
            bridge.diffListener = { _, _, _, _, _ ->
                retryObservationCount += 1
                if (retryObservationCount == 2) {
                    game.fireEvent(GameEventShuffle(game.humanPlayer))
                }
            }
            playback.receiveGameEvent(captureEvent)
            bridge.diffListener = null

            val suffix = bridge.reserveBundleFrame(1).events
            assertSoftly {
                playback.drainQueue() shouldHaveSize 2
                retryObservationCount shouldBe 2
                suffix.events.filterIsInstance<GameEvent.LibraryShuffled>() shouldHaveSize 1
                suffix.events.filterIsInstance<GameEvent.DamageDealtToPlayer>().shouldBeEmpty()
                suffix.events.filterIsInstance<GameEvent.PhaseChanged>().shouldBeEmpty()
                suffix.zoneMoves.shouldBeEmpty()
                counter.currentGsId() shouldBe counterBefore.currentGsId + 4
                bridge.bundleCursor.lastSent.shouldNotBeNull()
            }
        }

        test("split window preflights snapshot transfer without leaking an id") {
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }
            val land =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val forgeCardId = ForgeCardId(land.id)
            val oldInstanceId = bridge.getOrAllocInstanceId(forgeCardId)

            game.humanPlayer.playLand(land, null)
            val damage =
                GameEvent.DamageDealtToPlayer(
                    sourceCardId = forgeCardId,
                    targetSeatId = SeatId(2),
                    amount = 1,
                    sourceKind = DamageSourceKind.Combat,
                    changesLife = true,
                )
            collectorQueue(bridge).add(damage)
            collectorZoneMoves(bridge).clear()
            val reservation = bridge.reserveBundleFrame(1)
            reservation.events.zoneMoves.shouldBeEmpty()
            val firstFrameEvents = reservation.events.events.filterNot { it === damage }
            val results =
                BundleBuilder(bridge, "test-match", 1).remoteActionDiffSequence(
                    game = game,
                    counter = counter,
                    eventFrames =
                        listOf(
                            FrameEventLog(firstFrameEvents, reservation.events.zoneMoves),
                            FrameEventLog(listOf(damage)),
                        ),
                    bundleFrameReservation = reservation,
                )

            val gsm =
                results
                    .single()
                    .messages
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage
            val newInstanceId = bridge.getOrAllocInstanceId(forgeCardId)
            val objectIdChanged =
                gsm.annotationsList.single { AnnotationType.ObjectIdChanged in it.typeList }
            val damageDealt =
                gsm.annotationsList.single { AnnotationType.DamageDealt_af5a in it.typeList }

            assertSoftly {
                newInstanceId.value shouldBe objectIdChanged.detailInt("new_id")
                newInstanceId.value shouldBe damageDealt.affectorId
                newInstanceId.value shouldBe oldInstanceId.value + 1
                bridge
                    .reserveBundleFrame(1)
                    .events.events
                    .shouldBeEmpty()
                bridge
                    .reserveBundleFrame(1)
                    .events.zoneMoves
                    .shouldBeEmpty()
            }
        }

        test("split window preflights an empty-ledger token disappearance") {
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val token =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            token.gamePieceType = GamePieceType.TOKEN
            val tokenForgeCardId = ForgeCardId(token.id)
            val oldInstanceId = bridge.getOrAllocInstanceId(tokenForgeCardId)
            val baseline = bridge.bundleCursor.lastSent
            val counterBefore = counter.snapshot()
            val events =
                listOf(
                    GameEvent.CardSacrificed(
                        cardId = tokenForgeCardId,
                        seatId = SeatId(1),
                    ),
                    GameEvent.PhaseChanged(
                        SeatId(1),
                        Phase.Combat_a549.number,
                        Step.CombatDamage_a2cb.number,
                    ),
                )

            game.humanPlayer.getZone(ZoneType.Battlefield).remove(token)
            collectorQueue(bridge).addAll(events)
            collectorZoneMoves(bridge).clear()
            val reservation = bridge.reserveBundleFrame(1)
            reservation.events.zoneMoves.shouldBeEmpty()

            val results =
                BundleBuilder(bridge, "test-match", 1).remoteActionDiffSequence(
                    game = game,
                    counter = counter,
                    eventFrames = events.map { FrameEventLog(listOf(it)) },
                    bundleFrameReservation = reservation,
                )
            val committedTokenId = bridge.getOrAllocInstanceId(tokenForgeCardId)
            val nextInstanceId = bridge.getOrAllocInstanceId(ForgeCardId(1_000_000))

            assertSoftly {
                results shouldHaveSize 1
                committedTokenId.value shouldBe oldInstanceId.value + 1
                nextInstanceId.value shouldBe oldInstanceId.value + 2
                counter.currentGsId() shouldBe counterBefore.currentGsId + 2
                bridge.bundleCursor.lastSent
                    .shouldNotBeNull()
                    .gameStateId shouldBe
                    baseline.shouldNotBeNull().gameStateId + 1
                bridge
                    .reserveBundleFrame(1)
                    .events.events
                    .shouldBeEmpty()
                bridge
                    .reserveBundleFrame(1)
                    .events.zoneMoves
                    .shouldBeEmpty()
            }
        }
    })
