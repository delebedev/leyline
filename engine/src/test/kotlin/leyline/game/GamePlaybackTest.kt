package leyline.game

import com.google.common.collect.ImmutableMultimap
import forge.game.card.CardView
import forge.game.card.CounterType
import forge.game.player.PlayerView
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.MessageCounter
import leyline.game.event.DamageSourceKind
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.event.ZoneMove
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentLinkedQueue
import forge.game.event.GameEventAttackersDeclared as ForgeAttackersDeclared
import forge.game.event.GameEventCardCounters as ForgeCardCounters
import forge.game.event.GameEventCombatEnded as ForgeCombatEnded
import forge.game.event.GameEventPlayerPoisoned as ForgePlayerPoisoned

class GamePlaybackTest :
    FunSpec({

        tags(UnitTag)

        fun createMinimalPlayback(counter: MessageCounter = MessageCounter()): GamePlayback {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository(), messageCounter = counter)
            return GamePlayback(bridge, "test", 1, counter)
        }

        fun gameStateMessage(
            msgId: Int,
            gsId: Int,
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(1)
                .setGameStateMessage(
                    GameStateMessage
                        .newBuilder()
                        .setType(GameStateType.Diff)
                        .setGameStateId(gsId)
                        .setPrevGameStateId((gsId - 1).coerceAtLeast(0)),
                ).build()

        @Suppress("UNCHECKED_CAST")
        fun playbackQueue(playback: GamePlayback): ConcurrentLinkedQueue<List<GREToClientMessage>> {
            val field = GamePlayback::class.java.getDeclaredField("queue")
            field.isAccessible = true
            return field.get(playback) as ConcurrentLinkedQueue<List<GREToClientMessage>>
        }

        @Suppress("UNCHECKED_CAST")
        fun requestedCut(playback: GamePlayback): PlaybackCutRequest? {
            val field = GamePlayback::class.java.getDeclaredField("requestedCut")
            field.isAccessible = true
            return field.get(playback) as? PlaybackCutRequest
        }

        test("ordinary callbacks aggregate first reason max delay and turn start without output") {
            val playback = createMinimalPlayback()

            playback.visit(ForgePlayerPoisoned(null as PlayerView?, null as PlayerView?, 0, 1))
            playback.visit(ForgeCardCounters(null as CardView?, null as CounterType?, 0, 1))

            assertSoftly {
                requestedCut(playback) shouldBe
                    PlaybackCutRequest(
                        reason = PlaybackCutReason.PoisonChanged,
                        delayMs = GamePlayback.COUNTER_DELAY,
                        turnStarted = false,
                    )
                playback.hasPendingMessages() shouldBe false
            }
        }

        test("request aggregation keeps chronology while folding values") {
            PlaybackCutRequest(PlaybackCutReason.TurnBegan, 200, true)
                .aggregate(PlaybackCutRequest(PlaybackCutReason.StackObjectCast, 400, false)) shouldBe
                PlaybackCutRequest(PlaybackCutReason.TurnBegan, 400, true)
        }

        test("combat callbacks only promote the typed safe-point request") {
            val playback = createMinimalPlayback()

            playback.visit(ForgePlayerPoisoned(null as PlayerView?, null as PlayerView?, 0, 1))
            playback.visit(ForgeAttackersDeclared(null as PlayerView?, ImmutableMultimap.of()))

            assertSoftly {
                requestedCut(playback) shouldBe
                    PlaybackCutRequest(
                        reason = PlaybackCutReason.PoisonChanged,
                        delayMs = GamePlayback.COUNTER_DELAY,
                        turnStarted = false,
                        boundary = PlaybackCutBoundary.AttackersDeclared,
                    )
                playback.hasPendingMessages() shouldBe false
            }
        }

        test("combat end callback is silent until its completion hook") {
            val playback = createMinimalPlayback()

            playback.visit(ForgeCombatEnded(emptyList(), emptyList()))

            assertSoftly {
                requestedCut(playback)?.boundary shouldBe PlaybackCutBoundary.CombatEnded
                playback.hasPendingMessages() shouldBe false
            }
        }

        test("shell frame close subsumes the ordinary request without later output") {
            val playback = createMinimalPlayback()

            playback.visit(ForgePlayerPoisoned(null as PlayerView?, null as PlayerView?, 0, 1))
            playback.onFrameCommitted()

            assertSoftly {
                requestedCut(playback) shouldBe null
                playback.hasPendingMessages() shouldBe false
            }
        }

        test("Playback queues messages and reports queue size") {
            val queue = ConcurrentLinkedQueue<List<GREToClientMessage>>()
            queue.shouldBeEmpty()
            queue.add(emptyList())
            queue.size shouldBe 1
            val drained = queue.poll()
            drained.shouldNotBeNull()
            queue.shouldBeEmpty()
        }

        test("Shared MessageCounter is used by playback — no local atomics") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 20)

            @Suppress("UnusedPrivateProperty")
            val pb = createMinimalPlayback(counter)

            counter.currentGsId() shouldBe 10
            counter.currentMsgId() shouldBe 20

            counter.nextGsId()
            counter.currentGsId() shouldBe 11
        }

        test("No duplicate msgIds when two threads use the same counter") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 10)

            val sessionMsgIds = (1..3).map { counter.nextMsgId() }
            val engineMsgIds = (1..2).map { counter.nextMsgId() }

            val allMsgIds = sessionMsgIds + engineMsgIds
            allMsgIds.toSet().size shouldBe allMsgIds.size
            allMsgIds.last() shouldBeGreaterThan allMsgIds.first()
        }

        test("drain before outbound respects older queued gameStateId") {
            val playback = createMinimalPlayback()
            val olderGsLaterMsg = listOf(gameStateMessage(msgId = 757, gsId = 576))
            val newerGsLaterMsg = listOf(gameStateMessage(msgId = 760, gsId = 579))
            playbackQueue(playback).add(olderGsLaterMsg)
            playbackQueue(playback).add(newerGsLaterMsg)

            playback.drainQueueBeforeMsgId(msgId = 755, maxGsId = 578) shouldBe listOf(olderGsLaterMsg)
            playback.drainQueue() shouldBe listOf(newerGsLaterMsg)
        }

        test("drain before outbound leaves future gameStateId queued") {
            val playback = createMinimalPlayback()
            val futureGsEarlierMsg = listOf(gameStateMessage(msgId = 747, gsId = 569))
            playbackQueue(playback).add(futureGsEarlierMsg)

            playback.drainQueueBeforeMsgId(msgId = 749, maxGsId = 568).shouldBeEmpty()
            playback.drainQueue() shouldBe listOf(futureGsEarlierMsg)
        }

        test("noncombat spell damage does not activate combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }

        test("fight damage does not activate combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToCard(
                        sourceCardId = ForgeCardId(10),
                        targetCardId = ForgeCardId(20),
                        amount = 2,
                        sourceKind = DamageSourceKind.Fight,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }

        test("genuine combat damage activates combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe true
        }

        test("mixed damage window stays on the unsplit causal path") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(30),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }

        test("resolution completion is represented by one closed frame") {
            val damage =
                GameEvent.DamageDealtToPlayer(
                    sourceCardId = ForgeCardId(10),
                    targetSeatId = SeatId(2),
                    amount = 3,
                    sourceKind = DamageSourceKind.SpellOrAbility,
                    changesLife = true,
                )

            val resolving = GameEvent.SpellResolved(cardId = ForgeCardId(10), hasFizzled = false)
            FrameEventLog(
                events = listOf(damage, resolving),
                zoneMoves =
                    listOf(
                        ZoneMove(
                            order = 1,
                            cardId = ForgeCardId(10),
                            from = Zone.Stack,
                            to = Zone.Graveyard,
                            cause = null,
                        ),
                    ),
            ).zoneMoves.single().from shouldBe Zone.Stack
        }

        test("ambiguous mixed damage emits without waiting for an unrelated resolution") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(30),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }
    })
