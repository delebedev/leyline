package leyline.game.snapshot

import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.card.CounterEnumType
import forge.game.card.CounterType
import forge.game.combat.CombatUtil
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

class AbilityRemovalProjectionTest :
    BoardTest({
        test("a slime counter removes every printed ability with RemoveAbility all and Sludge leaving restores them") {
            val board = startPuzzleAtMain1(SLUDGE_PUZZLE)
            board.gameStart()
            val serra = board.human.battlefield.card("Serra Angel")
            val sludge = board.human.battlefield.card("Sludge Monster")
            val sentry = board.human.battlefield.card("Steadfast Sentry")
            val serraRows = board.printedRows(serra)
            val sentryRows = board.printedRows(sentry)

            val removed = board.snapshotDiff(checkSba = true) { serra.addSlimeCounter(board) }
            val row = removed.persistentAnnotationsList.single { AnnotationType.RemoveAbility in it.typeList }
            val effectId = row.detailInt("effect_id")
            assertSoftly {
                serra.hasKeyword("Flying") shouldBe false
                serra.hasKeyword("Vigilance") shouldBe false
                serraRows.size shouldBe 2
                removed.objectFor(board, serra).uniqueAbilitiesList.map { it.grpId } shouldBe emptyList()
                row.typeList shouldBe listOf(AnnotationType.RemoveAbility, AnnotationType.LayeredEffect)
                row.affectedIdsList shouldBe listOf(board.iid(serra))
                row.affectorId shouldBe board.iid(sludge)
                row.detailsList.map { it.key } shouldBe listOf(board.iid(serra).toString(), "effect_id")
                row.detailsList.first().valueStringList shouldBe listOf("all")
                removed.annotationsList.created(effectId) shouldBe 1
                board.projected(sentry).uniqueAbilitiesList.map { it.grpId } shouldBe sentryRows
            }

            val restored = board.snapshotDiff(checkSba = true) { destroy(sludge, board.game) }
            assertSoftly {
                serra.hasKeyword("Flying") shouldBe true
                restored.objectFor(board, serra).uniqueAbilitiesList.map { it.grpId } shouldBe serraRows
                restored.annotationsList.destroyed(effectId) shouldBe 1
                restored.diffDeletedPersistentAnnotationIdsList shouldContain row.id
                restored.persistentAnnotationsList.none { AnnotationType.RemoveAbility in it.typeList } shouldBe true
                board.projected(sentry).uniqueAbilitiesList.map { it.grpId } shouldBe sentryRows
            }
        }

        test("an ability granted after the removal survives while printed rows stay removed") {
            val board = startPuzzleAtMain1(SLUDGE_PUZZLE)
            board.gameStart()
            val serra = board.human.battlefield.card("Serra Angel")
            board.snapshotDiff(checkSba = true) { serra.addSlimeCounter(board) }

            board.snapshotDiff(checkSba = true) {
                serra.addCounterInternal(CounterType.getType("Flying"), 1, board.human, true, null, AbilityKey.newMap())
            }
            val rows = board.projected(serra).uniqueAbilitiesList.map { it.grpId }
            assertSoftly {
                serra.hasKeyword("Flying") shouldBe true
                serra.hasKeyword("Vigilance") shouldBe false
                rows shouldBe listOf(FLYING_GRP_ID)
            }
        }

        test("losing one keyword removes only that row with the grpId list and can't-have hides any grant") {
            val board =
                startPuzzleAtMain1(
                    ARCHETYPE_PUZZLE.replace(
                        "aibattlefield=Atraxa, Praetors' Voice",
                        "aibattlefield=Atraxa, Praetors' Voice;Levitation",
                    ),
                )
            board.gameStart()
            val atraxa = board.ai.battlefield.card("Atraxa, Praetors' Voice")
            val hawk = board.human.battlefield.card("Healer's Hawk")
            val archetype = board.human.battlefield.card("Archetype of Imagination")
            val atraxaRows = board.printedRows(atraxa)
            val flyingRow =
                atraxaRows.single {
                    board.bridge.cardRepository
                        .findAbilityLocalization(it)
                        ?.keyword == "Flying"
                }
            val row =
                board.bridge
                    .viewerProjectionCursor()
                    .fullState!!
                    .persistentAnnotationsList
                    .single { AnnotationType.RemoveAbility in it.typeList && board.iid(atraxa) in it.affectedIdsList }
            assertSoftly {
                atraxa.hasKeyword("Flying") shouldBe false
                board.projected(atraxa).uniqueAbilitiesList.map { it.grpId } shouldBe atraxaRows - flyingRow
                row.detailsList.first().valueInt32List shouldBe listOf(flyingRow)
                row.affectorId shouldBe board.iid(archetype)
                board.projected(hawk).uniqueAbilitiesList.map { it.grpId } shouldContain FLYING_GRP_ID
            }

            val restored = board.snapshotDiff(checkSba = true) { destroy(archetype, board.game) }
            assertSoftly {
                atraxa.hasKeyword("Flying") shouldBe true
                CombatUtil.canBlock(hawk, atraxa) shouldBe true
                board.projected(atraxa).uniqueAbilitiesList.map { it.grpId } shouldContainAll atraxaRows + FLYING_GRP_ID
                restored.annotationsList.destroyed(row.detailInt("effect_id")) shouldBe 1
            }
        }

        test("opposing Archetypes remove both sides and destroying one restores only its opponent's Flying") {
            val board =
                startPuzzleAtMain1(
                    ARCHETYPE_PUZZLE.replace(
                        "aibattlefield=Atraxa, Praetors' Voice",
                        "aibattlefield=Atraxa, Praetors' Voice;Archetype of Imagination",
                    ),
                )
            board.gameStart()
            val hawk = board.human.battlefield.card("Healer's Hawk")
            val atraxa = board.ai.battlefield.card("Atraxa, Praetors' Voice")

            fun flying(card: Card) =
                board.projected(card).uniqueAbilitiesList.any {
                    it.grpId == FLYING_GRP_ID ||
                        board.bridge.cardRepository
                            .findAbilityLocalization(it.grpId)
                            ?.keyword == "Flying"
                }
            assertSoftly {
                flying(hawk) shouldBe false
                flying(atraxa) shouldBe false
            }
            board.snapshotDiff(checkSba = true) { destroy(board.ai.battlefield.card("Archetype of Imagination"), board.game) }
            assertSoftly {
                flying(hawk) shouldBe true
                flying(atraxa) shouldBe false
                CombatUtil.canBlock(hawk, atraxa) shouldBe false
            }
        }
    })

private const val FLYING_GRP_ID = 8

private fun Card.addSlimeCounter(board: Board) {
    addCounterInternal(CounterEnumType.SLIME, 1, board.human, true, null, AbilityKey.newMap())
}

private fun Board.iid(card: Card): Int = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value

private fun Board.printedRows(card: Card): List<Int> =
    bridge.cardRepository
        .findByGrpId(projected(card).grpId)!!
        .abilityIds
        .map { it.first }

private fun Board.projected(card: Card): GameObjectInfo =
    bridge
        .viewerProjectionCursor()
        .fullState!!
        .gameObjectsList
        .single { it.instanceId == iid(card) }

private fun GameStateMessage.objectFor(
    board: Board,
    card: Card,
): GameObjectInfo = gameObjectsList.single { it.instanceId == board.iid(card) }

private fun AnnotationInfo.detailInt(key: String): Int = detailsList.single { it.key == key }.getValueInt32(0)

private fun List<AnnotationInfo>.created(effectId: Int): Int =
    count { AnnotationType.LayeredEffectCreated in it.typeList && effectId in it.affectedIdsList }

private fun List<AnnotationInfo>.destroyed(effectId: Int): Int =
    count { AnnotationType.LayeredEffectDestroyed in it.typeList && effectId in it.affectedIdsList }

private const val SLUDGE_PUZZLE = """
    [metadata]
    Name:Ability Removal Capture
    Goal:Demo
    Turns:3
    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20
    removesummoningsickness=true
    humanbattlefield=Sludge Monster;Serra Angel;Steadfast Sentry
    humanlibrary=Plains;Plains
    ailibrary=Forest;Forest
"""

private const val ARCHETYPE_PUZZLE = """
    [metadata]
    Name:Keyword Removal Capture
    Goal:Demo
    Turns:3
    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=40
    AILife=40
    removesummoningsickness=true
    humanbattlefield=Archetype of Imagination;Healer's Hawk
    humanlibrary=Plains;Plains
    aibattlefield=Atraxa, Praetors' Voice
    ailibrary=Forest;Forest
"""
