package com.murcalendar.feeding;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Local notifications via NotificationManager (no FCM).
 * Bodies arrive already declined from JS — do not reassemble cases here.
 */
public final class FeedNotifier {
    public static final String CHANNEL_ID = "mur_feeding";
    public static final String CHANNEL_NAME = "Кормление";

    public static final int ID_JUST_FED = 4101;
    public static final int ID_REMINDER = 4102;
    public static final int ID_HUNGRY = 4103;
    public static final int ID_MORNING = 4104;
    /** Family sync: someone else fed the pets (WS wake / future FCM). */
    public static final int ID_FAMILY_FED = 4105;

    private FeedNotifier() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
        );
        ch.setDescription("Напоминания о кормлении питомцев");
        nm.createNotificationChannel(ch);
    }

    public static void show(Context context, int id, String title, String body) {
        ensureChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(context, id, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(context, CHANNEL_ID);
        } else {
            b = new Notification.Builder(context);
        }
        String safeBody = body == null ? "" : body;
        b.setContentTitle(title == null ? "Feed Time" : title)
                .setContentText(safeBody)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setStyle(new Notification.BigTextStyle().bigText(safeBody));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }
        nm.notify(id, b.build());
    }

    /** @param body full already-declined phrase from JS */
    public static void justFed(Context context, String body) {
        String text = (body == null || body.trim().isEmpty())
                ? "Питомца покормили только что"
                : body.trim();
        /* Unique id so undo → feed again re-alerts instead of silently replacing. */
        int id = ID_JUST_FED + (int) (System.currentTimeMillis() & 0xffff);
        show(context, id, "Feed Time", text);
    }

    /** Cross-device: another household member recorded a feeding. */
    public static void familyFed(Context context, String body) {
        String text = (body == null || body.trim().isEmpty())
                ? "В семье отметили кормление"
                : body.trim();
        int id = ID_FAMILY_FED + (int) (System.currentTimeMillis() & 0xffff);
        show(context, id, "Feed Time · семья", text);
    }
}
