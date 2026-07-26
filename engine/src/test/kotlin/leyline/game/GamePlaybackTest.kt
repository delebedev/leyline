package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.DamageSourceKind
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.event.ZoneMove

class GamePlaybackTest :
    FunSpec({

        tags(UnitTag)

        test("noncombat spell damage does not activate combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }

        test("fight damage does not activate combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToCard(
                        sourceCardId = ForgeCardId(10),
                        targetCardId = ForgeCardId(20),
                        amount = 2,
                        sourceKind = DamageSourceKind.Fight,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }

        test("genuine combat damage activates combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe true
        }

        test("mixed damage window stays on the unsplit causal path") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(30),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }

        test("noncombat damage waits for its resolution boundary") {
            val damage =
                GameEvent.DamageDealtToPlayer(
                    sourceCardId = ForgeCardId(10),
                    targetSeatId = SeatId(2),
                    amount = 3,
                    sourceKind = DamageSourceKind.SpellOrAbility,
                    changesLife = true,
                )

            val resolving = GameEvent.SpellResolved(cardId = ForgeCardId(10), hasFizzled = false)
            assertSoftly {
                FrameEventLog(listOf(damage)).shouldAwaitResolutionBoundary() shouldBe false
                FrameEventLog(listOf(damage, resolving)).shouldAwaitResolutionBoundary() shouldBe true
                FrameEventLog(
                    events = listOf(damage, resolving),
                    zoneMoves =
                        listOf(
                            ZoneMove(
                                order = 1,
                                cardId = ForgeCardId(10),
                                from = Zone.Stack,
                                to = Zone.Graveyard,
                                cause = null,
                            ),
                        ),
                ).shouldAwaitResolutionBoundary() shouldBe false
            }
        }

        test("ambiguous mixed damage emits without waiting for an unrelated resolution") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(30),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            FrameEventLog(events).shouldAwaitResolutionBoundary() shouldBe false
        }

        test("pending frame reuses an open reservation without duplicating its prefix") {
            val damage =
                GameEvent.DamageDealtToPlayer(
                    sourceCardId = ForgeCardId(10),
                    targetSeatId = SeatId(2),
                    amount = 3,
                    sourceKind = DamageSourceKind.SpellOrAbility,
                    changesLife = true,
                )
            val resolved = GameEvent.SpellResolved(cardId = ForgeCardId(10), hasFizzled = false)
            val pending = FrameEventLog(listOf(damage, resolved))
            val completed =
                FrameEventLog(
                    events = listOf(damage, resolved),
                    zoneMoves =
                        listOf(
                            ZoneMove(
                                order = 1,
                                cardId = ForgeCardId(10),
                                from = Zone.Stack,
                                to = Zone.Graveyard,
                                cause = null,
                            ),
                        ),
                )

            pending.mergeReservedInput(completed).events shouldBe listOf(damage, resolved)
        }

        test("pending frame merges when another frame consumed its detached prefix") {
            val damage =
                GameEvent.DamageDealtToPlayer(
                    sourceCardId = ForgeCardId(10),
                    targetSeatId = SeatId(2),
                    amount = 3,
                    sourceKind = DamageSourceKind.SpellOrAbility,
                    changesLife = true,
                )
            val resolved = GameEvent.SpellResolved(cardId = ForgeCardId(10), hasFizzled = false)
            val move =
                ZoneMove(
                    order = 1,
                    cardId = ForgeCardId(10),
                    from = Zone.Stack,
                    to = Zone.Graveyard,
                    cause = null,
                )
            val pending = FrameEventLog(listOf(damage, resolved))

            val merged = pending.mergeReservedInput(FrameEventLog(emptyList(), listOf(move)))

            assertSoftly {
                merged.events shouldBe listOf(damage, resolved)
                merged.zoneMoves shouldBe listOf(move)
            }
        }
    })
