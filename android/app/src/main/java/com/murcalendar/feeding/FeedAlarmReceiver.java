package com.murcalendar.feeding;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires scheduled feed reminder / hungry / morning notifications.
 * Re-arms morning alarm after each morning fire.
 */
public class FeedAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        String names = FeedReminderScheduler.names(context);
        int count = FeedReminderScheduler.petCount(context);
        String hungryVerb = count > 1 ? "проголодались" : "проголодался";

        if (FeedReminderScheduler.ACTION_REMINDER.equals(action)) {
            FeedNotifier.show(
                    context,
                    FeedNotifier.ID_REMINDER,
                    "Мур-календарь",
                    "Пора покормить " + names + "!"
            );
        } else if (FeedReminderScheduler.ACTION_HUNGRY.equals(action)) {
            FeedNotifier.show(
                    context,
                    FeedNotifier.ID_HUNGRY,
                    "Мур-календарь",
                    names + " совсем " + hungryVerb + "!"
            );
        } else if (FeedReminderScheduler.ACTION_MORNING.equals(action)) {
            FeedNotifier.show(
                    context,
                    FeedNotifier.ID_MORNING,
                    "Мур-календарь",
                    "Доброе утро! Не забудьте покормить " + names + "."
            );
            /* Re-arm next morning. */
            FeedReminderScheduler.rescheduleAll(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            FeedReminderScheduler.rescheduleAll(context);
        }
    }
}
