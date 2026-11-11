package com.apexcsfc.takassist.maps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Phone restart, app update, or TAK Assist starting: schedule a sync. */
public class SyncReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Sync.schedule(c);
    }
}
