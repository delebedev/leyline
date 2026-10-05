package leyline.game.generator

import forge.deck.DeckSection
import forge.gamemodes.limited.DraftPickStrategy
import forge.gamemodes.limited.IBoosterDraft
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.SyntheticNameLookup
import java.util.concurrent.atomic.AtomicInteger

class ForgeBoosterDraftDriverTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        test("different sets draft concurrently without changing the global land edition") {
            val driver = ForgeBoosterDraftDriver(SyntheticNameLookup()::findGrpIdByName)
            val initialLandEdition = IBoosterDraft.LAND_SET_CODE[0]
            val packs =
                mutableMapOf(
                    "tdm" to driver.start("tdm", "TDM"),
                    "neo" to driver.start("neo", "NEO"),
                )
            repeat(200) {
                for ((key, pack) in packs.toMap()) {
                    if (pack.isNotEmpty()) packs[key] = driver.pick(key, pack.first()).nextPack
                }
                if (packs.values.all { it.isEmpty() }) return@repeat
            }
            assertSoftly {
                packs.values.forEach { it shouldHaveSize 0 }
                driver.complete("tdm").botDecks shouldHaveSize 7
                driver.complete("neo").botDecks shouldHaveSize 7
                IBoosterDraft.LAND_SET_CODE[0] shouldBe initialLandEdition
            }
        }

        test("headless bot decks use their pod land edition after interleaved picks") {
            val initialLandEdition = IBoosterDraft.LAND_SET_CODE[0]
            val drafts = listOf("TDM", "NEO").associateWith { HeadlessBoosterDraft(it) }
            repeat(200) {
                for (draft in drafts.values) {
                    val pack = draft.currentPackPaperCards()
                    if (pack.isNotEmpty()) draft.chooseLocally(pack.first()) shouldBe true
                }
            }
            assertSoftly {
                for ((set, draft) in drafts) {
                    draft.currentPackPaperCards() shouldHaveSize 0
                    val decks = draft.computerDeckMains()
                    decks shouldHaveSize 7
                    for (deck in decks) {
                        val basics = deck.getOrCreate(DeckSection.Main).toFlatList().filter { it.rules.type.isBasicLand }
                        basics.shouldNotBeEmpty()
                        basics.forEach { it.edition shouldBe set }
                    }
                }
                IBoosterDraft.LAND_SET_CODE[0] shouldBe initialLandEdition
            }
        }

        test("pack shrinks 1 card per pick within pack 0") {
            val driver = ForgeBoosterDraftDriver(SyntheticNameLookup()::findGrpIdByName)
            val firstPack = driver.start("session-1", "FDN")

            firstPack.size shouldBeGreaterThanOrEqual 13

            val initialPackSize = firstPack.size
            val firstPick = firstPack.first()
            val afterPick1 = driver.pick("session-1", firstPick)

            assertSoftly {
                afterPick1.complete shouldBe false
                afterPick1.packNumber shouldBe 0
                afterPick1.pickNumber shouldBe 1
                // After our pick + 7 bots picking + pack rotation, we hold a pack the
                // right neighbor passed us — it lost 1 card (their pick) so size = N-1.
                afterPick1.nextPack shouldHaveSize (initialPackSize - 1)
            }

            // 7 picks deep into the round, the pack we now hold has been picked from
            // 7 times (us + 6 prior neighbors via rotations), so size = N-7.
            var nextPack = afterPick1.nextPack
            repeat(6) {
                val r = driver.pick("session-1", nextPack.first())
                nextPack = r.nextPack
            }
            assertSoftly {
                nextPack shouldHaveSize (initialPackSize - 7)
            }
        }

        test("draft completes after consuming all packs; pod result has 7 bot decks") {
            val driver = ForgeBoosterDraftDriver(SyntheticNameLookup()::findGrpIdByName)
            var pack = driver.start("session-2", "FDN")
            var lastResult: PickResult? = null
            var requestPickNumber = 0
            var picks = 0

            while (pack.isNotEmpty() && picks < 200) {
                lastResult = driver.pick("session-2", pack.first())
                pack = lastResult.nextPack
                picks++
                if (lastResult.complete) break
                requestPickNumber = lastResult.pickNumber
            }

            assertSoftly {
                lastResult!!.complete shouldBe true
                lastResult.pickNumber shouldBe requestPickNumber
                pack shouldHaveSize 0
            }

            val pod = driver.complete("session-2")
            assertSoftly {
                pod.botDecks shouldHaveSize 7
                pod.botDecks.forEach { deck ->
                    deck.size shouldBeInRange (40..60)
                }
                pod.playerPool.size shouldBeInRange (40..50)
            }
        }

        test("pack 1 starts fresh after pack 0 fully drains") {
            val driver = ForgeBoosterDraftDriver(SyntheticNameLookup()::findGrpIdByName)
            var pack = driver.start("session-3", "FDN")
            val pack0Size = pack.size

            var result: PickResult
            do {
                result = driver.pick("session-3", pack.first())
                pack = result.nextPack
            } while (!result.complete && result.packNumber == 0)

            assertSoftly {
                result.packNumber shouldBe 1
                result.pickNumber shouldBe 0
                pack.size shouldBe pack0Size
            }

            driver.complete("session-3")
        }

        test("start throws when pack card names have no grpId mapping") {
            val driver = ForgeBoosterDraftDriver(findGrpIdByName = { null })
            shouldThrow<UnmappedCardNamesException> { driver.start("session-missing", "FDN") }
        }

        test("unknown set falls back to FDN") {
            val driver = ForgeBoosterDraftDriver(SyntheticNameLookup()::findGrpIdByName)
            val pack = driver.start("session-4", "ZZZZZ-not-a-set")
            pack.size shouldBeGreaterThanOrEqual 13
            driver.discardAll()
        }

        test("bot decks contain mapped grpIds") {
            val lookup = SyntheticNameLookup()
            val driver = ForgeBoosterDraftDriver(lookup::findGrpIdByName)
            var pack = driver.start("session-5", "FDN")
            while (pack.isNotEmpty()) {
                val r = driver.pick("session-5", pack.first())
                pack = r.nextPack
                if (r.complete) break
            }
            val pod = driver.complete("session-5")
            val allBotIds = pod.botDecks.flatten().toSet()
            allBotIds.size shouldBeGreaterThanOrEqual 1
            for (grpId in allBotIds) {
                lookup.findNameByGrpId(grpId) shouldNotBe null
            }
        }

        test("headless draft invokes custom bot pick strategy") {
            val calls = AtomicInteger(0)
            val strategy =
                DraftPickStrategy { context ->
                    calls.incrementAndGet()
                    context.pack.last()
                }
            val draft = HeadlessBoosterDraft("FDN", strategy)
            val pack = draft.currentPackPaperCards()

            assertSoftly {
                draft.chooseLocally(pack.first()) shouldBe true
                draft.currentPackPaperCards().shouldNotBeEmpty()
                calls.get() shouldBeGreaterThanOrEqual 1
            }
        }
    })
