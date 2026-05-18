package leyline.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.SimClientTag
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.game.data.ExposedCardRepository
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.detailInt
import org.jetbrains.exposed.v1.jdbc.Database
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class SimClientKeywordStackMechanicsTest :
    FunSpec({
        tags(SimClientTag)

        val cardRepo by lazy {
            val cardDbPath =
                requireNotNull(System.getenv("LEYLINE_CARD_DB")) {
                    "LEYLINE_CARD_DB is not set. Point it at Raw_CardDatabase_*.mtga."
                }
            require(File(cardDbPath).exists()) { "Card database not found at: $cardDbPath" }
            ExposedCardRepository(Database.connect("jdbc:sqlite:${File(cardDbPath).absolutePath}", "org.sqlite.JDBC"))
        }

        fun runDeck(
            deck: String,
            tag: String,
            maxTurns: Int,
        ): MatchFlowHarness {
            val harness =
                MatchFlowHarness(
                    seed = 42L,
                    deckList = deck,
                    validation =
                        InvariantSelection.protocolFactsExcept(
                            "simclient driver can replay older queued ids around play-land diffs (leyline-qiws)",
                            InvariantCheck.GsIdMonotonicity,
                        ),
                    cardRepositoryOverride = cardRepo,
                )
            val tempLog = Files.createTempFile("simclient-$tag-", ".log").toFile()
            var now = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
            tempLog.bufferedWriter().use { writer ->
                val playerLog =
                    PlayerLogWriter(
                        out = writer,
                        matchId = "simclient-$tag",
                        clock = {
                            now = now.plusSeconds(1)
                            now
                        },
                    )
                SimClientDriver(harness, playerLog, maxTurns = maxTurns).runOneGame()
            }
            harness.validatingSink?.assertClean()
            return harness
        }

        fun targetSpecs(harness: MatchFlowHarness) =
            harness.allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.persistentAnnotationsList }
                .filter { AnnotationType.TargetSpec in it.typeList }

        test("Backup target prompt carries keyword stack ability shape") {
            val harness = runDeck("24 Plains\n36 Enduring Bondwarden", "backup", maxTurns = 6)
            try {
                val backupGrpId = cardRepo.findKeywordAbilityGrpId(84262, KeywordAbilityIds.BACKUP)
                val prompts =
                    harness.allMessages
                        .filter { it.type == GREMessageType.SelectTargetsReq_695e }
                        .map { it.selectTargetsReq }
                        .filter { it.abilityGrpId == backupGrpId }
                prompts.shouldNotBeEmpty()

                val prompt = prompts.first()
                assertSoftly(prompt) {
                    sourceId shouldBeGreaterThan 0
                    abilityGrpId shouldBe backupGrpId
                    targetsList.single().targetingAbilityGrpId shouldBe KeywordAbilityIds.BACKUP
                    targetsList.single().minTargets shouldBe 1
                    targetsList.single().maxTargets shouldBe 1
                }
                targetSpecs(harness).any { it.detailInt("abilityGrpId") == backupGrpId } shouldBe true
            } finally {
                harness.shutdown()
            }
        }

        test("Mentor target prompt carries shared keyword prompt shape") {
            val harness = runDeck("24 Plains\n18 Sunhome Stalwart\n18 Enduring Bondwarden", "mentor", maxTurns = 12)
            try {
                val prompts =
                    harness.allMessages
                        .filter { it.type == GREMessageType.SelectTargetsReq_695e }
                        .map { it.selectTargetsReq }
                        .filter { it.abilityGrpId == KeywordAbilityIds.MENTOR }
                prompts.shouldNotBeEmpty()

                val prompt = prompts.first()
                val target = prompt.targetsList.single()
                assertSoftly(prompt) {
                    abilityGrpId shouldBe KeywordAbilityIds.MENTOR
                    target.targetingAbilityGrpId shouldBe KeywordAbilityIds.MENTOR
                    target.prompt.promptId shouldBe 2247
                    target.prompt.parametersList
                        .single()
                        .numberValue shouldBe sourceId
                    target.minTargets shouldBe 1
                    target.maxTargets shouldBe 1
                }
                targetSpecs(harness).any {
                    it.detailInt("abilityGrpId") == KeywordAbilityIds.MENTOR && it.detailInt("promptId") == 2247
                } shouldBe true
            } finally {
                harness.shutdown()
            }
        }
    })
