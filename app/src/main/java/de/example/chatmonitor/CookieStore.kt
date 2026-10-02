package de.example.chatmonitor

import android.content.Context
import android.util.Log
import android.webkit.CookieManager

/**
 * Keeps a private copy of the login cookies. WebView may drop session
 * cookies (no expiry date) when the app process ends, so we save them
 * and put them back on the next start / use them in the background worker.
 */
object CookieStore {

    private const val FRONT = "https://schueler.schule-infoportal.de/"
    private const val API = "https://api.schueler.schule-infoportal.de/luigywas/api/chat"
    private val URLS = listOf(FRONT, API)
    private const val TAG = "ChatMonitor"
    private const val MAX_AGE = 60 * 60 * 24 * 365

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("cookies", Context.MODE_PRIVATE)

    private fun parse(header: String?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        header?.split(";")?.forEach { part ->
            val p = part.trim()
            val i = p.indexOf('=')
            if (i > 0) result[p.substring(0, i)] = p.substring(i + 1)
        }
        return result
    }

    /** Save the WebView's current cookies. */
    fun save(ctx: Context) {
        val cm = CookieManager.getInstance()
        val editor = prefs(ctx).edit()
        for (url in URLS) {
            val current = cm.getCookie(url)
            if (!current.isNullOrEmpty()) editor.putString(url, current)
            Log.i(TAG, "save $url -> ${parse(current).keys}")
        }
        editor.apply()
        cm.flush()
    }

    /** Re-create saved cookies that the WebView lost, as persistent cookies. */
    fun restore(ctx: Context) {
        val cm = CookieManager.getInstance()
        for (url in URLS) {
            val saved = parse(prefs(ctx).getString(url, null))
            val current = parse(cm.getCookie(url))
            Log.i(TAG, "restore $url: live=${current.keys} saved=${saved.keys}")
            for ((name, value) in saved) {
                if (name !in current) {
                    Log.i(TAG, "  re-creating missing cookie: $name")
                    cm.setCookie(url, "$name=$value; Max-Age=$MAX_AGE; Path=/; Secure")
                }
            }
        }
        cm.flush()
    }

    /** Cookie header for background requests: saved copy, overridden by live cookies. */
    fun header(ctx: Context): String {
        val cm = CookieManager.getInstance()
        val merged = LinkedHashMap<String, String>()
        for (url in URLS) merged.putAll(parse(prefs(ctx).getString(url, null)))
        for (url in URLS) merged.putAll(parse(cm.getCookie(url)))
        Log.i(TAG, "header cookies: ${merged.keys}")
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
