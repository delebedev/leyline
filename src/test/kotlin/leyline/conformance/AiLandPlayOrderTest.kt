package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
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
class AiLandPlayOrderTest :
    FunSpec({

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("AI land play has dedicated diff with PlayLand annotation") {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            // Script AI: play land, then cast creature (separate actions)
            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.CastSpell("Raging Goblin"),
                    ScriptedAction.PassPriority,
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            h.passUntilTurn(3)

            val gsMessages = h.allMessages.filter { it.hasGameStateMessage() }

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

            playLandMsg.shouldNotBeNull()
            castSpellMsg.shouldNotBeNull()

            val playLandGsId = playLandMsg.gameStateMessage.gameStateId
            val castSpellGsId = castSpellMsg.gameStateMessage.gameStateId

            // PlayLand must have its own gsId, separate from CastSpell
            playLandGsId shouldNotBe castSpellGsId

            // PlayLand gsId must precede CastSpell gsId
            (playLandGsId < castSpellGsId).shouldBeTrue()

            // Verify PlayLand annotation structure matches Arena golden pattern
            val playLandGsm = playLandMsg.gameStateMessage
            val annTypes = playLandGsm.annotationsList.map { ann ->
                ann.typeList.firstOrNull()
            }

            annTypes shouldContain AnnotationType.ObjectIdChanged
            annTypes shouldContain AnnotationType.ZoneTransfer_af5a
            annTypes shouldContain AnnotationType.UserActionTaken

            // UserActionTaken should have actionType=3 (Play)
            val userAction = playLandGsm.annotationsList.first {
                it.typeList.contains(AnnotationType.UserActionTaken)
            }
            val actionTypeDetail = checkNotNull(userAction.detailsList.firstOrNull { it.key == "actionType" }) { "UserActionTaken should have actionType detail" }
            actionTypeDetail.valueInt32List shouldContain 3

            // PlayLand diff should contain a land object on the battlefield
            val landObj = playLandGsm.gameObjectsList.firstOrNull { obj ->
                obj.cardTypesList.contains(CardType.Land_a80b) && obj.zoneId == 28
            }
            landObj.shouldNotBeNull()

            // PlayLand diff should NOT contain a creature on the stack or battlefield
            val creatureOnStack = playLandGsm.gameObjectsList.firstOrNull { obj ->
                obj.cardTypesList.contains(CardType.Creature) && obj.zoneId == 27
            }
            creatureOnStack.shouldBeNull()
        }

        test("AI goes first land play not discarded") {
            val h = MatchFlowHarness(seed = 2L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            // Let the default AI play — don't install scripted controller
            h.passUntilTurn(2)

            val gsMessages = h.allMessages.filter { it.hasGameStateMessage() }

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

            // Turn 1 must have a PlayLand diff
            val turn1PlayLand = gsMessages.filter { gre ->
                val gsm = gre.gameStateMessage
                gsm.turnInfo.turnNumber == 1 &&
                    gsm.annotationsList.any { ann ->
                        ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                            ann.detailsList.any { d -> d.key == "category" && d.valueStringList.contains("PlayLand") }
                    }
            }

            turn1PlayLand.isEmpty().shouldBeFalse()
        }

        test("CastSpell diff does not contain PlayLand annotation") {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.CastSpell("Raging Goblin"),
                    ScriptedAction.PassPriority,
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            h.passUntilTurn(3)

            val gsMessages = h.allMessages.filter { it.hasGameStateMessage() }

            val castSpellMsg = gsMessages.firstOrNull { gre ->
                gre.gameStateMessage.annotationsList.any { ann ->
                    ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                        ann.detailsList.any { d -> d.key == "category" && d.valueStringList.contains("CastSpell") }
                }
            }

            castSpellMsg.shouldNotBeNull()

            // CastSpell diff must NOT also contain a PlayLand ZoneTransfer annotation
            val hasPlayLandAnnotation = castSpellMsg.gameStateMessage.annotationsList.any { ann ->
                ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                    ann.detailsList.any { d -> d.key == "category" && d.valueStringList.contains("PlayLand") }
            }
            hasPlayLandAnnotation.shouldBeFalse()
        }
    })
