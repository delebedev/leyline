package leyline.testkit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

class ProtocolContractTest :
    FunSpec({
        tags(UnitTag)

        test("malformed specs fail loading rather than weaken assertions") {
            for (invalid in listOf(
                contractText.replace("fields:", "fieldz:"),
                contractText.replace("details.damage", "unknown.damage"),
                contractText.replace("fields: {details.damage: [3]}", "equals: {affectorId: missing.affectorId}"),
                contractText.replace("type: DamageDealt", "type: UnknownEvent"),
                contractText.replace("type: DamageDealt", "type: DamageDealt\n            lane: object"),
                contractText.replace("type: DamageDealt", "type: DamageDealt\n            op: delete"),
                contractText.replace("name: damage", "name: damage\nname: duplicate"),
            )) {
                shouldThrowAny { ProtocolContract.parse(invalid) }
            }
        }

        test("co-occurrence and order are required within each frame") {
            val contract = ProtocolContract.parse(contractText)
            contract.verify(listOf(frame(start, damage)))
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start), frame(damage))) }
            shouldThrow<AssertionError> { contract.verify(listOf(frame(damage, start))) }
        }

        test("a later correct event cannot hide an earlier contradictory event") {
            val wrong = damage.toBuilder().setDetails(0, damage.getDetails(0).toBuilder().setValueInt32(0, 4)).build()
            shouldThrow<AssertionError> { ProtocolContract.parse(contractText).verify(listOf(frame(start, wrong, damage))) }
        }

        test("typed values and exact counts reject superficially similar output") {
            val text = contractText + "\ncounts:\n  - match: {type: DamageDealt}\n    exactly: 1\n"
            val contract = ProtocolContract.parse(text)
            contract.verify(listOf(frame(start, damage)))
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start, damage, damage))) }
            val stringDamage =
                damage
                    .toBuilder()
                    .setDetails(
                        0,
                        KeyValuePairInfo
                            .newBuilder()
                            .setKey("damage")
                            .setType(KeyValuePairValueType.String)
                            .addValueString("3"),
                    ).build()
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start, stringDamage))) }
        }
    })

private val contractText =
    """
    name: damage
    scenario: {suite: warmup, id: land-spell-face}
    frames:
      - id: resolution
        events:
          - id: start
            type: ResolutionStart
          - id: damage
            type: DamageDealt
            fields: {details.damage: [3]}
    """.trimIndent()

private val start = AnnotationInfo.newBuilder().addType(AnnotationType.ResolutionStart).build()
private val damage =
    AnnotationInfo
        .newBuilder()
        .addType(AnnotationType.DamageDealt_af5a)
        .addDetails(
            KeyValuePairInfo
                .newBuilder()
                .setKey("damage")
                .setType(KeyValuePairValueType.Int32)
                .addValueInt32(3),
        ).build()

private fun frame(vararg annotations: AnnotationInfo): GREToClientMessage =
    GREToClientMessage.newBuilder().setGameStateMessage(GameStateMessage.newBuilder().addAllAnnotations(annotations.toList())).build()
