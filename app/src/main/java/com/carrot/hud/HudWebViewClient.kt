package com.carrot.hud

import android.webkit.WebView
import android.webkit.WebViewClient

/** Compatibility with older OP pages that still inject a floating diagnostic link. */
class HudWebViewClient : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        view.evaluateJavascript("""(() => {const s=document.createElement('style');s.textContent='#canDiagnosticsLink{display:none!important}';document.head.append(s);document.getElementById('canDiagnosticsLink')?.remove();})();""", null)
    }
}
