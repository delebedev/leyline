package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.GamePlayback
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge

/** Playback reads committed sequence and owns no allocator. */
class GamePlaybackLogicalSequenceTest :
    FunSpec({

        tags(UnitTag)

        test("GamePlayback starts from committed sequence without a local planner") {
            val initial = LogicalSequenceState(currentGsId = 10, currentMsgId = 20, committedOutputOrdinal = 3)
            val bridge = GameBridge(cardRepository = InMemoryCardRepository(), initialSequence = initial)
            val playback = GamePlayback(bridge, 1)

            assertSoftly {
                playback.drainQueue().shouldBeEmpty()
                playback.hasPendingMessages().shouldBeFalse()
                bridge.committedSequence() shouldBe initial
            }
        }
    })
