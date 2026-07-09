package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTestBase
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Displayed-cost rule over fixture boards: the cost on an action offer is the
 * printed cost after state-derived modifications only, never reduced by a
 * payment-time choice (Delve, Convoke, Waterbend) — and computing it never
 * raises a prompt.
 *
 * See docs/decisions/0007-displayed-cost-and-controller-contexts.md.
 */
class CastDisplayCostBoardTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        fun castActionsFor(
            req: wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq,
            instanceId: Int,
            actionType: ActionType = ActionType.Cast,
        ): List<Action> = (req.actionsList + req.inactiveActionsList).filter { it.actionType == actionType && it.instanceId == instanceId }

        test("Delve card in hand displays printed cost with a full graveyard, no prompt raised") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Treasure Cruise", human, ZoneType.Hand)
                    repeat(4) { base.addCard("Forest", human, ZoneType.Graveyard) }
                    repeat(2) { base.addCard("Island", human) }
                }
            val cruise = game.humanPlayerCard("Treasure Cruise")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val req = ActionMapper.buildFromSnapshot(1, snap, b)

            val cast = castActionsFor(req, b.getOrAllocInstanceId(ForgeCardId(cruise.id)).value).single()
            cast should haveManaCost(generic = 7, blue = 1)
            b
                .seat(SeatId(1))
                .prompt.history
                .shouldBeEmpty()
        }

        test("Convoke card displays printed cost despite untapped creatures") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Conclave Tribunal", human, ZoneType.Hand)
                    repeat(4) { base.addCard("Grizzly Bears", human) }
                }
            val tribunal = game.humanPlayerCard("Conclave Tribunal")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val req = ActionMapper.buildFromSnapshot(1, snap, b)

            val cast = castActionsFor(req, b.getOrAllocInstanceId(ForgeCardId(tribunal.id)).value).single()
            cast should haveManaCost(generic = 3, white = 1)
            b
                .seat(SeatId(1))
                .prompt.history
                .shouldBeEmpty()
        }

        test("Convoke card displays a state-derived static reduction") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Conclave Tribunal", human, ZoneType.Hand)
                    // "Enchantment spells you cast cost {1} less to cast."
                    base.addCard("Starfield Mystic", human)
                    base.addCard("Grizzly Bears", human)
                }
            val tribunal = game.humanPlayerCard("Conclave Tribunal")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val req = ActionMapper.buildFromSnapshot(1, snap, b)

            val cast = castActionsFor(req, b.getOrAllocInstanceId(ForgeCardId(tribunal.id)).value).single()
            cast should haveManaCost(generic = 2, white = 1)
        }

        test("Waterbend activation cost displays printed value despite untapped creatures") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    // Giant Koi: "{Waterbend 3}: this creature can't be blocked."
                    base.addCard("Giant Koi", human)
                    repeat(2) { base.addCard("Grizzly Bears", human) }
                }
            val koi = game.humanPlayerCard("Giant Koi")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val req = ActionMapper.buildFromSnapshot(1, snap, b)

            val activate =
                castActionsFor(req, b.getOrAllocInstanceId(ForgeCardId(koi.id)).value, ActionType.Activate_add3).single()
            activate should haveManaCost(generic = 3)
            b
                .seat(SeatId(1))
                .prompt.history
                .shouldBeEmpty()
        }

        test("static reducer shows on a plain spell") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Fall of the Thran", human, ZoneType.Hand)
                    base.addCard("Starfield Mystic", human)
                }
            val fall = game.humanPlayerCard("Fall of the Thran")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val req = ActionMapper.buildFromSnapshot(1, snap, b)

            val cast = castActionsFor(req, b.getOrAllocInstanceId(ForgeCardId(fall.id)).value).single()
            cast should haveManaCost(generic = 4, white = 1)
        }

        test("AlternateAdditionalCost card yields one Cast offer at base cost") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    // Thunderherd Migration {1}{G}: additional cost — reveal a
                    // Dinosaur ({1}{G} variant) or pay {1} more ({2}{G} variant).
                    base.addCard("Thunderherd Migration", human, ZoneType.Hand)
                    repeat(2) { base.addCard("Forest", human) }
                }
            val migration = game.humanPlayerCard("Thunderherd Migration")
            val human = game.players.first { it.name == migration.controller.name }

            val (actions, inactive) =
                ActionMapper.buildHandCastActionsForCard(
                    card = migration,
                    player = human,
                    instanceId = b.getOrAllocInstanceId(ForgeCardId(migration.id)).value,
                    grpId = 0,
                    checkLegality = true,
                    idResolver = { b.getOrAllocInstanceId(it) },
                    grpIdResolver = { leyline.bridge.types.GrpId(0) },
                    cardDataLookup = { null },
                )

            val casts = actions + inactive
            casts.size shouldBe 1
            casts.single() should haveManaCost(generic = 1, green = 1)
        }

        test("naive and snapshot builders agree on displayed cost for every hand card") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Treasure Cruise", human, ZoneType.Hand)
                    base.addCard("Conclave Tribunal", human, ZoneType.Hand)
                    base.addCard("Grizzly Bears", human, ZoneType.Hand)
                    base.addCard("Fall of the Thran", human, ZoneType.Hand)
                    base.addCard("Starfield Mystic", human)
                    base.addCard("Grizzly Bears", human)
                    repeat(3) { base.addCard("Forest", human, ZoneType.Graveyard) }
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val snapshotReq = ActionMapper.buildFromSnapshot(1, snap, b)
            val naiveReq = ActionMapper.buildNaiveActions(1, b)

            val naiveCasts =
                naiveReq.actionsList
                    .filter { it.actionType == ActionType.Cast }
                    .associateBy { it.instanceId }
            naiveCasts.shouldNotBeEmpty()

            for ((instanceId, naive) in naiveCasts) {
                val snapshotCast =
                    (snapshotReq.actionsList + snapshotReq.inactiveActionsList)
                        .filter { it.actionType == ActionType.Cast && it.instanceId == instanceId }
                        // Alt-cost offers carry alternativeGrpId; compare the base offer.
                        .first { it.alternativeGrpId == 0 }
                snapshotCast.manaCostList shouldBe naive.manaCostList
            }
        }
    })

private fun forge.game.Game.humanPlayerCard(name: String): forge.game.card.Card =
    players
        .flatMap { p -> listOf(ZoneType.Hand, ZoneType.Battlefield).flatMap { p.getZone(it).cards } }
        .first { it.name == name }
