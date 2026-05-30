package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.game.codes.StaticChoiceIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class BannerStaticChoiceTest :
    SessionTest({
        test("Patchwork Banner exposes the full creature subtype static subset") {
            startPuzzleFile("puzzles/patchwork-banner-static-choice.pzl", validating = true)

            val req = castSpellUntilSelectNReq("Patchwork Banner")
            val ids = req.idsList

            assertSoftly {
                req.listType shouldBe SelectionListType.StaticSubset
                req.staticList shouldBe StaticList.SubTypes
                req.idsCount shouldBeGreaterThan 200
                ids shouldContain StaticChoiceIds.subtypeIdFor("Goblin")!!
                ids shouldContain StaticChoiceIds.subtypeIdFor("Berserker")!!
                ids shouldContain StaticChoiceIds.subtypeIdFor("Human")!!
                ids shouldContain StaticChoiceIds.subtypeIdFor("Kithkin")!!
            }
        }
    })
