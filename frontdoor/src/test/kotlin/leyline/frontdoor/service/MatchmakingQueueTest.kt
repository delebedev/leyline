package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.frontdoor.FdTag

class MatchmakingQueueTest :
    FunSpec({

        tags(FdTag)

        test("first player waits") {
            val queue = MatchmakingQueue()
            val result = queue.pair(PairingEntry("Alice") { _, _ -> })
            result.shouldBeInstanceOf<PairResult.Waiting>()
            queue.hasWaiting().shouldBeTrue()
        }

        test("second player pairs with first") {
            val queue = MatchmakingQueue()
            queue.pair(PairingEntry("Alice") { _, _ -> })

            val result = queue.pair(PairingEntry("Bob") { _, _ -> })
            result.shouldBeInstanceOf<PairResult.Paired>()
            val paired = result as PairResult.Paired
            paired.seat1.screenName shouldBe "Alice"
            paired.seat2.screenName shouldBe "Bob"
            paired.matchId.isNotEmpty().shouldBeTrue()
            queue.hasWaiting().shouldBeFalse()
        }

        test("cancel removes waiting player") {
            val queue = MatchmakingQueue()
            queue.pair(PairingEntry("Alice") { _, _ -> })
            queue.cancel("Alice").shouldBeTrue()
            queue.hasWaiting().shouldBeFalse()
        }

        test("cancel no-op for non-waiting player") {
            val queue = MatchmakingQueue()
            queue.cancel("Alice").shouldBeFalse()
        }

        test("pair triggers callbacks with correct seats") {
            val queue = MatchmakingQueue()
            var seat1Push: Pair<String, Int>? = null
            var seat2Push: Pair<String, Int>? = null

            queue.pair(PairingEntry("Alice") { mid, seat -> seat1Push = mid to seat })
            val result = queue.pair(PairingEntry("Bob") { mid, seat -> seat2Push = mid to seat })

            val paired = result as PairResult.Paired
            paired.seat1.pushCallback(paired.matchId, 1)
            paired.seat2.pushCallback(paired.matchId, 2)

            seat1Push!!.second shouldBe 1
            seat2Push!!.second shouldBe 2
            seat1Push!!.first shouldBe seat2Push!!.first
        }

        test("queue resets after pairing — third player waits") {
            val queue = MatchmakingQueue()
            queue.pair(PairingEntry("Alice") { _, _ -> })
            queue.pair(PairingEntry("Bob") { _, _ -> })

            queue.hasWaiting().shouldBeFalse()

            val result = queue.pair(PairingEntry("Carol") { _, _ -> })
            result.shouldBeInstanceOf<PairResult.Waiting>()
        }

        test("synthetic opponent — auto-pairs immediately") {
            val queue = MatchmakingQueue(syntheticOpponent = true)
            val result = queue.pair(PairingEntry("Alice") { _, _ -> })
            result.shouldBeInstanceOf<PairResult.Paired>()
            val paired = result as PairResult.Paired
            paired.seat1.screenName shouldBe "Alice"
            paired.seat2.screenName shouldBe MatchmakingQueue.SYNTHETIC_SCREEN_NAME
            paired.synthetic.shouldBeTrue()
            queue.hasWaiting().shouldBeFalse()
        }

        test("synthetic opponent preserves precomputed match id") {
            val queue = MatchmakingQueue(syntheticOpponent = true)
            val result = queue.pair(PairingEntry("Alice", matchId = "puzzle-bolt-face") { _, _ -> })
            val paired = result.shouldBeInstanceOf<PairResult.Paired>()
            paired.matchId shouldBe "puzzle-bolt-face"
        }

        test("synthetic opponent — real pair still works if someone waiting") {
            val queue = MatchmakingQueue(syntheticOpponent = true)
            // If someone is already waiting, real pairing takes priority
            queue.pair(PairingEntry("Alice") { _, _ -> })
                .shouldBeInstanceOf<PairResult.Paired>() // Alice auto-pairs with bot

            // Now Bob enters — also auto-pairs with bot
            val result = queue.pair(PairingEntry("Bob") { _, _ -> })
            result.shouldBeInstanceOf<PairResult.Paired>()
            (result as PairResult.Paired).synthetic.shouldBeTrue()
        }

        test("non-synthetic PairResult has synthetic=false") {
            val queue = MatchmakingQueue()
            queue.pair(PairingEntry("Alice") { _, _ -> })
            val result = queue.pair(PairingEntry("Bob") { _, _ -> })
            (result as PairResult.Paired).synthetic.shouldBeFalse()
        }
    })
