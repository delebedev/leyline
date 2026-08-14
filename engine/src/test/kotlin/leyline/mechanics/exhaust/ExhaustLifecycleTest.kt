package leyline.mechanics.exhaust

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.bundle.AbilityExhaustionFactsCapture
import leyline.game.codes.DetailKeys
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import leyline.testkit.StateMapperShell as StateMapper

class ExhaustLifecycleTest :
    SessionTest({
        test("Exhaust activation emits spent ability marker and suppresses repeat offer") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Jeong Jeong, the Deserter;Mountain;Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Exhaust Jeong Jeong",
                validating = true,
            )

            val jeongIid = human.battlefield.iid("Jeong Jeong, the Deserter")
            val initialActions = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList
            withClue(initialActions.map { "${it.actionType}:${it.instanceId}:${it.abilityGrpId}" }) {
                initialActions
                    .any {
                        it.actionType == ActionType.Activate_add3 &&
                            it.instanceId == jeongIid &&
                            it.abilityGrpId == JEONG_EXHAUST_ABILITY_GRP_ID
                    }.shouldBeTrue()
            }

            val activationStart = messageSnapshot()
            activateAbility("Jeong Jeong, the Deserter").shouldBeTrue()
            passUntilResolved(maxPasses = 8)

            val gsms = allMessages.gameStateMessages()
            val abilityExhausted =
                gsms
                    .flatMap { it.persistentAnnotationsList }
                    .last {
                        AnnotationType.AbilityExhausted in it.typeList &&
                            jeongIid in it.affectedIdsList
                    }
            val postActivationActions =
                messagesSince(activationStart)
                    .filter { it.hasActionsAvailableReq() }
                    .flatMap { it.actionsAvailableReq.actionsList }

            assertSoftly {
                abilityExhausted.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe JEONG_EXHAUST_ABILITY_GRP_ID
                abilityExhausted.detailInt(DetailKeys.USES_REMAINING) shouldBe 0
                abilityExhausted.detailInt(DetailKeys.UNIQUE_ABILITY_ID) shouldBe 51
                abilityExhausted.affectorId shouldBe jeongIid
                postActivationActions
                    .any {
                        it.actionType == ActionType.Activate_add3 &&
                            it.instanceId == jeongIid &&
                            it.abilityGrpId == JEONG_EXHAUST_ABILITY_GRP_ID
                    }.shouldBeFalse()
            }
        }

        test("Exhaust mana ability emits spent ability marker") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Loot, the Pathfinder;Forest
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Exhaust Loot mana ability",
                validating = true,
            )
            val lootIid = human.battlefield.iid("Loot, the Pathfinder")
            val loot = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Loot, the Pathfinder" }
            loot.addAbilityActivated(loot.manaAbilities.first { it.isExhaust })
            val snapshot = GsmSnapshot.capture(game(), harness.bridge, "exhaust-mana-regression", 0)

            val abilityExhausted =
                StateMapper
                    .buildFromSnapshot(
                        snapshot,
                        1,
                        "test",
                        harness.bridge,
                        effectFacts = harness.bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = AbilityExhaustionFactsCapture.capture(snapshot, harness.bridge),
                    ).gsm
                    .persistentAnnotationsList
                    .last {
                        AnnotationType.AbilityExhausted in it.typeList &&
                            lootIid in it.affectedIdsList
                    }

            assertSoftly {
                abilityExhausted.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe LOOT_MANA_EXHAUST_ABILITY_GRP_ID
                abilityExhausted.detailInt(DetailKeys.USES_REMAINING) shouldBe 0
                abilityExhausted.detailInt(DetailKeys.UNIQUE_ABILITY_ID) shouldBe 53
                abilityExhausted.affectorId shouldBe lootIid
            }
        }
    })

private const val JEONG_EXHAUST_ABILITY_GRP_ID = 192720
private const val LOOT_MANA_EXHAUST_ABILITY_GRP_ID = 176608
