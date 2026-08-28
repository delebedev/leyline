package leyline.session.stack

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

class RecommissionEffectStackTest :
    SessionTest({
        fun MatchFlowHarness.enableStackAutoResolve() {
            updateSettings(
                SettingsMessage
                    .newBuilder()
                    .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                    .build(),
            )
        }

        fun GameStateMessage.hasZoneTransfer(
            category: String,
            src: Int,
            dest: Int,
        ): Boolean =
            annotationsList.any { ann ->
                AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                    ann.detailString("category") == category &&
                    ann.detailsList.any { it.key == "zone_src" && it.getValueInt32(0) == src } &&
                    ann.detailsList.any { it.key == "zone_dest" && it.getValueInt32(0) == dest }
            }

        session(
            "Recommission silent SP Effect resolution moves the spell off stack into graveyard",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Plains;Plains
                humanhand=Recommission
                humangraveyard=Grizzly Bears
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """,
        ) {
            enableStackAutoResolve()

            val bearIid = human.graveyard.iid("Grizzly Bears")

            val messages =
                after {
                    castSpellByName("Recommission").shouldBeTrue()
                    selectTargets(listOf(bearIid))
                }.messages
            val gsms = messages.gameStateMessages()

            val castFrame =
                gsms
                    .firstOrNull { it.hasZoneTransfer("CastSpell", src = ZoneIds.P1_HAND, dest = ZoneIds.STACK) }
                    .shouldNotBeNull()
            val stackIid =
                castFrame
                    .annotationsList
                    .first { AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailString("category") == "CastSpell" }
                    .affectedIdsList
                    .single()

            val clearFrame =
                gsms
                    .firstOrNull { it.hasZoneTransfer("Resolve", src = ZoneIds.STACK, dest = ZoneIds.P1_GRAVEYARD) }
                    .shouldNotBeNull()
            val objectIdChanged =
                clearFrame.annotationsList.first {
                    AnnotationType.ObjectIdChanged in it.typeList && it.detailInt("orig_id") == stackIid
                }
            val graveyardIid = objectIdChanged.detailInt("new_id")
            val zoneTransfer =
                clearFrame.annotationsList.first {
                    AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailString("category") == "Resolve"
                }
            val graveyardObject = clearFrame.gameObjectsList.first { it.instanceId == graveyardIid }
            assertSoftly {
                clearFrame.zonesList.first { it.zoneId == ZoneIds.STACK }.objectInstanceIdsList shouldNotContain stackIid
                clearFrame.zonesList.first { it.zoneId == ZoneIds.P1_GRAVEYARD }.objectInstanceIdsList shouldContain graveyardIid
                clearFrame.zonesList.first { it.zoneId == ZoneIds.LIMBO }.objectInstanceIdsList shouldContain stackIid
                clearFrame.annotationsList.indexOf(objectIdChanged) shouldBeLessThan clearFrame.annotationsList.indexOf(zoneTransfer)
                zoneTransfer.affectedIdsList shouldBe listOf(graveyardIid)
                graveyardObject.zoneId shouldBe ZoneIds.P1_GRAVEYARD
            }
        }
    })
