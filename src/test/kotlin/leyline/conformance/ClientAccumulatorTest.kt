package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.*

class ClientAccumulatorTest :
    FunSpec({

        test("fullStateReplacesAllObjects") {
            val acc = ClientAccumulator()

            val gs = GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(1)
                .addGameObjects(
                    GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
                )
                .addGameObjects(
                    GameObjectInfo.newBuilder().setInstanceId(101).setType(GameObjectType.Card),
                )
                .build()
            acc.process(greMessage(msgId = 1, gsm = gs))

            acc.objects.size shouldBe 2
            acc.objects.containsKey(100).shouldBeTrue()
            acc.objects.containsKey(101).shouldBeTrue()
            acc.latestGsId shouldBe 1
        }

        test("diffMergesIntoExistingState") {
            val acc = ClientAccumulator()

            val full = GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(1)
                .addGameObjects(
                    GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
                )
                .build()
            acc.process(greMessage(msgId = 1, gsm = full))

            val diff = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(2)
                .addGameObjects(
                    GameObjectInfo.newBuilder().setInstanceId(101).setType(GameObjectType.Card),
                )
                .build()
            acc.process(greMessage(msgId = 2, gsm = diff))

            acc.objects.size shouldBe 2
            acc.objects.containsKey(100).shouldBeTrue()
            acc.objects.containsKey(101).shouldBeTrue()
            acc.latestGsId shouldBe 2
        }

        test("fullStateResetsObjects") {
            val acc = ClientAccumulator()

            val full1 = GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(1)
                .addGameObjects(
                    GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
                )
                .build()
            acc.process(greMessage(msgId = 1, gsm = full1))

            val full2 = GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(2)
                .addGameObjects(
                    GameObjectInfo.newBuilder().setInstanceId(200).setType(GameObjectType.Card),
                )
                .build()
            acc.process(greMessage(msgId = 2, gsm = full2))

            acc.objects.size shouldBe 1
            acc.objects.containsKey(100).shouldBeFalse()
            acc.objects.containsKey(200).shouldBeTrue()
        }

        test("zonesTrackedFromState") {
            val acc = ClientAccumulator()

            val gs = GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(1)
                .addZones(
                    ZoneInfo.newBuilder().setZoneId(10).setType(ZoneType.Hand)
                        .addObjectInstanceIds(100).addObjectInstanceIds(101),
                )
                .addZones(
                    ZoneInfo.newBuilder().setZoneId(20).setType(ZoneType.Library)
                        .addObjectInstanceIds(200),
                )
                .build()
            acc.process(greMessage(msgId = 1, gsm = gs))

            acc.zones.size shouldBe 2
            acc.zones[10]!!.objectInstanceIdsList.size shouldBe 2
        }

        test("gsIdMonotonicInvariant") {
            val acc = ClientAccumulator()

            val gs1 = GameStateMessage.newBuilder()
                .setType(GameStateType.Full).setGameStateId(5).build()
            acc.process(greMessage(msgId = 1, gsm = gs1))
            acc.latestGsId shouldBe 5

            val gs2 = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff).setGameStateId(3).build()
            acc.process(greMessage(msgId = 2, gsm = gs2))

            acc.latestGsId shouldBe 5
        }

        test("actionsAvailableReqTracked") {
            val acc = ClientAccumulator()

            acc.process(
                actionsMessage(msgId = 1, gsId = 1) {
                    addActions(
                        Action.newBuilder()
                            .setActionType(ActionType.Play_add3)
                            .setInstanceId(100),
                    )
                },
            )

            val storedActions = checkNotNull(acc.actions)
            storedActions.actionsCount shouldBe 1
        }

        test("actionInstanceIdsMissingFromObjectsDetectsMissing") {
            val acc = ClientAccumulator()

            // Full state with object 100 only
            val gs = GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(1)
                .addGameObjects(
                    GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
                )
                .build()
            acc.process(greMessage(msgId = 1, gsm = gs))

            // Actions referencing iid 100 (exists) and 200 (missing)
            acc.process(
                actionsMessage(msgId = 2, gsId = 1) {
                    addActions(Action.newBuilder().setActionType(ActionType.Play_add3).setInstanceId(100))
                    addActions(Action.newBuilder().setActionType(ActionType.Cast).setInstanceId(200))
                },
            )

            val missing = acc.actionInstanceIdsMissingFromObjects()
            missing.size shouldBe 1
            missing[0] shouldBe 200
        }
    })
