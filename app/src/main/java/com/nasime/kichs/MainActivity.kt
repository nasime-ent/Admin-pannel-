package com.nasime.kichs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val appUrl = "https://kichs-app.pages.dev/"
    private val prefs by lazy { getSharedPreferences("kichs_security", MODE_PRIVATE) }
    private lateinit var webView: WebView
    private lateinit var root: LinearLayout
    private lateinit var actionButton: Button
    private lateinit var statusText: TextView
    private var authenticatedThisLaunch = false
    private var hasLoaded = false
    private var backgrounded = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Android's system document/photo picker returns the selected image URI to WebView.
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult

            if (result.resultCode != Activity.RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val selectedUris = ArrayList<Uri>()
            result.data?.data?.let { selectedUris.add(it) }
            val clipData: ClipData? = result.data?.clipData
            if (clipData != null) {
                for (index in 0 until clipData.itemCount) {
                    clipData.getItemAt(index).uri?.let { selectedUris.add(it) }
                }
            }
            val uniqueUris = selectedUris.distinct()
            callback.onReceiveValue(if (uniqueUris.isEmpty()) null else uniqueUris.toTypedArray())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        if (prefs.getBoolean("biometric_enabled", false)) {
            showLockedState()
            authenticateForUnlock()
        } else {
            showWebApp()
        }
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101820.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xFF17232F.toInt())
        }
        val title = TextView(this).apply {
            text = "KICHS Student App"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(4, 8, 8, 8)
        }
        actionButton = Button(this).apply {
            text = "Enable fingerprint lock"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { enableBiometricLock() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(actionButton)
        root.addView(bar)
        statusText = TextView(this).apply {
            text = "Sign in to your student account, then enable fingerprint protection."
            setTextColor(0xFFB8C7D8.toInt())
            textSize = 13f
            setPadding(14, 8, 14, 8)
        }
        root.addView(statusText)
        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        configureWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.allowFileAccess = false
        // Required so WebView can read the content:// URI returned by Android's picker.
        webView.settings.allowContentAccess = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // Close any older picker request before opening a new one.
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                return try {
                    val pickerIntent = fileChooserParams.createIntent().apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (type.isNullOrBlank() || type == "*/*") "image/*" else type
                    }
                    filePickerLauncher.launch(
                        Intent.createChooser(pickerIntent, "Choose a profile picture")
                    )
                    true
                } catch (error: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "Unable to open the photo picker. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (uri.scheme == "https" && uri.host == "kichs-app.pages.dev") {
                    false
                } else {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    true
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.startsWith(appUrl)) {
                    statusText.text = if (prefs.getBoolean("biometric_enabled", false))
                        "Fingerprint protection is enabled." else
                        "After signing in, tap “Enable fingerprint lock” above."
                }
            }
        }
    }

    private fun showWebApp() {
        root.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        actionButton.visibility = View.VISIBLE
        if (!hasLoaded) {
            webView.loadUrl(appUrl)
            hasLoaded = true
        }
    }

    private fun enableBiometricLock() {
        if (!hasLoaded) return
        webView.evaluateJavascript(
            "(function(){try{return Object.keys(localStorage).some(k=>k.endsWith('-auth-token') && !!JSON.parse(localStorage.getItem(k)||'{}').access_token)}catch(e){return false}})();"
        ) { result ->
            if (result != "true") {
                Toast.makeText(this, "Please sign in to your KICHS student account first.", Toast.LENGTH_LONG).show()
            } else {
                authenticateForEnrollment()
            }
        }
    }

    private fun biometricAvailable(): Boolean {
        val manager = BiometricManager.from(this)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun authenticateForEnrollment() {
        if (!biometricAvailable()) {
            Toast.makeText(this, "No enrolled strong biometric is available. Set up a fingerprint in Android Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prefs.edit().putBoolean("biometric_enabled", true).apply()
                    authenticatedThisLaunch = true
                    statusText.text = "Fingerprint protection enabled. It will be requested when KICHS is reopened."
                    actionButton.text = "Fingerprint lock enabled"
                    actionButton.isEnabled = false
                    Toast.makeText(this@MainActivity, "KICHS fingerprint protection enabled.", Toast.LENGTH_SHORT).show()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable KICHS fingerprint protection")
            .setSubtitle("Verify your identity to protect this app on this device")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build())
    }

    private fun showLockedState() {
        root.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        actionButton.visibility = View.GONE
        statusText.text = "KICHS is locked. Verify your fingerprint to continue."
    }

    private fun authenticateForUnlock() {
        if (!biometricAvailable()) {
            statusText.text = "Fingerprint is unavailable. Re-enrol a fingerprint in Android Settings or clear KICHS app data to return to web sign-in."
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticatedThisLaunch = true
                    statusText.text = "Verified. Opening your student account…"
                    showWebApp()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    statusText.text = "KICHS remains locked. Tap retry after resolving the biometric prompt."
                    actionButton.visibility = View.VISIBLE
                    actionButton.text = "Retry fingerprint"
                    actionButton.setOnClickListener { authenticateForUnlock() }
                }
                override fun onAuthenticationFailed() {
                    statusText.text = "Fingerprint not recognised. Try again."
                }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock KICHS")
            .setSubtitle("Use your enrolled fingerprint to open your student dashboard")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build())
    }

    override fun onPause() {
        super.onPause()
        if (prefs.getBoolean("biometric_enabled", false)) backgrounded = true
    }

    override fun onResume() {
        super.onResume()
        if (backgrounded && prefs.getBoolean("biometric_enabled", false)) {
            backgrounded = false
            showLockedState()
            authenticateForUnlock()
        }
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack() && webView.visibility == View.VISIBLE) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}