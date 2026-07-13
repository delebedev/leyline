package leyline.mechanics.mdfc

import forge.card.CardStateName
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.buildMdfcBackLandAbility
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

@Suppress("WeakAssertionOnly")
class MdfcActionTest :
    BoardTest({

        fun actionsFor(
            actions: List<Action>,
            iid: Int,
            actionType: ActionType,
        ): List<Action> = actions.filter { it.actionType == actionType && it.instanceId == iid }

        test("spell-front land-back MDFC offers normal Cast and PlayMdfc") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Silundi Vision", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val iid = human.hand.iid("Silundi Vision")

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)

            val mainCast = actions.actionsList.firstOrNull { it.actionType == ActionType.Cast && it.instanceId == iid }
            val landFace = actionsFor(actions.actionsList, iid, ActionType.PlayMdfc).firstOrNull()
            assertSoftly {
                mainCast shouldNotBe null
                landFace shouldNotBe null
                landFace!!.grpId shouldBe 0
                landFace.facetId shouldBe 0
                landFace.abilityGrpId shouldBe 0
                landFace.sourceId shouldBe 0
                landFace.manaCostCount shouldBe 0
            }
        }

        test("spell-back MDFC offers CastMdfc with spell-face cost") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Esika, God of the Tree", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val iid = human.hand.iid("Esika, God of the Tree")

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)

            val backSpell = actionsFor(actions.actionsList, iid, ActionType.CastMdfc).firstOrNull()
            assertSoftly {
                backSpell shouldNotBe null
                backSpell!!.grpId shouldBe 0
                backSpell.facetId shouldBe 0
                backSpell.alternativeGrpId shouldBe 0
                backSpell.manaCostCount shouldNotBe 0
            }
        }

        test("MDFC actions are hand-only") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Silundi Vision", human, ZoneType.Graveyard)
                }
            val iid = game.humanPlayer.graveyard.iid("Silundi Vision")

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)

            actionsFor(actions.actionsList, iid, ActionType.PlayMdfc).shouldBeEmpty()
            actionsFor(actions.inactiveActionsList, iid, ActionType.PlayMdfc).shouldBeEmpty()
        }

        test("MDFC accept helpers resolve backside spell and land abilities") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Esika, God of the Tree", human, ZoneType.Hand)
                    addCard("Silundi Vision", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val esika = human.getZone(ZoneType.Hand).cards.first { it.name == "Esika, God of the Tree" }
            val silundi = human.getZone(ZoneType.Hand).cards.first { it.name == "Silundi Vision" }

            val castable = getAllCastableAbilities(esika, human)
            val backSpell = castable.firstOrNull { it.cardStateName == CardStateName.Backside && it.isSpell && !it.isLandAbility }
            val landAbility = buildMdfcBackLandAbility(silundi)
            landAbility?.activatingPlayer = human

            assertSoftly {
                backSpell shouldNotBe null
                castable.indexOfFirst { it === backSpell } shouldNotBe -1
                landAbility shouldNotBe null
                landAbility!!.cardStateName shouldBe CardStateName.Backside
                landAbility.canPlay() shouldBe true
            }

            // Keep bridge allocated for both hand cards; catches accidental iid assumptions in setup.
            b.getOrAllocInstanceId(ForgeCardId(esika.id)).value shouldNotBe b.getOrAllocInstanceId(ForgeCardId(silundi.id)).value
        }
    })
