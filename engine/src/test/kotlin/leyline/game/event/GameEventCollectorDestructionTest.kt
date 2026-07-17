package leyline.game.event

import forge.game.card.CardView
import forge.game.event.GameEventCardDamaged
import forge.game.event.GameEventCardDestroyed
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import forge.game.event.DamageSourceKind as ForgeDamageSourceKind

/**
 * Destruction-cause classification and damage-flag mapping in
 * [leyline.game.event.GameEventCollector]: SBA lethal-damage and deathtouch
 * deaths vs effect destroys, and the combat flag on card damage events.
 */
class GameEventCollectorDestructionTest :
    BoardTest({

        test("SBA destroy after normal damage classifies as lethal-damage death") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bolt =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(
                GameEventCardDamaged(
                    CardView.get(creature),
                    CardView.get(bolt),
                    3,
                    GameEventCardDamaged.DamageType.Normal,
                    false,
                ),
            )
            game.fireEvent(GameEventCardDestroyed(creature, null as SpellAbility?))

            val destroyed = collector.closeFrame().events.filterIsInstance<GameEvent.CardDestroyed>()
            destroyed.size shouldBe 1
            destroyed[0].destruction shouldBe DestructionCause.LethalDamage
        }

        test("SBA destroy after deathtouch damage classifies as deathtouch death") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(
                GameEventCardDamaged(
                    CardView.get(creature),
                    CardView.get(source),
                    1,
                    GameEventCardDamaged.DamageType.Deathtouch,
                    false,
                ),
            )
            game.fireEvent(GameEventCardDestroyed(creature, null as SpellAbility?))

            val events = collector.closeFrame().events
            val damaged = events.filterIsInstance<GameEvent.DamageDealtToCard>()
            val destroyed = events.filterIsInstance<GameEvent.CardDestroyed>()
            assertSoftly {
                damaged.size shouldBe 1
                damaged[0].deathtouch.shouldBeTrue()
                destroyed.size shouldBe 1
                destroyed[0].destruction shouldBe DestructionCause.Deathtouch
            }
        }

        test("card damage carries the combat flag from the engine event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(
                GameEventCardDamaged(
                    CardView.get(creature),
                    CardView.get(source),
                    3,
                    GameEventCardDamaged.DamageType.Normal,
                    false,
                ),
            )
            game.fireEvent(
                GameEventCardDamaged(
                    CardView.get(creature),
                    CardView.get(source),
                    2,
                    GameEventCardDamaged.DamageType.Normal,
                    true,
                ),
            )
            game.fireEvent(
                GameEventCardDamaged(
                    CardView.get(creature),
                    CardView.get(source),
                    1,
                    GameEventCardDamaged.DamageType.Normal,
                    ForgeDamageSourceKind.Fight,
                ),
            )

            val damaged = collector.closeFrame().events.filterIsInstance<GameEvent.DamageDealtToCard>()
            damaged.map { it.sourceKind } shouldContainExactly
                listOf(DamageSourceKind.SpellOrAbility, DamageSourceKind.Combat, DamageSourceKind.Fight)
        }

        test("destroy with a causing source stays an effect destruction") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bolt =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventCardDestroyed(creature, bolt))

            val destroyed = collector.closeFrame().events.filterIsInstance<GameEvent.CardDestroyed>()
            destroyed.size shouldBe 1
            destroyed[0].destruction shouldBe DestructionCause.Effect
        }
    })
