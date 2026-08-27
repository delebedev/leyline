package leyline.mechanics.powerup

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.allActions
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class PowerUpLifecycleTest :
    SessionTest({
        session(
            "Power Up preserves activation identity, payment, exhaustion, and printed effect",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Serpent Specialist;Forest;Forest;Forest;Forest
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 4,
        ) {
            val sourceIid = human.battlefield.iid("Serpent Specialist")
            passUntilTurn(3, maxPasses = 80)
            val initialActions = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList
            val offer =
                withClue(initialActions.map { "${it.actionType}:${it.instanceId}:${it.abilityGrpId}:${it.uniqueAbilityId}" }) {
                    initialActions.single {
                        it.actionType == ActionType.Activate_add3 &&
                            it.instanceId == sourceIid &&
                            it.abilityGrpId == POWER_UP_ABILITY_GRP_ID
                    }
                }
            assertSoftly {
                offer.grpId shouldBe SERPENT_SPECIALIST_GRP_ID
                offer should haveManaCost(generic = 3, green = 1)
                offer.uniqueAbilityId shouldBeGreaterThan 0
            }

            val activationStart = messageSnapshot()
            activateAbility("Serpent Specialist").shouldBeTrue()
            passUntilResolved(maxPasses = 12)
            val messages = messagesSince(activationStart)
            val gsms = messages.gameStateMessages()
            val created =
                messages.annotationsOfType(AnnotationType.AbilityInstanceCreated).single {
                    it.affectorId == sourceIid && it.affectedIdsCount == 1
                }
            val stackIid = created.affectedIdsList.single()
            val stackObject = messages.allGameObjects().first { it.instanceId == stackIid }
            val commitGsm =
                gsms.single { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.UserActionTaken in it.typeList &&
                            it.detailInt(DetailKeys.ABILITY_GRP_ID) == POWER_UP_ABILITY_GRP_ID
                    }
                }
            val paymentRows = commitGsm.annotationsList.filter { AnnotationType.ManaPaid in it.typeList }
            val userAction =
                commitGsm.annotationsList.single {
                    AnnotationType.UserActionTaken in it.typeList &&
                        it.detailInt(DetailKeys.ABILITY_GRP_ID) == POWER_UP_ABILITY_GRP_ID
                }
            val exhausted =
                commitGsm.persistentAnnotationsList.single {
                    AnnotationType.AbilityExhausted in it.typeList && sourceIid in it.affectedIdsList
                }
            val resolutionGsm =
                gsms.single { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList && it.affectorId == stackIid
                    }
                }
            val resolutionTypes = resolutionGsm.annotationsList.map { it.typeList.single() }
            val deleted =
                resolutionGsm.annotationsList.single {
                    AnnotationType.AbilityInstanceDeleted in it.typeList && stackIid in it.affectedIdsList
                }
            val source = human.battlefield.card("Serpent Specialist")

            assertSoftly {
                stackObject.type shouldBe GameObjectType.Ability
                stackObject.grpId shouldBe POWER_UP_ABILITY_GRP_ID
                stackObject.parentId shouldBe sourceIid
                stackObject.objectSourceGrpId shouldBe SERPENT_SPECIALIST_GRP_ID
                stackObject.zoneId shouldBe ZoneIds.STACK
                created.detailInt(DetailKeys.SOURCE_ZONE) shouldBe ZoneIds.BATTLEFIELD

                paymentRows shouldHaveSize 4
                paymentRows.forEach { it.affectedIdsList shouldContainExactly listOf(stackIid) }
                commitGsm.annotationsList.lastIndexOf(paymentRows.last()) shouldBeLessThan
                    commitGsm.annotationsList.indexOf(userAction)
                userAction.affectedIdsList shouldContainExactly listOf(stackIid)
                userAction.detailInt(DetailKeys.ACTION_TYPE) shouldBe ActionType.Activate_add3.number

                exhausted.affectorId shouldBe sourceIid
                exhausted.affectedIdsList shouldContainExactly listOf(sourceIid)
                exhausted.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe POWER_UP_ABILITY_GRP_ID
                exhausted.detailInt(DetailKeys.USES_REMAINING) shouldBe 0
                exhausted.detailInt(DetailKeys.UNIQUE_ABILITY_ID) shouldBe offer.uniqueAbilityId
                resolutionTypes.indexOf(AnnotationType.ResolutionStart) shouldBeLessThan
                    resolutionTypes.indexOf(AnnotationType.ResolutionComplete)
                resolutionTypes.indexOf(AnnotationType.ResolutionComplete) shouldBeLessThan
                    resolutionTypes.indexOf(AnnotationType.AbilityInstanceDeleted)
                deleted.affectedIdsList shouldContainExactly listOf(stackIid)
                messages.deletedPersistentAnnotationIds() shouldNotContain exhausted.id

                source.getCounters(CounterEnumType.P1P1) shouldBe 2
                source.netPower shouldBe 3
                source.netToughness shouldBe 3
                messages
                    .allActions()
                    .any {
                        it.actionType == ActionType.Activate_add3 &&
                            it.instanceId == sourceIid &&
                            it.abilityGrpId == POWER_UP_ABILITY_GRP_ID
                    }.shouldBeFalse()
            }
        }
    })

private const val SERPENT_SPECIALIST_GRP_ID = 105081
private const val POWER_UP_ABILITY_GRP_ID = 206280
