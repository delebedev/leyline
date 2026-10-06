package leyline.game.mapping

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.game.snapshot.ZoneSnapshot
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class ZoneMapperSnapshotTest :
    FunSpec({

        tags(UnitTag)

        val spell = StackEntry(ForgeCardId(101), SeatId(1), SeatId(1), 9001, 9001, true, targets = emptyList())
        val ability = spell.copy(isSpell = false, grpId = 9002, forgeAbilityId = 7)
        val secondSpell = spell.copy(forgeCardId = ForgeCardId(102))
        val secondAbility = ability.copy(forgeCardId = ForgeCardId(102), forgeAbilityId = 8)
        val abilityId = FrameIdResolver.triggerStackAbilityForgeId(7)
        val ids =
            mapOf(
                ForgeCardId(101) to InstanceId(201),
                abilityId to InstanceId(202),
                ForgeCardId(102) to InstanceId(203),
                FrameIdResolver.triggerStackAbilityForgeId(8) to InstanceId(204),
            )
        for (case in listOf(
            StackOrderCase("ability above spell", listOf(ability, spell), listOf(201), listOf(202, 201)),
            StackOrderCase("spell above ability", listOf(spell, ability), listOf(201), listOf(201, 202)),
            StackOrderCase(
                "interleaved entries",
                listOf(secondAbility, spell, ability, secondSpell),
                listOf(203, 201),
                listOf(204, 201, 202, 203),
            ),
            StackOrderCase(
                "reverse interleaving",
                listOf(secondSpell, ability, spell, secondAbility),
                listOf(201, 203),
                listOf(203, 202, 201, 204),
            ),
            StackOrderCase("empty stack", emptyList(), emptyList(), emptyList()),
            StackOrderCase("single spell", listOf(spell), listOf(201), listOf(201)),
            StackOrderCase("single ability", listOf(ability), emptyList(), listOf(202)),
            StackOrderCase("pending card above existing ability", listOf(ability), listOf(201), listOf(201, 202)),
            StackOrderCase("retired spell entry", listOf(ability, spell), emptyList(), listOf(202)),
        )) {
            test("stack membership is top-first for ${case.label}") {
                val zones =
                    mutableListOf(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.STACK)
                            .addAllObjectInstanceIds(case.cards)
                            .build(),
                    )
                val objects = mutableListOf<GameObjectInfo>()
                ZoneMapper.addStackAbilitiesFromSnapshot(
                    GsmSnapshot.forTest(stack = StackSnapshot(case.entries)),
                    compilerEnvironment(),
                    { ids.getValue(it) },
                    { null },
                    zones,
                    objects,
                )
                zones.single().objectInstanceIdsList shouldContainExactly case.expected
                objects.map { it.instanceId } shouldContainExactly
                    case.entries.filterNot { it.isSpell }.map {
                        ids.getValue(FrameIdResolver.triggerStackAbilityForgeId(it.forgeAbilityId)).value
                    }
            }
        }

        test("snapshot carries a hand zone with ordered card contents") {
            val snap =
                GsmSnapshot.forTest(
                    zones =
                        mapOf(
                            ZoneIds.P1_HAND to
                                ZoneSnapshot(
                                    id = ZoneIds.P1_HAND,
                                    type = ZoneType.Hand,
                                    owner = SeatId(1),
                                    visibility = Visibility.Private,
                                    contents = listOf(ForgeCardId(101), ForgeCardId(102), ForgeCardId(103)),
                                ),
                        ),
                )
            snap.zones[ZoneIds.P1_HAND]?.contents shouldBe listOf(ForgeCardId(101), ForgeCardId(102), ForgeCardId(103))
        }

        test("missing zone is null on snapshot") {
            val snap = GsmSnapshot.forTest()
            snap.zones[ZoneIds.P1_HAND] shouldBe null
        }
    })

private data class StackOrderCase(
    val label: String,
    val entries: List<StackEntry>,
    val cards: List<Int>,
    val expected: List<Int>,
)
