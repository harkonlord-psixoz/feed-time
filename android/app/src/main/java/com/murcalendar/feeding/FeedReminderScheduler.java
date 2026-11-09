package com.murcalendar.feeding;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * Schedules local feed reminders with AlarmManager.
 * Quiet hours 23:00–06:00: fire times are pushed to 06:15.
 * Reminder ≈ 3.5h after last feed; hungry at 5h; morning 06:15 daily.
 * Interval settings (3–4h) — future product setting; see docs.
 */
public final class FeedReminderScheduler {
    public static final String PREFS = "mur_calendar_notify";
    public static final String KEY_LAST_MS = "last_feed_ms";
    public static final String KEY_NAMES = "pet_names";
    public static final String KEY_COUNT = "pet_count";

    public static final String ACTION_REMINDER = "com.murcalendar.feeding.ACTION_REMINDER";
    public static final String ACTION_HUNGRY = "com.murcalendar.feeding.ACTION_HUNGRY";
    public static final String ACTION_MORNING = "com.murcalendar.feeding.ACTION_MORNING";

    public static final long REMINDER_MS = (long) (3.5 * 60 * 60 * 1000);
    public static final long HUNGRY_MS = 5L * 60 * 60 * 1000;

    private static final int REQ_REMINDER = 5201;
    private static final int REQ_HUNGRY = 5202;
    private static final int REQ_MORNING = 5203;

    private FeedReminderScheduler() {}

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveState(Context context, long lastFeedMs, String names, String count) {
        prefs(context).edit()
                .putLong(KEY_LAST_MS, lastFeedMs)
                .putString(KEY_NAMES, names == null ? "" : names)
                .putString(KEY_COUNT, count == null ? "1" : count)
                .apply();
    }

    public static String names(Context context) {
        String n = prefs(context).getString(KEY_NAMES, "");
        return (n == null || n.trim().isEmpty()) ? "питомцев" : n.trim();
    }

    public static int petCount(Context context) {
        try {
            return Math.max(1, Integer.parseInt(prefs(context).getString(KEY_COUNT, "1")));
        } catch (Exception e) {
            return 1;
        }
    }

    public static void rescheduleAll(Context context) {
        FeedNotifier.ensureChannel(context);
        long last = prefs(context).getLong(KEY_LAST_MS, 0L);
        long now = System.currentTimeMillis();
        long base = last > 0 ? last : now;

        scheduleAt(context, ACTION_REMINDER, REQ_REMINDER, adjustQuiet(base + REMINDER_MS));
        scheduleAt(context, ACTION_HUNGRY, REQ_HUNGRY, adjustQuiet(base + HUNGRY_MS));
        scheduleAt(context, ACTION_MORNING, REQ_MORNING, nextMorningMillis(now));
    }

    public static void cancelAll(Context context) {
        cancel(context, ACTION_REMINDER, REQ_REMINDER);
        cancel(context, ACTION_HUNGRY, REQ_HUNGRY);
        cancel(context, ACTION_MORNING, REQ_MORNING);
    }

    /** Push into quiet window → next 06:15. Quiet: 23:00–06:00. */
    public static long adjustQuiet(long atMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(atMs);
        int h = c.get(Calendar.HOUR_OF_DAY);
        int min = c.get(Calendar.MINUTE);
        boolean quiet = h >= 23 || h < 6 || (h == 6 && min < 0);
        if (!quiet) return atMs;
        c.set(Calendar.HOUR_OF_DAY, 6);
        c.set(Calendar.MINUTE, 15);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        /* If original was after 23:00 today, morning is tomorrow — already handled if past. */
        if (h >= 23) {
            Calendar morn = Calendar.getInstance();
            morn.setTimeInMillis(atMs);
            morn.add(Calendar.DAY_OF_YEAR, 1);
            morn.set(Calendar.HOUR_OF_DAY, 6);
            morn.set(Calendar.MINUTE, 15);
            morn.set(Calendar.SECOND, 0);
            morn.set(Calendar.MILLISECOND, 0);
            return morn.getTimeInMillis();
        }
        return c.getTimeInMillis();
    }

    public static long nextMorningMillis(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, 6);
        c.set(Calendar.MINUTE, 15);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMs) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private static void scheduleAt(Context context, String action, int reqCode, long atMs) {
        long when = Math.max(atMs, System.currentTimeMillis() + 5_000L);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(context, action, reqCode);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (SecurityException se) {
            /* Exact alarms may be restricted; fall back to inexact. */
            am.set(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    private static void cancel(Context context, String action, int reqCode) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(context, action, reqCode));
    }

    private static PendingIntent pending(Context context, String action, int reqCode) {
        Intent i = new Intent(context, FeedAlarmReceiver.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, reqCode, i, flags);
    }
}
