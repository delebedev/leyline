package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GreToClientEvent
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File

/**
 * Closed-loop conformance: run library-search puzzle, capture SearchReq,
 * write to disk for diffing against recording via `just conform-proto`.
 */
class SearchReqConformanceTest :
    FunSpec({
        tags(IntegrationTag)

        test("capture engine SearchReq for proto diff") {
            val h = MatchFlowHarness()
            h.connectAndKeepPuzzleText(
                """
                [metadata]
                Name:Library Search Conformance
                Goal:Win
                Turns:1
                Description:Cast Sylvan Ranger to search for Mountain.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=5
                humanhand=Sylvan Ranger;Lava Axe
                humanbattlefield=Forest;Forest;Forest;Forest;Forest;Forest
                humanlibrary=Mountain;Mountain
                aibattlefield=Forest;Forest
                ailibrary=Forest
                """.trimIndent(),
            )

            // Cast Sylvan Ranger → stack
            h.castSpellByName("Sylvan Ranger") shouldBe true
            // Pass priority → resolve → ETB trigger → search
            h.messageSnapshot() // snapshot baseline before pass
            h.passPriority()

            // Search prompt is handled by TargetingHandler after ETB resolves
            // Give it a moment to process, then drain
            Thread.sleep(500)
            h.drainSink()

            val messages = h.allMessages.filter { it.type == GREMessageType.SearchReq_695e }
            val searchReq = messages.firstOrNull()
            checkNotNull(searchReq) { "Engine did not produce a SearchReq. All types: ${h.allMessages.map { it.type.name }.distinct()}" }

            searchReq.searchReq.maxFind shouldBe 1
            searchReq.searchReq.itemsSoughtCount shouldBe 2
            searchReq.searchReq.itemsToSearchCount shouldBe 2
            searchReq.searchReq.zonesToSearchCount shouldBe 1

            // Write engine frames as .bin for CLI diff
            val outDir = File("build/conformance/engine-search").also { it.mkdirs() }
            writeFrame(outDir, 1, searchReq)

            val allGsms = messages.filter { it.type == GREMessageType.GameStateMessage_695e }
            for ((i, gsm) in allGsms.withIndex()) {
                writeFrame(outDir, i + 2, gsm)
            }

            println("Wrote ${1 + allGsms.size} engine frames to ${outDir.path}/")
            println("Diff: just conform-proto SearchReq 2026-03-21_22-05-00 --seat 0 --engine ${outDir.path}/")
        }
    }) {
    companion object {
        fun writeFrame(dir: File, index: Int, msg: GREToClientMessage) {
            val wrapper = MatchServiceToClientMessage.newBuilder()
                .setGreToClientEvent(
                    GreToClientEvent.newBuilder().addGreToClientMessages(msg),
                )
                .build()
            dir.resolve("${String.format(java.util.Locale.US, "%09d", index)}_MD_S-C_MATCH_DATA.bin")
                .writeBytes(wrapper.toByteArray())
        }
    }
}
