package leyline.game.state

import forge.game.staticability.StaticAbilityMode
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.game.codes.SlotKind
import leyline.game.data.CardData
import leyline.game.state.AbilityRegistry
import leyline.testkit.BoardTestBase
import leyline.testkit.CardDataDeriver
import leyline.testkit.TestCardInjector

class AbilityRegistryTest :
    FunSpec({
        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Station activated ability maps to shared abilityGrpId 373") {
            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, "Lumen-Class Frigate", ZoneType.Battlefield)
            val stationAbility =
                injected.card.spellAbilities.first { ability ->
                    ability.isActivatedAbility && ability.isIntrinsic && !ability.isManaAbility()
                }

            val registry = AbilityRegistry.build(injected.card, CardDataDeriver.fromForgeCard(injected.card, "Lumen-Class Frigate"))

            registry.forSpellAbility(stationAbility.id) shouldBe 373
        }

        test("keyword-backed activated ability dispatches by activated index") {
            val cardName = "Ninja of the Deep Hours"
            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Hand)
            val ninjutsuAbility =
                injected.card.spellAbilities.single { ability ->
                    ability.isActivatedAbility && ability.isIntrinsic && !ability.isManaAbility()
                }

            val registry = AbilityRegistry.build(injected.card, CardDataDeriver.fromForgeCard(injected.card, cardName))

            registry.forSpellAbility(ninjutsuAbility.id) shouldBe 5341
            registry.slotLayout.forgeIndexFor(5341) shouldBe 0
        }

        test("unclaimed intrinsic static maps to matching intrinsic ability slot") {
            val (_, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Pacifism", human, ZoneType.Battlefield)
                }
            val pacifism =
                game.players[0]
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Pacifism" }
            val restriction =
                pacifism.staticAbilities.single {
                    it.checkMode(StaticAbilityMode.CantAttack) && it.checkMode(StaticAbilityMode.CantBlock)
                }

            val registry = AbilityRegistry.build(pacifism, CardDataDeriver.fromForgeCard(pacifism, "Pacifism"))

            registry.forStaticAbility(restriction.id) shouldBe 1083
        }

        test("unclaimed printed static maps with legacy slot data") {
            val (_, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Pacifism", human, ZoneType.Battlefield)
                }
            val pacifism =
                game.players[0]
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Pacifism" }
            val restriction =
                pacifism.staticAbilities.single {
                    it.checkMode(StaticAbilityMode.CantAttack) && it.checkMode(StaticAbilityMode.CantBlock)
                }
            restriction.setIntrinsic(false)
            val cardData =
                CardDataDeriver.fromForgeCard(pacifism, "Pacifism").copy(
                    abilityIds = listOf(1027 to 605, 1083 to 62428),
                    abilityKinds = emptyList(),
                )

            val registry = AbilityRegistry.build(pacifism, cardData)

            registry.forStaticAbility(restriction.id) shouldBe 1083
        }

        test("planeswalker loyalty abilities map to distinct abilityGrpId slots") {
            val cardName = "Chandra, Torch of Defiance"
            val (b, game, _) = base.startWithBoard { _, _, _ -> }

            // Inject planeswalker onto battlefield
            val injected =
                TestCardInjector.inject(
                    b,
                    1,
                    cardName,
                    ZoneType.Battlefield,
                )
            val card = injected.card

            // Derive CardData from the live card (has game context → full abilities),
            // stamped with client identity from the fixture.
            val cardData = CardDataDeriver.fromForgeCard(card, cardName)

            // Chandra has 4 loyalty abilities (all activated, non-mana)
            val loyaltyAbilities =
                card.spellAbilities
                    .filter { it.isActivatedAbility && !it.isManaAbility() }
            loyaltyAbilities.shouldHaveSize(4)

            // Chandra's fixture has ability ids for all 4 loyalty abilities.
            cardData.abilityIds.shouldHaveSize(4)

            // Build registry
            val registry = AbilityRegistry.build(card, cardData)

            // Each loyalty ability should map to a distinct abilityGrpId
            val mappedGrpIds =
                loyaltyAbilities.map { sa ->
                    val mapped = registry.forSpellAbility(sa.id)
                    mapped.shouldNotBeNull()
                    mapped
                }
            mappedGrpIds.distinct().shouldHaveSize(4)

            // The mapped grpIds should match the slots from cardData.abilityIds
            val expectedSlots = cardData.abilityIds.map { it.first }
            mappedGrpIds shouldBe expectedSlots
        }

        // Regression for leyline-xht: when CardData carries Arena-style
        // abilityKinds with a non-activated slot (trigger/static) interleaved
        // before the activated abilities, the registry must skip those slots
        // when assigning Forge activated SAs to abilityGrpIds.
        test("trigger slot interleaved before activated abilities does not shift mapping") {
            val cardName = "Kaito, Cunning Infiltrator"
            val (b, _, _) = base.startWithBoard { _, _, _ -> }

            val injected =
                TestCardInjector.inject(
                    b,
                    1,
                    cardName,
                    ZoneType.Battlefield,
                )
            val card = injected.card

            val activated =
                card.spellAbilities
                    .filter { it.isActivatedAbility && !it.isManaAbility() && it.isIntrinsic }
            activated.shouldHaveSize(3) // [+1], [-2], [-9]

            // Simulate Arena's actual slot layout: trigger first, then activated.
            // The grpIds match Arena DB for Kaito (grpId 93757):
            //   slot 0 = 175794 (trigger)
            //   slot 1 = 175795 ([+1])
            //   slot 2 = 175796 ([-2])
            //   slot 3 = 175798 ([-9])
            val arenaShapedCardData =
                CardData(
                    grpId = 93757,
                    titleId = 0,
                    power = "",
                    toughness = "3",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    abilityIds =
                        listOf(
                            175794 to 0,
                            175795 to 0,
                            175796 to 0,
                            175798 to 0,
                        ),
                    abilityKinds =
                        listOf(
                            SlotKind.Intrinsic,
                            SlotKind.Activated,
                            SlotKind.Activated,
                            SlotKind.Activated,
                        ),
                    manaCost = emptyList(),
                )

            val registry = AbilityRegistry.build(card, arenaShapedCardData)

            // Forward direction (forSpellAbility): each Forge activated SA
            // should map to an Arena Activated slot, skipping the trigger at
            // slot 0. Order in card.spellAbilities is [+1], [-2], [-9] per
            // the Forge card definition.
            val mapped =
                activated.map { sa ->
                    val grp = registry.forSpellAbility(sa.id)
                    grp.shouldNotBeNull()
                    grp
                }
            mapped shouldBe listOf(175795, 175796, 175798)

            // Reverse direction (slotLayout.forgeIndexFor): the dispatch
            // path used by ActionPerformer.resolveAbilityIndex. Indices are
            // into the Forge-order non-mana activated list (which excludes
            // triggers/statics), so the trigger at slot 0 must return null
            // and each Activated slot must produce its position among the
            // Activated slots — not its raw slot index.
            assertSoftly(registry.slotLayout) {
                forgeIndexFor(175794).shouldBeNull() // trigger — not activatable
                forgeIndexFor(175795) shouldBe 0 // [+1]
                forgeIndexFor(175796) shouldBe 1 // [-2]
                forgeIndexFor(175798) shouldBe 2 // [-9]
            }
        }

        test("mana slot interleaved before activated land ability does not shift mapping") {
            val cardName = "Racers' Ring"
            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
            val card = injected.card
            val manaAbility = card.spellAbilities.single { it.isManaAbility() && it.isIntrinsic }
            val drawAbility =
                card.spellAbilities.single {
                    it.isActivatedAbility && !it.isManaAbility() && it.isIntrinsic
                }
            val cardData = CardDataDeriver.fromForgeCard(card, cardName)

            val registry = AbilityRegistry.build(card, cardData)

            assertSoftly {
                cardData.abilityKinds shouldBe listOf(SlotKind.Intrinsic, SlotKind.Mana, SlotKind.Activated)
                registry.forSpellAbility(manaAbility.id) shouldBe 1131
                registry.forSpellAbility(drawAbility.id) shouldBe 149629
                registry.slotLayout.forgeIndexFor(149629) shouldBe 0
            }
        }
    })
