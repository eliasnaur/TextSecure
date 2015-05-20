package org.thoughtcrime.securesms.service;

import android.content.Intent;
import android.content.Context;
import android.util.Log;

import org.thoughtcrime.securesms.ApplicationContext;
import org.whispersystems.jobqueue.Job;
import org.whispersystems.jobqueue.JobParameters;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import android.support.v4.content.WakefulBroadcastReceiver;

public class BootCompletedReceiver extends WakefulBroadcastReceiver {
    @Override public void onReceive(final Context context, Intent intent) {
        Intent service = new Intent(MessageRetrievalService.ACTION_INIT, null, context, MessageRetrievalService.class);
        WakefulBroadcastReceiver.startWakefulService(context, service);
    }
}
