package com.murcalendar.feeding;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires scheduled feed reminder / hungry / morning notifications.
 * Bodies are pre-declined phrases stored from JS.
 */
public class FeedAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (FeedReminderScheduler.ACTION_REMINDER.equals(action)) {
            FeedNotifier.show(
                    context,
                    FeedNotifier.ID_REMINDER,
                    "Мур-календарь",
                    FeedReminderScheduler.reminderBody(context)
            );
        } else if (FeedReminderScheduler.ACTION_HUNGRY.equals(action)) {
            FeedNotifier.show(
                    context,
                    FeedNotifier.ID_HUNGRY,
                    "Мур-календарь",
                    FeedReminderScheduler.hungryBody(context)
            );
        } else if (FeedReminderScheduler.ACTION_MORNING.equals(action)) {
            FeedNotifier.show(
                    context,
                    FeedNotifier.ID_MORNING,
                    "Мур-календарь",
                    FeedReminderScheduler.morningBody(context)
            );
            FeedReminderScheduler.rescheduleAll(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            FeedReminderScheduler.rescheduleAll(context);
        }
    }
}
