package org.thoughtcrime.securesms.service;

import android.content.Intent;
import android.content.Context;

import android.support.v4.content.WakefulBroadcastReceiver;

public class KeepAliveReceiver extends WakefulBroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Intent service = new Intent(MessageRetrievalService.ACTION_KEEPALIVE, null, context, MessageRetrievalService.class);
        WakefulBroadcastReceiver.startWakefulService(context, service);
    }
}
