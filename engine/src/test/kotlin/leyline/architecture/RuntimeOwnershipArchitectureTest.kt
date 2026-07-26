package leyline.architecture

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.state.GameBridge
import leyline.match.ConnectionState
import leyline.match.MatchSession
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

class RuntimeOwnershipArchitectureTest :
    FunSpec({
        tags(UnitTag)

        test("GameBridge lifecycle resources live behind one sealed runtime mode") {
            GameBridge::class.nestedClasses.single { it.simpleName == "ActiveGame" }.isSealed shouldBe true

            val fields = GameBridge::class.java.declaredFields.map { it.name }
            fields.filter {
                it in
                    setOf(
                        "game",
                        "humanController",
                        "eventCollector",
                        "phaseStopProfile",
                        "loopController",
                    )
            } shouldBe emptyList()
            fields shouldContain "activeGame"
        }

        test("MatchSession handler implementations are reachable only through the owner") {
            MatchSession::class.java.declaredFields
                .filter { field ->
                    field.type.simpleName in
                        setOf(
                            "ActionPerformer",
                            "AutoPassEngine",
                            "CombatHandler",
                            "NumericInputHandler",
                            "OptionalActionHandler",
                            "TargetingHandler",
                        )
                }.onEach { field -> Modifier.isPrivate(field.modifiers) shouldBe true }
                .map { it.type.simpleName }
                .shouldContainExactlyInAnyOrder(
                    "ActionPerformer",
                    "AutoPassEngine",
                    "CombatHandler",
                    "NumericInputHandler",
                    "OptionalActionHandler",
                    "TargetingHandler",
                )

            ConnectionState::class.java.declaredFields
                .map { it.name } shouldNotContain "sessionLock"
        }

        test("match-progress delivery has one outbox terminal") {
            val cwd = Path.of("").toAbsolutePath()
            val matchSource =
                listOf(
                    cwd.resolve("engine/src/main/kotlin/leyline/match"),
                    cwd.resolve("src/main/kotlin/leyline/match"),
                ).first(Files::isDirectory)
            val directSend = Regex("""\b(?:output|sink|peer\.sink)\.(?:send|sendRaw)\(""")
            val violations = mutableListOf<String>()
            Files.walk(matchSource).use { files ->
                files
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        val allowed =
                            file.fileName.toString() in
                                setOf(
                                    "MatchConnection.kt",
                                    "MatchOutbox.kt",
                                )
                        Files.readAllLines(file).forEachIndexed { index, line ->
                            if (!allowed && directSend.containsMatchIn(line)) {
                                violations += "${matchSource.relativize(file)}:${index + 1}"
                            }
                        }
                    }
            }

            assertSoftly {
                violations shouldBe emptyList()
                Files
                    .readString(matchSource.resolve("MatchConnection.kt"))
                    .let { directSend.findAll(it).count() } shouldBe 2
                Files.exists(matchSource.resolve("PendingPromptEnvelope.kt")) shouldBe false
            }
        }
    })
