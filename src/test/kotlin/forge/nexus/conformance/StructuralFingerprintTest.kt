package forge.nexus.conformance

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

class StructuralFingerprintTest {

    @Test
    fun extractFromGameStateMessage() {
        // Build a ZoneTransfer annotation with PlayLand category
        val annotation = AnnotationInfo.newBuilder()
            .addType(AnnotationType.ZoneTransfer_af5a)
            .addAffectedIds(100)
            .addDetails(
                KeyValuePairInfo.newBuilder()
                    .setKey("category")
                    .addValueString("PlayLand"),
            )
            .build()

        // Build a Pass action embedded in the game state
        val passAction = ActionInfo.newBuilder()
            .setActionId(1)
            .setSeatId(1)
            .setAction(Action.newBuilder().setActionType(ActionType.Pass))
            .build()

        val zone = ZoneInfo.newBuilder()
            .setZoneId(28)
            .setType(ZoneType.Battlefield)
            .setOwnerSeatId(0)
            .setVisibility(Visibility.Public)
            .addObjectInstanceIds(100)
            .build()

        val obj = GameObjectInfo.newBuilder()
            .setInstanceId(100)
            .setGrpId(12345)
            .setType(GameObjectType.Card)
            .setZoneId(28)
            .build()

        val gameState = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(5)
            .setUpdate(GameStateUpdate.SendAndRecord)
            .addZones(zone)
            .addGameObjects(obj)
            .addAnnotations(annotation)
            .addActions(passAction)
            .build()

        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(1)
            .setGameStateId(5)
            .addSystemSeatIds(1)
            .setGameStateMessage(gameState)
            .build()

        val fp = StructuralFingerprint.fromGRE(gre)

        assertEquals(fp.greMessageType, "GameStateMessage")
        assertEquals(fp.gsType, "Diff")
        assertEquals(fp.updateType, "SendAndRecord")
        assertEquals(fp.zoneCount, 1)
        assertEquals(fp.objectCount, 1)
        assertEquals(fp.annotationTypes, listOf("ZoneTransfer"))
        assertEquals(fp.annotationCategories, listOf("PlayLand"))
        assertTrue(fp.fieldPresence.contains("zones"))
        assertTrue(fp.fieldPresence.contains("objects"))
        assertTrue(fp.fieldPresence.contains("annotations"))
        assertTrue(fp.fieldPresence.contains("actions"))
        assertEquals(fp.actionTypes, listOf("Pass"))
        assertFalse(fp.hasPrompt)
        assertNull(fp.promptId)
    }

    @Test
    fun extractFromActionsAvailableReq() {
        val actions = ActionsAvailableReq.newBuilder()
            .addActions(
                Action.newBuilder()
                    .setActionType(ActionType.Play_add3)
                    .setInstanceId(100)
                    .setGrpId(12345),
            )
            .addActions(
                Action.newBuilder()
                    .setActionType(ActionType.Cast)
                    .setInstanceId(200)
                    .setGrpId(67890),
            )
            .addActions(Action.newBuilder().setActionType(ActionType.Pass))
            .build()

        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(2)
            .setGameStateId(5)
            .addSystemSeatIds(1)
            .setActionsAvailableReq(actions)
            .setPrompt(Prompt.newBuilder().setPromptId(2).build())
            .build()

        val fp = StructuralFingerprint.fromGRE(gre)

        assertEquals(fp.greMessageType, "ActionsAvailableReq")
        assertNull(fp.gsType)
        assertNull(fp.updateType)
        // Action types should be sorted for deterministic comparison
        assertEquals(fp.actionTypes, listOf("Cast", "Pass", "Play"))
        assertTrue(fp.hasPrompt)
        assertEquals(fp.promptId, 2)
        assertEquals(fp.zoneCount, 0)
        assertEquals(fp.objectCount, 0)
    }

    @Test
    fun fingerprintsMatchAcrossDifferentIds() {
        // Two GRE messages with same structure but different IDs
        fun buildGRE(instanceId: Int, grpId: Int, gsId: Int, zoneId: Int): GREToClientMessage {
            val zone = ZoneInfo.newBuilder()
                .setZoneId(zoneId)
                .setType(ZoneType.Battlefield)
                .setOwnerSeatId(0)
                .setVisibility(Visibility.Public)
                .addObjectInstanceIds(instanceId)
                .build()

            val obj = GameObjectInfo.newBuilder()
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setType(GameObjectType.Card)
                .setZoneId(zoneId)
                .build()

            val gameState = GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(gsId)
                .setUpdate(GameStateUpdate.SendAndRecord)
                .addZones(zone)
                .addGameObjects(obj)
                .build()

            return GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(1)
                .setGameStateId(gsId)
                .addSystemSeatIds(1)
                .setGameStateMessage(gameState)
                .build()
        }

        val fp1 = StructuralFingerprint.fromGRE(buildGRE(100, 12345, 5, 28))
        val fp2 = StructuralFingerprint.fromGRE(buildGRE(999, 67890, 42, 31))

        assertEquals(fp1, fp2)
    }

    @Test
    fun roundTripJsonSerialization() {
        val fp = StructuralFingerprint(
            greMessageType = "GameStateMessage",
            gsType = "Diff",
            updateType = "SendAndRecord",
            annotationTypes = listOf("ZoneTransfer"),
            annotationCategories = listOf("CastSpell"),
            fieldPresence = setOf("turnInfo", "zones", "objects", "annotations"),
            zoneCount = 3,
            objectCount = 5,
            actionTypes = listOf("Pass"),
            hasPrompt = false,
            promptId = null,
        )
        val sequence = listOf(fp, fp.copy(gsType = "Full", zoneCount = 17))
        val json = GoldenSequence.toJson(sequence)
        val parsed = GoldenSequence.fromJson(json)
        assertEquals(parsed, sequence)
    }

    @Test
    fun loadGoldenFromFile() {
        val fp = StructuralFingerprint(
            greMessageType = "ActionsAvailableReq",
            actionTypes = listOf("Pass", "Play"),
            hasPrompt = true,
            promptId = 2,
        )
        val json = GoldenSequence.toJson(listOf(fp))
        val tempFile = java.io.File.createTempFile("golden-test", ".json")
        tempFile.writeText(json)
        tempFile.deleteOnExit()
        val loaded = GoldenSequence.fromFile(tempFile)
        assertEquals(loaded, listOf(fp))
    }
}
