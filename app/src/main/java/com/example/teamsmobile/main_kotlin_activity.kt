package com.example.teamsmobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val TEAMS_URL = "https://teams.live.com/v2/"

        // Пълно десктоп представяне като Microsoft Edge на Windows 10/11
        private const val DESKTOP_EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0"

        // Скрипт за маскиране на платформата и пълно деактивиране на Service Worker кеша
        private const val JS_DEVICE_SPOOF = """
            (function() {
                try {
                    // 1. Деактивиране и отписване на Service Worker (премахва фоновия мобилен кеш)
                    if ('serviceWorker' in navigator) {
                        try {
                            navigator.serviceWorker.getRegistrations().then(function(registrations) {
                                for (let r of registrations) {
                                    r.unregister();
                                }
                            });
                        } catch(e) {}
                        
                        navigator.serviceWorker.register = function() {
                            return Promise.reject(new Error('ServiceWorker disabled'));
                        };
                    }

                    // 2. Симулиране на Windows платформа и vendor
                    const winPlatform = 'Win32';
                    const vendor = 'Google Inc.';

                    try { Object.defineProperty(navigator, 'platform', { get: () => winPlatform, configurable: true }); } catch(e){}
                    try { Object.defineProperty(Navigator.prototype, 'platform', { get: () => winPlatform, configurable: true }); } catch(e){}
                    try { Object.defineProperty(navigator, 'vendor', { get: () => vendor, configurable: true }); } catch(e){}
                    try { Object.defineProperty(Navigator.prototype, 'vendor', { get: () => vendor, configurable: true }); } catch(e){}

                    if (!window.chrome) {
                        window.chrome = { runtime: {} };
                    }

                    // 3. Симулиране на Client Hints (mobile: false, platform: Windows)
                    const fakeUAData = {
                        brands: [
                            { brand: 'Chromium', version: '128' },
                            { brand: 'Microsoft Edge', version: '128' },
                            { brand: 'Not;A=Brand', version: '24' }
                        ],
                        mobile: false,
                        platform: 'Windows',
                        getHighEntropyValues: function(hints) {
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

                    // 4. Симулиране на десктоп ширина на екрана за проверката на Teams
                    try {
                        const targetWidth = Math.max(window.innerWidth, 1366);
                        Object.defineProperty(screen, 'width', { get: () => targetWidth, configurable: true });
                        Object.defineProperty(screen, 'availWidth', { get: () => targetWidth, configurable: true });
                        Object.defineProperty(Screen.prototype, 'width', { get: () => targetWidth, configurable: true });
                        Object.defineProperty(Screen.prototype, 'availWidth', { get: () => targetWidth, configurable: true });
                    } catch(e) {}

                    // 5. Задаване на мобилен viewport за правилно оразмеряване
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
        if (result.resultCode == Activity.RESULT_OK) {
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
        fileUploadCallback = null
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(webView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                10
            )
            visibility = View.GONE
        }
        rootLayout.addView(progressBar)

        setContentView(rootLayout)

        requestRequiredPermissions()
        configureCookieManager()
        configureWebSettings()
        setupClients()
        handleBackNavigation()

        if (savedInstanceState == null) {
            webView.loadUrl(TEAMS_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun configureCookieManager() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings() {
        val settings = webView.settings
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
    }

    private fun setupClients() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                view?.evaluateJavascript(JS_DEVICE_SPOOF, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                view?.evaluateJavascript(JS_DEVICE_SPOOF, null)
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val host = request?.url?.host?.lowercase() ?: return false

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

                return try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let {
                    it.grant(it.resources)
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

                try {
                    filePickerLauncher.launch(intent)
                } catch (e: Exception) {
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }
    }

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun requestRequiredPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}