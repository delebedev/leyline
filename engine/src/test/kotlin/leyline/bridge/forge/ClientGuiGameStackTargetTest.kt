package leyline.bridge.forge

import forge.game.spellability.SpellAbility
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.TargetingCandidateValue
import leyline.bridge.handoff.TargetingInteractionRuntime
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity
import java.util.concurrent.atomic.AtomicReference

class ClientGuiGameStackTargetTest :
    FunSpec({
        tags(UnitTag)

        fun candidate(index: Int) =
            TargetingCandidateValue.StackObject(
                optionIndex = index,
                stackInstanceId = index + 10,
                sourceForgeCardId = ForgeCardId(index + 100),
                forgeAbilityId = index + 200,
                isSpell = true,
                isAbility = false,
                isTrigger = false,
            )

        test("complete stack option set keeps exact indexes and ignores finish sentinel") {
            val gui =
                ClientGuiGame(
                    InteractivePromptBridge(timeoutMs = 0),
                    stackTargetingActive = { true },
                    stackTargetCandidate = { index, option ->
                        if (option == "opaque") candidate(index) else null
                    },
                )

            gui.stackTargetCandidates(listOf("opaque", "[FINISH TARGETING]")).map { it.optionIndex } shouldContainExactly listOf(0)
        }

        test("incomplete stack option set fails instead of defaulting") {
            val gui =
                ClientGuiGame(
                    InteractivePromptBridge(timeoutMs = 0),
                    stackTargetingActive = { true },
                    stackTargetCandidate = { _, _ -> null },
                )

            shouldThrow<IllegalStateException> {
                gui.stackTargetCandidates(listOf("opaque"))
            }
        }

        test("finish sentinel freezes its index and permits an empty Submit") {
            val bridge = InteractivePromptBridge(timeoutMs = 1_000)
            val observed = AtomicReference<PromptRequest>()
            bridge.runtimeBindings =
                leyline.bridge.handoff.PromptRuntimeBindings(
                    targeting =
                        object : TargetingInteractionRuntime {
                            override fun awaitTargeting(
                                request: PromptRequest,
                                targetingAbility: SpellAbility?,
                                abilityIdentity: ResolvedAbilityIdentity?,
                                timeoutMs: Long?,
                            ): List<Int> {
                                observed.set(request)
                                return listOf(checkNotNull(request.targetingFinishOptionIndex))
                            }
                        },
                )
            val gui =
                ClientGuiGame(
                    bridge,
                    stackTargetingActive = { true },
                    stackTargetCandidate = { index, option ->
                        if (option == "stack") candidate(index) else null
                    },
                )

            gui.one("Choose a stack target", listOf("stack", "[FINISH TARGETING]")) shouldBe "[FINISH TARGETING]"
            val request = observed.get()
            request.min shouldBe 0
            request.targetingFinishOptionIndex shouldBe 1
        }
    })
