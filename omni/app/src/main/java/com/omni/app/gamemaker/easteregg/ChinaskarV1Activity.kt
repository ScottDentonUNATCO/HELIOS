package com.omni.app.gamemaker.easteregg

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Hidden easter egg: CHINASKAR v1 — a playable top-down tribute inside a
 * plain WebView.
 *
 * What runs here: assets/chinaskar-v1/chinaskar-v1.html, adapted from the
 * surviving current CHINASCAR build's top-down 2D renderer with two
 * surgical deltas —
 *   1. default race camera forced to 'city' (CITY MAP top-down), the
 *      original top-down experience, instead of the newer pseudo-3D
 *      'chase' default;
 *   2. an on-screen overlay reading "CHINASKAR v1 — the original top-down.
 *      Easter egg." plus a small close button.
 *
 * Source provenance (honest): no playable day-one bundle survives — the
 * earliest audit snapshots of the original space contain screenshots only.
 * So this easter egg reuses the current build's top-down CITY MAP render
 * path (the same mode the original shipped), NOT a byte-identical
 * recovered original executable. The user-facing label says "the original
 * top-down" as a tribute description, not a forensic claim.
 *
 * No new Android permissions. Uses only android SDK classes + ComponentActivity
 * (already on the app classpath). Touch works through the WebView's normal
 * pointer-event pipeline; the game ships its own touch controls.
 *
 * NOTE (for whoever wires nav): this activity must be declared in
 * AndroidManifest.xml. That file is owned by the parent task, so it is
 * intentionally not touched here:
 *   <activity
 *       android:name="com.omni.app.gamemaker.easteregg.ChinaskarV1Activity"
 *       android:exported="false"
 *       android:configChanges="orientation|screenSize|keyboardHidden" />
 */
class ChinaskarV1Activity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemChrome()

        val wv = WebView(this)
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true      // game persists options in localStorage
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
        }
        // Bridge for the in-page close button (injected in chinaskar-v1.html).
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun closeEgg() {
                runOnUiThread { finish() }
            }
        }, "ChinaskarBridge")
        wv.webViewClient = WebViewClient()
        wv.webChromeClient = WebChromeClient()
        setContentView(wv)
        if (savedInstanceState == null) {
            wv.loadUrl("file:///android_asset/chinaskar-v1/chinaskar-v1.html")
        } else {
            wv.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.let { wv ->
            wv.removeJavascriptInterface("ChinaskarBridge")
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun hideSystemChrome() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }
}
