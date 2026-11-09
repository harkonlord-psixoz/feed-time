package com.murcalendar.feeding;

import android.content.Context;
import android.webkit.JavascriptInterface;

/** JS bridge: window.MurCalendarAndroid.* */
public class MurJsBridge {
    private final Context appContext;

    public MurJsBridge(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @JavascriptInterface
    public void onFeedingsChanged(String dateKey, String lastFeed, String slotCount) {
        onFeedingsChanged(dateKey, lastFeed, slotCount, "");
    }

    @JavascriptInterface
    public void onFeedingsChanged(String dateKey, String lastFeed, String slotCount, String feedPrefix) {
        FeedPrefs.save(appContext, dateKey, lastFeed, slotCount, feedPrefix);
        FeedingAppWidget.requestUpdate(appContext);
    }

    /** Immediate notification after feed button — body already declined in JS. */
    @JavascriptInterface
    public void notifyJustFed(String body) {
        FeedNotifier.justFed(appContext, body);
    }

    /**
     * Persist last-feed timestamp + pre-declined notification bodies; reschedule alarms.
     * @param lastFeedMs epoch millis as string (0 = unknown / use now as base)
     * @param reminderBody full «Пора покормить …!» phrase
     * @param hungryBody full «… совсем проголодал…!» phrase
     * @param morningBody full morning phrase
     */
    @JavascriptInterface
    public void scheduleFeedReminders(
            String lastFeedMs,
            String reminderBody,
            String hungryBody,
            String morningBody
    ) {
        long ms = 0L;
        try {
            ms = Long.parseLong(lastFeedMs == null ? "0" : lastFeedMs.trim());
        } catch (Exception ignored) {}
        FeedReminderScheduler.saveState(appContext, ms, reminderBody, hungryBody, morningBody);
        FeedReminderScheduler.rescheduleAll(appContext);
    }

    /**
     * Open https URL outside WebView: APK → DownloadManager (+ browser fallback),
     * other links → ACTION_VIEW in external browser.
     */
    @JavascriptInterface
    public void openUrl(String url) {
        ExternalLinks.open(appContext, url);
    }
}
