package leyline.game.annotations

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationOrderEnforcer
import leyline.game.annotations.OrderRules
import leyline.game.eid
import leyline.game.grp
import leyline.game.iid
import leyline.game.sid
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import kotlin.random.Random

/**
 * Properties [AnnotationOrderEnforcer] holds for every input, complementing the
 * per-rule examples in [AnnotationOrderEnforcerTest].
 *
 * The rules in [OrderRules.all] contribute edges independently and the enforcer
 * merges them, so examples can only cover the rule combinations someone thought
 * to write. These assert what the enforcer owns whichever rules happen to fire,
 * and keep asserting it when a rule is added.
 *
 * Generation is seeded, so a failure names a reproducible seed rather than
 * appearing intermittently.
 */
class AnnotationOrderEnforcerPropertyTest :
    FunSpec({

        tags(UnitTag)

        // Spans the annotation types the active rules key on, so generated lists
        // exercise edge contribution instead of trivially producing none. Ids
        // overlap deliberately: several rules only contribute an edge when two
        // annotations name the same card.
        val builders: List<(Int) -> AnnotationInfo> =
            listOf(
                { i -> AnnotationBuilder.objectIdChanged(origId = (100 + i % 3).iid, newId = (200 + i % 3).iid) },
                { i ->
                    AnnotationBuilder.zoneTransfer(
                        instanceId = (200 + i % 3).iid,
                        srcZoneId = 31,
                        destZoneId = 28,
                        category = if (i % 2 == 0) "CastSpell" else "Resolve",
                    )
                },
                { i ->
                    AnnotationBuilder.userActionTaken(
                        instanceId = (200 + i % 3).iid,
                        seatId = 1.sid,
                        actionType = ActionType.Play_add3,
                    )
                },
                { i -> AnnotationBuilder.counterAdded(instanceId = (200 + i % 3).iid, counterType = "+1/+1", amount = 1) },
                { i -> AnnotationBuilder.layeredEffectCreated(effectId = (7000 + i).eid, affectorId = (200 + i % 3).iid) },
                { i -> AnnotationBuilder.controllerChanged(affectorId = (500 + i).iid, instanceId = (200 + i % 3).iid) },
                { i -> AnnotationBuilder.tappedUntappedPermanent(permanentId = (200 + i % 3).iid, abilityId = (104 + i).iid) },
                { i -> AnnotationBuilder.playerSubmittedTargets(instanceId = (200 + i % 3).iid, casterSeatId = 1.sid) },
                { i -> AnnotationBuilder.phaseOrStepModified(activeSeat = 1.sid, phase = 3 + i % 2, step = 0) },
                { i -> AnnotationBuilder.resolutionStart(instanceId = (200 + i % 3).iid, grpId = (900 + i).grp) },
                { i -> AnnotationBuilder.resolutionComplete(instanceId = (200 + i % 3).iid, grpId = (900 + i).grp) },
            )

        fun generate(seed: Int): List<AnnotationInfo> {
            val rng = Random(seed)
            val size = 2 + rng.nextInt(7)
            return (0 until size).map { i -> builders[rng.nextInt(builders.size)](i) }
        }

        /** Independent cycle check, so soundness is only demanded where it is owed. */
        fun acyclic(
            n: Int,
            edges: List<Pair<Int, Int>>,
        ): Boolean {
            val inDegree = IntArray(n)
            val adj = Array(n) { mutableListOf<Int>() }
            for ((from, to) in edges) {
                if (from == to) continue
                adj[from].add(to)
                inDegree[to]++
            }
            val queue = ArrayDeque((0 until n).filter { inDegree[it] == 0 })
            var visited = 0
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                visited++
                for (next in adj[idx]) {
                    inDegree[next]--
                    if (inDegree[next] == 0) queue.add(next)
                }
            }
            return visited == n
        }

        val seeds = (1..400).toList()

        // Guards the generator itself. If a builder signature drifts and the pool
        // stops producing edges, every property below would pass vacuously.
        test("generated frames exercise edge contribution, reordering, and the cycle fallback") {
            var withEdges = 0
            var reordered = 0
            var cyclic = 0
            seeds.forEach { seed ->
                val input = generate(seed)
                val edges = OrderRules.all.flatMap { it.edges(input) }
                if (edges.isNotEmpty()) withEdges++
                if (edges.isNotEmpty() && !acyclic(input.size, edges)) cyclic++
                val result = AnnotationOrderEnforcer.enforce(input)
                if (result.map { System.identityHashCode(it) } != input.map { System.identityHashCode(it) }) reordered++
            }

            withClue("frames contributing edges") { withEdges shouldBeGreaterThan seeds.size / 4 }
            withClue("frames actually reordered") { reordered shouldBeGreaterThan seeds.size / 10 }
            withClue("frames whose merged graph cycles, exercising the fallback") { cyclic shouldBeGreaterThan 0 }
        }

        test("enforce returns a permutation of its input for every generated frame") {
            seeds.forEach { seed ->
                val input = generate(seed)
                val result = AnnotationOrderEnforcer.enforce(input)

                withClue("seed=$seed") {
                    result.size shouldBe input.size
                    result.toSet() shouldBe input.toSet()
                }
            }
        }

        test("enforce respects every contributed edge whenever the merged graph is acyclic") {
            seeds.forEach { seed ->
                val input = generate(seed)
                val edges = OrderRules.all.flatMap { it.edges(input) }
                if (edges.isNotEmpty() && acyclic(input.size, edges)) {
                    val result = AnnotationOrderEnforcer.enforce(input)
                    val positionOf = input.indices.associateWith { i -> result.indexOfFirst { it === input[i] } }

                    edges.forEach { (from, to) ->
                        if (from != to) {
                            withClue("seed=$seed edge $from->$to") {
                                positionOf.getValue(from) shouldBeLessThan positionOf.getValue(to)
                            }
                        }
                    }
                }
            }
        }

        test("enforce is idempotent for every generated frame") {
            seeds.forEach { seed ->
                val input = generate(seed)
                val once = AnnotationOrderEnforcer.enforce(input)
                val twice = AnnotationOrderEnforcer.enforce(once)

                withClue("seed=$seed") {
                    twice.map { System.identityHashCode(it) } shouldBe once.map { System.identityHashCode(it) }
                }
            }
        }

        test("a frame contributing no edges is returned untouched") {
            seeds.forEach { seed ->
                val input = generate(seed)
                if (OrderRules.all.flatMap { it.edges(input) }.isEmpty()) {
                    withClue("seed=$seed") {
                        AnnotationOrderEnforcer.enforce(input) shouldBe input
                    }
                }
            }
        }
    })
