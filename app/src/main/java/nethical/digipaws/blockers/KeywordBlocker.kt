package nethical.digipaws.blockers

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class KeywordBlocker(val service: AccessibilityService) : BaseBlocker() {
    companion object {
        val URL_BAR_ID_LIST = mapOf(

            "com.android.chrome" to BrowserUrlBarInfo(
                displayUrlBarId = "url_bar",
                browserSugggestionBoxId = "omnibox_suggestions_dropdown"
            ),
            "app.vanadium.browser" to BrowserUrlBarInfo(
                displayUrlBarId = "url_bar",
                browserSugggestionBoxId = "omnibox_suggestions_dropdown"
            ),
            "com.brave.browser" to BrowserUrlBarInfo(
                displayUrlBarId = "url_bar",
                browserSugggestionBoxId = "omnibox_suggestions_dropdown"
            ),

            "org.mozilla.firefox" to BrowserUrlBarInfo(
                displayUrlBarId = "mozac_browser_toolbar_url_view",
                browserSugggestionBoxId = "sfcnt",
            ),
            "com.opera.browser" to BrowserUrlBarInfo(
                displayUrlBarId = "url_field",
                browserSugggestionBoxId = "right_state_button",
                isSuggestionEqualToGo = true
            ),
        )

    }

    lateinit var blockedKeyword: HashSet<String>

    lateinit var redirectUrl: String
    var isSearchAllTextFields = false

    var recursionResultNodes: MutableList<AccessibilityNodeInfo> = mutableListOf()

    private fun containsBlockedKeyword(url: String): String? {

        val keywords = parseTextForKeywords(url)
        keywords.forEach { word ->
            if (blockedKeyword.contains(word.lowercase())) {
                return word
            }
        }
        return null
    }

    private fun parseTextForKeywords(input: String): Set<String> {

        fun extractWords(text: String): Set<String> {
            return text.split(Regex("[^a-zA-Z0-9]+"))
                .filter { it.isNotEmpty() }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
        }

        val urlPattern = "([\\w-]+\\.)+[\\w-]+(/[^?#]*)?\\??([^#]*)?"

        val regex = Regex(urlPattern)
        val words = mutableSetOf<String>()

        if (regex.find(input) != null) {

            words.addAll(extractWords(input))

            regex.find(input)?.groups?.get(3)?.value?.let { queryParams ->
                queryParams.split('&').forEach { param ->
                    if ('=' in param) {
                        val (key, value) = param.split('=', limit = 2)
                        words.addAll(extractWords(key))
                        words.addAll(extractWords(value))
                    }
                }
            }
        } else {

            words.addAll(extractWords(input))
        }

        return words
    }

    fun checkIfUserGettingFreaky(
        rootNode: AccessibilityNodeInfo?,
        event: AccessibilityEvent
    ): KeywordBlockerResult {
        rootNode ?: return KeywordBlockerResult()
        val displayUrlTextNode: AccessibilityNodeInfo?

        var detectedAdultKeyword: String? = null

        if (isSearchAllTextFields) {

            recursionResultNodes.clear()
            findNodesByClassName(rootNode, "android.widget.TextView", false)

            try {
                recursionResultNodes.forEach { node ->

                    val nodeText = node.text?.toString() ?: ""
                    if (nodeText.isEmpty()) return@forEach
                    val word = containsBlockedKeyword(nodeText)
                    if (word != null) {
                        detectedAdultKeyword = word
                        return@forEach
                    }
                }
            } catch (e: Exception) {
                Log.d("Keyword Blocker 111", e.toString())
            }
        }

        val urlBarInfo = URL_BAR_ID_LIST[event.packageName]
        if (urlBarInfo == null && detectedAdultKeyword != null) {

            return KeywordBlockerResult(true, detectedAdultKeyword)
        }

        if (urlBarInfo == null) return KeywordBlockerResult()
        val idPrefixPart = event.packageName.toString() + ":id/"
        displayUrlTextNode =
            ViewBlocker.findElementById(rootNode, idPrefixPart + urlBarInfo.displayUrlBarId)

        if (detectedAdultKeyword == null) {

            val webViewKeyword = searchKeywordsInWebViewTitle(rootNode)
            val displayText = displayUrlTextNode?.text?.toString() ?: ""
            detectedAdultKeyword = webViewKeyword ?: (if (displayText.isNotEmpty()) containsBlockedKeyword(displayText) else null) ?: return KeywordBlockerResult()

        }
        performSmallUpwardScroll()
        Thread.sleep(200)
        displayUrlTextNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(200)

        Log.d("edit", idPrefixPart + urlBarInfo.editUrlBarId)

        val editUrlBarId = urlBarInfo.editUrlBarId ?: urlBarInfo.displayUrlBarId
        val editUrlBar = ViewBlocker.findElementById(rootNode, idPrefixPart + editUrlBarId)
            ?: return KeywordBlockerResult(false, detectedAdultKeyword)

        editUrlBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, redirectUrl
            )
        })
        Thread.sleep(300)
        val goBtnNode =
            ViewBlocker.findElementById(rootNode, idPrefixPart + urlBarInfo.browserSugggestionBoxId)
                ?: return KeywordBlockerResult(resultDetectWord = detectedAdultKeyword)
        if (urlBarInfo.isSuggestionEqualToGo) {
            goBtnNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            goBtnNode.getChild(urlBarInfo.suggestionBoxIndexOfGoBtn)
                .performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        return KeywordBlockerResult(resultDetectWord = detectedAdultKeyword)
    }

    private fun searchKeywordsInWebViewTitle(rootNode: AccessibilityNodeInfo): String? {

        recursionResultNodes.clear()
        try {
            findNodesByClassName(rootNode, "android.webkit.WebView")
        } catch (e: Exception) {
            Log.d("error", e.toString())
            return null
        }
        val webView = recursionResultNodes.getOrNull(0) ?: return null
        Log.d("Keyword Blocker", "Webview found")
        val resultWord = webView.text

        Log.d("Keyword Blocker", "Webview title $resultWord")

        val titleText = resultWord?.toString() ?: ""
        if (titleText.isEmpty()) return null

        return containsBlockedKeyword(titleText)

    }

    private fun findNodesByClassName(
        node: AccessibilityNodeInfo?,
        targetClassName: String,
        returnOnFirstResult: Boolean = true
    ) {
        node ?: return

        if (node.className == targetClassName) {
            recursionResultNodes.add(node)
        }

        for (i in 0 until node.childCount) {
            findNodesByClassName(node.getChild(i), targetClassName)
        }
        if (returnOnFirstResult && recursionResultNodes.isNotEmpty()) return
        return
    }

    fun performSmallUpwardScroll() {

        val path = Path()

        val screenHeight = Resources.getSystem().displayMetrics.heightPixels

        val startY = (screenHeight * 0.75).toFloat()

        val endY = startY - (screenHeight * 0.1).toFloat()

        val centerX = Resources.getSystem().displayMetrics.widthPixels / 2f

        path.moveTo(centerX, startY)
        path.lineTo(centerX, endY)

        val gestureBuilder = GestureDescription.Builder()
        val gestureStroke = GestureDescription.StrokeDescription(
            path,
            0, 
            200 
        )

        val gesture = gestureBuilder
            .addStroke(gestureStroke)
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)

            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                service.performGlobalAction(GLOBAL_ACTION_HOME)

            }
        }, null)
    }

    data class BrowserUrlBarInfo(
        val displayUrlBarId: String,
        val editUrlBarId: String? = null,
        val browserSugggestionBoxId: String,
        val suggestionBoxIndexOfGoBtn: Int = 0,
        val isSuggestionEqualToGo: Boolean = false
    )

    data class KeywordBlockerResult(
        val isHomePressRequested: Boolean = false,
        val resultDetectWord: String? = null
    )
}