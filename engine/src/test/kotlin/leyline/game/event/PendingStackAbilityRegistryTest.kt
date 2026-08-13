package leyline.game.event

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity

class PendingStackAbilityRegistryTest :
    FunSpec({
        tags(UnitTag)

        test("trigger context is visible until consumed") {
            val registry = PendingStackAbilityRegistry()

            val identity = ResolvedAbilityIdentity(AbilityDefinitionRef.Trigger(5), 101)
            registry.recordTrigger(7, ForgeCardId(42), identity)

            assertSoftly {
                registry.isTriggerResolving(7) shouldBe true
                registry.consume(7) shouldBe
                    PendingStackAbilityContext(
                        kind = PendingStackAbilityKind.Trigger,
                        sourceCardId = ForgeCardId(42),
                        identity = identity,
                    )
                registry.isTriggerResolving(7) shouldBe false
                registry.consume(7) shouldBe null
            }
        }

        test("activation context preserves ability grpId") {
            val registry = PendingStackAbilityRegistry()

            val identity = ResolvedAbilityIdentity(AbilityDefinitionRef.SpellAbility(6), 202)
            registry.recordActivation(9, ForgeCardId(77), identity)

            assertSoftly {
                registry.isTriggerResolving(9) shouldBe false
                registry.consume(9) shouldBe
                    PendingStackAbilityContext(
                        kind = PendingStackAbilityKind.Activation,
                        sourceCardId = ForgeCardId(77),
                        identity = identity,
                    )
            }
        }

        test("ability lookup can stay trigger-only") {
            val registry = PendingStackAbilityRegistry()

            val activation = ResolvedAbilityIdentity(AbilityDefinitionRef.SpellAbility(5), 777)
            val trigger = ResolvedAbilityIdentity(AbilityDefinitionRef.Trigger(5), 777)
            registry.recordActivation(8, ForgeCardId(42), activation)
            registry.recordTrigger(9, ForgeCardId(42), trigger)

            registry.abilityIdFor(ForgeCardId(42), 777, PendingStackAbilityKind.Trigger) shouldBe 9
        }

        test("repeated trigger firings keep distinct runtime ids and one definition identity") {
            val registry = PendingStackAbilityRegistry()
            val identity = ResolvedAbilityIdentity(AbilityDefinitionRef.Trigger(5), 101)

            registry.recordTrigger(7, ForgeCardId(42), identity)
            registry.recordTrigger(8, ForgeCardId(42), identity)

            assertSoftly {
                registry.contextFor(7)?.identity shouldBe identity
                registry.contextFor(8)?.identity shouldBe identity
                registry.consume(7)?.identity shouldBe identity
                registry.contextFor(8)?.identity shouldBe identity
            }
        }

        test("runtime id overwrite clears stale Paradigm source and consume removes it atomically") {
            val registry = PendingStackAbilityRegistry()
            val identity = ResolvedAbilityIdentity(AbilityDefinitionRef.Trigger(5), 101)

            registry.recordTrigger(7, ForgeCardId(42), identity, paradigmSourceCardId = ForgeCardId(9))
            registry.contextFor(7)?.paradigmSourceCardId shouldBe ForgeCardId(9)

            registry.recordTrigger(7, ForgeCardId(43), identity)
            assertSoftly {
                registry.contextFor(7)?.paradigmSourceCardId shouldBe null
                registry.consume(7)?.paradigmSourceCardId shouldBe null
                registry.contextFor(7) shouldBe null
            }
        }
    })
