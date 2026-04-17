package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Fails when a declaration carries KDoc that contains no prose — every non-blank
 * line is a `@tag` like `@return`, `@param`, or `@throws`. Such KDoc restates the
 * signature and gives reviewers false confidence that a function is documented.
 *
 * To pass: include at least one narrative line, or remove the KDoc entirely.
 */
class TrivialKDoc(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "TrivialKDoc",
        severity = Severity.Maintainability,
        description = "KDoc contains only @tags with no prose — delete it or add a description.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitDeclaration(declaration: KtDeclaration) {
        super.visitDeclaration(declaration)
        val kdoc = declaration.docComment ?: return
        if (isTrivial(kdoc)) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(kdoc),
                    "Trivial KDoc on '${declaration.name ?: "<anon>"}': contains only @tags, " +
                        "no narrative description. Add prose or delete the KDoc.",
                ),
            )
        }
    }

    private fun isTrivial(kdoc: KDoc): Boolean {
        val body = kdoc.text
            .lineSequence()
            .map { stripKdocSyntax(it) }
            .filter { it.isNotBlank() }
            .toList()
        if (body.isEmpty()) return true
        return body.all { it.startsWith("@") }
    }

    private fun stripKdocSyntax(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("/**")) s = s.removePrefix("/**")
        if (s.endsWith("*/")) s = s.removeSuffix("*/")
        s = s.trim()
        if (s.startsWith("*")) s = s.removePrefix("*").trim()
        return s
    }
}
