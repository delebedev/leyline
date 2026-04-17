package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Flags Kotest Spec subclasses whose init lambda contains no `tags(...)` call.
 * Leyline relies on `tags(UnitTag)` / `tags(IntegrationTag)` to route tests into
 * the correct CI bucket. A forgotten tag call means the test silently runs in
 * the wrong bucket — polluting fast unit runs with integration work, or being
 * skipped entirely by a `-Pexclude=Integration` gate. Adding tags is a one-line
 * change that makes the test's intended scope explicit.
 */
class FunSpecMissingTags(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "FunSpecMissingTags",
        severity = Severity.Defect,
        description = "Kotest Spec class must call tags(...) so CI can bucket the test correctly.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val superCall = classOrObject.superTypeListEntries
            .filterIsInstance<KtSuperTypeCallEntry>()
            .firstOrNull { superTypeName(it) in KOTEST_SPECS } ?: return
        // Search the whole class — tags() may live in the super-call lambda,
        // an init {} block, or any helper invoked from either.
        if (containsTagsCall(classOrObject)) return
        report(
            CodeSmell(
                issue,
                Entity.from(classOrObject),
                "Kotest ${superTypeName(superCall)} '${classOrObject.name ?: "<anon>"}' has no tags(...) call. " +
                    "Add e.g. `tags(UnitTag)` or `tags(IntegrationTag)` in the super-call lambda or an init block.",
            ),
        )
    }

    private fun superTypeName(entry: KtSuperTypeCallEntry): String =
        entry.typeAsUserType?.referencedName ?: entry.calleeExpression?.text ?: ""

    private fun containsTagsCall(element: KtElement): Boolean {
        // Class-level: tags(...) function call in super-call lambda, init block,
        // or any helper invoked from them.
        val hasTagsCall = element.collectDescendantsOfType<KtCallExpression>()
            .any { it.calleeExpression?.text == "tags" }
        if (hasTagsCall) return true
        // Per-test: `test("...").config(tags = setOf(XTag)) { ... }` — any named
        // argument `tags = ...` inside the class counts as a tagging decision.
        return element.collectDescendantsOfType<KtValueArgument>()
            .any { it.getArgumentName()?.asName?.asString() == "tags" }
    }

    companion object {
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
