package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.GreToClientEvent
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File

/**
 * Captures engine output as .bin files for relationship validation.
 *
 * Runs a puzzle exercising multiple mechanics, writes all GRE messages to disk.
 * Then validate via: `just segment-relationships` on the engine output.
 *
 * This test captures the output. Validation runs separately via CLI
 * because RelationshipValidator lives in tooling/ (different module).
 */
class EngineRelationshipTest :
    FunSpec({
        tags(IntegrationTag)

        test("capture multi-mechanic engine output for relationship validation") {
            val h = MatchFlowHarness()
            h.connectAndKeepPuzzleText(
                """
                [metadata]
                Name:Multi-Mechanic Conformance
                Goal:Win
                Turns:3
                Description:Exercise cast, play land, combat.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=3
                humanhand=Mountain;Lightning Bolt
                humanbattlefield=Mountain;Mountain;Raging Goblin
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Forest
                ailibrary=Forest;Forest;Forest
                """.trimIndent(),
            )

            // Play Mountain → PlayLand segment
            h.playLand()

            // Cast Lightning Bolt → CastSpell segment
            h.castSpellByName("Lightning Bolt")

            // Write all messages as .bin
            val outDir = File("build/conformance/engine-multi").also {
                it.deleteRecursively()
                it.mkdirs()
            }
            val allMessages = h.allMessages
            allMessages.shouldNotBeEmpty()

            for ((i, msg) in allMessages.withIndex()) {
                val wrapper = MatchServiceToClientMessage.newBuilder()
                    .setGreToClientEvent(
                        GreToClientEvent.newBuilder().addGreToClientMessages(msg),
                    )
                    .build()
                outDir.resolve("${String.format("%09d", i + 1)}_MD_S-C_MATCH_DATA.bin")
                    .writeBytes(wrapper.toByteArray())
            }

            println("Wrote ${allMessages.size} engine frames to ${outDir.path}/")
            println()
            println("Validate with:")
            println("  just build && just segment-relationships-engine build/conformance/engine-multi/")
        }
    })
