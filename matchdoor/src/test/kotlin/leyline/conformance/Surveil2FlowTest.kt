package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Regression: multi-card surveil GroupResp card lookup failed because
 * `player.allCards` can't see zoneless cards during surveil resolution.
 * Fix: use `game.findById()` (#168).
 */
class Surveil2FlowTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("surveil 2 puts chosen cards into graveyard") {
            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/surveil-2.pzl")

            // Cast Sterling Hound (ETB surveil 2) — blocks on GroupReq
            val groupReq = h.castSpellUntilGroupReq("Sterling Hound")
            groupReq.context shouldBe GroupingContext.Surveil
            groupReq.instanceIdsList.size shouldBeGreaterThanOrEqual 2

            val allIds = groupReq.instanceIdsList
            // Put all cards to graveyard (away group)
            val msg = ClientToGREMessage.newBuilder()
                .setType(ClientMessageType.GroupResp_097b)
                .setGroupResp(
                    GroupResp.newBuilder()
                        .addGroups(
                            Group.newBuilder()
                                .setZoneType(ZoneType.Library)
                                .setSubZoneType(SubZoneType.Top),
                        )
                        .addGroups(
                            Group.newBuilder()
                                .addAllIds(allIds)
                                .setZoneType(ZoneType.Graveyard)
                                .setSubZoneType(SubZoneType.None_a455),
                        )
                        .setGroupType(GroupType.Ordered),
                )
                .build()
            h.session.onGroupResp(msg)
            h.drainSink()

            // Both surveiled cards should be in graveyard
            val player = h.bridge.getPlayer(SeatId(1))!!
            val gy = player.getZone(ForgeZoneType.Graveyard)
            gy.size() shouldBeGreaterThanOrEqual 2

            h.isGameOver().shouldBeFalse()
        }
    })
