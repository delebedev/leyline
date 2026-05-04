package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtFile

/**
 * Keeps matchdoor tests in their lane packages as the suite grows.
 *
 * Enforced shape:
 * - board/<domain> files do not use Session-tier markers
 * - session/<domain> files do not use Board-tier markers
 * - mechanics tests live under mechanics/<mechanic>
 * - mixed BoardTag + IntegrationTag files stay out of domain packages
 *
 * `conformance/` is retired: old mixed files must be split by tier before
 * landing in a durable package.
 */
class TestLayoutCheck(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "TestLayoutCheck",
        severity = Severity.Defect,
        description = "Test package does not match its Board/Session/mechanics lane.",
        debt = Debt.TEN_MINS,
    )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val packageName = file.packageFqName.asString()
        if (!packageName.startsWith("leyline.")) return
        if (packageName.startsWith("leyline.conformance")) {
            report(file, "conformance/ is retired. Split by tier and move the test to its owning package.")
            return
        }
        if (packageName.startsWith("leyline.testkit")) return
        if (packageName == "leyline") return

        val text = file.text
        when {
            packageName == "leyline.board" ->
                report(file, "Board tests must live under board/<domain>, not board/.")
            packageName.startsWith("leyline.board.") && text.hasSessionMarker() ->
                report(file, "Board package contains Session-tier markers. Move it to session/<domain> or split the file.")
            packageName == "leyline.session" ->
                report(file, "Session tests must live under session/<domain>, not session/.")
            packageName.startsWith("leyline.session.") && text.hasBoardMarker() ->
                report(file, "Session package contains Board-tier markers. Move it to board/<domain> or split the file.")
            packageName == "leyline.mechanics" ->
                report(file, "Mechanic tests must live under mechanics/<mechanic>.")
            packageName.startsWith("leyline.mechanics.") && packageName.split('.').size < MECHANIC_PACKAGE_PARTS ->
                report(file, "Mechanic tests must live under mechanics/<mechanic>.")
        }

        if (isDomainPackage(packageName) && text.contains("BoardTag") && text.contains("IntegrationTag")) {
            report(file, "Do not mix BoardTag and IntegrationTag in one domain file. Split action/shape from lifecycle.")
        }
    }

    private fun String.hasSessionMarker(): Boolean =
        contains("IntegrationTag") ||
            contains("SessionTest(") ||
            contains("MatchFlowHarness(")

    private fun String.hasBoardMarker(): Boolean =
        contains("BoardTag") ||
            contains("BoardTest(")

    private fun isDomainPackage(packageName: String): Boolean =
        packageName.startsWith("leyline.board.") ||
            packageName.startsWith("leyline.session.") ||
            packageName.startsWith("leyline.mechanics.")

    private fun report(
        file: KtFile,
        message: String,
    ) {
        report(CodeSmell(issue, Entity.from(file), message))
    }

    private companion object {
        private const val MECHANIC_PACKAGE_PARTS = 3
    }
}
