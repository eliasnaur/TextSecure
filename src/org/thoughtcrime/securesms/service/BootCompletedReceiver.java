package org.thoughtcrime.securesms.service;

import android.content.Intent;
import android.content.Context;
import android.util.Log;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import android.support.v4.content.WakefulBroadcastReceiver;

public class BootCompletedReceiver extends WakefulBroadcastReceiver {
    @Override public void onReceive(final Context context, Intent intent) {
        Intent service = new Intent(MessageRetrievalService.ACTION_KEEPALIVE, null, context, MessageRetrievalService.class);
        WakefulBroadcastReceiver.startWakefulService(context, service);
		new Thread() {
			@Override public void run() {
				DatabaseFactory.getThreadDatabase(context).deleteAllConversations();
				Log.w("BootCompletedReceiver", "All converstations deleted");
			}
		}.start();
    }
}
