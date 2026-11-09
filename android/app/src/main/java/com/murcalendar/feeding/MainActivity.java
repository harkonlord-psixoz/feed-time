package com.murcalendar.feeding;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.DownloadListener;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private WebView webView;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotifPermissionIfNeeded();
        webView = new WebView(this);
        setContentView(webView);

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
