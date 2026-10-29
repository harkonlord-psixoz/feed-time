package com.murcalendar.feeding;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class FeedingAppWidget extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.murcalendar.feeding.WIDGET_REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateOne(context, appWidgetManager, id);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            requestUpdate(context);
        }
    }

    public static void requestUpdate(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, FeedingAppWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;
        Intent intent = new Intent(context, FeedingAppWidget.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        context.sendBroadcast(intent);
    }

    static void updateOne(Context context, AppWidgetManager mgr, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_feeding);

        Calendar cal = Calendar.getInstance();
        Locale ru = new Locale("ru", "RU");
        String dayNum = new SimpleDateFormat("d", ru).format(cal.getTime());
        String monthName = new SimpleDateFormat("MMMM", ru).format(cal.getTime());
        String weekday = new SimpleDateFormat("EEEE", ru).format(cal.getTime());
        // Capitalize weekday/month for nicer Russian display
        weekday = capitalize(weekday);
        monthName = capitalize(monthName);

        views.setTextViewText(R.id.widget_brand, context.getString(R.string.app_name));
        views.setTextViewText(R.id.widget_date_line, weekday + " · " + dayNum + " " + monthName);

        String storedDate = FeedPrefs.getDate(context);
        String todayKey = String.format(
                Locale.US,
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
        );
        String last = FeedPrefs.getLast(context);
        String feedLine;
        if (last != null && !last.isEmpty() && todayKey.equals(storedDate)) {
            String prefix = FeedPrefs.getFeedPrefix(context);
            if (prefix != null && !prefix.isEmpty()) {
                feedLine = prefix + last;
            } else {
                feedLine = context.getString(R.string.widget_fed_at, last);
            }
        } else {
            feedLine = context.getString(R.string.widget_not_fed);
        }
        views.setTextViewText(R.id.widget_feed_line, feedLine);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, openPi);

        Intent refresh = new Intent(context, FeedingAppWidget.class);
        refresh.setAction(ACTION_REFRESH);
        PendingIntent refreshPi = PendingIntent.getBroadcast(
                context,
                1,
                refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPi);

        mgr.updateAppWidget(appWidgetId, views);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
