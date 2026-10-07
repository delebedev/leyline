package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.UnitTag
import leyline.game.codes.DetailKeys
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.*

class InvariantCheckerTransitionTest :
    FunSpec({
        tags(UnitTag)

        fun detail(
            key: String,
            value: Int,
        ) = KeyValuePairInfo
            .newBuilder()
            .setKey(key)
            .addValueInt32(value)
            .build()

        fun zone(
            id: Int,
            type: ZoneType,
            vararg ids: Int,
        ) = ZoneInfo
            .newBuilder()
            .setZoneId(
                id,
            ).setType(type)
            .setVisibility(Visibility.Public)
            .addAllObjectInstanceIds(ids.toList())
            .build()

        fun change(
            old: Int,
            new: Int,
        ) = AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ObjectIdChanged)
            .addDetails(detail(DetailKeys.ORIG_ID, old))
            .addDetails(detail(DetailKeys.NEW_ID, new))
            .build()

        fun transfer(
            id: Int,
            source: Int,
            destination: Int,
            category: String,
        ) = AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ZoneTransfer_af5a)
            .addAffectedIds(id)
            .addDetails(detail(DetailKeys.ZONE_SRC, source))
            .addDetails(detail(DetailKeys.ZONE_DEST, destination))
            .addDetails(KeyValuePairInfo.newBuilder().setKey(DetailKeys.CATEGORY).addValueString(category))
            .build()
        val cast = transfer(200, ZoneIds.P1_HAND, ZoneIds.STACK, "CastSpell")
        val pair = change(100, 200)
        val stackObject =
            GameObjectInfo
                .newBuilder()
                .setInstanceId(200)
                .setZoneId(ZoneIds.STACK)
                .build()
        val clean =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(1)
                .addAnnotations(pair)
                .addAnnotations(cast)
                .addGameObjects(stackObject)
                .addZones(zone(ZoneIds.P1_HAND, ZoneType.Hand))
                .addZones(zone(ZoneIds.STACK, ZoneType.Stack, 200))
                .addZones(zone(ZoneIds.LIMBO, ZoneType.Limbo, 100))
                .build()

        fun check(gsm: GameStateMessage): List<InvariantChecker.Violation> =
            InvariantChecker().also { it.process(GREToClientMessage.newBuilder().setGameStateMessage(gsm).build()) }.violations

        test("ordinary transitions reject malformed pairs and projections") {
            check(clean).shouldBeEmpty()
            val mutants =
                listOf(
                    clean.toBuilder().setAnnotations(0, change(100, 201)).build(),
                    clean.toBuilder().setAnnotations(0, change(200, 200)).build(),
                    clean.toBuilder().setAnnotations(1, cast.toBuilder().clearAffectedIds().addAffectedIds(201)).build(),
                    clean.toBuilder().setZones(0, zone(ZoneIds.P1_HAND, ZoneType.Hand, 100)).build(),
                    clean.toBuilder().clearGameObjects().build(),
                    clean
                        .toBuilder()
                        .clearGameObjects()
                        .setZones(1, zone(ZoneIds.STACK, ZoneType.Stack))
                        .addAnnotations(change(200, 300))
                        .build(),
                    clean.toBuilder().setZones(1, zone(ZoneIds.STACK, ZoneType.Stack)).build(),
                    clean
                        .toBuilder()
                        .clearAnnotations()
                        .addAnnotations(cast)
                        .addAnnotations(pair)
                        .build(),
                    clean.toBuilder().setAnnotations(1, cast.toBuilder().clearAffectedIds()).build(),
                )
            for (mutant in mutants) {
                check(mutant).map { it.check }.shouldContain(InvariantCheck.ZoneTransitionIdentity.id)
            }
        }

        test("Limbo hidden and same-frame chain identities remain valid") {
            // Hidden destinations expose membership without requiring an object row.
            check(
                clean
                    .toBuilder()
                    .clearGameObjects()
                    .setZones(1, clean.getZones(1).toBuilder().setVisibility(Visibility.Hidden))
                    .build(),
            ).shouldBeEmpty()
            // A shuffle-only identity change is outside ordinary spell transfers.
            check(
                clean
                    .toBuilder()
                    .clearAnnotations()
                    .addAnnotations(change(100, 201))
                    .build(),
            ).shouldBeEmpty()
            val chain =
                clean
                    .toBuilder()
                    .clearGameObjects()
                    .addGameObjects(stackObject.toBuilder().setInstanceId(300).setZoneId(ZoneIds.P1_GRAVEYARD))
                    .addAnnotations(change(200, 300))
                    .addAnnotations(transfer(300, ZoneIds.STACK, ZoneIds.P1_GRAVEYARD, "Resolve"))
                    .setZones(1, zone(ZoneIds.STACK, ZoneType.Stack))
                    .setZones(2, zone(ZoneIds.LIMBO, ZoneType.Limbo, 100, 200))
                    .addZones(zone(ZoneIds.P1_GRAVEYARD, ZoneType.Graveyard, 300))
                    .build()
            assertSoftly {
                check(chain).shouldBeEmpty()
                check(chain.toBuilder().setZones(1, zone(ZoneIds.STACK, ZoneType.Stack, 200)).build()).shouldNotBeEmpty()
                check(chain.toBuilder().clearGameObjects().build()).shouldNotBeEmpty()
                check(
                    chain.toBuilder().setAnnotations(3, transfer(300, ZoneIds.P1_HAND, ZoneIds.P1_GRAVEYARD, "Resolve")).build(),
                ).shouldNotBeEmpty()
            }
        }
    })
