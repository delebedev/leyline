package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Verifies AI land play produces a dedicated diff with correct annotations,
 * matching the Arena golden pattern:
 *
 * 1. PlayLand gets its own gsId (never merged with CastSpell)
 * 2. Annotations: ObjectIdChanged + ZoneTransfer("PlayLand") + UserActionTaken(actionType=3)
 * 3. Land diff precedes any subsequent CastSpell diff
 *
 * Uses [ScriptedPlayerController] to control AI actions deterministically.
 */
@Test(groups = ["integration"])
class AiLandPlayOrderTest {

    private lateinit var harness: MatchFlowHarness

    @AfterMethod(alwaysRun = true)
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    @Test(description = "AI land play produces dedicated diff with PlayLand annotation before CastSpell")
    fun aiLandPlayHasDedicatedDiffWithPlayLandAnnotation() {
        harness = MatchFlowHarness(seed = 42L, deckList = CombatFlowTest.COMBAT_DECK, validating = false)
        harness.connectAndKeep()

        // Script AI: play land, then cast creature (separate actions)
        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.CastSpell("Raging Goblin"),
                ScriptedAction.PassPriority,
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        harness.passUntilTurn(3)

        val gsMessages = harness.allMessages.filter { it.hasGameStateMessage() }

        // Find the PlayLand diff: a GSM with ZoneTransfer annotation category="PlayLand"
        val playLandMsg = gsMessages.firstOrNull { gre ->
            gre.gameStateMessage.annotationsList.any { ann ->
                ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                    ann.detailsList.any { d -> d.key == "category" && d.valueStringList.contains("PlayLand") }
            }
        }

        // Find the CastSpell diff: a GSM with ZoneTransfer annotation category="CastSpell"
        val castSpellMsg = gsMessages.firstOrNull { gre ->
            gre.gameStateMessage.annotationsList.any { ann ->
                ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                    ann.detailsList.any { d -> d.key == "category" && d.valueStringList.contains("CastSpell") }
            }
        }

        assertNotNull(playLandMsg, "Should find a diff with ZoneTransfer category='PlayLand'")
        assertNotNull(castSpellMsg, "Should find a diff with ZoneTransfer category='CastSpell'")

        val playLandGsId = playLandMsg!!.gameStateMessage.gameStateId
        val castSpellGsId = castSpellMsg!!.gameStateMessage.gameStateId

        // PlayLand must have its own gsId, separate from CastSpell
        assertNotEquals(
            playLandGsId,
            castSpellGsId,
            "PlayLand and CastSpell must have different gsIds (dedicated diffs)",
        )

        // PlayLand gsId must precede CastSpell gsId
        assertTrue(
            playLandGsId < castSpellGsId,
            "PlayLand gsId ($playLandGsId) should precede CastSpell gsId ($castSpellGsId)",
        )

        // Verify PlayLand annotation structure matches Arena golden pattern:
        // ObjectIdChanged + ZoneTransfer("PlayLand") + UserActionTaken(actionType=3)
        val playLandGsm = playLandMsg.gameStateMessage
        val annTypes = playLandGsm.annotationsList.map { ann ->
            ann.typeList.firstOrNull()
        }

        assertTrue(
            annTypes.contains(AnnotationType.ObjectIdChanged),
            "PlayLand diff should contain ObjectIdChanged annotation",
        )
        assertTrue(
            annTypes.contains(AnnotationType.ZoneTransfer_af5a),
            "PlayLand diff should contain ZoneTransfer annotation",
        )
        assertTrue(
            annTypes.contains(AnnotationType.UserActionTaken),
            "PlayLand diff should contain UserActionTaken annotation",
        )

        // UserActionTaken should have actionType=3 (Play)
        val userAction = playLandGsm.annotationsList.first {
            it.typeList.contains(AnnotationType.UserActionTaken)
        }
        val actionTypeDetail = userAction.detailsList.firstOrNull { it.key == "actionType" }
        assertNotNull(actionTypeDetail, "UserActionTaken should have actionType detail")
        assertTrue(actionTypeDetail!!.valueInt32List.contains(3), "actionType should be 3 (Play)")

        // PlayLand diff should contain a land object on the battlefield
        val landObj = playLandGsm.gameObjectsList.firstOrNull { obj ->
            obj.cardTypesList.contains(CardType.Land_a80b) && obj.zoneId == 28
        }
        assertNotNull(landObj, "PlayLand diff should contain a land object on battlefield (zone 28)")

        // PlayLand diff should NOT contain a creature on the stack or battlefield
        val creatureOnStack = playLandGsm.gameObjectsList.firstOrNull { obj ->
            obj.cardTypesList.contains(CardType.Creature) && obj.zoneId == 27
        }
        assertNull(creatureOnStack, "PlayLand diff should NOT contain a creature on the stack")
    }

    @Test(description = "Default AI (seed=2, AI goes first): turn 1 PlayLand must not be discarded")
    fun aiGoesFirstLandPlayNotDiscarded() {
        // seed=2: AI goes first (active=2 on turn 1). The bug: onMulliganKeep
        // calls drainQueue() which discards AI action diffs queued during
        // awaitPriority. Turn 1 PlayLand + CastSpell are lost — only Resolve
        // survives because it happens after the snapshot is established.
        harness = MatchFlowHarness(seed = 2L, deckList = CombatFlowTest.COMBAT_DECK, validating = false)
        harness.connectAndKeep()

        // Let the default AI play — don't install scripted controller
        harness.passUntilTurn(2)

        val gsMessages = harness.allMessages.filter { it.hasGameStateMessage() }

        // Summarize turn 1 diffs for diagnosis
        val turn1Diffs = gsMessages
            .filter { it.gameStateMessage.turnInfo.turnNumber == 1 }
            .map { gre ->
                val gsm = gre.gameStateMessage
                val cats = gsm.annotationsList
                    .filter { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }
                    .flatMap { ann -> ann.detailsList.filter { it.key == "category" }.flatMap { it.valueStringList } }
                "gsId=${gsm.gameStateId} objs=${gsm.gameObjectsCount} cats=$cats"
            }

        // Turn 1 must have a PlayLand diff — the AI plays a land on its first turn
        val turn1PlayLand = gsMessages.filter { gre ->
            val gsm = gre.gameStateMessage
            gsm.turnInfo.turnNumber == 1 &&
                gsm.annotationsList.any { ann ->
                    ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                        ann.detailsList.any { d -> d.key == "category" && d.valueStringList.contains("PlayLand") }
                }
        }

        assertFalse(
            turn1PlayLand.isEmpty(),
            "AI turn 1 must have a PlayLand diff (currently discarded by drainQueue). Turn 1 diffs:\n${turn1Diffs.joinToString("\n")}",
        )
    }

    @Test(description = "CastSpell diff must NOT contain PlayLand ZoneTransfer annotation")
    fun castSpellDiffDoesNotContainPlayLandAnnotation() {
        harness = MatchFlowHarness(seed = 42L, deckList = CombatFlowTest.COMBAT_DECK, validating = false)
        harness.connectAndKeep()

        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.CastSpell("Raging Goblin"),
                ScriptedAction.PassPriority,
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        harness.passUntilTurn(3)

        val gsMessages = harness.allMessages.filter { it.hasGameStateMessage() }

        val castSpellMsg = gsMessages.firstOrNull { gre ->
            gre.gameStateMessage.annotationsList.any { ann ->
                ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                    ann.detailsList.any { d -> d.key == "category" && d.valueStringList.contains("CastSpell") }
            }
        }

        assertNotNull(castSpellMsg, "Should find CastSpell diff")

        // CastSpell diff must NOT also contain a PlayLand ZoneTransfer annotation.
        // In the Arena golden pattern, PlayLand has already been sent as a separate
        // gsId. If both categories appear in the same diff, the land play was merged.
        val hasPlayLandAnnotation = castSpellMsg!!.gameStateMessage.annotationsList.any { ann ->
            ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                ann.detailsList.any { d -> d.key == "category" && d.valueStringList.contains("PlayLand") }
        }
        assertFalse(
            hasPlayLandAnnotation,
            "CastSpell diff should NOT contain PlayLand ZoneTransfer annotation (land was already sent in its own diff)",
        )
    }
}
