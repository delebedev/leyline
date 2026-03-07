package leyline.bridge

import forge.gamemodes.match.HostedMatch
import forge.gui.download.GuiDownloadService
import forge.gui.interfaces.IGuiBase
import forge.gui.interfaces.IGuiGame
import forge.item.PaperCard
import forge.localinstance.skin.FSkinProp
import forge.localinstance.skin.ISkinImage
import forge.sound.IAudioClip
import forge.sound.IAudioMusic
import forge.util.FSerializableFunction
import forge.util.ImageFetcher
import org.jupnp.UpnpServiceConfiguration
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.URISyntaxException
import java.util.function.Consumer

class WebGuiBase(
    private val assetsDir: String,
    val promptBridge: InteractivePromptBridge = InteractivePromptBridge(),
) : IGuiBase {
    private val imageFetcher = object : ImageFetcher() {
        override fun getDownloadTask(downloadUrls: Array<String>, destPath: String, notifyObservers: Runnable): Runnable = Runnable { notifyObservers.run() }
    }

    override fun hasNetGame(): Boolean = false

    override fun isRunningOnDesktop(): Boolean = false

    override fun isLibgdxPort(): Boolean = false

    override fun getCurrentVersion(): String = "web"

    override fun getAssetsDir(): String = ensureTrailingSlash(assetsDir)

    override fun getImageFetcher(): ImageFetcher = imageFetcher

    override fun invokeInEdtNow(runnable: Runnable) {
        runnable.run()
    }

    override fun invokeInEdtLater(runnable: Runnable) {
        runnable.run()
    }

    override fun invokeInEdtAndWait(proc: Runnable) {
        proc.run()
    }

    // No Swing EDT in web mode. Must return false so assertExecutedByEdt(false)
    // checks pass in cost payment / Input paths. The only assertExecutedByEdt(true)
    // caller is ImageFetcher — irrelevant to game logic.
    override fun isGuiThread(): Boolean = false

    override fun getSkinIcon(skinProp: FSkinProp): ISkinImage = throw UnsupportedOperationException("Skin icons are not supported in headless mode")

    override fun getUnskinnedIcon(path: String): ISkinImage = throw UnsupportedOperationException("Icons are not supported in headless mode")

    override fun getCardArt(card: PaperCard): ISkinImage = throw UnsupportedOperationException("Card art is not supported in headless mode")

    override fun getCardArt(card: PaperCard, backFace: Boolean): ISkinImage = throw UnsupportedOperationException("Card art is not supported in headless mode")

    override fun createLayeredImage(
        card: PaperCard,
        background: FSkinProp,
        overlayFilename: String,
        opacity: Float,
    ): ISkinImage = throw UnsupportedOperationException("Layered images are not supported in headless mode")

    override fun showBugReportDialog(title: String, text: String, showExitAppBtn: Boolean) {
        // Log instead of throwing — BugReporter calls this for game exceptions
        org.slf4j.LoggerFactory.getLogger(WebGuiBase::class.java)
            .warn("BugReporter: $title — ${text.take(300)}")
    }

    override fun showImageDialog(image: ISkinImage, message: String, title: String): Unit = throw UnsupportedOperationException("Image dialog not supported in headless mode")

    override fun showOptionDialog(
        message: String,
        title: String,
        icon: FSkinProp,
        options: MutableList<String>,
        defaultOption: Int,
    ): Int {
        val request = PromptRequest(
            promptType = "confirm",
            message = "$title: $message",
            options = options.toList(),
            min = 1,
            max = 1,
            defaultIndex = defaultOption.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
        )
        val result = promptBridge.requestChoice(request)
        return result.firstOrNull() ?: defaultOption
    }

    override fun showInputDialog(
        message: String,
        title: String,
        icon: FSkinProp,
        initialInput: String,
        inputOptions: MutableList<String>,
        isNumeric: Boolean,
    ): String {
        if (inputOptions.isEmpty()) {
            // Free-form input not supported via bridge; return initial value
            return initialInput
        }
        val request = PromptRequest(
            promptType = "choose_one",
            message = "$title: $message",
            options = inputOptions.toList(),
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = promptBridge.requestChoice(request)
        val idx = result.firstOrNull()?.coerceIn(0, inputOptions.size - 1) ?: 0
        return inputOptions[idx]
    }

    override fun <T> getChoices(
        message: String,
        min: Int,
        max: Int,
        choices: MutableCollection<T>,
        selected: MutableCollection<T>,
        display: FSerializableFunction<T, String>,
    ): MutableList<T> {
        val choiceList = choices.toList()
        if (choiceList.isEmpty()) return mutableListOf()

        val labels = choiceList.map { item -> display.apply(item) ?: item.toString() }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = message,
            options = labels,
            min = min.coerceAtLeast(0),
            max = max.coerceAtMost(choiceList.size),
            defaultIndex = 0,
        )
        val selectedIndices = promptBridge.requestChoice(request)
        val result = mutableListOf<T>()
        for (idx in selectedIndices) {
            if (idx in choiceList.indices) {
                result.add(choiceList[idx])
            }
        }
        // Ensure min constraint: if bridge returned fewer, pad from start
        if (result.size < min) {
            for (i in choiceList.indices) {
                if (result.size >= min) break
                if (choiceList[i] !in result) {
                    result.add(choiceList[i])
                }
            }
        }
        return result
    }

    override fun <T> order(
        title: String,
        top: String,
        remainingObjectsMin: Int,
        remainingObjectsMax: Int,
        sourceChoices: MutableList<T>,
        destChoices: MutableList<T>,
    ): MutableList<T> {
        val allItems = sourceChoices.toList()
        if (allItems.isEmpty()) return mutableListOf()

        val labels = allItems.map { it.toString() }
        val request = PromptRequest(
            promptType = "order",
            message = title,
            options = labels,
            min = allItems.size,
            max = allItems.size,
            defaultIndex = 0,
        )
        val orderedIndices = promptBridge.requestChoice(request)
        val result = mutableListOf<T>()
        for (idx in orderedIndices) {
            if (idx in allItems.indices) {
                result.add(allItems[idx])
            }
        }
        // If bridge didn't return full ordering, append remaining in original order
        if (result.size < allItems.size) {
            for (item in allItems) {
                if (item !in result) result.add(item)
            }
        }
        return result
    }

    override fun showFileDialog(title: String, defaultDir: String): String = throw UnsupportedOperationException("File dialog not supported in headless mode")

    override fun getSaveFile(defaultFile: File): File = throw UnsupportedOperationException("Save file dialog not supported in headless mode")

    override fun download(service: GuiDownloadService, callback: Consumer<Boolean>) {
        callback.accept(false)
    }

    override fun refreshSkin() {
        // no-op
    }

    override fun showCardList(title: String, message: String, list: MutableList<PaperCard>): Unit = throw UnsupportedOperationException("Card list not supported in headless mode")

    override fun showBoxedProduct(title: String, message: String, list: MutableList<PaperCard>): Boolean = throw UnsupportedOperationException("Boxed product not supported in headless mode")

    override fun chooseCard(title: String, message: String, list: MutableList<PaperCard>): PaperCard = throw UnsupportedOperationException("Card chooser not supported in headless mode")

    override fun getAvatarCount(): Int = 0

    override fun getSleevesCount(): Int = 0

    override fun copyToClipboard(text: String) {
        // no-op
    }

    @Throws(IOException::class, URISyntaxException::class)
    override fun browseToUrl(url: String): Unit = throw UnsupportedOperationException("Browsing not supported in headless mode")

    override fun isSupportedAudioFormat(file: File): Boolean = false

    override fun createAudioClip(filename: String): IAudioClip = throw UnsupportedOperationException("Audio not supported in headless mode")

    override fun createAudioMusic(filename: String): IAudioMusic = throw UnsupportedOperationException("Audio not supported in headless mode")

    override fun startAltSoundSystem(filename: String, isSynchronized: Boolean) {
        // no-op
    }

    override fun clearImageCache() {
        // no-op
    }

    override fun showSpellShop(): Unit = throw UnsupportedOperationException("Spell shop not supported in headless mode")

    override fun showBazaar(): Unit = throw UnsupportedOperationException("Bazaar not supported in headless mode")

    override fun hostMatch(): HostedMatch = throw UnsupportedOperationException("Hosted match not supported in headless mode")

    override fun runBackgroundTask(message: String, task: Runnable) {
        task.run()
    }

    override fun encodeSymbols(str: String, formatReminderText: Boolean): String = str

    override fun preventSystemSleep(preventSleep: Boolean) {
        // no-op
    }

    override fun getScreenScale(): Float = 1f

    override fun getUpnpPlatformService(): UpnpServiceConfiguration = throw UnsupportedOperationException("UPnP not supported in headless mode")

    override fun getNewGuiGame(): IGuiGame {
        val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when {
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                method.returnType == java.lang.Float.TYPE -> 0f
                method.returnType == java.lang.Double.TYPE -> 0.0
                method.returnType == java.lang.Short.TYPE -> 0.toShort()
                method.returnType == java.lang.Byte.TYPE -> 0.toByte()
                method.returnType == Character.TYPE -> '\u0000'
                method.returnType == Void.TYPE -> null
                List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                Map::class.java.isAssignableFrom(method.returnType) -> emptyMap<Any, Any>()
                Iterable::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                else -> null
            }
        }

        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            IGuiGame::class.java.classLoader,
            arrayOf(IGuiGame::class.java),
            handler,
        ) as IGuiGame
    }

    private fun ensureTrailingSlash(path: String): String = if (path.endsWith(File.separator)) {
        path
    } else {
        path + File.separator
    }
}
