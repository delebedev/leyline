package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.MechanicSourceFacts

class MechanicSourceProjectionTest :
    FunSpec({
        tags(UnitTag)

        val sourceId = ForgeCardId(10)
        val triggeringId = ForgeCardId(11)
        val tokenId = ForgeCardId(12)
        val fallbackSourceId = ForgeCardId(13)
        val facts =
            MechanicSourceFacts(
                sourceZoneByForgeCardId =
                    mapOf(
                        sourceId to ZoneIds.P1_GRAVEYARD,
                        triggeringId to ZoneIds.P1_HAND,
                    ),
                tokenCreatorByTokenForgeCardId =
                    mapOf(
                        tokenId to MechanicSourceFacts.TokenCreator(fallbackSourceId, 71),
                    ),
            )

        test("source zones preserve explicit activation then cut facts then battlefield default") {
            val explicit =
                GameEvent.SpellCast(
                    sourceId,
                    SeatId(1),
                    isAbility = true,
                    activationZoneId = ZoneIds.EXILE,
                    triggeringObjectCardId = triggeringId,
                )
            val collapsed = explicit.copy(activationZoneId = 0)
            val absent = collapsed.copy(cardId = ForgeCardId(99), triggeringObjectCardId = ForgeCardId(98))

            assertSoftly {
                MechanicSourceProjection.sourceZoneId(explicit, facts) shouldBe ZoneIds.EXILE
                MechanicSourceProjection.sourceZoneId(collapsed, facts) shouldBe ZoneIds.P1_GRAVEYARD
                MechanicSourceProjection.triggeringObjectZoneId(collapsed, ZoneIds.P1_GRAVEYARD, facts) shouldBe
                    ZoneIds.P1_HAND
                MechanicSourceProjection.sourceZoneId(absent, facts) shouldBe ZoneIds.BATTLEFIELD
                MechanicSourceProjection.triggeringObjectZoneId(absent, ZoneIds.BATTLEFIELD, facts) shouldBe
                    ZoneIds.BATTLEFIELD
            }
        }

        test("token source precedence covers explicit ability resolving spell source card fallback and sole resolving spell") {
            val resolving = mapOf(sourceId to InstanceId(501))
            val stackAbility: (Int, ForgeCardId) -> InstanceId = { abilityId, cardId ->
                InstanceId(abilityId * 100 + cardId.value)
            }
            val cardIid: (ForgeCardId) -> InstanceId = { InstanceId(1_000 + it.value) }

            val explicitAbility =
                GameEvent.TokenCreated(tokenId, SeatId(1), sourceCardId = sourceId, sourceAbilityForgeId = 7)
            val explicitSpell = explicitAbility.copy(sourceAbilityForgeId = 0)
            val explicitCard = explicitSpell.copy(sourceCardId = triggeringId)
            val fallback = GameEvent.TokenCreated(tokenId, SeatId(1))
            val soleResolvingSpell = fallback.copy(cardId = ForgeCardId(99))

            assertSoftly {
                MechanicSourceProjection.tokenCreatedAffectorId(explicitAbility, facts, resolving, stackAbility, cardIid) shouldBe
                    InstanceId(710)
                MechanicSourceProjection.tokenCreatedAffectorId(explicitSpell, facts, resolving, stackAbility, cardIid) shouldBe
                    InstanceId(501)
                MechanicSourceProjection.tokenCreatedAffectorId(explicitCard, facts, resolving, stackAbility, cardIid) shouldBe
                    InstanceId(1011)
                MechanicSourceProjection.tokenCreatedAffectorId(fallback, facts, resolving, stackAbility, cardIid) shouldBe
                    InstanceId(7113)
                MechanicSourceProjection.tokenCreatedAffectorId(soleResolvingSpell, facts, resolving, stackAbility, cardIid) shouldBe
                    InstanceId(501)
            }
        }

        test("tap affector uses resolving spell stack card iid") {
            val affector =
                MechanicSourceProjection.tapAffectorId(
                    GameEvent.CardTapped(
                        cardId = ForgeCardId(99),
                        tapped = true,
                        affectorSpellCardId = sourceId,
                    ),
                    resolvingStackIidsByCard = mapOf(sourceId to InstanceId(501)),
                    castStackIidsByCard = emptyMap(),
                    abilityIid = { error("spell source should not use ability iid") },
                    cardIid = { error("resolving spell iid is already known") },
                )

            affector shouldBe InstanceId(501)
        }

        test("mana attribution reads basic-land identity from snapshot and defaults unknown sources") {
            val forest = ForgeCardId(20)
            val nonland = ForgeCardId(21)
            val snapshot =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            forest to CardSnapshot(forest, "Forest", 100, SeatId(1), SeatId(1), basicLandManaAbilityGrpId = 1005),
                            nonland to CardSnapshot(nonland, "Mana Rock", 101, SeatId(1), SeatId(1)),
                        ),
                )

            assertSoftly {
                MechanicSourceProjection.manaAbilityGrpId(snapshot, forest).value shouldBe 1005
                MechanicSourceProjection.manaAbilityGrpId(snapshot, nonland).value shouldBe 0
                MechanicSourceProjection.manaAbilityGrpId(snapshot, ForgeCardId(99)).value shouldBe 0
            }
        }
    })
