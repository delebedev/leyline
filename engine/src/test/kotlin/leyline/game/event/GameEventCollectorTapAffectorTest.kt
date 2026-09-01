package leyline.game.event

import forge.game.ability.ApiType
import forge.game.cost.Cost
import forge.game.event.GameEventCardTapped
import forge.game.spellability.AbilityActivated
import forge.game.spellability.AbilitySub
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer

class GameEventCollectorTapAffectorTest :
    BoardTest({
        test("spell tap event keeps the spell card affector") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Shock", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val land =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()

            game.fireEvent(GameEventCardTapped(land, true, spell.firstSpellAbility))

            val tapped =
                collector
                    .closeFrame()
                    .events
                    .filterIsInstance<GameEvent.CardTapped>()
                    .single()
            assertSoftly {
                tapped.affectorSpellCardId shouldBe ForgeCardId(spell.id)
                tapped.affectorAbilityForgeId shouldBe 0
            }
        }

        test("chained ability tap event keeps the root ability affector") {
            val (b, game, _) =
                startWithBoard { _, human, _ -> addCard("Forest", human, ZoneType.Battlefield) }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val land =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val root =
                object : AbilityActivated(land, Cost("1", true), null) {
                    override fun resolve() = Unit
                }.also { it.api = ApiType.PutCounter }
            val child = AbilitySub(ApiType.Tap, land, null, emptyMap())
            root.setSubAbility(child)

            game.fireEvent(GameEventCardTapped(land, true, child))

            val tapped =
                collector
                    .closeFrame()
                    .events
                    .filterIsInstance<GameEvent.CardTapped>()
                    .single()
            assertSoftly {
                tapped.affectorAbilityForgeId shouldBe root.id
                tapped.affectorAbilityForgeId shouldBe child.rootAbility.id
            }
        }

        test("triggered tap event keeps the wrapped stack ability affector") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Lunarch Veteran", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Lunarch Veteran" }
            val target =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Forest" }
            val trigger = source.triggers.first { it.overridingAbility != null }
            val underlying = trigger.overridingAbility
            val child = AbilitySub(ApiType.Tap, source, null, emptyMap())
            underlying.setSubAbility(child)
            val wrapped = WrappedAbility(trigger, underlying, game.humanPlayer)
            game.stack.addAndUnfreeze(wrapped)

            game.fireEvent(GameEventCardTapped(target, true, child))

            val tapped =
                collector
                    .closeFrame()
                    .events
                    .filterIsInstance<GameEvent.CardTapped>()
                    .single()
            assertSoftly {
                wrapped.id shouldNotBe child.rootAbility.id
                tapped.affectorAbilityForgeId shouldBe wrapped.id
            }
        }
    })
