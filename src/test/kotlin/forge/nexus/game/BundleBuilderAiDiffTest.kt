package forge.nexus.game

import org.testng.Assert.*
import org.testng.annotations.Test

@Test(groups = ["unit"])
class BundleBuilderAiDiffTest {

    @Test(description = "aiActionDiff method exists with correct signature")
    fun aiActionDiffExists() {
        val method = BundleBuilder::class.java.getMethod(
            "aiActionDiff",
            forge.game.Game::class.java,
            GameBridge::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        assertNotNull(method, "aiActionDiff method should exist on BundleBuilder")
    }
}
