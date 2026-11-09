package com.murcalendar.feeding;

import android.content.Context;
import android.webkit.JavascriptInterface;

/** JS bridge: window.MurCalendarAndroid.onFeedingsChanged(dateKey, lastHhmm, slotCount[, feedPrefix]). */
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
}
