package com.example.zebra_rfid_sdk_plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IntentFilter;
import android.os.Build;

public class CompatibleContext extends ContextWrapper {
    public CompatibleContext(Context base) {
        super(base);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) requires RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
            return registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            return super.registerReceiver(receiver, filter);
        }
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Ensure the flag is set for Android 13+
            if ((flags & (Context.RECEIVER_EXPORTED | Context.RECEIVER_NOT_EXPORTED)) == 0) {
                flags |= Context.RECEIVER_NOT_EXPORTED;
            }
        }
        return super.registerReceiver(receiver, filter, flags);
    }
}