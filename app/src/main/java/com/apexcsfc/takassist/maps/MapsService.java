package com.apexcsfc.takassist.maps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.apexcsfc.takassist.maps.core.Installer;
import com.apexcsfc.takassist.maps.core.MapPackManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the download-and-build in the foreground (with a notification), so it isn't cut
 * short when the screen turns off. One run at a time; its status is read by the screen.
 */
public class MapsService extends Service {

    static final String EXTRA_IDS = "ids";
    private static final String CH = "maps";
    private static final int NOTE = 1;

    // Status shared with the screen (same process).
    static volatile boolean running;
    static volatile String text = "";
    static volatile double fraction = -1;
    private static final Installer[] HANDLE = new Installer[1];

    static void start(Context c, List<String> ids) {
        Intent i = new Intent(c, MapsService.class).putStringArrayListExtra(EXTRA_IDS, new ArrayList<>(ids));
        c.startForegroundService(i);
    }

    static void cancel() {
        Installer in = HANDLE[0];
        if (in != null) in.cancel();
    }

    static Object[] status() {
        return new Object[]{running, text, fraction};
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final ArrayList<String> ids = intent == null ? null : intent.getStringArrayListExtra(EXTRA_IDS);
        channel();
        Notification n = note("Preparing offline maps…", -1);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTE, n);
        }
        if (ids == null || ids.isEmpty() || running) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        text = "Starting…";
        fraction = -1;
        new Thread(new Runnable() {
            @Override
            public void run() {
                PowerManager.WakeLock wl = ((PowerManager) getSystemService(POWER_SERVICE))
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "takassist:maps");
                wl.acquire(4 * 60 * 60 * 1000L);
                String result;
                try {
                    result = Sync.run(MapsService.this, ids, new MapPackManager.Listener() {
                        long last;

                        @Override
                        public void onStatus(String t, double f) {
                            text = t;
                            fraction = f;
                            long now = System.currentTimeMillis();
                            if (now - last > 1500) {
                                last = now;
                                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTE, note(t, f));
                            }
                        }
                    }, HANDLE);
                } catch (Exception e) {
                    Log.w(Sync.TAG, "maps failed", e);
                    result = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    HANDLE[0] = null;
                    wl.release();
                }
                text = result;
                fraction = -1;
                running = false;
                done(result);
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }
        }, "takassist-maps").start();
        return START_NOT_STICKY;
    }

    private void done(String result) {
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("TAK Assist offline maps")
                .setContentText(result)
                .setStyle(new Notification.BigTextStyle().bigText(result))
                .setContentIntent(open())
                .setAutoCancel(true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTE, b.build());
    }

    private Notification note(String t, double f) {
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("TAK Assist offline maps")
                .setContentText(t)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open());
        if (f >= 0) b.setProgress(1000, (int) (f * 1000), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private PendingIntent open() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void channel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Offline maps", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
