package com.murcalendar.feeding;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private static final int REQ_FILE_CHOOSER = 1002;
    private static final int REQ_READ_MEDIA = 1003;

    private WebView webView;
    private int insetTopPx;
    private int insetBottomPx;

    /** Pending callback for &lt;input type=file&gt; (avatar picker). */
    private ValueCallback<Uri[]> filePathCallback;
    private WebChromeClient.FileChooserParams pendingFileChooserParams;

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
        settings.setAllowContentAccess(true);
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

        /* Without this, &lt;input type="file"&gt; does nothing in Android WebView. */
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                pendingFileChooserParams = params;
                if (needsMediaPermission() && !hasMediaPermission()) {
                    requestMediaPermission();
                    return true;
                }
                return launchFileChooser(params);
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

    private boolean needsMediaPermission() {
        /* SAF/GET_CONTENT often works without grant; still ask so OEM galleries can read. */
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                    new String[]{android.Manifest.permission.READ_MEDIA_IMAGES},
                    REQ_READ_MEDIA
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_READ_MEDIA
            );
        }
    }

    private boolean launchFileChooser(WebChromeClient.FileChooserParams params) {
        Intent intent;
        if (params != null) {
            intent = params.createIntent();
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }
        /* Prefer images for avatar; keep accept from HTML when present. */
        String[] accept = params != null ? params.getAcceptTypes() : null;
        if (accept == null || accept.length == 0
                || (accept.length == 1 && (accept[0] == null || accept[0].isEmpty()))) {
            intent.setType("image/*");
        }
        try {
            startActivityForResult(
                    Intent.createChooser(intent, "Выберите фото"),
                    REQ_FILE_CHOOSER
            );
            return true;
        } catch (ActivityNotFoundException e) {
            cancelFileChooser();
            android.widget.Toast.makeText(
                    this,
                    "Нет приложения для выбора фото",
                    android.widget.Toast.LENGTH_SHORT
            ).show();
            return false;
        }
    }

    private void cancelFileChooser() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        pendingFileChooserParams = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (filePathCallback != null) {
                if (resultCode == RESULT_OK) {
                    results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            pendingFileChooserParams = null;
            return;
        }
        //noinspection deprecation
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        if (requestCode == REQ_READ_MEDIA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            WebChromeClient.FileChooserParams params = pendingFileChooserParams;
            /* Even if denied, still try SAF/GET_CONTENT — often works without grant. */
            if (filePathCallback != null) {
                if (!launchFileChooser(params) && !granted) {
                    android.widget.Toast.makeText(
                            this,
                            "Нужен доступ к фото, чтобы выбрать аватар",
                            android.widget.Toast.LENGTH_SHORT
                    ).show();
                }
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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

    @Override
    protected void onDestroy() {
        cancelFileChooser();
        super.onDestroy();
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
