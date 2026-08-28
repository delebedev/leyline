package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.game.InMemoryCardRepository
import leyline.game.bundle.LogicalSequenceState
import java.lang.reflect.Modifier

class ProjectionStateTest :
    FunSpec({
        tags(UnitTag)

        test("private editor freezes a complete value without changing prior state") {
            val prior = ProjectionState.initial(sequence = LogicalSequenceState(currentGsId = 7, committedOutputOrdinal = 11))
            val editor = prior.editor()
            editor.identities.getOrAlloc(ForgeCardId(1))
            editor.limboInstanceIds += 99

            val next = editor.freeze()
            assertSoftly {
                next.identities.forgeIdToInstanceId.keys shouldBe setOf(ForgeCardId(1))
                next.limboInstanceIds shouldBe setOf(99)
                next.sequence shouldBe prior.sequence
                Modifier.isPrivate(ProjectionState.Editor::class.java.getDeclaredField("sequence").modifiers) shouldBe true
            }
        }

        test("stale top-level transition installs nothing") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val prior = bridge.projectionStateSnapshot()
            val (_, firstNext) = bridge.editProjection(prior) { it.identities.getOrAlloc(ForgeCardId(1)) }
            bridge.commitProjection(ProjectionTransition(prior.revision, firstNext))
            val committed = bridge.projectionStateSnapshot()

            bridge.installProjection(
                ProjectionTransition(
                    expectedRevision = prior.revision,
                    nextState = firstNext.copy(limboInstanceIds = setOf(777)),
                ),
            ) shouldBe false
            bridge.projectionStateSnapshot() shouldBe committed
        }

        test("existing identity lookup does not advance committed revision") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val forgeCardId = ForgeCardId(1)
            val instanceId = bridge.getOrAllocInstanceId(forgeCardId)
            val committed = bridge.projectionStateSnapshot()

            bridge.getOrAllocInstanceId(forgeCardId) shouldBe instanceId
            bridge.projectionStateSnapshot() shouldBe committed
        }

        test("engine-shell projection writes preserve logical sequence") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val seeded =
                bridge.projectionStateSnapshot().copy(
                    sequence = LogicalSequenceState(currentGsId = 12, currentMsgId = 18, committedOutputOrdinal = 4),
                )
            bridge.replaceProjectionStateForTest(seeded)

            bridge.reserveInstanceId()
            bridge.getOrAllocInstanceId(ForgeCardId(99))

            bridge.committedSequence() shouldBe seeded.sequence
        }
    })
