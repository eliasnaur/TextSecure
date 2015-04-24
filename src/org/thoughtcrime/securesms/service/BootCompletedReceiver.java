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
        Intent service = new Intent(MessageRetrievalService.ACTION_KEEPALIVE, null, context, MessageRetrievalService.class);
        WakefulBroadcastReceiver.startWakefulService(context, service);
		ApplicationContext.getInstance(context).getJobManager().add(new Job(JobParameters.newBuilder()
					.withWakeLock(true)
					.create()) {
			@Override public void onRun() throws Exception {
				DatabaseFactory.getThreadDatabase(context).deleteAllConversations();
				Log.w("BootCompletedReceiver", "All converstations deleted");
			}
			@Override public void onCanceled() {}
			@Override public void onAdded() {}
			@Override public boolean onShouldRetry(Exception e) {
				return false;
			}
		});
    }
}
