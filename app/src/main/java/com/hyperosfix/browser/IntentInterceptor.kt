package com.hyperosfix.browser

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Core interception logic for web-link intents that are being forced
 * to Xiaomi Browser or Xiaomi App Store.
 *
 * ## Architecture
 *
 * We hook two methods:
 * - [ContextImpl.startActivity] — the main gateway for all Activity starts.
 * - [Instrumentation.execStartActivity] — a secondary path used by some system components.
 *
 * When an [Intent] with ACTION_VIEW + http/https scheme is detected:
 * 1. Check if its package/component targets Xiaomi Browser or Xiaomi Market.
 * 2. If so, clean the intent (remove forced package/component).
 * 3. Re-dispatch to the system's default browser or the browser chooser.
 *
 * ## Infinite-loop prevention
 *
 * A [ThreadLocal] flag + a per-Intent-id [ConcurrentHashMap] guard
 * ensures that re-dispatching does not re-trigger the hook on the same Intent.
 *
 * ## Scope
 *
 * Only intercepts ACTION_VIEW with http/https scheme.
 * Non-web intents (tel:, sms:, geo:, market:, file:, custom schemes) pass through untouched.
 */
object IntentInterceptor {

    private const val TAG = "HyperOSBrowserFix_Intent"

    // ── Re-entrancy guard ────────────────────────────────────────────────
    // We use TWO layers to be safe:
    // 1. A ThreadLocal counter for simple same-thread recursion.
    // 2. A ConcurrentHashMap of "seen" Intent identity hash codes, cleared
    //    periodically to prevent memory leak.

    private val threadGuard = ThreadLocal<Boolean>()
    private val seenIntentIds = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var lastMiShareUrl: Uri? = null

    @Volatile
    private var lastMiShareUrlAt: Long = 0L

    /** Max size before we clear the seen-intents set to avoid memory leak. */
    private const val MAX_SEEN_INTENTS = 200
    private const val MI_SHARE_URL_CACHE_MS = 2 * 60 * 1000L
    private const val MAX_OBJECT_SCAN_DEPTH = 4

    // ── Public API: called from MainHook ─────────────────────────────────

    /**
     * Called from [ContextImpl.startActivity] hook (before invocation).
     *
     * @param intent   The Intent being launched.
     * @param context  The calling Context (used for re-dispatching).
     * @param options  The optional ActivityOptions Bundle.
     * @param param    The [XC_MethodHook.MethodHookParam] so we can set result.
     */
    fun onStartActivity(
        intent: Intent?,
        context: Context?,
        options: Bundle?,
        param: XC_MethodHook.MethodHookParam
    ) {
        if (intent == null || context == null) return

        if (!shouldIntercept(intent, context.packageName)) return

        if (!enterGuard(intent)) return

        try {
            Log.i(TAG, "Intercepted web intent from ${context.packageName}: ${intent.data}")

            // Clean the intent: remove forced package/component targeting
            val cleaned = cleanIntent(intent)

            // Get default browser info from system
            val browser = DefaultBrowserResolver.resolveDefaultBrowser(context)

            if (browser != null) {
                // If URL recovery failed and we still have a market:// or mi:// scheme,
                // don't try to open it in the browser — just open the browser's main
                // activity directly. Browsers can't handle market:// URIs.
                val effectiveData = cleaned.data
                val scheme = effectiveData?.scheme
                val needsFallback = scheme == "market" || scheme == "mi" ||
                    (scheme != null && scheme.startsWith("mi"))

                if (needsFallback) {
                    Log.w(TAG, "URL recovery failed; keeping original intent instead of opening https://")
                    return
                }

                val replacement = if (browser.isDefault) {
                    DefaultBrowserResolver.buildSpecificBrowserIntent(
                        effectiveData ?: return,
                        browser.packageName
                    )
                } else {
                    Intent.createChooser(
                        DefaultBrowserResolver.buildChooserIntent(effectiveData ?: return),
                        "Open with"
                    )
                }

                replacement.flags = cleaned.flags
                if (context !is Activity) {
                    replacement.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                replacement.putExtras(cleaned)

                Log.i(TAG, "Redirecting to: ${replacement.component ?: replacement.`package`
                    ?: "chooser"}")

                // Cancel the original call and start our replacement
                param.result = null  // prevents original startActivity from proceeding

                // Start the replacement intent using the same context
                try {
                    context.startActivity(replacement, options)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start replacement activity", e)
                    // Fallback: let original proceed (worst case: Xiaomi browser opens)
                    // We don't re-throw to avoid crashing the calling app.
                }
            } else {
                // No browser at all — let the original intent proceed
                // (Android will show an error or app-not-found dialog)
                Log.w(TAG, "No browser found on device, letting original intent proceed")
            }
        } finally {
            exitGuard(intent)
        }
    }

    /**
     * Secondary hook: [Instrumentation.execStartActivity].
     * Some system components bypass ContextImpl and call Instrumentation directly.
     */
    fun onExecStartActivity(
        context: Context?,
        intent: Intent?,
        param: XC_MethodHook.MethodHookParam
    ) {
        if (intent == null || context == null) return

        if (!shouldIntercept(intent, context.packageName)) return

        if (!enterGuard(intent)) return

        try {
            Log.i(TAG, "Intercepted (Instrumentation) from ${context.packageName}: ${intent.data}")

            val cleaned = cleanIntent(intent)
            val browser = DefaultBrowserResolver.resolveDefaultBrowser(context)

            if (browser != null) {
                val effectiveData = cleaned.data
                val scheme = effectiveData?.scheme
                val needsFallback = scheme == "market" || scheme == "mi" ||
                    (scheme != null && scheme.startsWith("mi"))

                if (needsFallback) {
                    Log.w(TAG, "URL recovery failed (Instr); keeping original intent instead of opening https://")
                    return
                }

                val replacement = if (browser.isDefault) {
                    DefaultBrowserResolver.buildSpecificBrowserIntent(
                        effectiveData ?: return,
                        browser.packageName
                    )
                } else {
                    Intent.createChooser(
                        DefaultBrowserResolver.buildChooserIntent(effectiveData ?: return),
                        "Open with"
                    )
                }

                replacement.flags = cleaned.flags
                if (context !is Activity) {
                    replacement.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                replacement.putExtras(cleaned)

                Log.i(TAG, "Redirecting (Instrumentation) to: ${replacement.component ?: replacement.`package`
                    ?: "chooser"}")

                param.result = null

                try {
                    context.startActivity(replacement)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start replacement (Instrumentation)", e)
                }
            } else {
                Log.w(TAG, "No browser found, letting original proceed (Instrumentation)")
            }
        } finally {
            exitGuard(intent)
        }
    }

    /**
     * Mi Share sometimes converts the URL into a market:// browser-download
     * Intent before startActivity/PendingIntent hooks see it. Cache the original
     * URL when we still have access to Mi Share's service Intent.
     */
    internal fun rememberMiShareUrl(intent: Intent) {
        val url = extractWebUriFromIntent(intent)
        if (url != null) {
            lastMiShareUrl = url
            lastMiShareUrlAt = System.currentTimeMillis()
            Log.i(TAG, "Cached Mi Share URL: $url")
        }
    }

    internal fun recoverUrl(intent: Intent): Uri? {
        return extractWebUriFromIntent(intent)
    }

    // ── Decision logic ───────────────────────────────────────────────────

    /**
     * Returns true if this Intent should be intercepted and potentially redirected.
     *
     * Conditions:
     * - ACTION_VIEW (or ACTION_MAIN with http/https data)
     * - http / https / market / mi scheme
     * - Target package/component is a known Xiaomi browser or market,
     *   OR the caller is a known Xiaomi system app and the data is http/https
     *   OR the scheme is "mi" (Xiaomi's custom URL scheme that wraps real URLs)
     */
    private fun shouldIntercept(intent: Intent, callerPackage: String): Boolean {
        // Only ACTION_VIEW (or ACTION_MAIN which some apps use for web intents)
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_MAIN) return false

        // Must have a web-like URI, either as Intent.data or tucked into
        // extras/ClipData by Xiaomi system components.
        val data: Uri = intent.data ?: extractWebUriFromIntent(intent) ?: return false

        val scheme: String = data.scheme ?: return false

        // Check if this Intent is being forced to Xiaomi Browser or Market
        val targetPkg = intent.`package`
        val targetComponent = intent.component

        val targetsXiaomiBrowser = XiaomiPackageList.isXiaomiBrowser(targetPkg) ||
            (targetComponent != null && XiaomiPackageList.isXiaomiBrowser(targetComponent.packageName))

        val targetsXiaomiMarket = XiaomiPackageList.isXiaomiMarket(targetPkg) ||
            (targetComponent != null && XiaomiPackageList.isXiaomiMarket(targetComponent.packageName))

        // Case A: http/https URL forced to Xiaomi Browser → intercept
        if ((scheme == "http" || scheme == "https") && targetsXiaomiBrowser) {
            Log.d(TAG, "Will intercept (http→browser): targetPkg=$targetPkg, component=$targetComponent, " +
                "caller=$callerPackage, data=$data")
            return true
        }

        // Case B: market:// URL targeting Xiaomi Market → intercept and try to recover URL
        if (scheme == "market" && targetsXiaomiMarket) {
            Log.d(TAG, "Will intercept (market→market): targetPkg=$targetPkg, component=$targetComponent, " +
                "caller=$callerPackage, data=$data")
            return true
        }

        // Case C: http/https URL with Xiaomi Market as target (browser uninstalled redirect)
        if ((scheme == "http" || scheme == "https") && targetsXiaomiMarket) {
            Log.d(TAG, "Will intercept (http→market): targetPkg=$targetPkg, component=$targetComponent, " +
                "caller=$callerPackage, data=$data")
            return true
        }

        // Case D: http/https URL with NO explicit target but called from a
        // Xiaomi system app. HyperOS may redirect implicitly.
        if ((scheme == "http" || scheme == "https") &&
            targetPkg == null && targetComponent == null &&
            XiaomiPackageList.isXiaomiSystemApp(callerPackage)) {
            Log.d(TAG, "Will intercept (http from Xiaomi sys app): caller=$callerPackage, data=$data")
            return true
        }

        // Case D2: Wi-Fi settings' "Manage Xiaomi router" may route the
        // router admin page through Xiaomi Browser. Keep it on the user's
        // system default browser, especially when Xiaomi Browser is disabled.
        if ((scheme == "http" || scheme == "https") &&
            callerPackage == XiaomiPackageList.SETTINGS &&
            isRouterAdminUrl(data)) {
            Log.d(TAG, "Will intercept (Settings router admin): caller=$callerPackage, data=$data")
            return true
        }

        // Case E: mi:// scheme — Xiaomi's custom URL wrapper
        // (used by Xiaomi AI Engine and voice assistant)
        if (scheme == "mi" || scheme.startsWith("mi")) {
            Log.d(TAG, "Will intercept (mi:// scheme): caller=$callerPackage, data=$data")
            return true
        }

        // Case F: intent:// scheme used to wrap URLs in some Xiaomi flows
        if (scheme == "intent" && targetsXiaomiBrowser) {
            Log.d(TAG, "Will intercept (intent:// scheme → browser): caller=$callerPackage, data=$data")
            return true
        }

        // Case G: market:// URL with id=<xiaomi_browser> but no explicit package target.
        // This is what Mi Share does: it calls startActivity(market://details?id=com.android.browser)
        // with pkg=null. The URL has already been converted to a market link.
        if (scheme == "market" && targetPkg == null && targetComponent == null) {
            val marketId = data.getQueryParameter("id")
            if (XiaomiPackageList.isXiaomiBrowser(marketId)) {
                Log.d(TAG, "Will intercept (market://id=browser): caller=$callerPackage, data=$data")
                return true
            }
        }

        return false
    }

    // ── Intent cleaning ──────────────────────────────────────────────────

    /**
     * Remove forced package/component targeting from the Intent.
     *
     * This strips the explicit "go to Xiaomi Browser" directive so the system
     * resolver can pick the user's default browser instead.
     *
     * Also attempts to recover the original URL from extras if the data URI
     * has been transformed into a market:// download-page URI.
     */
    private fun cleanIntent(intent: Intent): Intent {
        val cleaned = Intent(intent)  // copy

        if (cleaned.data == null) {
            extractWebUriFromIntent(cleaned)?.let {
                Log.i(TAG, "Recovered URL from intent payload: $it")
                cleaned.data = it
            }
        }

        // Remove forced package targeting
        if (XiaomiPackageList.isXiaomiBrowser(cleaned.`package`) ||
            XiaomiPackageList.isXiaomiMarket(cleaned.`package`)) {
            Log.d(TAG, "Removing forced package: ${cleaned.`package`}")
            cleaned.`package` = null
        }

        // Remove forced component targeting
        val comp = cleaned.component
        if (comp != null && (XiaomiPackageList.isXiaomiBrowser(comp.packageName) ||
                XiaomiPackageList.isXiaomiMarket(comp.packageName))) {
            Log.d(TAG, "Removing forced component: $comp")
            cleaned.component = null
        }

        // Attempt to recover original URL from market:// or mi:// scheme intents.
        // When Xiaomi Browser is uninstalled, Mi Share often redirects to
        // Xiaomi Market with a market://details?... URI that embeds the
        // original URL as a referrer or extra parameter.
        // Xiaomi AI Engine and voice assistant use mi:// scheme to wrap URLs.
        val data = cleaned.data
        if (data != null) {
            val scheme = data.scheme
            if (scheme == "market") {
                val recovered = recoverUrlFromMarketIntent(cleaned)
                if (recovered != null) {
                    Log.i(TAG, "Recovered original URL from market intent: $recovered")
                    cleaned.data = recovered
                }
            } else if (scheme == "mi" || (scheme != null && scheme.startsWith("mi"))) {
                val recovered = recoverUrlFromMiScheme(cleaned)
                if (recovered != null) {
                    Log.i(TAG, "Recovered original URL from mi:// intent: $recovered")
                    cleaned.data = recovered
                }
            } else if (scheme == "intent") {
                val recovered = recoverUrlFromIntentScheme(cleaned)
                if (recovered != null) {
                    Log.i(TAG, "Recovered original URL from intent:// scheme: $recovered")
                    cleaned.data = recovered
                }
            }
        }

        return cleaned
    }

    /**
     * Try to recover the original web URL from an intent that has been
     * re-targeted to Xiaomi Market (market://details?...).
     *
     * Common patterns observed in HyperOS:
     * - `intent.getStringExtra("android.intent.extra.REFERRER")` contains the original URL
     * - `intent.data.getQueryParameter("url")` or `"referrer"`
     */
    private fun recoverUrlFromMarketIntent(intent: Intent): Uri? {
        extractWebUriFromIntent(intent)?.let { return it }

        val data: Uri = intent.data ?: return null

        val urlParam = data.getQueryParameter("url")
            ?: data.getQueryParameter("referrer")
            ?: data.getQueryParameter("link")
            ?: data.getQueryParameter("target_url")
        if (!urlParam.isNullOrEmpty() &&
            (urlParam.startsWith("http://") || urlParam.startsWith("https://"))) {
            return Uri.parse(urlParam)
        }

        getRecentMiShareUrl()?.let {
            Log.i(TAG, "Recovered original URL from Mi Share cache: $it")
            return it
        }

        return null
    }

    /**
     * Extract the real web URL from Xiaomi's mi:// custom scheme.
     *
     * Xiaomi AI Engine / voice assistant wraps URLs like:
     *   mi://<encoded_data>?url=https://real.url.com
     * or buries the URL in query parameters or extras.
     *
     * Strategy (from reference module):
     * 1. Check known query parameter keys: url, query, q, link, text
     * 2. Sniff all query parameters for http-like values
     * 3. Search extras Bundle for URL-like string values
     * 4. URL-decode and normalize the result
     */
    internal fun recoverUrlFromMiScheme(intent: Intent): Uri? {
        val data: Uri = intent.data ?: return null

        // Priority 1: Known key names
        val targetKeys = arrayOf("url", "query", "q", "link", "text")
        for (key in targetKeys) {
            try {
                val value = data.getQueryParameter(key)
                if (!value.isNullOrEmpty()) {
                    val trimmed = value.trim()
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        return Uri.parse(trimmed)
                    }
                }
            } catch (_: Exception) {}
        }

        // Priority 2: Sniff all query parameters for http-like values
        try {
            for (key in data.queryParameterNames) {
                val value = data.getQueryParameter(key)
                if (!value.isNullOrEmpty()) {
                    val trimmed = value.trim()
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                        (trimmed.contains(".") && !trimmed.contains(" "))) {
                        return Uri.parse(normalizeUrl(trimmed))
                    }
                }
            }
        } catch (_: Exception) {}

        // Priority 3: Search extras for URL-like strings
        if (intent.extras != null) {
            for (key in intent.extras!!.keySet()) {
                val value = intent.extras!!.get(key)
                if (value is String) {
                    val trimmed = value.trim()
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                        (trimmed.contains(".") && !trimmed.contains(" ") && trimmed.length > 4)) {
                        return Uri.parse(normalizeUrl(trimmed))
                    }
                }
            }
        }

        return null
    }

    /**
     * Extract URL from intent:// scheme (used in some Xiaomi flows).
     * intent:// URLs often wrap a real URL in query parameters.
     */
    internal fun recoverUrlFromIntentScheme(intent: Intent): Uri? {
        val data: Uri = intent.data ?: return null

        // Try query parameters
        val targetKeys = arrayOf("url", "link", "target_url", "q")
        for (key in targetKeys) {
            try {
                val value = data.getQueryParameter(key)
                if (!value.isNullOrEmpty()) {
                    val trimmed = value.trim()
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        return Uri.parse(trimmed)
                    }
                }
            } catch (_: Exception) {}
        }

        // Try extras
        if (intent.extras != null) {
            for (key in intent.extras!!.keySet()) {
                val value = intent.extras!!.get(key)
                if (value is String && (value.startsWith("http://") || value.startsWith("https://"))) {
                    return Uri.parse(value)
                }
            }
        }

        return null
    }

    /**
     * Ensure a URL string has a proper scheme.
     * If the trimmed string starts with "www." or just a domain, prepend "https://".
     */
    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("www.") -> "https://$trimmed"
            else -> "https://$trimmed"
        }
    }

    private fun isRouterAdminUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host == "miwifi.com" || host.endsWith(".miwifi.com")) return true
        if (host == "router.miwifi.com" || host == "www.miwifi.com") return true

        val path = uri.path?.lowercase().orEmpty()
        if ((path.contains("miwifi") || path.contains("luci")) && isPrivateHost(host)) {
            return true
        }

        // Xiaomi router admin pages usually use the current gateway address.
        return isPrivateGatewayHost(host)
    }

    private fun isPrivateGatewayHost(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false

        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false

        return when {
            nums[0] == 10 && nums[3] == 1 -> true
            nums[0] == 192 && nums[1] == 168 && nums[3] == 1 -> true
            nums[0] == 172 && nums[1] in 16..31 && nums[3] == 1 -> true
            else -> false
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false

        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false

        return nums[0] == 10 ||
            (nums[0] == 192 && nums[1] == 168) ||
            (nums[0] == 172 && nums[1] in 16..31)
    }

    private fun getRecentMiShareUrl(): Uri? {
        val url = lastMiShareUrl ?: return null
        val age = System.currentTimeMillis() - lastMiShareUrlAt
        return if (age in 0..MI_SHARE_URL_CACHE_MS) url else null
    }

    private fun extractWebUriFromIntent(intent: Intent): Uri? {
        extractWebUri(intent.data?.toString())?.let { return it }

        val knownExtras = arrayOf(
            Intent.EXTRA_TEXT,
            Intent.EXTRA_HTML_TEXT,
            Intent.EXTRA_REFERRER_NAME,
            "android.intent.extra.REFERRER",
            "url",
            "uri",
            "link",
            "target_url",
            "referrer",
            "text",
            "share_url",
            "content",
            "android.intent.extra.TEXT"
        )
        for (key in knownExtras) {
            val value = runCatching { intent.extras?.get(key) }.getOrNull()
            extractWebUriFromValue(value, newVisitedSet())?.let { return it }
        }

        extractWebUriFromBundle(intent.extras)?.let { return it }
        extractWebUriFromClipData(intent.clipData)?.let { return it }

        return null
    }

    private fun extractWebUriFromBundle(bundle: Bundle?): Uri? {
        if (bundle == null) return null
        for (key in bundle.keySet()) {
            val value = runCatching { bundle.get(key) }.getOrNull()
            extractWebUriFromValue(value, newVisitedSet())?.let { return it }
        }
        return null
    }

    private fun extractWebUriFromClipData(clipData: ClipData?): Uri? {
        if (clipData == null) return null
        for (i in 0 until clipData.itemCount) {
            val item = clipData.getItemAt(i) ?: continue
            extractWebUri(item.uri?.toString())?.let { return it }
            extractWebUri(item.text?.toString())?.let { return it }
            item.intent?.let { extractWebUriFromIntent(it)?.let { uri -> return uri } }
        }
        return null
    }

    private fun extractWebUriFromValue(
        value: Any?,
        visited: MutableSet<Any>,
        depth: Int = 0
    ): Uri? {
        return when (value) {
            null -> null
            is Uri -> extractWebUri(value.toString())
            is Intent -> extractWebUriFromIntent(value)
            is Bundle -> extractWebUriFromBundle(value)
            is CharSequence -> extractWebUri(value.toString())
            is Array<*> -> value.asSequence().mapNotNull {
                extractWebUriFromValue(it, visited, depth + 1)
            }.firstOrNull()
            is Iterable<*> -> value.asSequence().mapNotNull {
                extractWebUriFromValue(it, visited, depth + 1)
            }.firstOrNull()
            else -> extractWebUri(value.toString())
                ?: extractWebUriFromObjectFields(value, visited, depth)
        }
    }

    private fun extractWebUriFromObjectFields(
        value: Any,
        visited: MutableSet<Any>,
        depth: Int
    ): Uri? {
        if (depth >= MAX_OBJECT_SCAN_DEPTH) return null
        if (!visited.add(value)) return null

        var clazz: Class<*>? = value.javaClass
        val className = clazz?.name ?: return null
        if (className.startsWith("java.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("android.os.")) {
            return null
        }

        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val ownerClass = clazz
                val fieldValue = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull() ?: continue

                extractWebUriFromValue(fieldValue, visited, depth + 1)?.let {
                    Log.i(TAG, "Recovered URL from object field ${ownerClass.name}.${field.name}: $it")
                    return it
                }
            }
            clazz = clazz.superclass
        }

        return null
    }

    private fun extractWebUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null

        val direct = raw.trim()
        if (direct.startsWith("http://") || direct.startsWith("https://")) {
            return Uri.parse(direct)
        }

        val decoded = runCatching { Uri.decode(direct) }.getOrDefault(direct)
        val match = Regex("""https?://[^\s"'<>]+""").find(decoded) ?: return null
        return Uri.parse(match.value.trimEnd(')', ']', '}', ',', '.', ';'))
    }

    private fun newVisitedSet(): MutableSet<Any> {
        return Collections.newSetFromMap(IdentityHashMap())
    }

    // ── Re-entrancy guards ───────────────────────────────────────────────

    /**
     * Returns true if we should proceed with interception.
     * Returns false if we are already processing this intent (re-entrancy).
     */
    private fun enterGuard(intent: Intent): Boolean {
        val id = System.identityHashCode(intent)

        // Thread-local check
        if (threadGuard.get() == true) {
            Log.d(TAG, "Re-entrancy guard: skipping (same thread already processing)")
            return false
        }

        // Cross-thread check
        if (!seenIntentIds.add(id)) {
            Log.d(TAG, "Re-entrancy guard: skipping (intent already seen: $id)")
            return false
        }

        // Prevent unbounded growth of seenIntentIds
        if (seenIntentIds.size > MAX_SEEN_INTENTS) {
            Log.d(TAG, "Clearing seen intent cache (size=${seenIntentIds.size})")
            seenIntentIds.clear()
        }

        threadGuard.set(true)
        return true
    }

    private fun exitGuard(@Suppress("UNUSED_PARAMETER") intent: Intent) {
        threadGuard.remove()
        // Keep the intent in seenIntentIds briefly to prevent rapid re-entry
        // It will be cleaned up when the cache grows too large.
    }
}
