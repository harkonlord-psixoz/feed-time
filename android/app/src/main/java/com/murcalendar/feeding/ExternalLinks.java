package com.murcalendar.feeding;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * Open http(s) links outside the in-app WebView so APK downloads / installs work.
 */
final class ExternalLinks {
    private ExternalLinks() {}

    static boolean isHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("https://") || u.startsWith("http://");
    }

    static boolean looksLikeApk(String url, String mimeType, String contentDisposition) {
        if (mimeType != null) {
            String m = mimeType.toLowerCase();
            if (m.contains("android.package") || m.contains("java-archive") || m.equals("application/vnd.android.package-archive")) {
                return true;
            }
        }
        if (contentDisposition != null && contentDisposition.toLowerCase().contains(".apk")) {
            return true;
        }
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        return u.endsWith(".apk");
    }

    /**
     * Open http(s) outside WebView.
     * APK: DownloadManager (notification → Загрузки) with external-browser fallback.
     * Other links: ACTION_VIEW in the system browser.
     */
    static void open(Context context, String url) {
        if (!isHttpUrl(url)) {
            toast(context, "Ссылка недоступна");
            return;
        }
        Context app = context.getApplicationContext();
        if (looksLikeApk(url, null, null)) {
            if (enqueueApkDownload(app, url, guessFileName(url, null))) {
                toast(app, "Скачиваю APK… Откройте файл в «Загрузки» для установки.");
                return;
            }
            toast(app, "Открываю браузер для скачивания APK…");
        }
        openInBrowser(app, url);
    }

    static void openInBrowser(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.getApplicationContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(context, "Нет браузера для открытия ссылки");
        } catch (Exception e) {
            toast(context, "Не удалось открыть ссылку");
        }
    }

    static boolean enqueueApkDownload(Context context, String url, String fileName) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return false;
            String name = TextUtils.isEmpty(fileName) ? "feed-time.apk" : fileName;
            if (!name.toLowerCase().endsWith(".apk")) {
                name = name + ".apk";
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url.trim()));
            req.setTitle(name);
            req.setDescription("Feed Time");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);
            dm.enqueue(req);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String guessFileName(String url, String contentDisposition) {
        if (contentDisposition != null) {
            // filename="feed-time.apk" or filename*=UTF-8''feed-time.apk
            String cd = contentDisposition;
            int star = cd.toLowerCase().indexOf("filename*=");
            if (star >= 0) {
                String part = cd.substring(star + 10).trim();
                int semi = part.indexOf(';');
                if (semi >= 0) part = part.substring(0, semi);
                part = part.replace("\"", "").trim();
                int quote = part.indexOf("''");
                if (quote >= 0) part = part.substring(quote + 2);
                if (!part.isEmpty()) return sanitizeFileName(part);
            }
            int idx = cd.toLowerCase().indexOf("filename=");
            if (idx >= 0) {
                String part = cd.substring(idx + 9).trim();
                int semi = part.indexOf(';');
                if (semi >= 0) part = part.substring(0, semi);
                part = part.replace("\"", "").trim();
                if (!part.isEmpty()) return sanitizeFileName(part);
            }
        }
        if (url != null) {
            String u = url.trim();
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            int slash = u.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < u.length()) {
                return sanitizeFileName(u.substring(slash + 1));
            }
        }
        return "feed-time.apk";
    }

    private static String sanitizeFileName(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "feed-time.apk" : n;
    }

    private static void toast(Context context, String message) {
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        );
    }
}
