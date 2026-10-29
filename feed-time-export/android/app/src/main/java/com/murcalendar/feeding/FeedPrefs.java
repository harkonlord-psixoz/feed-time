package com.murcalendar.feeding;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mirrored feeding state for the home-screen widget.
 * Written from WebView via {@link MurJsBridge} when localStorage changes.
 */
public final class FeedPrefs {
    public static final String PREFS = "mur_calendar_widget";
    public static final String KEY_DATE = "today_date";
    public static final String KEY_LAST = "last_feed";
    public static final String KEY_SLOTS = "slot_count";
    public static final String KEY_PREFIX = "feed_prefix";

    private FeedPrefs() {}

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context context, String dateKey, String lastFeed, String slotCount) {
        save(context, dateKey, lastFeed, slotCount, null);
    }

    public static void save(Context context, String dateKey, String lastFeed, String slotCount, String feedPrefix) {
        prefs(context).edit()
                .putString(KEY_DATE, dateKey == null ? "" : dateKey)
                .putString(KEY_LAST, lastFeed == null ? "" : lastFeed)
                .putString(KEY_SLOTS, slotCount == null ? "4" : slotCount)
                .putString(KEY_PREFIX, feedPrefix == null ? "" : feedPrefix)
                .apply();
    }

    public static String getDate(Context context) {
        return prefs(context).getString(KEY_DATE, "");
    }

    public static String getLast(Context context) {
        return prefs(context).getString(KEY_LAST, "");
    }

    public static String getFeedPrefix(Context context) {
        return prefs(context).getString(KEY_PREFIX, "");
    }
}
