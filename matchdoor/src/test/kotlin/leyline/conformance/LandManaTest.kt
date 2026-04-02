package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.ForgeCardId
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ZoneIds
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Land play and mana production subsystem tests.
 *
 * Covers: zone transfer on land play, ColorProduction annotation ordinals,
 * instanceId reallocation, Limbo retirement, accumulated client state,
 * Play/ActivateMana action fields, autoTapSolution for mana sources.
 *
 * For land ETB choices (shock lands), see ShockLandEtbTest.
 */
class LandManaTest : SubsystemTest({

    // --- Zone transfer & annotation shape ---

    test("play land — annotations, zone transfer, instanceId realloc, Limbo") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Plains", human, ZoneType.Hand)
        }

        val player = humanPlayer(b)
        val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
        val origId = b.getOrAllocInstanceId(ForgeCardId(land.id)).value
        val cardId = land.id

        val gsm = capture(b, game, counter) {
            moveToBattlefield(land, game)
        }
        val newId = b.getOrAllocInstanceId(ForgeCardId(cardId)).value

        origId shouldNotBe newId

        // Strict type ordering: OIC → ZT → UAT (client replays sequentially for animations)
        val types = gsm.annotationsList.map { it.typeList.first() }
        types shouldBe listOf(
            AnnotationType.ObjectIdChanged,
            AnnotationType.ZoneTransfer_af5a,
            AnnotationType.UserActionTaken,
        )
        val ids = gsm.annotationsList.map { it.id }
        ids shouldBe ids.sorted()
        ids.toSet().size shouldBe ids.size

        assertSoftly {
            val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
            oic.affectedIdsList.shouldContain(origId)
            oic.detailInt("orig_id") shouldBe origId
            oic.detailInt("new_id") shouldBe newId
            oic.affectorId shouldBe 0

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.affectedIdsList.shouldContain(newId)
            zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
            zt.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
            zt.detailString("category") shouldBe "PlayLand"
            zt.affectorId shouldBe 0

            val uat = gsm.annotation(AnnotationType.UserActionTaken)
            uat.affectorId.toInt() shouldBe SEAT_ID
            uat.affectedIdsList.shouldContain(newId)
            uat.detailInt("actionType") shouldBe ActionType.Play_add3.number

            gsm.prevGameStateId shouldBe gsm.gameStateId - 1

            val entered = gsm.persistentAnnotation(AnnotationType.EnteredZoneThisTurn)
            entered.affectedIdsList.shouldContain(newId)

            val landObj = gsm.gameObjectsList.first { it.instanceId == newId }
            landObj.zoneId shouldBe ZoneIds.BATTLEFIELD
            landObj.uniqueAbilitiesCount shouldBeGreaterThan 0

            assertLimboContains(gsm, origId)
            gsm.diffDeletedInstanceIdsList.contains(origId).shouldBeFalse()
        }
    }

    test("play land — accumulated client state consistent") {
        val (b, game, counter) = startGameAtMain1()

        val startResult = gameStart(game, b, counter)
        val acc = ClientAccumulator()
        acc.seedFull(handshakeFull(game, b, counter.currentGsId()))
        acc.processAll(startResult.messages)
        b.snapshotFromGame(game)

        val player = humanPlayer(b)
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand")
        val origId = b.getOrAllocInstanceId(ForgeCardId(land.id))
        val cardId = land.id

        playLand(b) ?: error("No land in hand")
        val postResult = postAction(game, b, counter)
        acc.processAll(postResult.messages)
        val newId = b.getOrAllocInstanceId(ForgeCardId(cardId))

        assertSoftly {
            acc.objects[newId.value].shouldNotBeNull().zoneId shouldBe ZoneIds.BATTLEFIELD

            val handZone = acc.zones.values
                .first { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
            handZone.objectInstanceIdsList.contains(origId.value).shouldBeFalse()

            acc.zones[ZoneIds.BATTLEFIELD]!!.objectInstanceIdsList.shouldContain(newId.value)
            acc.zones[ZoneIds.LIMBO]!!.objectInstanceIdsList.shouldContain(origId.value)
        }

        acc.assertConsistent("after play land")
    }

    // --- Color production ---

    test("Forest — ColorProduction [5]") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
        }
        playLandFromHand(b, game, counter)
            .persistentAnnotation(AnnotationType.ColorProduction)
            .detailIntList("colors") shouldBe listOf(ManaColor.Green_afc9.number)
    }

    test("Jungle Hollow — ColorProduction [3, 5]") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Jungle Hollow", human, ZoneType.Hand)
        }
        playLandFromHand(b, game, counter)
            .persistentAnnotation(AnnotationType.ColorProduction)
            .detailIntList("colors")
            .shouldContainExactlyInAnyOrder(ManaColor.Black_afc9.number, ManaColor.Green_afc9.number)
    }

    // --- Action fields ---

    test("Play action — shouldStop, no abilityGrpId, no manaCost") {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Plains", human, ZoneType.Hand)
            addCard("Forest", human, ZoneType.Hand)
            addCard("Grizzly Bears", human, ZoneType.Hand)
        }

        val actions = ActionMapper.buildActions(game, 1, b)
        val playActions = actions.ofType(ActionType.Play_add3)
        playActions.shouldHaveSize(2)

        assertSoftly {
            for (a in playActions) {
                a.shouldStop.shouldBeTrue()
                a.instanceId shouldNotBe 0
                a.grpId shouldNotBe 0
                a.facetId shouldBe a.instanceId
                a.abilityGrpId shouldBe 0
                a.manaCostCount shouldBe 0
            }

            val pass = actions.ofType(ActionType.Pass)
            pass.shouldHaveSize(1)
            pass[0].instanceId shouldBe 0
            pass[0].grpId shouldBe 0
            pass[0].shouldStop.shouldBeFalse()
        }
    }

    test("ActivateMana fields after land on battlefield") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Battlefield)
            addCard("Forest", human, ZoneType.Hand)
            addCard("Grizzly Bears", human, ZoneType.Hand)
        }

        val player = humanPlayer(b)
        val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
        capture(b, game, counter) { moveToBattlefield(land, game) }

        val manaActions = ActionMapper.buildActions(game, 1, b).ofType(ActionType.ActivateMana)
        manaActions.shouldHaveSize(2)

        assertSoftly {
            for (a in manaActions) {
                a.instanceId shouldNotBe 0
                a.grpId shouldNotBe 0
                a.facetId shouldBe a.instanceId
                a.shouldStop.shouldBeFalse()
                a.isBatchable.shouldBeTrue()
                a.manaPaymentOptionsCount shouldBeGreaterThan 0
                a.manaSelectionsCount shouldBeGreaterThan 0
            }
        }
    }

    test("Cast action — manaCost, autoTapSolution with mana source details") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Battlefield)
            addCard("Forest", human, ZoneType.Hand)
            addCard("Grizzly Bears", human, ZoneType.Hand)
        }

        val player = humanPlayer(b)
        val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
        capture(b, game, counter) { moveToBattlefield(land, game) }

        val cast = ActionMapper.buildActions(game, 1, b).ofType(ActionType.Cast)
        cast.shouldHaveSize(1)

        val a = cast[0]
        assertSoftly {
            a.shouldStop.shouldBeTrue()
            a.instanceId shouldNotBe 0
            a.grpId shouldNotBe 0
            a.manaCostCount shouldBeGreaterThan 0
            a.hasAutoTapSolution().shouldBeTrue()
            a.autoTapSolution.autoTapActionsCount shouldBe 2

            for (tap in a.autoTapSolution.autoTapActionsList) {
                tap.instanceId shouldNotBe 0
                tap.hasManaPaymentOption().shouldBeTrue()
                for (m in tap.manaPaymentOption.manaList) {
                    m.srcInstanceId shouldNotBe 0
                    m.color shouldBe ManaColor.Green_afc9
                    m.count shouldBe 1
                }
            }
        }
    }

    test("dual land autoTapSolution — Jungle Hollow casts Grizzly Bears") {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Jungle Hollow", human, ZoneType.Battlefield)
            addCard("Jungle Hollow", human, ZoneType.Battlefield)
            addCard("Grizzly Bears", human, ZoneType.Hand)
        }

        val cast = ActionMapper.buildActions(game, 1, b).ofType(ActionType.Cast)
        cast.shouldHaveSize(1)

        val a = cast[0]
        a.hasAutoTapSolution().shouldBeTrue()
        a.autoTapSolution.autoTapActionsCount shouldBeGreaterThan 0
        for (tap in a.autoTapSolution.autoTapActionsList) {
            tap.instanceId shouldNotBe 0
            tap.hasManaPaymentOption().shouldBeTrue()
        }
    }

    test("GSM embedded actions stripped — no grpId, facetId, autoTap") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Battlefield)
            addCard("Forest", human, ZoneType.Hand)
            addCard("Grizzly Bears", human, ZoneType.Hand)
        }

        val player = humanPlayer(b)
        val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
        capture(b, game, counter) { moveToBattlefield(land, game) }

        val result = postAction(game, b, counter)
        val gsm = result.gsmOrNull.shouldNotBeNull()
        gsm.pendingMessageCount shouldBe 1

        fun actionStub(type: ActionType) =
            gsm.actionsList.map { it.action }.filter { it.actionType == type }

        val cast = actionStub(ActionType.Cast)
        cast.shouldHaveSize(1)
        cast[0].instanceId shouldNotBe 0
        cast[0].grpId shouldBe 0
        cast[0].facetId shouldBe 0
        cast[0].shouldStop.shouldBeFalse()
        cast[0].hasAutoTapSolution().shouldBeFalse()

        val mana = actionStub(ActionType.ActivateMana)
        mana.shouldHaveSize(2)
        for (a in mana) {
            a.instanceId shouldNotBe 0
            a.grpId shouldBe 0
            a.facetId shouldBe 0
        }

        val pass = actionStub(ActionType.Pass)
        pass.shouldHaveSize(1)
        pass[0].instanceId shouldBe 0
    }

    // --- Limbo accumulation ---

    test("Limbo grows across multiple land plays") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Plains", human, ZoneType.Hand)
            addCard("Forest", human, ZoneType.Hand)
        }

        val player = humanPlayer(b)
        val lands = player.getZone(ZoneType.Hand).cards.filter { it.isLand }
        val origId1 = b.getOrAllocInstanceId(ForgeCardId(lands[0].id))
        val origId2 = b.getOrAllocInstanceId(ForgeCardId(lands[1].id))

        capture(b, game, counter) { moveToBattlefield(lands[0], game) }
        b.getLimboInstanceIds().shouldHaveSize(1)
        b.getLimboInstanceIds().shouldContain(origId1)

        capture(b, game, counter) { moveToBattlefield(lands[1], game) }
        b.getLimboInstanceIds().shouldHaveSize(2)
        b.getLimboInstanceIds().shouldContain(origId1)
        b.getLimboInstanceIds().shouldContain(origId2)
    }

    // --- AutoTap preference ---

    test("autoTapSolution prefers lands over mana dorks") {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Plains", human, ZoneType.Battlefield)
            addCard("Plains", human, ZoneType.Battlefield)
            addCard("Plains", human, ZoneType.Battlefield)
            addCard("Plains", human, ZoneType.Battlefield)
            addCard("Forest", human, ZoneType.Battlefield)
            addCard("Llanowar Elves", human, ZoneType.Battlefield)
            addCard("Pacifism", human, ZoneType.Hand)
        }

        val cast = ActionMapper.buildActions(game, 1, b).ofType(ActionType.Cast)
        cast.shouldHaveSize(1)

        val autoTap = cast[0].autoTapSolution
        autoTap.shouldNotBeNull()
        autoTap.autoTapActionsCount shouldBe 2

        val landInstanceIds = game.humanPlayer.getZone(ZoneType.Battlefield).cards
            .filter { it.isLand }
            .map { b.getOrAllocInstanceId(ForgeCardId(it.id)).value }
            .toSet()

        for (tap in autoTap.autoTapActionsList) {
            (tap.instanceId in landInstanceIds) shouldBe true
        }
    }
})
