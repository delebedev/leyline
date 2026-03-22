package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

class RecordingFrameLoaderTest :
    FunSpec({

        tags(UnitTag)

        test("parseToGRE loads GRE messages from test fixture") {
            val bytes = javaClass.getResourceAsStream("/conformance/timer-frame.bin")!!.readBytes()
            val messages = RecordingParser.parseToGRE(bytes)
            messages.shouldNotBeEmpty()
        }

        test("loadFromDir resolves indexed messages from fixture dir") {
            val dir = createTempDir()
            try {
                val bytes = javaClass.getResourceAsStream("/conformance/timer-frame.bin")!!.readBytes()
                // name must match S-C pattern and sort correctly
                dir.resolve("000000024_MD_S-C_MATCH_DATA.bin").writeBytes(bytes)

                val messages = RecordingFrameLoader.loadFromDir(dir)
                messages.shouldNotBeEmpty()
                messages[0].frameIndex shouldBe 24
                messages[0].message.type shouldBe GREMessageType.TimerStateMessage_695e
            } finally {
                dir.deleteRecursively()
            }
        }

        test("loadFromDir returns messages in frame order") {
            val dir = createTempDir()
            try {
                val bytes = javaClass.getResourceAsStream("/conformance/timer-frame.bin")!!.readBytes()
                // write two copies with different frame numbers
                dir.resolve("000000024_MD_S-C_MATCH_DATA.bin").writeBytes(bytes)
                dir.resolve("000000051_MD_S-C_MATCH_DATA.bin").writeBytes(bytes)

                val messages = RecordingFrameLoader.loadFromDir(dir)
                val indices = messages.map { it.frameIndex }
                indices shouldBe indices.sorted()
            } finally {
                dir.deleteRecursively()
            }
        }

        test("load returns empty when session does not exist") {
            val messages = RecordingFrameLoader.load("nonexistent-session-0000")
            messages shouldBe emptyList()
        }

        test("load resolves session path and returns indexed messages") {
            val session = "2026-03-21_22-05-00"
            if (!java.io.File("recordings/$session").exists()) return@test

            val messages = RecordingFrameLoader.load(session, seat = 1)
            messages.shouldNotBeEmpty()
            val types = messages.map { it.message.type }.toSet()
            (types.size > 1) shouldBe true
            val indices = messages.map { it.frameIndex }
            indices shouldBe indices.sorted()
        }

        test("loadByType filters to requested greType") {
            val session = "2026-03-21_22-05-00"
            if (!java.io.File("recordings/$session").exists()) return@test

            val searchReqs =
                RecordingFrameLoader.loadByType(
                    session,
                    GREMessageType.SearchReq_695e,
                    seat = 1,
                )
            searchReqs.size shouldBe 1
            searchReqs[0].message.hasSearchReq() shouldBe true
        }
    })
