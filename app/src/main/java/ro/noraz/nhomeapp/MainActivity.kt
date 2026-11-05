package ro.noraz.nhomeapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Nhome shell: on start (and on every network change) look for the Nhome server on the home
 * Wi-Fi. If it answers, load the UI directly from it; otherwise use the public address.
 */
class MainActivity : Activity() {
    companion object {
        const val REMOTE = "https://nhome.nicu.xyz/"
        const val LAN_PORT = 8788
        const val PROBE_TIMEOUT_MS = 700
        const val SCAN_BUDGET_MS = 3500L
    }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var currentBase: String? = null
    private var pausedAt = 0L
    @Volatile private var checking = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(11, 14, 19)) }
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(11, 14, 19))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val base = currentBase ?: return false
                    val baseHost = Uri.parse(base).host
                    if (url.host == baseHost) return false
                    // Links outside Nhome (maps, help pages) open in the normal browser.
                    return try { startActivity(Intent(Intent.ACTION_VIEW, url)); true } catch (e: Exception) { false }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    status.visibility = View.GONE
                }
            }
        }
        status = TextView(this).apply {
            setTextColor(Color.rgb(170, 178, 190))
            textSize = 15f
            gravity = Gravity.CENTER
            text = "Nhome · caut serverul de acasă…"
        }
        // Pairing is a persistent cookie; keep it and write it to disk promptly (see flush()).
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        choose(force = true)
        watchNetwork()
    }

    override fun onResume() {
        super.onResume()
        if (pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt > 60_000) choose(force = false)
        pausedAt = 0L
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        pausedAt = SystemClock.elapsedRealtime()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        callback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        super.onDestroy()
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    /** Pick LAN or public address; reload only when the choice changes (or on first start). */
    private fun choose(force: Boolean) {
        if (checking) return
        checking = true
        thread(name = "nhome-lan") {
            val lan = try { findLan() } catch (e: Exception) { null }
            checking = false
            val target = lan ?: REMOTE
            ui.post {
                if (force || target != currentBase) {
                    currentBase = target
                    if (lan != null) remember(lan)
                    web.loadUrl(target)
                }
            }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var lastWifi: Boolean? = null
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (lastWifi != null && lastWifi != wifi) ui.postDelayed({ choose(force = false) }, 1500)
                lastWifi = wifi
            }
            override fun onLost(network: Network) {
                ui.postDelayed({ choose(force = false) }, 1500)
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    private fun prefs() = getSharedPreferences("nhome", Context.MODE_PRIVATE)
    private fun remember(url: String) = prefs().edit().putString("lan", url).apply()

    /** Returns http://ip:8788/ of the Nhome server on the current Wi-Fi, or null. */
    private fun findLan(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        prefs().getString("lan", null)?.let { if (isNhome(it)) return it }
        val myIp = ipv4(cm.getLinkProperties(net)) ?: return null
        val prefix = myIp.substringBeforeLast('.') + "."
        val pool = Executors.newFixedThreadPool(48)
        try {
            val done = ExecutorCompletionService<String?>(pool)
            val candidates = (1..254).map { "http://$prefix$it:$LAN_PORT/" }.filter { !it.contains("//$myIp:") }
            candidates.forEach { url -> done.submit { if (isNhome(url)) url else null } }
            val deadline = SystemClock.elapsedRealtime() + SCAN_BUDGET_MS
            repeat(candidates.size) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return null
                val f = done.poll(left, TimeUnit.MILLISECONDS) ?: return null
                val hit = try { f.get() } catch (e: Exception) { null }
                if (hit != null) return hit
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ipv4(lp: LinkProperties?): String? =
        lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress

    private fun isNhome(base: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(base + "api/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText() }.contains("Nhome Client")
        } catch (e: Exception) {
            false
        } finally {
            c?.disconnect()
        }
    }
}
