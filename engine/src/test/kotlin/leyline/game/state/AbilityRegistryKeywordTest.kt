package leyline.game.state

import forge.card.CardRarity
import forge.card.CardRules
import forge.game.card.Card
import forge.game.card.CardFactory
import forge.game.keyword.Keyword
import forge.game.zone.ZoneType
import forge.item.PaperCard
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.hasAmbiguousActionCatalog
import leyline.bridge.coord.resolveActionOffer
import leyline.bridge.handoff.ActionResponseKey
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.codes.SlotKind
import leyline.game.data.CardData
import leyline.game.data.ForgeCardRepository
import leyline.game.data.grantedKeywordAbilityGrpId
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ActivatedActionEmitter
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq

class AbilityRegistryKeywordTest :
    BoardTest({
        fun equipment(
            declarations: List<String>,
            sharedRow: Boolean = false,
        ): Pair<Card, CardData> {
            val board = startWithBoard { _, _, _ -> }
            val rules =
                CardRules.Reader().readCard(
                    listOf("Name:Paired Equipment", "ManaCost:1", "Types:Artifact Equipment") + declarations.map { "K:$it" },
                )
            val card = Card.fromPaperCard(PaperCard(rules, "TST", CardRarity.Common), board.human)
            val data =
                CardData(
                    grpId = 9000,
                    titleId = 0,
                    power = "",
                    toughness = "",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    manaCost = emptyList(),
                    abilityIds = declarations.indices.map { (if (sharedRow) 9001 else 9001 + it) to 0 },
                    abilityKinds = declarations.map { SlotKind.Keyword },
                )
            return card to data
        }

        test("distinct intrinsic equip declarations retain separate catalog and recipient identities") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Commander's Plate", human, ZoneType.Battlefield)
                    addCard("Isamaru, Hound of Konda", human, ZoneType.Battlefield).setCommander(true)
                    repeat(5) { addCard("Plains", human, ZoneType.Battlefield) }
                }
            val card = board.human.battlefield.card("Commander's Plate")
            val repository = ForgeCardRepository.open()
            val data = checkNotNull(repository.findByGrpId(checkNotNull(repository.findGrpIdByName(card.name))))
            val keywords = card.keywords.filter { it.isIntrinsic && it.keyword == Keyword.EQUIP }
            keywords.shouldHaveSize(2)
            val abilities = keywords.map { it.abilities.single() }
            val registry = AbilityRegistry.build(card, data)
            val identities = abilities.map { AbilityRegistry.resolveActivated(card, it, { data }, { _, _ -> registry }) }
            println(
                "equip declarations=${keywords.map {
                    it.original
                }} costs=${abilities.map { it.payCosts.toSimpleString() }} catalog=${data.abilityIds} identities=$identities",
            )

            assertSoftly {
                keywords.map { it.original } shouldBe listOf("Equip:3:Creature.IsCommander+YouCtrl:commander", "Equip:5")
                identities.map { it.abilityGrpId } shouldBe data.abilityIds.take(2).map { it.first }
                identities.map { it.uniqueAbilityId } shouldBe listOf(50, 51)
                abilities.map { registry.forSpellAbility(it) }.distinct().shouldHaveSize(2)
            }
            val (bridge, game, _) = board
            val iid = bridge.instanceId(card)
            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, bridge, "test", 0), bridge)
            val offers = projection.offers.filter { it.action.instanceId == iid && it.action.actionType == ActionType.Activate_add3 }
            val catalog = offers.mapIndexed { index, offer -> index.toLong() to offer }.groupBy { ActionResponseKey.from(it.second.action) }
            assertSoftly {
                offers.size shouldBe 2
                offers.map { it.action.abilityGrpId } shouldBe data.abilityIds.take(2).map { it.first }
                offers.map { it.action.uniqueAbilityId } shouldBe listOf(50, 51)
                hasAmbiguousActionCatalog(projection.offers) shouldBe false
            }
            for ((index, offer) in offers.withIndex()) {
                val selected = checkNotNull(resolveActionOffer(catalog, offer.action)).second
                val command = selected.command as PlayerAction.ActivateAbility
                assertSoftly {
                    checkNotNull(command.ability).id shouldBe abilities[index].id
                    command.ability.payCosts.totalMana.cmc shouldBe listOf(3, 5)[index]
                    selected shouldBe offer
                }
            }
        }

        test("ordinary single equip retains its first keyword catalog identity") {
            val board = startWithBoard { _, human, _ -> addCard("Bonesplitter", human, ZoneType.Battlefield) }
            val card = board.human.battlefield.card("Bonesplitter")
            val repository = ForgeCardRepository.open()
            val data = checkNotNull(repository.findByGrpId(checkNotNull(repository.findGrpIdByName(card.name))))
            val ability =
                card.keywords
                    .single { it.keyword == Keyword.EQUIP }
                    .abilities
                    .single()
            val registry = AbilityRegistry.build(card, data)
            val identity = AbilityRegistry.resolveActivated(card, ability, { data }, { _, _ -> registry })
            identity.abilityGrpId shouldBe data.abilityIds.first().first
            identity.uniqueAbilityId shouldBe 50
        }

        for ((label, declarations) in listOf(
            "identical declarations" to listOf("Equip:2", "Equip:2"),
            "equal costs with distinct targets" to listOf("Equip:3:Creature.IsCommander+YouCtrl:commander", "Equip:3"),
        )) {
            test("$label retain one live keyword per declaration slot") {
                val (card, data) = equipment(declarations)
                val abilities = card.keywords.filter { it.isIntrinsic }.map { it.abilities.single() }
                abilities.shouldHaveSize(2)
                val registry = AbilityRegistry.build(card, data)
                assertSoftly {
                    abilities.map { registry.forSpellAbility(it) } shouldBe listOf(9001, 9002)
                    abilities.map { registry.forSpellAbility(it.copy()) } shouldBe listOf(9001, 9002)
                }
            }
        }

        test("identical declarations sharing a catalog row retain distinct per-object ordinals") {
            val (card, data) = equipment(listOf("Equip:2", "Equip:2"), sharedRow = true)
            val abilities = card.keywords.filter { it.isIntrinsic }.map { it.abilities.single() }
            abilities.shouldHaveSize(2)
            val registry = AbilityRegistry.build(card, data)
            val identities = abilities.map { AbilityRegistry.resolveActivated(card, it, { data }, { _, _ -> registry }) }
            identities.map { it.abilityGrpId } shouldBe listOf(9001, 9001)
            identities.map { it.uniqueAbilityId } shouldBe listOf(50, 51)
            val emitted = ActionsAvailableReq.newBuilder()
            val offers =
                identities.mapIndexed { index, identity ->
                    val ability = abilities[index]
                    ActivatedActionEmitter.emitActivatedAbilityAction(
                        emitted,
                        instanceId = 100,
                        grpId = data.grpId,
                        abilityGrpId = identity.abilityGrpId,
                        uniqueAbilityId = identity.uniqueAbilityId,
                        abilityCost = ability.payCosts.totalMana,
                        canPay = true,
                        envelope = ActivatedActionEmitter.Envelope.PERMANENT_SOURCE,
                    )
                    GameActionBridge.ActionOffer(
                        emitted.getActions(index),
                        PlayerAction.ActivateAbility(ForgeCardId(card.id), index, ability = ability),
                        forgeAbilityId = ability.id,
                    )
                }
            hasAmbiguousActionCatalog(offers) shouldBe false
            val catalog = offers.mapIndexed { index, offer -> index.toLong() to offer }.groupBy { ActionResponseKey.from(it.second.action) }
            for ((index, offer) in offers.withIndex()) {
                val response =
                    offer.action
                        .toBuilder()
                        .clearManaCost()
                        .clearAutoTapSolution()
                        .build()
                val selected = checkNotNull(resolveActionOffer(catalog, response)).second
                (selected.command as PlayerAction.ActivateAbility).ability?.id shouldBe abilities[index].id
            }
        }

        test("copied permanent states retain both intrinsic declaration slots") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Commander's Plate", human, ZoneType.Battlefield)
                    addCard("Sculpting Steel", human, ZoneType.Battlefield)
                }
            val source = board.human.battlefield.card("Commander's Plate")
            val copy = board.human.battlefield.card("Sculpting Steel")
            copy.addCloneState(
                CardFactory.getCloneStates(source, copy, copy.spellAbilities.single { it.isSpell }),
                board.game.nextTimestamp,
            )
            val repository = ForgeCardRepository.open()
            val data = checkNotNull(repository.findByGrpId(checkNotNull(repository.findGrpIdByName(source.name))))
            val abilities = copy.keywords.filter { it.keyword == Keyword.EQUIP }.map { it.abilities.single() }
            abilities.shouldHaveSize(2)
            val registry = AbilityRegistry.build(copy, data)
            abilities.map { registry.forSpellAbility(it) } shouldBe data.abilityIds.take(2).map { it.first }
        }

        test("normalized family fallback reserves a later exact declaration first") {
            val (card, data) = equipment(listOf("Equip:X", "Equip:5"))
            card.removeIntrinsicKeyword(Keyword.EQUIP)
            card.addIntrinsicKeywords(listOf("Equip:3", "Equip:5"))
            val abilities = card.keywords.filter { it.isIntrinsic }.map { it.abilities.single() }
            val registry = AbilityRegistry.build(card, data)
            abilities.map { registry.forSpellAbility(it) } shouldBe listOf(9001, 9002)
        }

        test("ambiguous normalized keyword families do not invent declaration assignments") {
            val (card, data) = equipment(listOf("Equip:X", "Equip:X:Creature.IsCommander+YouCtrl:commander"))
            card.removeIntrinsicKeyword(Keyword.EQUIP)
            card.addIntrinsicKeywords(listOf("Equip:2", "Equip:3"))
            val abilities = card.keywords.filter { it.isIntrinsic }.map { it.abilities.single() }
            val registry = AbilityRegistry.build(card, data)
            abilities.shouldHaveSize(2)
            abilities.forEach { registry.forSpellAbility(it).shouldBeNull() }
        }

        test("identical triggered keywords retain their declaration occurrences") {
            val (card, data) = equipment(listOf("Prowess", "Prowess"))
            val triggers = card.keywords.filter { it.isIntrinsic }.map { it.triggers.single() }
            triggers.shouldHaveSize(2)
            val registry = AbilityRegistry.build(card, data)
            triggers.map { registry.forTrigger(it.definitionId) } shouldBe listOf(9001, 9002)
        }

        test("a granted keyword from the same family does not consume an intrinsic slot") {
            val board =
                startWithBoard { game, human, _ ->
                    addCard("Commander's Plate", human, ZoneType.Battlefield)
                    addCard("Puresteel Paladin", human, ZoneType.Battlefield)
                    repeat(2) { addCard("Ornithopter", human, ZoneType.Battlefield) }
                    game.action.checkStaticAbilities(false)
                }
            val card = board.human.battlefield.card("Commander's Plate")
            val repository = ForgeCardRepository.open()
            val data = checkNotNull(repository.findByGrpId(checkNotNull(repository.findGrpIdByName(card.name))))
            val keywords = card.keywords.filter { it.keyword == Keyword.EQUIP }
            keywords.shouldHaveSize(3)
            val registry = AbilityRegistry.build(card, data, repository::grantedKeywordAbilityGrpId)
            val intrinsic = keywords.filter { it.isIntrinsic }.map { it.abilities.single() }
            val granted = keywords.single { !it.isIntrinsic }.abilities.single()
            assertSoftly {
                intrinsic.map { registry.forSpellAbility(it) } shouldBe data.abilityIds.take(2).map { it.first }
                val expectedGrant = repository.grantedKeywordAbilityGrpId(granted).shouldNotBeNull()
                registry.forSpellAbility(granted) shouldBe expectedGrant
            }
        }
    })
