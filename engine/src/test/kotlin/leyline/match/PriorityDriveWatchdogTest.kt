package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

/**
 * Pure decision-logic tests for [PriorityDriveWatchdog.tick]. The orphan
 * signature is "same un-prompted pending action across staleChecks ticks";
 * a prompted window or a cleared/changed pending must never trigger a re-drive.
 */
@Suppress("MissingAssertSoftly")
class PriorityDriveWatchdogTest :
    FunSpec({
        tags(UnitTag)

        fun watchdog(
            probes: List<PriorityDriveWatchdog.Probe?>,
            staleChecks: Int = 2,
        ): Pair<PriorityDriveWatchdog, IntArray> {
            val redrives = intArrayOf(0)
            val i = 0
            val wd =
                PriorityDriveWatchdog(
                    probe = { probes[i.coerceAtMost(probes.size - 1)] },
                    redrive = { redrives[0]++ },
                    staleChecks = staleChecks,
                )
            return wd to redrives
        }

        test("re-drives an un-prompted pending action that persists staleChecks ticks") {
            val redrives = intArrayOf(0)
            val p = PriorityDriveWatchdog.Probe("a", prompted = false)
            val wd = PriorityDriveWatchdog(probe = { p }, redrive = { redrives[0]++ }, staleChecks = 2)

            wd.tick() shouldBe false // first sighting
            wd.tick() shouldBe true // second sighting of same un-prompted id → re-drive
            redrives[0] shouldBe 1
        }

        test("never re-drives a prompted window") {
            val redrives = intArrayOf(0)
            val p = PriorityDriveWatchdog.Probe("a", prompted = true)
            val wd = PriorityDriveWatchdog(probe = { p }, redrive = { redrives[0]++ }, staleChecks = 2)

            repeat(10) { wd.tick() shouldBe false }
            redrives[0] shouldBe 0
        }

        test("never re-drives when there is no pending action") {
            val redrives = intArrayOf(0)
            val wd = PriorityDriveWatchdog(probe = { null }, redrive = { redrives[0]++ }, staleChecks = 2)

            repeat(10) { wd.tick() shouldBe false }
            redrives[0] shouldBe 0
        }

        test("a new (different) un-prompted action resets the stale counter") {
            val redrives = intArrayOf(0)
            val probes =
                ArrayDeque(
                    listOf(
                        PriorityDriveWatchdog.Probe("a", false),
                        PriorityDriveWatchdog.Probe("b", false), // different id → reset, no re-drive yet
                        PriorityDriveWatchdog.Probe("b", false), // second sighting of b → re-drive
                    ),
                )
            val wd = PriorityDriveWatchdog(probe = { probes.removeFirst() }, redrive = { redrives[0]++ }, staleChecks = 2)

            wd.tick() shouldBe false // a #1
            wd.tick() shouldBe false // b #1 (reset)
            wd.tick() shouldBe true // b #2 → re-drive
            redrives[0] shouldBe 1
        }

        test("a window that gets prompted between ticks is not re-driven") {
            val redrives = intArrayOf(0)
            val probes =
                ArrayDeque(
                    listOf(
                        PriorityDriveWatchdog.Probe("a", false), // un-prompted
                        PriorityDriveWatchdog.Probe("a", true), // now prompted → reset
                        PriorityDriveWatchdog.Probe("a", false), // un-prompted again (new window reuse) → first sighting
                    ),
                )
            val wd = PriorityDriveWatchdog(probe = { probes.removeFirst() }, redrive = { redrives[0]++ }, staleChecks = 2)

            wd.tick() shouldBe false
            wd.tick() shouldBe false // prompted resets
            wd.tick() shouldBe false // only first sighting since reset
            redrives[0] shouldBe 0
        }

        test("keeps re-driving on each staleChecks cycle if the orphan persists") {
            val redrives = intArrayOf(0)
            val p = PriorityDriveWatchdog.Probe("a", prompted = false)
            val wd = PriorityDriveWatchdog(probe = { p }, redrive = { redrives[0]++ }, staleChecks = 2)

            // Two full cycles: tick,tick(redrive) ; tick,tick(redrive)
            wd.tick()
            wd.tick()
            wd.tick()
            wd.tick()
            redrives[0] shouldBe 2
        }
    })
