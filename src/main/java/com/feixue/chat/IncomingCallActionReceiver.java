package com.feixue.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/** Handles accept/reject actions from the background incoming-call notification. */
public final class IncomingCallActionReceiver extends BroadcastReceiver {
    public static final String ACTION_ACCEPT = "com.feixue.chat.ACTION_ACCEPT_VOICE";
    public static final String ACTION_REJECT = "com.feixue.chat.ACTION_REJECT_VOICE";

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, ChatService.class)
                .setAction(intent.getAction())
                .putExtra(ChatService.EXTRA_PEER_NAME, intent.getStringExtra(ChatService.EXTRA_PEER_NAME))
                .putExtra(ChatService.EXTRA_PEER_PASSWORD, intent.getStringExtra(ChatService.EXTRA_PEER_PASSWORD));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
