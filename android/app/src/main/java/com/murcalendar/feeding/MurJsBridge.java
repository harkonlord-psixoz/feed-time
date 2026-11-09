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

    /** Immediate toast-style notification after feed button. */
    @JavascriptInterface
    public void notifyJustFed(String names) {
        FeedNotifier.justFed(appContext, names);
    }

    /**
     * Persist last-feed timestamp + names and (re)schedule AlarmManager reminders.
     * @param lastFeedMs epoch millis as string (0 = unknown / use now as base)
     * @param names display names for copy
     * @param petCount number of pets (for verb agreement)
     */
    @JavascriptInterface
    public void scheduleFeedReminders(String lastFeedMs, String names, String petCount) {
        long ms = 0L;
        try {
            ms = Long.parseLong(lastFeedMs == null ? "0" : lastFeedMs.trim());
        } catch (Exception ignored) {}
        FeedReminderScheduler.saveState(appContext, ms, names, petCount);
        FeedReminderScheduler.rescheduleAll(appContext);
    }
}
