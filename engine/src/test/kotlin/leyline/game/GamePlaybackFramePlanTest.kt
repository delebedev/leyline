package leyline.game

import forge.card.GamePieceType
import forge.game.card.CardView
import forge.game.event.GameEventLandPlayed
import forge.game.event.GameEventShuffle
import forge.game.player.PlayerView
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrowAny
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
import leyline.game.mapping.NaiveGsmActionCapture
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step

class GamePlaybackFramePlanTest :
    BoardTest({

        fun drainPublishedYield(
            bridge: leyline.game.state.GameBridge,
            counter: leyline.game.bundle.MessageCounter,
        ): List<BundleBuilder.BundleResult> {
            val checkpoint = bridge.latestEngineCutCheckpoint()
            val cut = bridge.peekEngineCutThrough(checkpoint) as? EngineCut.Observation ?: error("No playback observation")
            val results = BundleBuilder(bridge, "test-match", 1).playbackYield(cut.value, counter)
            bridge.acknowledgeEngineCut(cut)
            return results
        }

        test("interactive noncombat damage deferral releases its exact prefix") {
            val (bridge, game) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val sourceId =
                ForgeCardId(
                    game.humanPlayer
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .single()
                        .id,
                )
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = sourceId,
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                    GameEvent.SpellResolved(sourceId, hasFizzled = false),
                )
            checkNotNull(bridge.eventCollector).appendEventsForTest(events)
            val materializer =
                InteractivePlaybackMaterializer(
                    bridge = bridge,
                    matchId = "test-match",
                    seatId = 1,
                    combatFramePlanner = CombatFramePlanner(bridge, 1),
                )

            materializer.materialize(
                game = game,
                cutReason = PlaybackCutReason.SPELL_RESOLVED,
                turnStarted = false,
            ) shouldBe false

            val retained = bridge.reserveBundleFrame(1)
            assertSoftly {
                materializer.isAwaitingResolutionBoundary() shouldBe true
                bridge.hasPendingEngineCuts() shouldBe false
                retained.events.events shouldBe events
            }
            bridge.releaseBundleFrame(retained)
        }

        test("spectator local-seat playback retries reserved input and preserves a later suffix") {
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
            shouldThrowAny {
                drainPublishedYield(bridge, counter)
            }

            val retainedYield =
                (bridge.peekEngineCutThrough(bridge.latestEngineCutCheckpoint()) as EngineCut.Observation)
                    .value
            val retainedInput = retainedYield.events
            assertSoftly {
                retainedYield.snapshot.seats
                    .map { it.seatId }
                    .toSet() shouldBe setOf(SeatId(1), SeatId(2))
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
            val retryResults = drainPublishedYield(bridge, counter)
            bridge.diffListener = null

            val suffix = bridge.reserveBundleFrame(1).events
            assertSoftly {
                retryInput?.events shouldBe failedInput?.events
                retryInput?.zoneMoves shouldBe failedInput?.zoneMoves
                retryResults shouldHaveSize 1
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
            checkNotNull(bridge.eventCollector).appendEventsForTest(events)
            val baseline = bridge.bundleCursor.lastSent
            val counterBefore = counter.snapshot()
            val captureEvent = GameEventLandPlayed(PlayerView.get(game.humanPlayer), CardView.get(source))
            var observationCount = 0
            bridge.diffListener = { _, _, _, _, _ ->
                observationCount += 1
                if (observationCount == 2) error("induced final split failure")
            }

            playback.receiveGameEvent(captureEvent)
            shouldThrowAny {
                drainPublishedYield(bridge, counter)
            }

            val retained =
                (bridge.peekEngineCutThrough(bridge.latestEngineCutCheckpoint()) as EngineCut.Observation)
                    .value
                    .events
            assertSoftly {
                observationCount shouldBe 2
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
            val retryResults = drainPublishedYield(bridge, counter)
            bridge.diffListener = null

            val suffix = bridge.reserveBundleFrame(1).events
            assertSoftly {
                retryResults shouldHaveSize 2
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
            val generatedEvents = checkNotNull(bridge.eventCollector).closeFrame().events
            val damage =
                GameEvent.DamageDealtToPlayer(
                    sourceCardId = forgeCardId,
                    targetSeatId = SeatId(2),
                    amount = 1,
                    sourceKind = DamageSourceKind.Combat,
                    changesLife = true,
                )
            val inputEvents = FrameEventLog(generatedEvents + damage)
            val reservation = bridge.reserveBundleFrame(1).copy(events = inputEvents)
            reservation.events.zoneMoves.shouldBeEmpty()
            val playbackYield =
                PlaybackYield(
                    sourceGeneration = reservation.sourceGeneration,
                    cutReason = PlaybackCutReason.COMBAT_ENDED,
                    observation =
                        bridge.materializeEngineObservation(
                            game,
                            GsmSnapshot.captureForPlayback(game, bridge, "test-match"),
                        ),
                    events = inputEvents,
                    reservation = reservation,
                    combatFrames =
                        listOf(
                            CombatYieldFrame(FrameEventLog(generatedEvents), emptyMap()),
                            CombatYieldFrame(FrameEventLog(listOf(damage)), emptyMap()),
                        ),
                    naiveActions = NaiveGsmActionCapture.materialize(1, bridge),
                )
            val results =
                BundleBuilder(bridge, "test-match", 1).playbackYield(playbackYield, counter)

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
            bridge.closeBundleFrame(1)
            val inputEvents = FrameEventLog(events)
            val reservation = bridge.reserveBundleFrame(1).copy(events = inputEvents)
            reservation.events.zoneMoves.shouldBeEmpty()

            val playbackYield =
                PlaybackYield(
                    sourceGeneration = reservation.sourceGeneration,
                    cutReason = PlaybackCutReason.COMBAT_ENDED,
                    observation =
                        bridge.materializeEngineObservation(
                            game,
                            GsmSnapshot.captureForPlayback(game, bridge, "test-match"),
                        ),
                    events = inputEvents,
                    reservation = reservation,
                    combatFrames =
                        events.map { CombatYieldFrame(FrameEventLog(listOf(it)), emptyMap()) },
                    naiveActions = NaiveGsmActionCapture.materialize(1, bridge),
                )
            val results =
                BundleBuilder(bridge, "test-match", 1).playbackYield(playbackYield, counter)
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
