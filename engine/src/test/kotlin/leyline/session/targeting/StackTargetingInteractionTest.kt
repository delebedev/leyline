package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.gameStateMessages
import leyline.tooling.headless.HeadlessMatch
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import forge.game.zone.ZoneType as ForgeZoneType

class StackTargetingInteractionTest :
    SessionTest({
        fun HeadlessMatch.latestTargetCandidateIds(): List<Int> =
            allMessages
                .last { it.hasSelectTargetsReq() }
                .selectTargetsReq
                .targetsList
                .flatMap { it.targetsList }
                .map { it.targetInstanceId }
                .filter { it > OPPONENT_SEAT }

        fun HeadlessMatch.targetPromptDebug(cardName: String): String {
            val prompts =
                allMessages
                    .filter { it.hasSelectTargetsReq() }
                    .takeLast(3)
                    .map { msg ->
                        val ids =
                            msg.selectTargetsReq.targetsList
                                .flatMap { it.targetsList }
                                .map { it.targetInstanceId }
                        val names = ids.map { iid -> iid to (cardByIid(iid)?.name ?: "<no card>") }
                        "gs=${msg.gameStateId} source=${msg.selectTargetsReq.sourceId} ids=$names"
                    }
            val history = observe().promptHistory.takeLast(5).map { it.toString() }
            return "Expected target '$cardName'. prompts=$prompts history=$history"
        }

        fun HeadlessMatch.latestTargetIidByCardName(cardName: String): Int {
            val found =
                waitFor(timeoutMs = 2_000L) {
                    drainSink()
                    latestTargetCandidateIds().any { iid -> cardByIid(iid)?.name == cardName }
                }
            withClue(targetPromptDebug(cardName)) { found.shouldBeTrue() }
            val candidateIds = latestTargetCandidateIds()
            return findInstanceId(candidateIds, cardName)
        }

        fun HeadlessMatch.latestTargetSourceName(): String? {
            val sourceId =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq
                    .sourceId
            return cardByIid(sourceId)?.name
        }

        session(
            "targeted instant can be cast while another targeted instant is on stack",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;Lightning Bolt
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            assertSoftly {
                hasPendingAction().shouldBeTrue()
                castSpellByName("Lightning Bolt").shouldBeTrue()

                human
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .any { it.name == "Lightning Bolt" }
                    .shouldBeFalse()
                observe().stackSize shouldBe 2
            }
        }

        session(
            "Make Disappear without Casualty counters the only stack spell",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;Make Disappear
                humanbattlefield=Grizzly Bears;Mountain;Island;Island
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            castSpellByName("Make Disappear").shouldBeTrue()
            respondToOptionalCost(0)
            latestTargetSourceName() shouldBe "Make Disappear"
            val shockTarget = latestTargetIidByCardName("Shock")
            selectTargets(listOf(shockTarget))
            passUntilResolved(maxPasses = 10)

            ai.life shouldBe 20
        }

        session(
            "Make Disappear with Casualty can counter two stack spells",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;Lightning Bolt;Make Disappear
                humanbattlefield=Grizzly Bears;Mountain;Mountain;Island;Island
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            val bearIid = human.battlefield.iid("Grizzly Bears")

            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            castSpellByName("Lightning Bolt").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            castSpellByName("Make Disappear").shouldBeTrue()
            respondToOptionalCost(1)
            latestTargetSourceName() shouldBe "Make Disappear"
            val originalPrompt = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            val boltTarget = latestTargetIidByCardName("Lightning Bolt")
            selectTargets(listOf(boltTarget))
            respondToEffectCost(listOf(bearIid))
            latestTargetSourceName() shouldBe "Make Disappear"
            val copyPrompt = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            assertSoftly {
                copyPrompt.abilityGrpId shouldBe originalPrompt.abilityGrpId
                copyPrompt.abilityGrpId shouldNotBe 0
                copyPrompt.targetsList.single().targetingAbilityGrpId shouldBe
                    originalPrompt.targetsList.single().targetingAbilityGrpId
            }
            val shockTarget = latestTargetIidByCardName("Shock")
            selectTargets(listOf(shockTarget))
            passUntilResolved(maxPasses = 20)

            ai.life shouldBe 20
        }

        session(
            "Casualty copy is visible on the stack while choosing its new target",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Cut Your Losses
                humanbattlefield=Grizzly Bears;Island;Island;Island;Island;Island;Island
                humanlibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            val bearIid = human.battlefield.iid("Grizzly Bears")

            castSpellByName("Cut Your Losses").shouldBeTrue()
            respondToOptionalCost(1)
            val originalPrompt = allMessages.last { it.hasSelectTargetsReq() }
            selectTargets(listOf(OPPONENT_SEAT))
            respondToEffectCost(listOf(bearIid))
            val copyPrompt = allMessages.last { it.hasSelectTargetsReq() }
            val copyObject =
                allMessages
                    .gameStateMessages()
                    .filter { it.gameStateId == copyPrompt.gameStateId }
                    .flatMap { it.gameObjectsList }
                    .single { it.instanceId == copyPrompt.selectTargetsReq.sourceId }

            assertSoftly {
                copyPrompt.selectTargetsReq.sourceId shouldNotBe originalPrompt.selectTargetsReq.sourceId
                copyObject.type shouldBe GameObjectType.Card
                copyObject.zoneId shouldBe ZoneIds.STACK
                copyObject.isCopy.shouldBeTrue()
            }
        }
    })

@Suppress("NoThreadSleepInTests")
private fun waitFor(
    timeoutMs: Long,
    predicate: () -> Boolean,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return true
        Thread.sleep(20)
    }
    return predicate()
}
