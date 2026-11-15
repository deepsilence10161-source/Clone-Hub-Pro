package com.clonehub.advanced.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * The sign-in path a real user hit: a third-party app that signs in with Google through a WebView.
 *
 * Reported from the field (screenshot, 2026-11-15): "Continue with Google" inside a cloned app answers
 * "Google sign-in failed — dobara try karo". That app is web-based, so nothing about it goes through
 * AccountManager — it goes through a WebView, a page load, cookies and a console error. The existing
 * sign-in gate tests only the native account path, so this probe exists to measure the other one.
 *
 * It draws a real WebView, loads one HTTPS page, and reports what the platform did, one marker per
 * log line, in a fixed order:
 *
 *   WEBVIEW_SIGNIN_RUN process=<name> pid=<pid> kind=<native|clone>
 *   WEBVIEW_SIGNIN_MARKER <step> <key=value ...>
 *   WEBVIEW_SIGNIN_DONE kind=<kind> steps=<n>
 *
 * Everything it measures is a property a web sign-in actually depends on: whether a WebView can be
 * created at all, whether an HTTPS page loads and finishes, whether TLS fails, what the JS console
 * said, what the user agent is (Google refuses "disallowed_useragent" embedded browsers), whether the
 * page counts as a secure context, and whether a cookie survives a reload — the session cookie that a
 * Google OAuth redirect chain needs in order to recognise the user afterwards.
 *
 * Deliberately dependency-free Java: inside the clone this runs under the container's runtime, and the
 * host's Kotlin stdlib is not the entry point.
 */
public final class WebViewSignInProbe {

    private static final String TAG = "WEBVIEW_SIGNIN";
    private static final String MARKER = "WEBVIEW_SIGNIN_MARKER";
    private static final String RUN = "WEBVIEW_SIGNIN_RUN";
    private static final String DONE = "WEBVIEW_SIGNIN_DONE";
    private static final String START_URL = "https://example.com/";
    /** The script tag every "Continue with Google" page loads; Google decides here whether to serve. */
    private static final String GSI_URL = "https://accounts.google.com/gsi/client";
    /** How long the whole probe may take before it reports what it has. */
    private static final long DEADLINE_MS = 45_000L;

    /** The most recently attached probe, so the hosting activity can shut it down. */
    static WebViewSignInProbe lastAttached;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private String kind = "unknown";
    private int steps = 0;
    private boolean cookieReloadRequested = false;
    private boolean gsiLoaded = false;
    private String lastCookieStore = "";
    private long startedAt;

    /**
     * Attaches the probe to [host]'s own view hierarchy, when and only when it has been asked for.
     *
     * The probe must run down the route the container actually takes, which is the package's launcher
     * activity — so it lives inside that activity instead of being an activity of its own. It also must
     * not change what every other gate measures, so it stays dormant unless asked.
     *
     * Three independent ways to ask, because the answer has to survive whatever the container does to
     * this process's view of the device: an intent extra (used for the un-cloned run), the global
     * setting `clonehub_webprobe` (readable without a permission) and the system property
     * `debug.clonehub.webprobe`. The gate sets all three; the log says which one was seen, so a trigger
     * that is invisible inside the clone is a finding rather than a mystery.
     *
     * @return the trigger that turned the probe on, or empty when it stayed off.
     */
    public static String attachIfAsked(Activity host, android.view.ViewGroup container) {
        String trigger = askedBy(host);
        if (trigger.isEmpty()) return "";
        try {
            WebViewSignInProbe probe = new WebViewSignInProbe(host, container);
            lastAttached = probe;
            probe.start();
        } catch (Throwable error) {
            android.util.Log.i(TAG, MARKER + " attach_failed error="
                + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        return trigger;
    }

    /** Whether any trigger is set, for a host that was re-launched rather than created. */
    static boolean hasTrigger(Activity host) {
        return !askedBy(host).isEmpty();
    }

    /**
     * Which trigger is set, cheapest first.
     *
     * Measured (run 43693467484): inside a clone neither the global setting nor the system property is
     * visible — the probe reported `trigger=none` in the agent process, so a container that virtualises
     * Settings cannot be asked that way. The process name still tells the truth there: a clone runs in
     * `<package>:agentN`, which is what makes the clone side measurable at all.
     */
    private static String askedBy(Activity host) {
        android.content.Intent intent = host.getIntent();
        if (intent != null && intent.getIntExtra("webview_probe", 0) == 1) return "intent";
        try {
            if (android.provider.Settings.Global.getInt(
                    host.getContentResolver(), "clonehub_webprobe", 0) == 1) return "global_setting";
        } catch (Throwable ignored) {
            // a container that hides the settings provider simply skips this channel
        }
        String process = android.os.Build.VERSION.SDK_INT >= 28
            ? android.app.Application.getProcessName() : "";
        if (process != null && process.contains(":agent")) return "clone_process";
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, "debug.clonehub.webprobe");
            if ("1".equals(value)) return "system_property";
        } catch (Throwable ignored) {
            // SystemProperties is a hidden API; reflection keeps the probe compiling and honest
        }
        return "";
    }

    private final Activity host;
    private final android.view.ViewGroup container;

    private WebViewSignInProbe(Activity host, android.view.ViewGroup container) {
        this.host = host;
        this.container = container;
    }

    private void start() {
        String process = android.os.Build.VERSION.SDK_INT >= 28
                ? android.app.Application.getProcessName() : "";
        kind = process != null && process.contains(":agent") ? "clone" : "native";
        startedAt = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, RUN + " process=" + process + " pid=" + android.os.Process.myPid()
            + " kind=" + kind + " trigger=" + askedBy(host));

        try {
            webView = new WebView(host);
            container.removeAllViews();
            container.addView(
                webView,
                new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT)
            );
            marker("webview_created", "provider=" + describeWebViewProvider());
            configure(webView.getSettings());
            webView.setWebViewClient(client);
            webView.setWebChromeClient(chrome);
            marker("browser_intent", describeBrowserHandler());
            marker("load_started", "url=" + START_URL);
            webView.loadUrl(START_URL);
        } catch (Throwable error) {
            // A WebView that cannot even be constructed is a finding, not a crash: report it with the
            // platform's own message and finish, so the gate can compare it with the un-cloned app.
            marker("webview_failed", "error=" + error.getClass().getSimpleName() + ": " + error.getMessage());
            finishProbe();
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() { finishProbe(); }
        }, DEADLINE_MS);
    }

    /** The settings a real web sign-in needs; recorded, because a container may change them. */
    private void configure(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        marker(
            "settings",
            "js=" + settings.getJavaScriptEnabled()
                + " domStorage=" + settings.getDomStorageEnabled()
                + " database=" + settings.getDatabaseEnabled()
                + " windowOpen=" + settings.getJavaScriptCanOpenWindowsAutomatically()
                + " cookies=" + android.webkit.CookieManager.getInstance().acceptCookie()
                + " userAgent=" + safe(settings.getUserAgentString())
        );
    }

    private final WebViewClient client = new WebViewClient() {
        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            marker("page_started", "url=" + safe(url));
        }

        @Override public void onPageFinished(WebView view, String url) {
            marker("page_finished", "url=" + safe(url) + " title=" + safe(view.getTitle()));
            if (url != null && url.contains("gsi/client")) {
                gsiLoaded = true;
                marker("gsi_finished", "url=" + safe(url) + " title=" + safe(view.getTitle()));
                reportGsiThenFinish(view);
                return;
            }
            if (cookieReloadRequested) {
                evaluateCookieReadBack(view);
                return;
            }
            cookieReloadRequested = true;
            evaluateAndReport(view);
        }

        @Override public void onReceivedError(
                WebView view, android.webkit.WebResourceRequest request,
                android.webkit.WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                marker("main_frame_error", "code=" + error.getErrorCode()
                    + " description=" + safe(error.getDescription())
                    + " url=" + safe(request.getUrl() == null ? null : request.getUrl().toString()));
            }
        }

        @Override public void onReceivedHttpError(
                WebView view, android.webkit.WebResourceRequest request,
                android.webkit.WebResourceResponse response) {
            marker("http_error", "status=" + response.getStatusCode()
                + " url=" + safe(request.getUrl() == null ? null : request.getUrl().toString()));
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            marker("ssl_error", "primary=" + error.getPrimaryError()
                + " url=" + safe(error.getUrl()));
            // Never proceed past a TLS failure, not even to keep a probe alive: that would hide the
            // very thing being measured.
            handler.cancel();
        }
    };

    private final WebChromeClient chrome = new WebChromeClient() {
        @Override public boolean onConsoleMessage(ConsoleMessage message) {
            marker("console", "level=" + message.messageLevel()
                + " line=" + message.lineNumber()
                + " text=" + safe(message.message()));
            return true;
        }
    };

    /**
     * The handler an https link resolves to from inside this process.
     *
     * This is the difference between the two shapes of "Continue with Google": a page inside a WebView
     * (measured above) and a Chrome Custom Tab, which is an ordinary https VIEW intent. If the clone
     * cannot resolve a browser, the Custom Tabs path silently fails where the un-cloned app succeeds —
     * and that is worth recording separately because it is a different defect.
     */
    private String describeBrowserHandler() {
        try {
            android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://accounts.google.com/"));
            intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
            android.content.pm.ResolveInfo resolved = host.getPackageManager().resolveActivity(intent, 0);
            if (resolved == null) return "unresolved";
            String pkg = resolved.activityInfo == null ? "?" : resolved.activityInfo.packageName;
            return "component=" + (resolved.activityInfo == null ? "?" :
                resolved.activityInfo.name) + " package=" + pkg;
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    /** What the page itself can see — the things a "Continue with Google" button depends on. */
    private void evaluateAndReport(WebView view) {
        String js = "JSON.stringify({"
            + "ua: navigator.userAgent,"
            + "secure: window.isSecureContext === true,"
            + "origin: location.origin,"
            + "popups: typeof window.open === 'function',"
            + "crypto: typeof window.crypto === 'object' && !!window.crypto.subtle,"
            + "localStorage: (function(){try{localStorage.setItem('ch','1');return localStorage.getItem('ch')==='1';}catch(e){return 'error:'+e.name;}})(),"
            + "cookieWrite: (function(){try{document.cookie='ch_probe=1; path=/; SameSite=Lax';return document.cookie.indexOf('ch_probe=')>=0;}catch(e){return 'error:'+e.name;}})()"
            + "})";
        view.evaluateJavascript("(function(){var r;" + "try{r=" + js + ";}catch(e){r=JSON.stringify({error:e.name+': '+e.message});}return r;})()",
            value -> {
                marker("page_state", "json=" + safe(value));
                marker("cookie_set", "value=" + safe(android.webkit.CookieManager.getInstance()
                    .getCookie(START_URL)));
                // Reload once: a cookie that does not survive a navigation is a sign-in that cannot
                // finish, because Google's redirect chain depends on exactly that.
                main.post(() -> webView.loadUrl(START_URL));
            });
    }

    private void evaluateCookieReadBack(WebView view) {
        view.evaluateJavascript("JSON.stringify({cookie: document.cookie, oauth: typeof window.google})",
            value -> {
                marker("cookie_after_reload", "json=" + safe(value));
                String cookies = android.webkit.CookieManager.getInstance().getCookie(START_URL);
                marker("cookie_store", "value=" + safe(cookies));
                lastCookieStore = cookies == null ? "" : cookies;
                marker("gsi_request", "url=" + GSI_URL);
                main.post(() -> webView.loadUrl(GSI_URL));
            });
    }

    /**
     * Records Google's own answer to the script every "Continue with Google" page loads.
     *
     * This is the closest a gate can come to the real failure without a Google account: the page that
     * renders the button is served, or it is not. A 4xx here, a "disallowed_useragent" text, or a
     * certificate failure is a named reason a web sign-in cannot start inside every app that embeds it.
     */
    private void reportGsiThenFinish(WebView view) {
        view.evaluateJavascript(
            "JSON.stringify({title: document.title, len: document.body ? document.body.innerText.length : -1})",
            value -> {
                marker("gsi_state", "json=" + safe(value));
                marker("verdict",
                    "webview=" + (webView != null)
                        + " cookie_roundtrip=" + lastCookieStore.contains("ch_probe=1")
                        + " gsi_loaded=" + gsiLoaded
                        + " process=" + safe(android.os.Build.VERSION.SDK_INT >= 28
                            ? android.app.Application.getProcessName() : ""));
                finishProbe();
            });
    }

    private void finishProbe() {
        Log.i(TAG, DONE + " kind=" + kind + " steps=" + steps
            + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
    }

    private void marker(String step, String detail) {
        steps++;
        Log.i(TAG, MARKER + " " + step + " " + detail);
    }

    private static String safe(CharSequence value) {
        if (value == null) return "null";
        return value.toString().replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Which package implements WebView here.
     *
     * `WebView.getCurrentWebViewPackage()` is API 26+, so it is asked only on those platforms and the
     * answer is recorded either way: a container that ships no WebView provider, or one whose provider
     * is unreachable from the clone, is exactly what a web sign-in would trip over.
     */
    private static String describeWebViewProvider() {
        if (android.os.Build.VERSION.SDK_INT < 26) return "unavailable_on_sdk_" + android.os.Build.VERSION.SDK_INT;
        try {
            android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
            if (info == null) return "none";
            return info.packageName + ":" + info.versionName;
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    /** Called by the host activity when it is being destroyed. */
    void shutdown() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Throwable ignored) {
                // a WebView that cannot be destroyed is already reported by the probe
            }
            webView = null;
        }
    }
}
