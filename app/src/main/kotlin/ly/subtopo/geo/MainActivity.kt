package ly.subtopo.geo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Coque Android de SubTopo-Geo : affiche l'application web (assets/index.html) dans une WebView.
 * Tout fonctionne hors ligne : la page est dans l'APK, les données sont stockées sur le téléphone.
 * Le GPS est fourni par [TrackingService] (service au premier plan) : l'enregistrement continue écran éteint.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var pendingBytes: ByteArray? = null
    private var pendingFileJson: String? = null
    private var trackingWanted = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val ok = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            geoCallback?.invoke(geoOrigin, ok, false)
            geoCallback = null
            if (ok && trackingWanted) {
                trackingWanted = false
                startTrackingService()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data
            val result: Array<Uri>? = if (r.resultCode == RESULT_OK && uri != null) arrayOf(uri) else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data
            val bytes = pendingBytes
            pendingBytes = null
            if (r.resultCode == RESULT_OK && uri != null && bytes != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    toast(getString(R.string.saved))
                } catch (e: Exception) {
                    toast(getString(R.string.save_failed))
                }
            }
        }

    /** Pont JavaScript : l'application web appelle Android.xxx(...). */
    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            runOnUiThread {
                pendingBytes = bytes
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                try {
                    saveLauncher.launch(intent)
                } catch (e: Exception) {
                    pendingBytes = null
                    toast(getString(R.string.save_failed))
                }
            }
        }

        /** Démarre le service GPS au premier plan (fonctionne écran éteint). */
        @JavascriptInterface
        fun startTracking() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (hasLocationPermission()) {
                    startTrackingService()
                } else {
                    trackingWanted = true
                    permLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }

        @JavascriptInterface
        fun stopTracking() {
            runOnUiThread { stopService(Intent(this@MainActivity, TrackingService::class.java)) }
        }

        @JavascriptInterface
        fun isTracking(): Boolean = TrackingService.running

        /** Positions enregistrées par le service depuis la séquence [since] (tableau JSON). */
        @JavascriptInterface
        fun fixesSince(since: Double): String = FixStore.since(applicationContext, since.toLong())

        @JavascriptInterface
        fun ackFixes(upTo: Double) {
            FixStore.ack(applicationContext, upTo.toLong())
        }

        /** Indique au service si un enregistrement de trace est en cours (texte de la notification). */
        @JavascriptInterface
        fun setRecording(on: Boolean) {
            runOnUiThread {
                if (TrackingService.running) {
                    val i = Intent(this@MainActivity, TrackingService::class.java)
                        .putExtra(TrackingService.EXTRA_REC, on)
                    ContextCompat.startForegroundService(this@MainActivity, i)
                } else {
                    TrackingService.recording = on
                }
            }
        }

        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        /** Fichier reçu depuis une autre application (Google Earth, gestionnaire de fichiers...). */
        @JavascriptInterface
        fun takePendingFile(): String {
            val s = pendingFileJson ?: ""
            pendingFileJson = null
            return s
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = request?.let { loader.shouldInterceptRequest(it.url) }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (hasLocationPermission()) {
                    callback?.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCallback = callback
                    permLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    pickLauncher.launch(Intent.createChooser(intent, getString(R.string.pick_file)))
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.settings.allowFileAccess = false
        web.addJavascriptInterface(Bridge(), "Android")

        // Bouton retour : ferme d'abord les fenêtres de l'application, puis quitte.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.onBack && window.onBack())") { v ->
                    if (v != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (!hasLocationPermission()) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        if (savedInstanceState == null) handleIntent(intent)

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onResume() {
        super.onResume()
        // Positions en direct tant que l'écran est allumé ; en veille, le service les met en mémoire.
        FixStore.listener = { line ->
            web.post { web.evaluateJavascript("window.onNativeFix&&window.onNativeFix($line)", null) }
        }
        web.evaluateJavascript("window.onNativeResume&&window.onNativeResume()", null)
    }

    override fun onPause() {
        FixStore.listener = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Ouverture / partage d'un fichier KML, KMZ, GPX, CSV, DXF depuis une autre application. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> streamExtra(i)
            else -> null
        }
        if (uri == null) return
        try {
            val name = displayName(uri)
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val o = JSONObject()
            o.put("name", name)
            o.put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            pendingFileJson = o.toString()
            web.post { web.evaluateJavascript("window.takeNativeFile&&window.takeNativeFile()", null) }
        } catch (e: Exception) {
            toast(getString(R.string.import_failed))
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
        }
        return name ?: (uri.lastPathSegment ?: "import")
    }

    private fun startTrackingService() {
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
