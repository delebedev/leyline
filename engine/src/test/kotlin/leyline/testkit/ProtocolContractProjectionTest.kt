package leyline.testkit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq

class ProtocolContractProjectionTest :
    FunSpec({
        tags(UnitTag)

        test("a separate prompt binds the preceding state identity without sharing its packet") {
            val specification =
                contract(
                    """
                    - id: created
                      events:
                        - {id: created, type: AbilityInstanceCreated, lane: transient}
                    - id: prompt
                      events:
                        - id: targets
                          type: SelectTargetsReq
                          lane: prompt
                          equals: {gameStateId: created.gameStateId}
                    """.trimIndent(),
                )
            val created =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateMessage(
                        GameStateMessage.newBuilder().setGameStateId(42).addAnnotations(
                            AnnotationInfo.newBuilder().addType(AnnotationType.AbilityInstanceCreated),
                        ),
                    ).build()
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateId(42)
                    .setSelectTargetsReq(SelectTargetsReq.getDefaultInstance())
                    .build()
            specification.verify(listOf(created, prompt))
            shouldThrow<AssertionError> { specification.verify(listOf(created, prompt.toBuilder().setGameStateId(41).build())) }
        }

        test("persistent publications and deletion IDs have no relative wire order") {
            val introduced = state(rows = listOf(target))
            val completed = state(rows = listOf(source), deleted = listOf(target.id))
            val retired = "{id: retired, type: TargetSpec, lane: persistent, op: delete, sameRow: target}"
            val created = "{id: source, type: TriggeringObject, lane: persistent, op: create}"
            for (events in listOf(listOf(retired, created), listOf(created, retired))) {
                val contract =
                    contract(
                        "- id: start\n  events:\n    - {id: target, type: TargetSpec, lane: persistent, op: create}\n" +
                            "- id: finish\n  events:\n" + events.joinToString("\n") { "    - $it" },
                    )
                contract.verify(listOf(introduced, completed))
                shouldThrow<AssertionError> {
                    contract.verify(
                        listOf(introduced, state(deleted = listOf(target.id)), state(rows = listOf(source))),
                    )
                }
            }
        }

        test("each persistent wire list retains its own order") {
            val publications =
                contract(
                    """
                    - id: publications
                      events:
                        - {id: target, type: TargetSpec, lane: persistent, op: create}
                        - {id: source, type: TriggeringObject, lane: persistent, op: create}
                    """.trimIndent(),
                )
            publications.verify(listOf(state(rows = listOf(target, source))))
            shouldThrow<AssertionError> { publications.verify(listOf(state(rows = listOf(source, target)))) }
            val deletions =
                contract(
                    """
                    - id: introduced
                      events:
                        - {id: target, type: TargetSpec, lane: persistent, op: create}
                        - {id: source, type: TriggeringObject, lane: persistent, op: create}
                    - id: retired
                      events:
                        - {id: target-retired, type: TargetSpec, lane: persistent, op: delete, sameRow: target}
                        - {id: source-retired, type: TriggeringObject, lane: persistent, op: delete, sameRow: source}
                    """.trimIndent(),
                )
            val baseline = state(rows = listOf(target, source))
            deletions.verify(listOf(baseline, state(deleted = listOf(target.id, source.id))))
            shouldThrow<AssertionError> { deletions.verify(listOf(baseline, state(deleted = listOf(source.id, target.id)))) }
        }

        test("list frontiers prevent backwards matches across frames and reuse of type aliases") {
            val backwards =
                contract(
                    """
                    - id: first
                      events:
                        - {id: source, type: TriggeringObject, lane: persistent}
                    - id: next
                      events:
                        - {id: target, type: TargetSpec, lane: persistent}
                    """.trimIndent(),
                )
            shouldThrow<AssertionError> { backwards.verify(listOf(state(rows = listOf(target, source)))) }
            backwards.verify(listOf(state(rows = listOf(source)), state(rows = listOf(target))))
            val aliases = source.toBuilder().addType(AnnotationType.TargetSpec).build()
            shouldThrow<AssertionError> { backwards.verify(listOf(state(rows = listOf(aliases)))) }
        }

        test("independent rails keep the first selected publication strict") {
            val specification =
                contract(
                    """
                    - id: introduced
                      events:
                        - {id: target, type: TargetSpec, lane: persistent}
                    - id: completed
                      events:
                        - {id: retired, type: TargetSpec, lane: persistent, op: delete, sameRow: target}
                        - id: source
                          type: TriggeringObject
                          lane: persistent
                          fields: {affectorId: 100}
                    """.trimIndent(),
                )
            val wrong = source.toBuilder().setAffectorId(999).build()
            shouldThrow<AssertionError> {
                specification.verify(listOf(state(rows = listOf(target)), state(rows = listOf(wrong, source), deleted = listOf(target.id))))
            }
        }

        test("Full omission ends holds even when the same ID reappears") {
            val holds =
                contract(
                    """
                    - id: introduced
                      events:
                        - {id: target, type: TargetSpec, lane: persistent, op: create}
                    - id: finish
                      events:
                        - {id: finished, type: ResolutionComplete, lane: transient}
                    """.trimIndent(),
                    """
                    windows:
                      - {id: holds, holds: target, until: finished}
                    """.trimIndent(),
                )
            val introduced = state(rows = listOf(target))
            val full = state(type = GameStateType.Full)
            val finish = state(annotations = listOf(resolved))
            holds.verify(listOf(introduced, state(), finish))
            for (mutant in listOf(
                listOf(introduced, full, finish),
                listOf(introduced, full, state(rows = listOf(target)), finish),
                listOf(introduced, state(type = GameStateType.Full, annotations = listOf(resolved))),
            )) {
                shouldThrow<AssertionError> { holds.verify(mutant) }
            }
        }

        test("snapshot disappearance is not explicit deletion and reappearance is creation") {
            val reappeared =
                contract(
                    """
                    - id: introduced
                      events:
                        - {id: target, type: TargetSpec, lane: persistent, op: create}
                    - id: reappeared
                      events:
                        - {id: target-again, type: TargetSpec, lane: persistent, op: create, sameRow: target}
                    """.trimIndent(),
                    """
                    counts:
                      - {match: {type: TargetSpec, lane: persistent, op: delete}, exactly: 0}
                      - {match: {type: TargetSpec, lane: persistent, op: create}, exactly: 2}
                    """.trimIndent(),
                )
            val introduced = state(rows = listOf(target))
            reappeared.verify(listOf(introduced, state(type = GameStateType.Full), introduced))
            shouldThrow<AssertionError> { reappeared.verify(listOf(introduced, state(), introduced)) }
            val deletion =
                contract(
                    """
                    - id: introduced
                      events:
                        - {id: target, type: TargetSpec, lane: persistent, op: create}
                    - id: retired
                      events:
                        - {id: target-retired, type: TargetSpec, lane: persistent, op: delete, sameRow: target}
                    """.trimIndent(),
                )
            shouldThrow<AssertionError> { deletion.verify(listOf(introduced, state(type = GameStateType.Full))) }
            deletion.verify(listOf(introduced, state(deleted = listOf(target.id))))
        }

        test("unchanged Full and Diff publications remain observable updates") {
            val specification =
                contract(
                    """
                    - id: introduced
                      events:
                        - {id: target, type: TargetSpec, lane: persistent, op: create}
                    """.trimIndent(),
                    """
                    counts:
                      - {match: {type: TargetSpec, lane: persistent, op: create}, exactly: 1}
                      - {match: {type: TargetSpec, lane: persistent, op: update}, exactly: 2}
                    """.trimIndent(),
                )
            val introduced = state(rows = listOf(target))
            specification.verify(listOf(introduced, state(type = GameStateType.Full, rows = listOf(target)), introduced))
            shouldThrow<AssertionError> { specification.verify(listOf(introduced, introduced)) }
        }

        test("Full omission and explicit object removal reset first-publication identity") {
            val specification =
                contract(
                    """
                    - id: introduced
                      events:
                        - {id: card, type: Card, lane: object, op: create}
                    """.trimIndent(),
                    """
                    counts:
                      - {match: {type: Card, lane: object, op: create}, exactly: 2}
                      - {match: {type: Card, lane: object, op: update}, exactly: 0}
                    """.trimIndent(),
                )
            val card =
                GameObjectInfo
                    .newBuilder()
                    .setInstanceId(100)
                    .setType(GameObjectType.Card)
                    .build()
            val introduced = state(objects = listOf(card))
            val removed =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateMessage(
                        GameStateMessage.newBuilder().setType(GameStateType.Diff).addDiffDeletedInstanceIds(card.instanceId),
                    ).build()
            for (removal in listOf(state(type = GameStateType.Full), removed)) {
                specification.verify(listOf(introduced, removal, introduced))
            }
            shouldThrow<AssertionError> { specification.verify(listOf(introduced, state(), introduced)) }
        }

        test("explicit repeated deletions retain identity and exact counts") {
            val specification =
                contract(
                    """
                    - id: introduced
                      events:
                        - {id: target, type: TargetSpec, lane: persistent, op: create}
                    """.trimIndent(),
                    """
                    counts:
                      - {match: {type: TargetSpec, lane: persistent, op: delete, sameRow: target}, exactly: 2}
                    """.trimIndent(),
                )
            val retirement = state(deleted = listOf(target.id))
            specification.verify(listOf(state(rows = listOf(target)), retirement, retirement))
            shouldThrow<AssertionError> { specification.verify(listOf(state(rows = listOf(target)), retirement)) }
        }

        test("interval windows reject ambiguous cross-list anchors and matching boundary events") {
            val specification =
                contract(
                    """
                    - id: frame
                      events:
                        - {id: started, type: ResolutionStart, lane: transient}
                        - {id: source, type: TriggeringObject, lane: persistent}
                    """.trimIndent(),
                    """
                    windows:
                      - {id: interval, after: started, before: source, absent: {type: TargetSpec, lane: persistent}}
                    """.trimIndent(),
                )
            val failure =
                shouldThrow<AssertionError> {
                    specification.verify(listOf(state(rows = listOf(source), annotations = listOf(started))))
                }
            failure.message.orEmpty() shouldContain "observable wire order"
            val acrossMessages =
                contract(
                    """
                    - id: first
                      events:
                        - {id: started, type: ResolutionStart, lane: transient}
                    - id: last
                      events:
                        - {id: finished, type: ResolutionComplete, lane: transient}
                    """.trimIndent(),
                    """
                    windows:
                      - {id: interval, after: started, before: finished, absent: {type: TargetSpec, lane: persistent}}
                    """.trimIndent(),
                )
            acrossMessages.verify(listOf(state(annotations = listOf(started)), state(annotations = listOf(resolved))))
            val boundary =
                shouldThrow<AssertionError> {
                    acrossMessages.verify(
                        listOf(state(rows = listOf(target), annotations = listOf(started)), state(annotations = listOf(resolved))),
                    )
                }
            boundary.message.orEmpty() shouldContain "no cross-list ordering"
            shouldThrow<AssertionError> {
                acrossMessages.verify(
                    listOf(state(annotations = listOf(started)), state(rows = listOf(target)), state(annotations = listOf(resolved))),
                )
            }
        }
    })

private val target =
    AnnotationInfo
        .newBuilder()
        .setId(7)
        .addType(AnnotationType.TargetSpec)
        .setAffectorId(100)
        .build()
private val source =
    AnnotationInfo
        .newBuilder()
        .setId(8)
        .addType(AnnotationType.TriggeringObject)
        .setAffectorId(100)
        .build()
private val started = AnnotationInfo.newBuilder().addType(AnnotationType.ResolutionStart).build()
private val resolved = AnnotationInfo.newBuilder().addType(AnnotationType.ResolutionComplete).build()

private fun state(
    type: GameStateType = GameStateType.Diff,
    rows: List<AnnotationInfo> = emptyList(),
    deleted: List<Int> = emptyList(),
    annotations: List<AnnotationInfo> = emptyList(),
    objects: List<GameObjectInfo> = emptyList(),
): GREToClientMessage =
    GREToClientMessage
        .newBuilder()
        .setGameStateMessage(
            GameStateMessage
                .newBuilder()
                .setType(type)
                .addAllPersistentAnnotations(rows)
                .addAllDiffDeletedPersistentAnnotationIds(deleted)
                .addAllAnnotations(annotations)
                .addAllGameObjects(objects),
        ).build()

private fun contract(
    frames: String,
    clauses: String = "",
): ProtocolContract =
    ProtocolContract.parse(
        "name: projection\nscenario: {suite: warmup, id: land-spell-face}\nframes:\n" + frames.prependIndent("  ") + "\n" + clauses,
    )
