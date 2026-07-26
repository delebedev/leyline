package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.SeatId
import leyline.game.bundle.MessageCounter
import leyline.infra.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage

class MatchOutboxTest :
    FunSpec({
        tags(UnitTag)

        test("one commit assigns one monotonic sequence to every target") {
            val outbox = MatchOutbox()
            val player = outbox.bind(MatchOutbox.Audience.Player(SeatId(1)))
            val familiar = outbox.bind(MatchOutbox.Audience.Familiar(SeatId(2)))

            val first =
                outbox.append(
                    listOf(
                        player to grePayload(1),
                        familiar to grePayload(2),
                    ),
                )
            val second = outbox.append(listOf(player to grePayload(3)))

            assertSoftly {
                first shouldBe 1L
                second shouldBe 2L
                outbox.peek(player)?.sequence shouldBe first
                outbox.peek(familiar)?.sequence shouldBe first
            }
            outbox.acknowledge(player, first)
            outbox.peek(player)?.sequence shouldBe second
        }

        test("a failed delivery retains its prefix and blocks later entries") {
            val owner = MatchOwner("failure-prefix")
            val sink = DeferredMessageSink()
            val head =
                MatchProtocolHead(
                    owner,
                    MatchOutbox.Audience.Player(SeatId(1)),
                    sink,
                )

            owner.reduce {
                owner.appendOutbox(listOf(head.token to grePayload(1)))
                head.flush()
                owner.appendOutbox(listOf(head.token to grePayload(2)))
                head.flush()
            }
            sink.msgIds shouldContainExactly listOf(1)

            sink.completeNext(IllegalStateException("closed"))
            owner.reduce {}
            owner.reduce { head.flush() }
            sink.msgIds shouldContainExactly listOf(1, 1)

            sink.completeNext()
            owner.reduce {}
            sink.msgIds shouldContainExactly listOf(1, 1, 2)
            sink.completeNext()
            owner.reduce {}

            head.close()
            owner.close()
            owner.awaitTermination()
        }

        test("replacement generation drops old pending output and fences stale acknowledgement") {
            val outbox = MatchOutbox()
            val audience = MatchOutbox.Audience.Player(SeatId(1))
            val first = outbox.bind(audience)
            val sequence = outbox.append(listOf(first to grePayload(1)))
            val replacement = outbox.bind(audience)

            outbox.peek(first) shouldBe null
            shouldThrow<IllegalStateException> {
                outbox.acknowledge(first, sequence)
            }

            val replacementSequence = outbox.append(listOf(replacement to grePayload(2)))
            outbox.peek(replacement)?.sequence shouldBe replacementSequence
            outbox.peek(replacement)?.payload shouldBe grePayload(2)
        }

        test("terminal action waits for every accepted prefix entry") {
            val owner = MatchOwner("terminal-drain")
            val sink = DeferredMessageSink()
            val head =
                MatchProtocolHead(
                    owner,
                    MatchOutbox.Audience.Player(SeatId(1)),
                    sink,
                )
            var drained = false

            owner.reduce {
                owner.appendOutbox(listOf(head.token to grePayload(1)))
                head.flush()
                owner.appendOutbox(listOf(head.token to rawPayload()))
                head.flush()
                head.afterDrained { drained = true }
            }
            assertSoftly {
                sink.msgIds shouldContainExactly listOf(1)
                sink.rawCount shouldBe 0
                drained shouldBe false
            }

            sink.completeNext()
            owner.reduce {}
            assertSoftly {
                sink.rawCount shouldBe 1
                drained shouldBe false
            }

            sink.completeNext()
            owner.reduce {}
            drained shouldBe true

            head.close()
            owner.close()
            owner.awaitTermination()
        }

        test("terminal action waits for the mirrored Familiar head") {
            val owner = MatchOwner("familiar-terminal-drain")
            val playerSink = DeferredMessageSink()
            val familiarSink = DeferredMessageSink()
            val familiar =
                FamiliarSession(
                    SeatId(2),
                    "familiar-terminal-drain",
                    familiarSink,
                    owner = owner,
                )
            val outbox =
                MatchSessionOutbox(
                    owner,
                    SeatId(1),
                    playerSink,
                    MessageCounter(),
                    recorder = null,
                    familiarProvider = { familiar },
                )
            var drained = false

            owner.reduce {
                outbox.sendGre(grePayload(1).messages, mirror = true)
                outbox.afterDrained { drained = true }
            }
            playerSink.completeNext()
            owner.reduce {}
            drained shouldBe false

            familiarSink.completeNext()
            owner.reduce {}
            drained shouldBe true

            outbox.close()
            familiar.close()
            owner.close()
            owner.awaitTermination()
        }
    })

private fun grePayload(msgId: Int): MatchOutbox.Payload.Gre =
    MatchOutbox.Payload.Gre(
        listOf(
            GREToClientMessage
                .newBuilder()
                .setMsgId(msgId)
                .build(),
        ),
    )

private fun rawPayload(): MatchOutbox.Payload.Raw = MatchOutbox.Payload.Raw(MatchServiceToClientMessage.getDefaultInstance())

private class DeferredMessageSink : MessageSink {
    val msgIds = mutableListOf<Int>()
    var rawCount = 0
        private set
    private val completions = ArrayDeque<(Throwable?) -> Unit>()

    override fun send(messages: List<GREToClientMessage>) {
        error("Completion-aware send required")
    }

    override fun send(
        messages: List<GREToClientMessage>,
        completion: (Throwable?) -> Unit,
    ) {
        msgIds += messages.single().msgId
        completions.addLast(completion)
    }

    override fun sendRaw(msg: MatchServiceToClientMessage) {
        error("Completion-aware send required")
    }

    override fun sendRaw(
        msg: MatchServiceToClientMessage,
        completion: (Throwable?) -> Unit,
    ) {
        rawCount += 1
        completions.addLast(completion)
    }

    fun completeNext(error: Throwable? = null) {
        completions.removeFirst()(error)
    }
}
