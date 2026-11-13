package com.murcalendar.feeding;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private WebView webView;
    private int insetTopPx;
    private int insetBottomPx;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableEdgeToEdge();
        requestNotifPermissionIfNeeded();
        webView = new WebView(this);
        setContentView(webView);
        webView.setBackgroundColor(Color.TRANSPARENT);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        /* Local assets (file://) must call api.github.com for update-check. */
        //noinspection deprecation
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        webView.addJavascriptInterface(new MurJsBridge(this), "MurCalendarAndroid");
        FeedNotifier.ensureChannel(this);
        listenForSystemInsets();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                return handleExternalUrl(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Force Android shell flag (layout CSS) + nudge bridge sync.
                view.evaluateJavascript(
                        "(function(){try{document.documentElement.setAttribute('data-shell','android');}catch(e){}})();",
                        null
                );
                pushInsetsToWeb();
                FeedingAppWidget.requestUpdate(MainActivity.this);
                FeedReminderScheduler.rescheduleAll(MainActivity.this);
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength
            ) {
                if (!ExternalLinks.isHttpUrl(url)) return;
                if (ExternalLinks.looksLikeApk(url, mimeType, contentDisposition)) {
                    String name = ExternalLinks.guessFileName(url, contentDisposition);
                    if (ExternalLinks.enqueueApkDownload(MainActivity.this, url, name)) {
                        android.widget.Toast.makeText(
                                MainActivity.this,
                                "Скачиваю APK… Откройте файл в «Загрузки» для установки.",
                                android.widget.Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }
                }
                ExternalLinks.openInBrowser(MainActivity.this, url);
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    /** Draw content behind transparent status / navigation bars (Telegram-style). */
    private void enableEdgeToEdge() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            View decor = window.getDecorView();
            //noinspection deprecation
            int flags = decor.getSystemUiVisibility();
            //noinspection deprecation
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            //noinspection deprecation
            decor.setSystemUiVisibility(flags);
        }
    }

    private void listenForSystemInsets() {
        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
                top = sys.top;
                bottom = sys.bottom;
            } else {
                //noinspection deprecation
                top = insets.getSystemWindowInsetTop();
                //noinspection deprecation
                bottom = insets.getSystemWindowInsetBottom();
            }
            insetTopPx = Math.max(0, top);
            insetBottomPx = Math.max(0, bottom);
            pushInsetsToWeb();
            return insets;
        });
        webView.requestApplyInsets();
    }

    /** Push status/nav bar sizes into CSS vars so HTML pads under the icons. */
    void pushInsetsToWeb() {
        if (webView == null) return;
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) density = 1f;
        int topCss = Math.round(insetTopPx / density);
        int bottomCss = Math.round(insetBottomPx / density);
        String js = "(function(){try{"
                + "var r=document.documentElement;"
                + "r.style.setProperty('--android-inset-top','" + topCss + "px');"
                + "r.style.setProperty('--android-inset-bottom','" + bottomCss + "px');"
                + "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    /**
     * Sync system icon contrast with app light/dark mode.
     * @param darkMode true → light (white) icons; false → dark icons
     */
    void applySystemUiForMode(boolean darkMode) {
        runOnUiThread(() -> {
            Window window = getWindow();
            // lightStatusBar = dark icons on a light background
            boolean lightStatusBar = !darkMode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController c = window.getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    int appearance = lightStatusBar ? mask : 0;
                    c.setSystemBarsAppearance(appearance, mask);
                }
            } else {
                View decor = window.getDecorView();
                //noinspection deprecation
                int flags = decor.getSystemUiVisibility();
                //noinspection deprecation
                flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    //noinspection deprecation
                    if (lightStatusBar) {
                        //noinspection deprecation
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    } else {
                        //noinspection deprecation
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    //noinspection deprecation
                    if (lightStatusBar) {
                        //noinspection deprecation
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        //noinspection deprecation
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                //noinspection deprecation
                decor.setSystemUiVisibility(flags);
            }
        });
    }

    /** Keep local file:// pages in WebView; send http(s) out for browser / DownloadManager. */
    private boolean handleExternalUrl(String url) {
        if (!ExternalLinks.isHttpUrl(url)) {
            return false;
        }
        ExternalLinks.open(this, url);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        FeedingAppWidget.requestUpdate(this);
        /* Reschedule future alarms only; past-due windows are cancelled (no now+5s re-fire). */
        FeedReminderScheduler.rescheduleAll(this);
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQ_POST_NOTIFICATIONS
        );
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
