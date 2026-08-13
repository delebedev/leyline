package leyline.game.state

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.MechanicSourceFactsCapture
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.testkit.BoardTest

class MechanicSourceFactsTest :
    BoardTest({
        test("mechanic source facts defensively freeze caller maps") {
            val sourceId = ForgeCardId(11)
            val tokenId = ForgeCardId(12)
            val zones = linkedMapOf(sourceId to ZoneIds.P1_HAND)
            val creators =
                linkedMapOf(
                    tokenId to MechanicSourceFacts.TokenCreator(sourceId, sourceAbilityForgeId = 91),
                )

            val facts = MechanicSourceFacts(zones, creators)
            zones[sourceId] = ZoneIds.EXILE
            creators.clear()

            assertSoftly {
                facts.sourceZoneByForgeCardId shouldContainExactly mapOf(sourceId to ZoneIds.P1_HAND)
                facts.tokenCreatorByTokenForgeCardId shouldContainExactly
                    mapOf(tokenId to MechanicSourceFacts.TokenCreator(sourceId, 91))
                shouldThrow<UnsupportedOperationException> {
                    (facts.sourceZoneByForgeCardId as MutableMap)[sourceId] = ZoneIds.EXILE
                }
                shouldThrow<UnsupportedOperationException> {
                    (facts.tokenCreatorByTokenForgeCardId as MutableMap).clear()
                }
            }
        }

        test("shell capture freezes event-relevant zones and token creator before live state advances") {
            var sourceId: ForgeCardId? = null
            var triggeringId: ForgeCardId? = null
            var tokenId: ForgeCardId? = null
            var creatorId: ForgeCardId? = null
            var creatorAbilityId = 0
            val board =
                startWithBoard { _, human, _ ->
                    val source = addCard("Grizzly Bears", human, ZoneType.Graveyard)
                    val triggering = addCard("Walking Corpse", human, ZoneType.Hand)
                    val creator = addCard("Forest", human, ZoneType.Battlefield)
                    val token = addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    token.setTokenCard(true)
                    val creatorAbility = creator.manaAbilities.single()
                    token.tokenSpawningAbility = creatorAbility
                    sourceId = ForgeCardId(source.id)
                    triggeringId = ForgeCardId(triggering.id)
                    tokenId = ForgeCardId(token.id)
                    creatorId = ForgeCardId(creator.id)
                    creatorAbilityId = creatorAbility.id
                }
            val sourceForgeId = checkNotNull(sourceId)
            val triggeringForgeId = checkNotNull(triggeringId)
            val tokenForgeId = checkNotNull(tokenId)
            val creatorForgeId = checkNotNull(creatorId)
            val events =
                listOf(
                    GameEvent.SpellCast(
                        cardId = sourceForgeId,
                        seatId = SeatId(1),
                        isAbility = true,
                        isTrigger = true,
                        abilityForgeId = 41,
                        triggeringObjectCardId = triggeringForgeId,
                    ),
                    GameEvent.TokenCreated(tokenForgeId, SeatId(1)),
                )

            val frozen = MechanicSourceFactsCapture.capture(board.bridge, events)
            moveToBattlefield(checkNotNull(board.bridge.findCard(sourceForgeId)), board.game)
            exile(checkNotNull(board.bridge.findCard(triggeringForgeId)), board.game)
            checkNotNull(board.bridge.findCard(tokenForgeId)).tokenSpawningAbility = null
            val advanced = MechanicSourceFactsCapture.capture(board.bridge, events)

            assertSoftly {
                frozen.sourceZoneByForgeCardId shouldContainExactly
                    mapOf(
                        sourceForgeId to ZoneIds.P1_GRAVEYARD,
                        triggeringForgeId to ZoneIds.P1_HAND,
                    )
                frozen.tokenCreatorByTokenForgeCardId shouldContainExactly
                    mapOf(tokenForgeId to MechanicSourceFacts.TokenCreator(creatorForgeId, creatorAbilityId))
                advanced.sourceZone(sourceForgeId) shouldBe ZoneIds.BATTLEFIELD
                advanced.sourceZone(triggeringForgeId) shouldBe ZoneIds.EXILE
                advanced.tokenCreatorByTokenForgeCardId shouldBe emptyMap()
                frozen.sourceZone(sourceForgeId) shouldBe ZoneIds.P1_GRAVEYARD
                frozen.sourceZone(triggeringForgeId) shouldBe ZoneIds.P1_HAND
            }
        }
    })
