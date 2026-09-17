package com.example.teamsmobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val TEAMS_URL = "https://teams.live.com/v2/"

        // Пълно десктоп представяне като Microsoft Edge на Windows 10/11
        private const val DESKTOP_EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0"

        // Скрипт за маскиране на платформата и пълно деактивиране на Service Worker
        private const val JS_DEVICE_SPOOF = """
            (function() {
                try {
                    // 1. Пълно скриване на Service Worker API
                    // Това принуждава Teams да работи като стандартно уеб приложение и спира мобилния кеш
                    try {
                        delete window.navigator.serviceWorker;
                        delete Navigator.prototype.serviceWorker;
                    } catch(e) {}

                    try {
                        Object.defineProperty(window.navigator, 'serviceWorker', {
                            get: function() { return undefined; },
                            configurable: false
                        });
                    } catch(e) {}

                    // 2. Симулиране на Windows платформа и Chromium Edge
                    const winPlatform = 'Win32';
                    const vendor = 'Google Inc.';

                    try { Object.defineProperty(navigator, 'platform', { get: () => winPlatform, configurable: true }); } catch(e){}
                    try { Object.defineProperty(Navigator.prototype, 'platform', { get: () => winPlatform, configurable: true }); } catch(e){}
                    try { Object.defineProperty(navigator, 'vendor', { get: () => vendor, configurable: true }); } catch(e){}
                    try { Object.defineProperty(Navigator.prototype, 'vendor', { get: () => vendor, configurable: true }); } catch(e){}

                    if (!window.chrome) {
                        window.chrome = { runtime: {} };
                    }

                    // 3. Симулиране на Client Hints (десктоп, non-mobile)
                    const fakeUAData = {
                        brands: [
                            { brand: 'Chromium', version: '128' },
                            { brand: 'Microsoft Edge', version: '128' },
                            { brand: 'Not;A=Brand', version: '24' }
                        ],
                        mobile: false,
                        platform: 'Windows',
                        getHighEntropyValues: function() {
                            return Promise.resolve({
                                architecture: 'x86',
                                bitness: '64',
                                brands: [
                                    { brand: 'Chromium', version: '128' },
                                    { brand: 'Microsoft Edge', version: '128' },
                                    { brand: 'Not;A=Brand', version: '24' }
                                ],
                                mobile: false,
                                model: '',
                                platform: 'Windows',
                                platformVersion: '15.0.0',
                                uaFullVersion: '128.0.2739.67'
                            });
                        },
                        toJSON: function() {
                            return {
                                brands: this.brands,
                                mobile: false,
                                platform: 'Windows'
                            };
                        }
                    };

                    try { Object.defineProperty(navigator, 'userAgentData', { get: () => fakeUAData, configurable: true }); } catch(e){}
                    try { Object.defineProperty(Navigator.prototype, 'userAgentData', { get: () => fakeUAData, configurable: true }); } catch(e){}

                    // 4. Задаване на десктоп резолюция за валидатора на Teams
                    try {
                        const targetWidth = Math.max(window.innerWidth, 1366);
                        Object.defineProperty(screen, 'width', { get: () => targetWidth, configurable: true });
                        Object.defineProperty(screen, 'availWidth', { get: () => targetWidth, configurable: true });
                    } catch(e) {}

                    // 5. Задаване на мобилен viewport за красив и удобен изглед на телефона
                    function setViewport() {
                        let meta = document.querySelector('meta[name="viewport"]');
                        if (!meta) {
                            meta = document.createElement('meta');
                            meta.name = 'viewport';
                            document.head.appendChild(meta);
                        }
                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                    }

                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', setViewport);
                    } else {
                        setViewport();
                    }
                } catch(e) {}
            })();
        """
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                val intentData = result.data
                val results: Array<Uri>? = when {
                    intentData?.data != null -> arrayOf(intentData.data!!)
                    intentData?.clipData != null -> {
                        val clipData = intentData.clipData!!
                        Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    }
                    else -> null
                }
                fileUploadCallback?.onReceiveValue(results)
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
        } catch (e: Exception) {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Защита от неочаквани системни грешки - при евентуален проблем показва диагностичен екран
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            runOnUiThread {
                showCrashScreen(throwable)
            }
        }

        super.onCreate(savedInstanceState)

        try {
            initApp()
        } catch (t: Throwable) {
            showCrashScreen(t)
        }
    }

    private fun initApp() {
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }

        val wv = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        this.webView = wv
        rootLayout.addView(wv)

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                12
            ).apply {
                gravity = Gravity.TOP
            }
            visibility = View.GONE
        }
        this.progressBar = pb
        rootLayout.addView(pb)

        setContentView(rootLayout)

        configureCookieManager(wv)
        configureWebSettings(wv)
        setupClients(wv, pb)
        handleBackNavigation(wv)

        // Винаги зареждаме адреса директно, без възстановяване на счупени кеширани състояния
        wv.loadUrl(TEAMS_URL)
    }

    private fun configureCookieManager(wv: WebView) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(wv, true)
        } catch (e: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(wv: WebView) {
        try {
            val settings = wv.settings
            settings.userAgentString = DESKTOP_EDGE_USER_AGENT
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.displayZoomControls = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
        } catch (e: Exception) {}
    }

    private fun setupClients(wv: WebView, pb: ProgressBar) {
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pb.visibility = View.VISIBLE
                try {
                    view?.evaluateJavascript(JS_DEVICE_SPOOF, null)
                } catch (e: Exception) {}
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pb.visibility = View.GONE
                try {
                    view?.evaluateJavascript(JS_DEVICE_SPOOF, null)
                    CookieManager.getInstance().flush()
                } catch (e: Exception) {}
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val host = url.host?.lowercase() ?: return false

                // Оставяме всички вътрешни и удостоверителни адреси на Microsoft в WebView
                if (host.contains("teams.") ||
                    host.contains("live.com") ||
                    host.contains("microsoft.com") ||
                    host.contains("microsoftonline.com") ||
                    host.contains("office.com") ||
                    host.contains("msftauth.net") ||
                    host.contains("msauth.net") ||
                    host.contains("windows.net") ||
                    host.contains("skype.com")
                ) {
                    return false
                }

                // Външните връзки се отварят в стандартния браузър
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pb.progress = newProgress
                if (newProgress >= 100) {
                    pb.visibility = View.GONE
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { req ->
                    runOnUiThread {
                        try {
                            req.grant(req.resources)
                        } catch (e: Exception) {}
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                return try {
                    filePickerLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileUploadCallback = null
                    false
                }
            }
        }
    }

    private fun handleBackNavigation(wv: WebView) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    finish()
                }
            }
        })
    }

    private fun showCrashScreen(throwable: Throwable) {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        throwable.printStackTrace(pw)
        val errorDetails = sw.toString()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val header = TextView(this).apply {
            text = "Диагностичен екран"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 24)
        }
        layout.addView(header)

        val copyBtn = Button(this).apply {
            text = "Копирай грешката"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Crash Details", errorDetails)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Грешката е копирана!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(copyBtn)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(0, 24, 0, 0)
            }
        }

        val body = TextView(this).apply {
            text = errorDetails
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 12f
            setTextIsSelectable(true)
        }
        scrollView.addView(body)
        layout.addView(scrollView)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {}
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            webView?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        } catch (e: Exception) {}
        webView = null
        super.onDestroy()
    }
}