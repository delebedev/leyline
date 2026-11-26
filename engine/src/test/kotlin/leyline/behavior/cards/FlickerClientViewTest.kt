package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.ClientAccumulator
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.detail
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/** A permanent that leaves and returns gets a new instance id; the client must receive its object. */
class FlickerClientViewTest :
    SessionTest({
        /** Reads the client view right after the GSM that returns the permanent to the battlefield. */
        fun MatchFlowHarness.assertReturnedObjectSent(originalIid: Int) {
            val returnIndex =
                allMessages.indexOfFirst { message ->
                    message.hasGameStateMessage() &&
                        message.gameStateMessage.annotationsList.any {
                            AnnotationType.ZoneTransfer_af5a in it.typeList &&
                                it.detail("zone_dest")?.getValueInt32(0) == ZoneIds.BATTLEFIELD
                        }
                }
            returnIndex shouldBeGreaterThanOrEqual 0
            val returnedIid =
                allMessages[returnIndex]
                    .gameStateMessage.annotationsList
                    .first {
                        AnnotationType.ZoneTransfer_af5a in it.typeList &&
                            it.detail("zone_dest")?.getValueInt32(0) == ZoneIds.BATTLEFIELD
                    }.affectedIdsList
                    .single()
            val client = ClientAccumulator().apply { processAll(allMessages.take(returnIndex + 1)) }
            val battlefield = client.zones.getValue(ZoneIds.BATTLEFIELD).objectInstanceIdsList
            assertSoftly {
                returnedIid shouldNotBe originalIid
                battlefield shouldContain returnedIid
                battlefield shouldNotContain originalIid
                client.objects[returnedIid].shouldNotBeNull().zoneId shouldBe ZoneIds.BATTLEFIELD
            }
        }

        for (sick in listOf(false, true)) {
            val merchant = if (sick) "Gray Merchant of Asphodel|SummonSick" else "Gray Merchant of Asphodel"
            val entered = if (sick) "entered this turn" else "entered earlier"
            session(
                "cloudshift returns gray merchant that $entered with its object in the client view",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Cloudshift
                    humanbattlefield=$merchant;Plains
                    humanlibrary=Swamp;Swamp;Swamp
                    ailibrary=Island;Island;Island
                    """.trimIndent(),
            ) {
                val original = human.battlefield.iid("Gray Merchant of Asphodel")
                castSpellByName("Cloudshift").shouldBeTrue()
                selectTargets(listOf(original))
                passUntilResolved()
                assertReturnedObjectSent(original)
            }

            session(
                "aminatou minus one returns gray merchant that $entered with its object in the client view",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Aminatou, the Fateshifter|Counters:LOYALTY=3;$merchant;Swamp
                    humanlibrary=Swamp;Swamp;Swamp
                    ailibrary=Island;Island;Island
                    """.trimIndent(),
            ) {
                val original = human.battlefield.iid("Gray Merchant of Asphodel")
                activateAbility("Aminatou, the Fateshifter", 1).shouldBeTrue()
                selectTargets(listOf(original))
                passUntilResolved()
                assertReturnedObjectSent(original)
            }
        }
    })
