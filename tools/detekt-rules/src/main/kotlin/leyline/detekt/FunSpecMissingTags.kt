package leyline.detekt

import dev.detekt.api.Finding
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Flags Kotest Spec subclasses that do not declare exactly one lane tag.
 *
 * Leyline relies on `UnitTag`, `BoardTag`, `IntegrationTag`, and `SimClientTag`
 * to route tests into the correct CI bucket. Semantic tags may be added
 * alongside the lane tag, but a Spec/file with zero or multiple lane tags makes
 * gate membership ambiguous.
 */
class FunSpecMissingTags(config: Config) : Rule(
    config,
    description = "Kotest Spec class must declare exactly one lane tag so CI can bucket the test correctly.",
) {

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)

        val directSpecLaneTags =
            file.declarations
                .filterIsInstance<KtClassOrObject>()
                .mapNotNull { klass ->
                    if (directKotestSuperCall(klass) == null) null else klass to laneTagsIn(klass)
                }
        if (directSpecLaneTags.any { (_, tags) -> tags.size > 1 }) return

        val fileLaneTags = directSpecLaneTags.flatMap { (_, tags) -> tags }.toSet()
        if (fileLaneTags.size <= 1) return

        report(
            Finding(
                Entity.from(file),
                "Test file mixes lane tags ${fileLaneTags.sorted()}. Split direct Spec classes by lane.",
            ),
        )
    }

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val superCall = directKotestSuperCall(classOrObject) ?: return
        val laneTags = laneTagsIn(classOrObject)
        if (laneTags.size == 1) return

        val message =
            if (laneTags.isEmpty()) {
                "Kotest ${superTypeName(superCall)} '${classOrObject.name ?: "<anon>"}' has no lane tag. " +
                    "Add exactly one of UnitTag, BoardTag, IntegrationTag, or SimClientTag."
            } else {
                "Kotest ${superTypeName(superCall)} '${classOrObject.name ?: "<anon>"}' has multiple lane tags " +
                    "${laneTags.sorted()}. Keep exactly one lane tag; use separate files for separate lanes."
            }

        report(
            Finding(
                Entity.from(classOrObject),
                message,
            ),
        )
    }

    private fun directKotestSuperCall(classOrObject: KtClassOrObject): KtSuperTypeCallEntry? =
        classOrObject.superTypeListEntries
            .filterIsInstance<KtSuperTypeCallEntry>()
            .firstOrNull { superTypeName(it) in KOTEST_SPECS }

    private fun superTypeName(entry: KtSuperTypeCallEntry): String =
        entry.typeAsUserType?.referencedName ?: entry.calleeExpression.text

    private fun laneTagsIn(element: KtElement): Set<String> {
        val laneTags = mutableSetOf<String>()

        element
            .collectDescendantsOfType<KtCallExpression>()
            .filter { it.calleeExpression?.text == "tags" }
            .flatMap { it.valueArguments }
            .forEach { laneTags += laneTagsInText(it.text) }

        // Per-test: `test("...").config(tags = setOf(XTag)) { ... }`.
        element
            .collectDescendantsOfType<KtValueArgument>()
            .filter { it.getArgumentName()?.asName?.asString() == "tags" }
            .forEach { arg ->
                laneTags += laneTagsInText(arg.getArgumentExpression()?.text ?: arg.text)
            }

        return laneTags
    }

    private fun laneTagsInText(text: String): Set<String> =
        LANE_TAGS.filter { tag -> Regex("""\b$tag\b""").containsMatchIn(text) }.toSet()

    companion object {
        private val LANE_TAGS = setOf("UnitTag", "BoardTag", "IntegrationTag", "SimClientTag")

        private val KOTEST_SPECS = setOf(
            "FunSpec",
            "StringSpec",
            "DescribeSpec",
            "BehaviorSpec",
            "WordSpec",
            "ShouldSpec",
            "FeatureSpec",
            "ExpectSpec",
            "FreeSpec",
            "AnnotationSpec",
        )
    }
}
