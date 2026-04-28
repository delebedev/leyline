package leyline.conformance

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/**
 * Matchers for [GameObjectInfo] — single-object shape assertions that
 * appear across attack-state, look-and-pick visibility, and copy/projection
 * tests. Each matcher names the diverging field in its failure message,
 * which is the win over inline `assertSoftly { it.field shouldBe ... }`.
 *
 * Combine with `assertSoftly { ... }` when checking multiple invariants on
 * the same object — kotest soft-asserts collect every matcher failure.
 *
 * ```
 * attackerObj should haveAttackState(AttackState.Attacking)
 * attackerObj should beTapped()
 *
 * candidateObj should bePrivateTo(seatId)
 * candidateObj should haveZone(ZoneIds.libraryOf(seatId))
 *
 * preparedCopy should beCopyOf(sourceIid)
 * preparedCopy should haveZone(ZoneIds.EXILE)
 * ```
 */

fun haveAttackState(expected: AttackState): Matcher<GameObjectInfo> =
    Matcher { obj ->
        MatcherResult(
            obj.attackState == expected,
            { "object iid=${obj.instanceId} attackState should be $expected, was ${obj.attackState}" },
            { "object iid=${obj.instanceId} attackState should NOT be $expected" },
        )
    }

fun beTapped(): Matcher<GameObjectInfo> =
    Matcher { obj ->
        MatcherResult(
            obj.isTapped,
            { "object iid=${obj.instanceId} should be tapped, isTapped=${obj.isTapped}" },
            { "object iid=${obj.instanceId} should NOT be tapped" },
        )
    }

fun haveZone(expectedZoneId: Int): Matcher<GameObjectInfo> =
    Matcher { obj ->
        MatcherResult(
            obj.zoneId == expectedZoneId,
            { "object iid=${obj.instanceId} zoneId should be $expectedZoneId, was ${obj.zoneId}" },
            { "object iid=${obj.instanceId} zoneId should NOT be $expectedZoneId" },
        )
    }

/**
 * Visible only to [seatId] — `visibility = Private` AND `viewersList` contains
 * exactly the seat (look-and-pick candidate cards, hidden hand contents from
 * the chooser's POV).
 */
fun bePrivateTo(seatId: Int): Matcher<GameObjectInfo> =
    Matcher { obj ->
        val passed =
            obj.visibility == Visibility.Private &&
                seatId in obj.viewersList
        val explanation =
            when {
                obj.visibility != Visibility.Private ->
                    "object iid=${obj.instanceId} visibility should be Private, was ${obj.visibility}"
                seatId !in obj.viewersList ->
                    "object iid=${obj.instanceId} viewersList should contain seat $seatId, " +
                        "was ${obj.viewersList}"
                else -> "object is private to seat $seatId"
            }
        MatcherResult(
            passed,
            { explanation },
            { "object iid=${obj.instanceId} should NOT be private to seat $seatId" },
        )
    }

/**
 * The object is a copy projected onto another card — `isCopy=true` AND
 * `parentId` points back at [sourceIid]. Used for Prepared (Honorbound Page →
 * Forum's Favor copy) and other copy-projection cases.
 */
fun beCopyOf(sourceIid: Int): Matcher<GameObjectInfo> =
    Matcher { obj ->
        val passed = obj.isCopy && obj.parentId == sourceIid
        val explanation =
            when {
                !obj.isCopy ->
                    "object iid=${obj.instanceId} should have isCopy=true, was false"
                obj.parentId != sourceIid ->
                    "object iid=${obj.instanceId} parentId should be $sourceIid, was ${obj.parentId}"
                else -> "object is a copy of $sourceIid"
            }
        MatcherResult(
            passed,
            { explanation },
            { "object iid=${obj.instanceId} should NOT be a copy of $sourceIid" },
        )
    }

/**
 * `grpId > 0` — the snapshot pipeline resolved the object's card identity.
 * grpId=0 means the mapper fell back to "unknown" (token without
 * `tokenGrpIds` registration, or a name lookup that missed).
 */
fun haveResolvedGrpId(): Matcher<GameObjectInfo> =
    Matcher { obj ->
        MatcherResult(
            obj.grpId > 0,
            { "object iid=${obj.instanceId} grpId should be resolved (>0), was ${obj.grpId}" },
            { "object iid=${obj.instanceId} grpId should NOT be resolved" },
        )
    }
