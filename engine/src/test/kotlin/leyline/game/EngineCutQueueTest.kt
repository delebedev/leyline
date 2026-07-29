package leyline.game

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.event.FrameEventLog
import leyline.game.state.GameBridge

class EngineCutQueueTest :
    FunSpec({
        tags(UnitTag)

        test("acknowledgement through checkpoint preserves FIFO and leaves suffix") {
            val queue = EngineCutQueue()
            queue.beginGeneration()
            val observation = EngineObservation.forTest()
            val actionCheckpoint = queue.publishReady(InteractionReadiness.ACTION, observation)
            val checkpoint = queue.publishReady(InteractionReadiness.PROMPT, observation)
            queue.publishReady(InteractionReadiness.NUMERIC_INPUT, observation)

            val first = checkNotNull(queue.peekThrough(checkpoint))
            (first as EngineCut.InteractionReady).kind shouldBe InteractionReadiness.ACTION
            first.checkpoint shouldBe actionCheckpoint
            queue.acknowledge(first)
            val second = checkNotNull(queue.peekThrough(checkpoint))
            (second as EngineCut.InteractionReady).kind shouldBe InteractionReadiness.PROMPT
            queue.acknowledge(second)
            queue.peekThrough(checkpoint).shouldBeNull()
            val suffix = checkNotNull(queue.peekThrough(queue.latestCheckpoint()))
            (suffix as EngineCut.InteractionReady).kind shouldBe InteractionReadiness.NUMERIC_INPUT
        }

        test("unacknowledged cut remains at the head") {
            val queue = EngineCutQueue()
            queue.beginGeneration()
            val observation = EngineObservation.forTest()
            val checkpoint = queue.publishReady(InteractionReadiness.ACTION, observation)
            queue.publishReady(InteractionReadiness.PROMPT, observation)

            val firstAttempt = checkNotNull(queue.peekThrough(checkpoint))
            queue.peekThrough(queue.latestCheckpoint()) shouldBe firstAttempt
        }

        test("generation replacement rejects an old checkpoint") {
            val queue = EngineCutQueue()
            queue.beginGeneration()
            val old = queue.publishReady(InteractionReadiness.ACTION, EngineObservation.forTest())
            queue.beginGeneration()

            shouldThrow<IllegalStateException> {
                queue.peekThrough(old)
            }
        }

        test("readiness observation cannot overtake prior playback") {
            val queue = EngineCutQueue()
            val generation = queue.beginGeneration()
            val playbackObservation = EngineObservation.forTest(hasPendingEvents = true)
            val readinessObservation = EngineObservation.forTest(hasPendingEvents = false)
            val reservation =
                GameBridge.BundleFrameReservation(
                    sourceGeneration = generation,
                    viewingSeatId = 1,
                    events = FrameEventLog.EMPTY,
                    eventReservation = null,
                    revealReservations = emptyList(),
                )
            queue.publishObservation(
                PlaybackYield(
                    sourceGeneration = generation,
                    cutReason = PlaybackCutReason.TURN_PHASE,
                    observation = playbackObservation,
                    events = FrameEventLog.EMPTY,
                    reservation = reservation,
                ),
            )
            val readyCheckpoint = queue.publishReady(InteractionReadiness.ACTION, readinessObservation)

            val playback = checkNotNull(queue.peekThrough(readyCheckpoint))
            (playback as EngineCut.Observation).value.observation shouldBe playbackObservation
            queue.acknowledge(playback)

            val readiness = checkNotNull(queue.peekThrough(readyCheckpoint))
            (readiness as EngineCut.InteractionReady).observation shouldBe readinessObservation
            queue.latestReady() shouldBe readiness
        }
    })
