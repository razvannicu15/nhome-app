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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
            // Ignore the phone's font-size setting so the layout matches the PWA in Chrome.
            settings.textZoom = 100
            // Honour the page's viewport meta like Chrome (the wall tablet layout is width=1600, scaled to fit).
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // App, not a web page: no pinch / double-tap zoom.
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = object : WebChromeClient() {
                // No grey "play" placeholder on <video> elements before the first frame.
                override fun getDefaultVideoPoster(): android.graphics.Bitmap =
                    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val base = currentBase ?: return false
                    val baseHost = Uri.parse(base).host
                    if (url.host == baseHost) return false
                    // Links outside Nhome (maps, help pages) open in the normal browser.
                    return try { startActivity(Intent(Intent.ACTION_VIEW, url)); true } catch (e: Exception) { false }
                }
                // Server down (Cloudflare 502/503/504 or no connection): no raw error page, show a
                // calm message and look for the server again every few seconds.
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                    if (request.isForMainFrame && resp.statusCode in listOf(502, 503, 504, 520, 521, 522, 523, 524, 530)) serverDown()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) serverDown()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    syncSession()
                    applyDeviceProfile()
                    if (!down && url != "about:blank") { status.visibility = View.GONE; status.text = "Nhome · caut serverul de acasă…" }
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
        if (wall) setWall(true)
        if (pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt > 60_000) choose(force = false)
        pausedAt = 0L
    }

    override fun onPause() {
        super.onPause()
        syncSession()
        pausedAt = SystemClock.elapsedRealtime()
    }

    override fun onDestroy() {
        syncSession()
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
                    syncSession()
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


    /**
     * One pairing for both addresses: the server accepts the same device session token on the
     * LAN address and on nhome.nicu.xyz, so copy the cookie to whichever address lacks it.
     */
    private fun syncSession() {
        val cm = CookieManager.getInstance()
        val lan = prefs().getString("lan", null)
        val bases = listOfNotNull(REMOTE, lan)
        val token = bases.firstNotNullOfOrNull { b -> sessionOf(cm.getCookie(b)) }
        if (token != null) {
            for (b in bases) {
                if (sessionOf(cm.getCookie(b)) != token) {
                    val secure = if (b.startsWith("https")) "; Secure" else ""
                    cm.setCookie(b, "nhome_device_session=$token; Path=/; Max-Age=34560000; HttpOnly; SameSite=Lax$secure")
                }
            }
        }
        cm.flush()
    }

    private fun sessionOf(cookies: String?): String? =
        cookies?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("nhome_device_session=") }
            ?.substringAfter('=')?.takeIf { it.isNotEmpty() }


    private var wall = false

    /** The server marks a device as wall tablet (Setări → Acces). Then: full screen, screen always on. */
    private fun applyDeviceProfile() {
        web.evaluateJavascript("(function(){try{return localStorage.getItem('nhome_wall_device')}catch(e){return null}})()") { v ->
            setWall(v?.trim('"') == "1")
        }
    }

    private fun setWall(on: Boolean) {
        wall = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!on)
            window.insetsController?.let { c ->
                if (on) {
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    c.hide(WindowInsets.Type.systemBars())
                } else {
                    c.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && wall) setWall(true)
    }


    private var down = false
    private val retry = Runnable { down = false; currentBase = null; choose(force = true) }

    private fun serverDown() {
        down = true
        web.stopLoading()
        web.loadUrl("about:blank")
        status.text = "Serverul Nhome nu răspunde.\nVerifică dacă e pornit — reîncerc automat…"
        status.visibility = View.VISIBLE
        ui.removeCallbacks(retry)
        ui.postDelayed(retry, 5000)
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
