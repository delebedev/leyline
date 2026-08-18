package leyline.game.mapping

import forge.card.MagicColor
import forge.game.mana.Mana
import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.SnapshotCapture
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.SessionTest
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
    BoardTest({

        test("Delve card in hand displays printed cost with a full graveyard, no prompt raised") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Treasure Cruise", human, ZoneType.Hand)
                    repeat(4) { addCard("Forest", human, ZoneType.Graveyard) }
                    repeat(2) { addCard("Island", human) }
                }
            val (active, inactive) = castOffers(b, game, "Treasure Cruise")
            (active + inactive).single() should haveManaCost(generic = 7, blue = 1)
            b
                .seat(SeatId(1))
                .prompt.history
                .shouldBeEmpty()
        }

        test("Convoke card displays printed cost despite untapped creatures") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Conclave Tribunal", human, ZoneType.Hand)
                    repeat(4) { addCard("Grizzly Bears", human) }
                }
            val (active, inactive) = castOffers(b, game, "Conclave Tribunal")
            (active + inactive).single() should haveManaCost(generic = 3, white = 1)
            b
                .seat(SeatId(1))
                .prompt.history
                .shouldBeEmpty()
        }

        test("Convoke card displays a state-derived static reduction") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Conclave Tribunal", human, ZoneType.Hand)
                    // "Enchantment spells you cast cost {1} less to cast."
                    addCard("Starfield Mystic", human)
                    addCard("Grizzly Bears", human)
                }
            val (active, inactive) = castOffers(b, game, "Conclave Tribunal")
            (active + inactive).single() should haveManaCost(generic = 2, white = 1)
        }

        test("Waterbend activation cost displays printed value despite untapped creatures") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Giant Koi: "{Waterbend 3}: this creature can't be blocked."
                    addCard("Giant Koi", human)
                    repeat(2) { addCard("Grizzly Bears", human) }
                }
            val (active, inactive) = castOffers(b, game, "Giant Koi", ActionType.Activate_add3)
            (active + inactive).single() should haveManaCost(generic = 3)
            b
                .seat(SeatId(1))
                .prompt.history
                .shouldBeEmpty()
        }

        test("X-cost spell offered as castable with only its colored pips payable") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Traumatic Critique {X}{U}{R} — X can be 0; only U and R are required.
                    addCard("Traumatic Critique", human, ZoneType.Hand)
                    addCard("Island", human)
                    addCard("Mountain", human)
                }
            val (active, inactive) = castOffers(b, game, "Traumatic Critique")
            active shouldHaveSize 1
            inactive shouldHaveSize 0
        }

        test("spell is castable from floating mana alone with no untapped sources left") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Grizzly Bears {1}{G} — pay entirely from an already-floating
                    // pool; both lands that produced it are tapped and no other
                    // mana source exists.
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    val forest1 = addCard("Forest", human)
                    val forest2 = addCard("Forest", human)
                    forest1.setTapped(true)
                    forest2.setTapped(true)
                    human.manaPool.addMana(Mana(MagicColor.GREEN, forest1, null, human))
                    human.manaPool.addMana(Mana(MagicColor.GREEN, forest2, null, human))
                }
            val (active, inactive) = castOffers(b, game, "Grizzly Bears")
            active shouldHaveSize 1
            inactive shouldHaveSize 0
        }

        test("static reducer shows on a plain spell") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Fall of the Thran", human, ZoneType.Hand)
                    addCard("Starfield Mystic", human)
                }
            val (active, inactive) = castOffers(b, game, "Fall of the Thran")
            (active + inactive).single() should haveManaCost(generic = 4, white = 1)
        }

        test("AlternateAdditionalCost card yields one Cast offer at base cost") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Thunderherd Migration {1}{G}: additional cost — reveal a
                    // Dinosaur ({1}{G} variant) or pay {1} more ({2}{G} variant).
                    addCard("Thunderherd Migration", human, ZoneType.Hand)
                    repeat(2) { addCard("Forest", human) }
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

        test("best-effort affordability includes Emerge and restores payment state") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Wretched Gryff", human, ZoneType.Hand)
                    addCard("Walking Corpse", human)
                    repeat(4) { addCard("Island", human) }
                }
            val gryff = game.humanPlayerCard("Wretched Gryff")
            val corpse = game.humanPlayerCard("Walking Corpse")
            val human = gryff.controller
            val emerge =
                getAllCastableAbilities(gryff, human)
                    .single { it.alternativeCost == AlternativeCost.Emerge }

            assertSoftly {
                ActionManaCosts.canPayManaCost(emerge, human) shouldBe true
                emerge.sacrificedAsEmerge shouldBe null
                corpse.isUsedToPay shouldBe false
            }
        }

        test("restricted any-color mana only enables matching creature spells") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Voice of Victory", human, ZoneType.Hand)
                    addCard("Island", human)
                    addCard("Cavern of Souls", human).setChosenType("Elf")
                }
            val cavern = game.humanPlayerCard("Cavern of Souls")

            castOffers(b, game, "Voice of Victory").let { (active, inactive) ->
                active.shouldBeEmpty()
                inactive shouldHaveSize 1
            }

            cavern.setChosenType("Human")
            castOffers(b, game, "Voice of Victory").let { (active, inactive) ->
                active shouldHaveSize 1
                inactive.shouldBeEmpty()
            }
        }

        test("naive and snapshot builders agree on displayed cost for every hand card") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Treasure Cruise", human, ZoneType.Hand)
                    addCard("Conclave Tribunal", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Fall of the Thran", human, ZoneType.Hand)
                    addCard("Starfield Mystic", human)
                    addCard("Grizzly Bears", human)
                    repeat(3) { addCard("Forest", human, ZoneType.Graveyard) }
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val snapshotReq = ActionMapper.buildFromSnapshot(SessionTest.HUMAN_SEAT, snap, b)
            val naiveReq = ActionMapper.buildNaiveActions(SessionTest.HUMAN_SEAT, b)

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

/**
 * Cast-family action offers for [cardName], split active vs inactive.
 * [actionType] defaults to Cast; pass e.g. [ActionType.Activate_add3] for an
 * activated-ability offer (Waterbend, etc).
 */
private fun castOffers(
    b: GameBridge,
    game: forge.game.Game,
    cardName: String,
    actionType: ActionType = ActionType.Cast,
): Pair<List<Action>, List<Action>> {
    val instanceId = b.getOrAllocInstanceId(ForgeCardId(game.humanPlayerCard(cardName).id)).value
    val req = ActionMapper.buildFromSnapshot(SessionTest.HUMAN_SEAT, SnapshotCapture.run(game, b, "test", 0), b)
    return req.actionsList.filter { it.actionType == actionType && it.instanceId == instanceId } to
        req.inactiveActionsList.filter { it.actionType == actionType && it.instanceId == instanceId }
}
