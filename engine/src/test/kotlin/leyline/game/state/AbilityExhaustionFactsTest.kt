package leyline.game.state

import forge.game.ability.ApiType
import forge.game.cost.Cost
import forge.game.spellability.AbilityActivated
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.annotations.buildAbilityExhaustedAnnotations
import leyline.game.bundle.AbilityExhaustionFactsCapture
import leyline.game.mapping.FrameIdResolver
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest

class AbilityExhaustionFactsTest :
    BoardTest({
        test("facts defensively freeze final display rows") {
            val sourceId = ForgeCardId(11)
            val rows = mutableListOf(AbilityExhaustionFacts.Row(sourceId, 22, 3, 44))

            val facts = AbilityExhaustionFacts(rows)
            rows.clear()

            facts.rows shouldContainExactly listOf(AbilityExhaustionFacts.Row(sourceId, 22, 3, 44))
            shouldThrow<UnsupportedOperationException> {
                (facts.rows as MutableList).clear()
            }
        }

        test("shell materializes ordered used Boast and Exhaust rows before registry lookup") {
            var unrelatedId: ForgeCardId? = null
            var usherId: ForgeCardId? = null
            var jeongId: ForgeCardId? = null
            var lootId: ForgeCardId? = null
            val board =
                startWithBoard { _, human, _ ->
                    val unrelated = addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    val usher = addCard("Usher of the Fallen", human, ZoneType.Battlefield)
                    val jeong = addCard("Jeong Jeong, the Deserter", human, ZoneType.Battlefield)
                    val loot = addCard("Loot, the Pathfinder", human, ZoneType.Battlefield)
                    unrelatedId = ForgeCardId(unrelated.id)
                    usherId = ForgeCardId(usher.id)
                    jeongId = ForgeCardId(jeong.id)
                    lootId = ForgeCardId(loot.id)
                }
            val unrelatedForgeId = checkNotNull(unrelatedId)
            val usherForgeId = checkNotNull(usherId)
            val jeongForgeId = checkNotNull(jeongId)
            val lootForgeId = checkNotNull(lootId)
            val bridge = board.bridge
            val usher = checkNotNull(bridge.findCard(usherForgeId))
            val jeong = checkNotNull(bridge.findCard(jeongForgeId))
            val loot = checkNotNull(bridge.findCard(lootForgeId))
            val boast = usher.allSpellAbilities.first { it.isBoast }
            val exhaust = jeong.allSpellAbilities.first { it.isExhaust }
            val manaExhaust = loot.manaAbilities.first { it.isExhaust }
            val unmappedExhaust =
                object : AbilityActivated(jeong, Cost("1", true), null) {
                    override fun resolve() = Unit
                }.also {
                    it.api = ApiType.Draw
                    it.putParam("Exhaust", "True")
                    jeong.addSpellAbility(it)
                }
            jeong.addStaticAbility(
                "Mode\$ Activations | ValidCard\$ Card.Self | ValidSA\$ Activated.Exhaust | Additional\$ 2",
            )
            usher.addAbilityActivated(boast)
            jeong.addAbilityActivated(exhaust)
            jeong.addAbilityActivated(unmappedExhaust)
            loot.addAbilityActivated(manaExhaust)
            val snapshot = GsmSnapshot.capture(board.game, bridge, "ability-exhaustion", 21)
            bridge.clearAbilityRegistryCacheForTesting()

            val frozen = AbilityExhaustionFactsCapture.capture(snapshot, bridge)

            assertSoftly {
                frozen.rows shouldContainExactly
                    listOf(
                        AbilityExhaustionFacts.Row(usherForgeId, 139868, 0, 374),
                        AbilityExhaustionFacts.Row(jeongForgeId, 192720, 2, 51),
                        AbilityExhaustionFacts.Row(lootForgeId, 176608, 0, 53),
                    )
                bridge.cachedAbilityRegistryCardIds() shouldBe setOf(usherForgeId, jeongForgeId, lootForgeId)
                bridge.cachedAbilityRegistryCardIds() shouldNotContain unrelatedForgeId
            }

            val cacheAfterCapture = bridge.cachedAbilityRegistryCardIds()
            val firstProjection = buildAbilityExhaustedAnnotations(frozen, FrameIdResolver(bridge))
            val retryProjection = buildAbilityExhaustedAnnotations(frozen, FrameIdResolver(bridge))
            jeong.addAbilityActivated(exhaust)
            val advanced = AbilityExhaustionFactsCapture.capture(snapshot, bridge)

            assertSoftly {
                firstProjection shouldBe retryProjection
                bridge.cachedAbilityRegistryCardIds() shouldBe cacheAfterCapture
                frozen.rows[1].usesRemaining shouldBe 2
                advanced.rows[1].usesRemaining shouldBe 1
            }
        }
    })
