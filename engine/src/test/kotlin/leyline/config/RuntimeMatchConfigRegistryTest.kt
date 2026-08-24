package leyline.config

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.domain.deck.DeckSource

class RuntimeMatchConfigRegistryTest :
    FunSpec({
        tags(UnitTag)

        test("stores normalized configs by matchId") {
            val registry = RuntimeMatchConfigRegistry()

            val stored =
                registry.put(
                    RuntimeMatchConfig(
                        matchId = " web-gre-1 ",
                        seat1 = DeckSource.ForgeText("1 Shock"),
                        seat2 = DeckSource.ForgeText("1 Plains"),
                        gameVariant = " brawl ",
                        spectatorMode = true,
                    ),
                )

            assertSoftly {
                stored.matchId shouldBe "web-gre-1"
                stored.seat1 shouldBe DeckSource.ForgeText("1 Shock")
                stored.seat2 shouldBe DeckSource.ForgeText("1 Plains")
                stored.gameVariant shouldBe "brawl"
                stored.spectatorMode shouldBe true
                registry.get("web-gre-1") shouldBe stored
                registry.get(" web-gre-1 ").shouldBeNull()
            }
        }

        test("normalizes launch response for match control clients") {
            val registry = RuntimeMatchConfigRegistry()

            val response =
                registry.configure(
                    RuntimeMatchConfig(
                        matchId = " web-gre-2 ",
                        seat1 = DeckSource.ForgeText(" 60 Plains "),
                        seat2 = DeckSource.ForgeText(" "),
                        puzzle = " puzzle-name ",
                    ),
                )

            assertSoftly {
                response.accepted shouldBe true
                response.matchId shouldBe "web-gre-2"
                response.wireMatchId shouldBe "web-gre-2"
                response.config.matchId shouldBe "web-gre-2"
                response.config.seat1 shouldBe DeckSource.ForgeText("60 Plains")
                response.config.seat2.shouldBeNull()
                response.config.puzzle shouldBe "puzzle-name"
                registry.get("web-gre-2") shouldBe response.config
            }
        }

        test("removes configs independently") {
            val registry = RuntimeMatchConfigRegistry()
            registry.put(RuntimeMatchConfig(matchId = "one", puzzle = "/tmp/one.pzl"))
            val two = registry.put(RuntimeMatchConfig(matchId = "two", seat1 = DeckSource.ForgeText("1 Mountain")))

            assertSoftly {
                registry.remove("one")?.matchId shouldBe "one"
                registry.get("one").shouldBeNull()
                registry.get("two") shouldBe two
            }
        }
    })
