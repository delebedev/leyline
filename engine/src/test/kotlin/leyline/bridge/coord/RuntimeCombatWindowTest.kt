package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ForgePlayerId

class RuntimeCombatWindowTest :
    FunSpec({
        tags(UnitTag)

        test("attacker and blocker selections update iteratively behind the runtime interface") {
            val handles = handles()

            val firstAttack =
                DeclarationAnswer.Attackers.of(
                    attackerInstanceIds = listOf(101),
                    defenderByAttacker = mapOf(101 to DeclarationAnswer.Target.Player(2)),
                )
            val firstSelection = checkNotNull(handles.nextAttackers(firstAttack))
            firstSelection.keys shouldBe setOf(101)
            handles.replaceAttackers(firstSelection)
            handles.selectedAttackerInstanceIds() shouldBe listOf(101)

            val secondAttack =
                DeclarationAnswer.Attackers.of(
                    attackerInstanceIds = listOf(101, 102),
                    defenderByAttacker = mapOf(102 to DeclarationAnswer.Target.Player(2)),
                )
            handles.replaceAttackers(checkNotNull(handles.nextAttackers(secondAttack)))
            handles.selectedAttackerInstanceIds() shouldBe listOf(102)

            val firstBlock = DeclarationAnswer.Blockers.of(mapOf(201 to 102))
            handles.replaceBlockers(checkNotNull(handles.nextBlockers(firstBlock)))
            handles.selectedBlockAssignments() shouldBe mapOf(201 to 102)
            handles.replaceBlockers(checkNotNull(handles.nextBlockers(DeclarationAnswer.Blockers.of(emptyMap(), listOf(201)))))
            handles.selectedBlockAssignments() shouldBe emptyMap()
        }

        test("combat runtime rejects duplicate, unknown, illegal, and wrong-kind declarations") {
            val handles = handles()

            assertSoftly {
                handles.nextAttackers(DeclarationAnswer.Attackers.of(listOf(101, 101))).shouldBeNull()
                handles.nextAttackers(DeclarationAnswer.Attackers.of(listOf(999))).shouldBeNull()
                handles
                    .nextAttackers(
                        DeclarationAnswer.Attackers.of(
                            listOf(101),
                            attackAlternativeByAttacker = mapOf(101 to 99),
                            defenderByAttacker = mapOf(101 to DeclarationAnswer.Target.Player(2)),
                        ),
                    ).shouldBeNull()
                handles.nextBlockers(DeclarationAnswer.Blockers.of(mapOf(999 to 101))).shouldBeNull()
                handles.nextBlockers(DeclarationAnswer.Blockers.of(mapOf(201 to 999))).shouldBeNull()
                handles.resolveDeclaration(PendingActionKind.PRIORITY).shouldBeNull()
            }
        }
    }) {
    companion object {
        private fun handles() =
            RuntimeCombatWindow(
                attackerByInstanceId = mapOf(101 to ForgeCardId(11), 102 to ForgeCardId(12)),
                legalAlternativesByAttacker = mapOf(101 to setOf(0, 7), 102 to setOf(0)),
                blockerByInstanceId = mapOf(201 to ForgeCardId(21)),
                targetCardByInstanceId = emptyMap(),
                playerBySeatId = mapOf(1 to ForgePlayerId(100), 2 to ForgePlayerId(200)),
                defaultDefender = ForgePlayerId(200),
            )
    }
}
