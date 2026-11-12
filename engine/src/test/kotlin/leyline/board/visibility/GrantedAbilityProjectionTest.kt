package leyline.board.visibility

import forge.card.CardStateName
import forge.card.CardType
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.StateMapperShell
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class GrantedAbilityProjectionTest :
    BoardTest({
        test("equivalent public grants conceal printed slot counts across objects annotations and offers") {
            val (board, cards, _) = grantedBoard()
            val snapshot = GsmSnapshot.capture(board.game, board.bridge, "test", 1)
            cards
                .map {
                    snapshot.boundCards
                        .getValue(ForgeCardId(it.id))
                        .data!!
                        .abilityIds.size
                }.distinct()
                .size shouldBe 2
            val ids =
                cards.map { card ->
                    val iid = board.instanceId(card.id)
                    val gsm = opponentGrantProjection(board)
                    val obj = gsm.gameObjectsList.single { it.instanceId == iid }
                    val row = obj.uniqueAbilitiesList.single()
                    val annotation =
                        gsm.persistentAnnotationsList.single {
                            AnnotationType.AddAbility_af5a in it.typeList &&
                                iid in it.affectedIdsList
                        }
                    val offered = grantOffers(board, card).single().action
                    assertSoftly {
                        obj.grpId shouldBe 3
                        obj.name shouldBe 0
                        obj.isFacedown shouldBe true
                        annotation.detailsList.single { it.key == "UniqueAbilityId" }.valueInt32List shouldBe listOf(row.id)
                        offered.uniqueAbilityId shouldBe row.id
                        offered.abilityGrpId shouldBe row.grpId
                    }
                    row.id to row.grpId
                }
            ids[0] shouldBe ids[1]
            opponentGrantProjection(board)
                .gameObjectsList
                .filter {
                    it.isFacedown
                }.flatMap { it.uniqueAbilitiesList }
                .map { it.id to it.grpId } shouldBe
                ids

            val angel = cards[1]
            val originalId = ids[1].first
            angel.turnFaceUp(false, null) shouldBe true
            board.bridge.invalidateAbilityRegistries(listOf(GameEvent.CardTransformed(ForgeCardId(angel.id), CardStateName.Original)))
            grantOffers(board, angel).single().action.uniqueAbilityId shouldBe originalId
            board.bridge.invalidateAbilityRegistries(
                listOf(GameEvent.ZoneChanged(ForgeCardId(angel.id), Zone.Battlefield, Zone.Battlefield)),
            )
            val restored = opponentGrantProjection(board).gameObjectsList.single { it.instanceId == board.instanceId(angel.id) }
            assertSoftly {
                restored.isFacedown shouldBe false
                restored.uniqueAbilitiesList.single { it.grpId == ids[1].second }.id shouldBe originalId
                restored.uniqueAbilitiesList
                    .map { it.id }
                    .distinct()
                    .size shouldBe restored.uniqueAbilitiesCount
            }
        }

        test("ward and type-derived mana rows cannot collide with an explicit public grant") {
            val (board, cards, auras) = grantedBoard()
            cards[1].setCloaked(auras[1].spellAbilities.first())
            cards[0].faceDownState.setType(CardType(listOf("Land", "Forest"), false))
            val gsm = opponentGrantProjection(board)
            val land = gsm.gameObjectsList.single { it.instanceId == board.instanceId(cards[0].id) }
            val cloaked = gsm.gameObjectsList.single { it.instanceId == board.instanceId(cards[1].id) }
            assertSoftly {
                land.uniqueAbilitiesList.map { it.grpId }.toSet() shouldBe setOf(1005, 97312)
                cloaked.uniqueAbilitiesList.map { it.grpId }.toSet() shouldBe setOf(KeywordAbilityIds.WARD_TWO, 97312)
                for (obj in listOf(land, cloaked)) {
                    obj.uniqueAbilitiesList
                        .map { it.id }
                        .distinct()
                        .size shouldBe obj.uniqueAbilitiesCount
                    obj.uniqueAbilitiesList.single { it.grpId == 97312 }.id shouldBe
                        grantOffers(board, cards.single { board.instanceId(it.id) == obj.instanceId }).single().action.uniqueAbilityId
                }
            }
        }

        test("alternative activation snapshots retain the recipient grant identity after replacement") {
            lateinit var recipient: Card
            lateinit var aura: Card
            val board =
                startWithBoard { game, human, _ ->
                    addCard("Gavi, Nest Warden", human, ZoneType.Battlefield)
                    recipient = addCard("Greater Sandwurm", human, ZoneType.Battlefield)
                    aura = addCard("Presence of Gond", human, ZoneType.Battlefield)
                    aura.attachToEntity(recipient, null, true)
                    game.action.checkStaticAbilities(false)
                }
            val iid = board.instanceId(recipient.id)
            val old =
                opponentGrantProjection(board).gameObjectsList.single { it.instanceId == iid }.uniqueAbilitiesList.single {
                    it.grpId ==
                        97312
                }
            aura.unattachFromEntity(recipient, null)
            board.game.action.checkStaticAbilities(false)
            opponentGrantProjection(board)
            aura.attachToEntity(recipient, null, true)
            board.game.action.checkStaticAbilities(false)
            val gsm = opponentGrantProjection(board)
            val snapshot = GsmSnapshot.capture(board.game, board.bridge, "test", 2)
            val occurrences =
                snapshot.objects
                    .getValue(ForgeCardId(recipient.id))
                    .activationOccurrences
                    .getValue(SeatId(1))
            val current =
                gsm.gameObjectsList
                    .single { it.instanceId == iid }
                    .uniqueAbilitiesList
                    .single { it.grpId == 97312 }
            val offered = grantOffers(board, recipient).single { it.action.abilityGrpId == 97312 }.action
            assertSoftly {
                occurrences.count { it.isAlternative } shouldBe 1
                current.id shouldNotBe old.id
                occurrences.single { it.abilityGrpId == 97312 }.uniqueAbilityId shouldBe current.id
                offered.uniqueAbilityId shouldBe current.id
            }
        }

        test("retired grants do not regain stale action identities and shutdown clears recipient scope") {
            val (board, cards, auras) = grantedBoard()
            val card = cards[1]
            val aura = auras[1]
            val old = grantOffers(board, card).single().action
            aura.unattachFromEntity(card, null)
            board.game.action.checkStaticAbilities(false)
            opponentGrantProjection(board)
            grantOffers(board, card).size shouldBe 0
            aura.attachToEntity(card, null, true)
            board.game.action.checkStaticAbilities(false)
            val replacement = grantOffers(board, card).single().action
            assertSoftly {
                replacement.uniqueAbilityId shouldNotBe old.uniqueAbilityId
                replacement.abilityGrpId shouldBe old.abilityGrpId
                board.bridge.grantedAbilityIdentityCardIds().shouldNotBeEmpty()
            }
            board.bridge.invalidateAbilityRegistries(listOf(GameEvent.ZoneChanged(ForgeCardId(card.id), Zone.Battlefield, Zone.Hand)))
            grantOffers(board, card).single().action.uniqueAbilityId shouldNotBe replacement.uniqueAbilityId
            board.bridge.shutdown()
            board.bridge.grantedAbilityIdentityCardIds() shouldBe emptySet()
            board.bridge.cachedAbilityRegistryCardIds() shouldBe emptySet()
        }
    })

private fun BoardTest.grantedBoard(): Triple<Board, List<Card>, List<Card>> {
    val cards = mutableListOf<Card>()
    val auras = mutableListOf<Card>()
    val board =
        startWithBoard { _, human, _ ->
            for (name in listOf("Forest", "Serra Angel")) {
                val card = addCard(name, human, ZoneType.Battlefield)
                cards += card
                auras += addCard("Presence of Gond", human, ZoneType.Battlefield)
            }
        }
    cards.zip(auras).forEach { (card, aura) ->
        card.turnFaceDown() shouldBe true
        aura.attachToEntity(card, null, true)
    }
    board.game.action.checkStaticAbilities(false)
    return Triple(board, cards, auras)
}

private fun opponentGrantProjection(board: Board) =
    StateMapperShell
        .buildFromSnapshot(
            snap = GsmSnapshot.capture(board.game, board.bridge, "test", 1),
            gameStateId = 1,
            matchId = "test",
            bridge = board.bridge,
            viewingSeatId = 2,
            effectFacts = board.bridge.materializeEffectProjectionFacts(),
            abilityExhaustionFacts = AbilityExhaustionFacts(),
        ).gsm

private fun grantOffers(
    board: Board,
    card: Card,
) = ActionMapper
    .buildProjectionFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 1), board.bridge)
    .offers
    .filter { it.action.actionType == ActionType.Activate_add3 && it.action.instanceId == board.instanceId(card.id) }
