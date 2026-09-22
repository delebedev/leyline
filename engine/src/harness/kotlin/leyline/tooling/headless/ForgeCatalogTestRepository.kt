package leyline.tooling.headless

import leyline.game.InMemoryCardRepository
import leyline.game.data.AbilityInfo
import leyline.game.data.AbilityLocalization
import leyline.game.data.CardData
import leyline.game.data.ForgeCardRepository
import leyline.game.data.ModalAbilityInfo

/**
 * Shared test repository: fixture-registered rows win, everything else resolves
 * through the Forge-backed catalog.
 *
 * Cards still carrying a per-card YAML under
 * `engine/src/test/resources/test-cards/` are registered here by
 * [TestCardRegistry.ensureFixtureCatalogRegistered] and keep their pinned client
 * identity. Cards without a fixture fall through to
 * [ForgeCardRepository], which derives catalog-scoped identities from Forge
 * definitions on demand — the same path the Forge catalog probe suites use.
 *
 * Fixture rows therefore act as explicit identity oracles only; ordinary
 * gameplay coverage no longer needs a per-card YAML to resolve a name.
 */
class ForgeCatalogTestRepository : InMemoryCardRepository() {
    private val catalog: ForgeCardRepository by lazy { ForgeCardRepository.open() }

    override fun findByGrpId(grpId: Int): CardData? = super.findByGrpId(grpId) ?: catalog.findByGrpId(grpId)

    override fun findNameByGrpId(grpId: Int): String? = super.findNameByGrpId(grpId) ?: catalog.findNameByGrpId(grpId)

    override fun findGrpIdByName(name: String): Int? = super.findGrpIdByName(name) ?: catalog.findGrpIdByName(name)

    override fun findGrpIdByNameAnyFace(name: String): Int? = super.findGrpIdByNameAnyFace(name) ?: catalog.findGrpIdByNameAnyFace(name)

    override fun findDeckGrpIdByName(name: String): Int? = super.findDeckGrpIdByName(name) ?: catalog.findDeckGrpIdByName(name)

    override fun findDeckGrpIdByNameAndSet(
        name: String,
        setCode: String,
    ): Int? = super.findDeckGrpIdByNameAndSet(name, setCode) ?: catalog.findDeckGrpIdByNameAndSet(name, setCode)

    override fun findTokenGrpIdByName(name: String): Int? = super.findTokenGrpIdByName(name) ?: catalog.findTokenGrpIdByName(name)

    override fun findTokenGrpIdByScript(script: String): Int? =
        super.findTokenGrpIdByScript(script) ?: catalog.findTokenGrpIdByScript(script)

    override fun findAllGrpIds(): List<Int> = (super.findAllGrpIds() + catalog.findAllGrpIds()).distinct()

    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? =
        super.lookupModalOptions(cardGrpId) ?: catalog.lookupModalOptions(cardGrpId)

    override fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? =
        super.findAbilityInfo(abilityGrpId) ?: catalog.findAbilityInfo(abilityGrpId)

    override fun findAbilityLocalization(abilityGrpId: Int): AbilityLocalization? =
        super.findAbilityLocalization(abilityGrpId) ?: catalog.findAbilityLocalization(abilityGrpId)

    override fun findGrantedKeywordAbilityGrpId(
        sourceGrpId: Int,
        keyword: String,
    ): Int? = super.findGrantedKeywordAbilityGrpId(sourceGrpId, keyword) ?: catalog.findGrantedKeywordAbilityGrpId(sourceGrpId, keyword)
}
