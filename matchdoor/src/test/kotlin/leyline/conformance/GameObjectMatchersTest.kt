package leyline.conformance

import io.kotest.assertions.shouldFail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.Visibility

private fun obj(
    iid: Int = 100,
    attackState: AttackState = AttackState.None_a3a9,
    isTapped: Boolean = false,
    zoneId: Int = 0,
    visibility: Visibility = Visibility.Public,
    viewers: List<Int> = emptyList(),
    isCopy: Boolean = false,
    parentId: Int = 0,
    grpId: Int = 0,
): GameObjectInfo {
    val builder =
        GameObjectInfo
            .newBuilder()
            .setInstanceId(iid)
            .setType(GameObjectType.Card)
            .setAttackState(attackState)
            .setIsTapped(isTapped)
            .setZoneId(zoneId)
            .setVisibility(visibility)
            .setIsCopy(isCopy)
            .setParentId(parentId)
            .setGrpId(grpId)
    viewers.forEach { builder.addViewers(it) }
    return builder.build()
}

@Suppress(
    // Matcher tests verify failure-message shape via `shouldFail { ... }.message
    // shouldContain "..."`. Detekt doesn't recognize this pattern as equality-shape.
    "WeakAssertionOnly",
    "MissingAssertSoftly",
)
class GameObjectMatchersTest :
    FunSpec({

        tags(UnitTag)

        test("haveAttackState matches and reports the actual state on failure") {
            obj(attackState = AttackState.Attacking) should haveAttackState(AttackState.Attacking)

            val failure =
                shouldFail {
                    obj(iid = 12345, attackState = AttackState.None_a3a9) should haveAttackState(AttackState.Attacking)
                }
            failure.message shouldContain "iid=12345"
            failure.message shouldContain "should be Attacking"
            failure.message shouldContain "was None_a3a9"
        }

        test("beTapped fails with iid in the message") {
            obj(isTapped = true) should beTapped()

            val failure =
                shouldFail {
                    obj(iid = 7, isTapped = false) should beTapped()
                }
            failure.message shouldContain "iid=7"
            failure.message shouldContain "should be tapped"
        }

        test("haveZone names actual + expected zoneIds") {
            obj(zoneId = 30) should haveZone(30)

            val failure =
                shouldFail {
                    obj(iid = 99, zoneId = 28) should haveZone(30)
                }
            failure.message shouldContain "should be 30"
            failure.message shouldContain "was 28"
        }

        test("bePrivateTo passes when visibility=Private and viewers contain seat") {
            obj(visibility = Visibility.Private, viewers = listOf(1)) should bePrivateTo(1)
        }

        test("bePrivateTo fails informatively when visibility is wrong") {
            val failure =
                shouldFail {
                    obj(iid = 5, visibility = Visibility.Public, viewers = listOf(1)) should bePrivateTo(1)
                }
            failure.message shouldContain "visibility should be Private"
            failure.message shouldContain "was Public"
        }

        test("bePrivateTo fails informatively when seat not in viewers") {
            val failure =
                shouldFail {
                    obj(iid = 5, visibility = Visibility.Private, viewers = listOf(2)) should bePrivateTo(1)
                }
            failure.message shouldContain "viewersList should contain seat 1"
            failure.message shouldContain "was [2]"
        }

        test("beCopyOf passes when isCopy=true and parentId matches") {
            obj(isCopy = true, parentId = 42) should beCopyOf(42)
        }

        test("beCopyOf fails with field detail when isCopy is wrong") {
            val failure =
                shouldFail {
                    obj(iid = 8, isCopy = false, parentId = 42) should beCopyOf(42)
                }
            failure.message shouldContain "should have isCopy=true"
        }

        test("beCopyOf fails with field detail when parentId is wrong") {
            val failure =
                shouldFail {
                    obj(iid = 8, isCopy = true, parentId = 99) should beCopyOf(42)
                }
            failure.message shouldContain "parentId should be 42"
            failure.message shouldContain "was 99"
        }

        test("haveResolvedGrpId rejects 0 with explicit message") {
            obj(grpId = 188_700) should haveResolvedGrpId()

            val failure =
                shouldFail {
                    obj(iid = 11, grpId = 0) should haveResolvedGrpId()
                }
            failure.message shouldContain "grpId should be resolved"
            failure.message shouldContain "was 0"
        }

        test("shouldNot inversion uses the negative message") {
            val failure =
                shouldFail {
                    obj(iid = 3, isTapped = true) shouldNot beTapped()
                }
            failure.message shouldContain "should NOT be tapped"
        }
    })
