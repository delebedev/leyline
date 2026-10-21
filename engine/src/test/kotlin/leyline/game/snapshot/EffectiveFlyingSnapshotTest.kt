package leyline.game.snapshot

import forge.game.combat.CombatUtil
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.config.EngineSettings
import leyline.config.KeywordProjection
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.ZoneIds
import leyline.testkit.BoardTest
import leyline.testkit.aiPlayer
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class EffectiveFlyingSnapshotTest :
    BoardTest({
        test("fresh projection suppresses intrinsic and independent Flying grants and restores both after source removal") {
            val board =
                startPuzzleAtMain1(
                    PUZZLE.replace("aibattlefield=Atraxa, Praetors' Voice", "aibattlefield=Atraxa, Praetors' Voice;Levitation"),
                    EngineSettings(keywordProjection = KeywordProjection.WebEffectiveFlying),
                )
            val (bridge, game, _) = board
            board.gameStart()
            val fid =
                ForgeCardId(
                    game.aiPlayer.battlefield
                        .card("Atraxa, Praetors' Voice")
                        .id,
                )
            val iid = bridge.getOrAllocInstanceId(fid).value
            val before =
                bridge
                    .viewerProjectionCursor()
                    .fullState!!
                    .gameObjectsList
                    .single { it.instanceId == iid }
            val intrinsic =
                bridge.cardRepository.findByGrpId(before.grpId)!!.abilityIds.map { it.first }.filter {
                    bridge.cardRepository.findAbilityLocalization(it)?.keyword ==
                        "Flying"
                }
            intrinsic.size shouldBe 1
            before.uniqueAbilitiesList.none { it.grpId in intrinsic || it.grpId == 8 } shouldBe true
            destroy(game.humanPlayer.battlefield.card("Archetype of Imagination"), game)
            board.stateOnlyDiff()
            val after =
                bridge
                    .viewerProjectionCursor()
                    .fullState!!
                    .gameObjectsList
                    .single { it.instanceId == iid }
            assertSoftly {
                after.uniqueAbilitiesList.count { it.grpId in intrinsic } shouldBe 1
                after.uniqueAbilitiesList.count { it.grpId == 8 } shouldBe 1
                after.uniqueAbilitiesList.filter { it.grpId !in intrinsic && it.grpId != 8 } shouldBe before.uniqueAbilitiesList
            }
        }

        test("opposing Archetypes reconcile both sides and a fresh source-only removal restores the surviving side") {
            val board =
                startPuzzleAtMain1(
                    PUZZLE.replace(
                        "aibattlefield=Atraxa, Praetors' Voice",
                        "aibattlefield=Atraxa, Praetors' Voice;Archetype of Imagination",
                    ),
                    EngineSettings(keywordProjection = KeywordProjection.WebEffectiveFlying),
                )
            val (bridge, game, _) = board
            board.gameStart()
            val hawk = game.humanPlayer.battlefield.card("Healer's Hawk")
            val atraxa = game.aiPlayer.battlefield.card("Atraxa, Praetors' Voice")

            fun rows(card: forge.game.card.Card) =
                bridge
                    .viewerProjectionCursor()
                    .fullState!!
                    .gameObjectsList
                    .single {
                        it.instanceId ==
                            bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
                    }.uniqueAbilitiesList

            fun flying(card: forge.game.card.Card) =
                rows(card).any {
                    it.grpId == 8 ||
                        bridge.cardRepository.findAbilityLocalization(it.grpId)?.keyword == "Flying"
                }
            assertSoftly {
                flying(hawk) shouldBe false
                flying(atraxa) shouldBe false
            }
            destroy(game.aiPlayer.battlefield.card("Archetype of Imagination"), game)
            board.stateOnlyDiff()
            assertSoftly {
                flying(hawk) shouldBe true
                flying(atraxa) shouldBe false
                CombatUtil.canBlock(hawk, atraxa) shouldBe false
            }
        }

        for (policy in KeywordProjection.entries) {
            test("effective membership capture and source removal under $policy") {
                val (bridge, game, _) = startPuzzleAtMain1(PUZZLE, EngineSettings(keywordProjection = policy))
                val atraxa = game.aiPlayer.battlefield.card("Atraxa, Praetors' Voice")
                val hawk = game.humanPlayer.battlefield.card("Healer's Hawk")
                val source = game.humanPlayer.battlefield.card("Archetype of Imagination")
                val fid = ForgeCardId(atraxa.id)
                val iid = bridge.getOrAllocInstanceId(fid).value
                val before = SnapshotCapture.run(game, bridge, "effective-flying", 100).objects.getValue(fid)
                val flyingIds = before.flyingAbilityGrpIds
                val beforeObject = ObjectMapper.buildFromSnapshot(before, iid, ZoneIds.BATTLEFIELD, 2, bridge.cardProto, Visibility.Public)
                assertSoftly {
                    atraxa.hasKeyword("Flying") shouldBe false
                    CombatUtil.canBlock(hawk, atraxa) shouldBe false
                    before.effectiveFlying shouldBe if (policy == KeywordProjection.Native) null else false
                    if (policy == KeywordProjection.WebEffectiveFlying) {
                        beforeObject.uniqueAbilitiesList.none { it.grpId in flyingIds } shouldBe true
                        flyingIds.size shouldBe 2
                    } else {
                        beforeObject.toByteArray().toList() shouldBe
                            bridge.cardProto
                                .buildObjectInfo(before.grpId)
                                .setInstanceId(iid)
                                .setType(beforeObject.type)
                                .setZoneId(ZoneIds.BATTLEFIELD)
                                .setVisibility(Visibility.Public)
                                .setOwnerSeatId(2)
                                .setControllerSeatId(2)
                                .setOthersideGrpId(before.othersideGrpId)
                                .apply {
                                    power = beforeObject.power
                                    toughness = beforeObject.toughness
                                }.build()
                                .toByteArray()
                                .toList()
                    }
                }
                destroy(source, game)
                val after = SnapshotCapture.run(game, bridge, "effective-flying", 101).objects.getValue(fid)
                val afterObject = ObjectMapper.buildFromSnapshot(after, iid, ZoneIds.BATTLEFIELD, 2, bridge.cardProto, Visibility.Public)
                assertSoftly {
                    atraxa.hasKeyword("Flying") shouldBe true
                    CombatUtil.canBlock(hawk, atraxa) shouldBe true
                    after.effectiveFlying shouldBe if (policy == KeywordProjection.Native) null else true
                    before.effectiveFlying shouldBe if (policy == KeywordProjection.Native) null else false
                    afterObject.uniqueAbilitiesList shouldBe bridge.cardProto.buildObjectInfo(after.grpId).uniqueAbilitiesList
                }
            }
        }

        test("native settings default is unchanged and host projection policy is not serialized configuration") {
            EngineSettings().keywordProjection shouldBe KeywordProjection.Native
            val json = kotlinx.serialization.json.Json
            json.encodeToString(
                EngineSettings.serializer(),
                EngineSettings(keywordProjection = KeywordProjection.WebEffectiveFlying),
            ) shouldBe
                json.encodeToString(EngineSettings.serializer(), EngineSettings())
        }
    })

private const val PUZZLE = """
    [metadata]
    Name:Effective Flying Capture
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
