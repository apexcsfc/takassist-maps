package com.apexcsfc.takassist.maps;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.RestrictionsManager;
import android.os.Bundle;
import android.os.Environment;

import com.apexcsfc.takassist.maps.core.Installer;
import com.apexcsfc.takassist.maps.core.MapPackManager;
import com.apexcsfc.takassist.maps.core.MapPacks;
import com.apexcsfc.takassist.maps.core.Region;
import com.apexcsfc.takassist.maps.core.States;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The work itself: build any missing areas, then make the chosen set the active map.
 * The areas come from the administrator's managed settings (MDM) when there are any,
 * otherwise from what the user ticked.
 */
final class Sync {

    static final String TAG = "TAKAssistMaps";
    private static final int JOB_NOW = 1, JOB_PERIODIC = 2;

    /** The administrator's managed settings. */
    static final class Policy {
        List<String> regions = new ArrayList<>();
        boolean autoApply = true, wifiOnly = true;
        int updateDays = 0;
        String mirror = "";

        boolean managed() {
            return !regions.isEmpty();
        }
    }

    static Policy policy(Context c) {
        Policy p = new Policy();
        RestrictionsManager rm = (RestrictionsManager) c.getSystemService(Context.RESTRICTIONS_SERVICE);
        Bundle b = rm == null ? null : rm.getApplicationRestrictions();
        if (b == null) return p;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String s : b.getString("regions", "").split("[,;\\n]")) {
            if (!s.trim().isEmpty()) ids.add(States.idFor(s));
        }
        p.regions = new ArrayList<>(ids);
        p.autoApply = b.getBoolean("auto_apply", true);
        p.wifiOnly = b.getBoolean("wifi_only", true);
        p.updateDays = Math.max(0, b.getInt("update_days", 0));
        p.mirror = b.getString("mirror_url", "").trim();
        return p;
    }

    static boolean canWrite() {
        return Environment.isExternalStorageManager();
    }

    /** Ask for a run as soon as Wi-Fi (or any network) and power allow, plus a daily check. */
    static void schedule(Context c) {
        Policy p = policy(c);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        ComponentName svc = new ComponentName(c, SyncJob.class);
        int net = p.wifiOnly ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;
        js.schedule(new JobInfo.Builder(JOB_NOW, svc).setRequiredNetworkType(net).setRequiresCharging(true).build());
        js.schedule(new JobInfo.Builder(JOB_PERIODIC, svc).setRequiredNetworkType(net).setRequiresCharging(true)
                .setPeriodic(TimeUnit.HOURS.toMillis(24)).setPersisted(true).build());
    }

    /** What the administrator wants that isn't done yet, or null when up to date. */
    static List<String> pending(Context c) {
        Policy p = policy(c);
        if (!p.managed() || !p.autoApply) return null;
        for (String id : p.regions) {
            if (!MapPacks.isInstalled(id) || stale(id, p.updateDays)) return p.regions;
        }
        if (!new LinkedHashSet<>(MapPacks.activeIds()).equals(new LinkedHashSet<>(p.regions))) return p.regions;
        return null;
    }

    private static boolean stale(String id, int days) {
        if (days <= 0) return false;
        for (Region r : MapPacks.installed()) {
            if (!r.id.equals(id)) continue;
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(r.built);
                return d != null && System.currentTimeMillis() - d.getTime() > TimeUnit.DAYS.toMillis(days);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /** Blocking. Builds what's missing (or stale), then activates. Returns a line for the UI. */
    static String run(Context c, List<String> ids, MapPackManager.Listener l, Installer[] handle) throws Exception {
        if (!canWrite()) throw new Exception("Needs \"All files access\" before it can save maps. Open TAK Assist Maps once.");
        com.apexcsfc.takassist.maps.core.PackFiles.importAll(l);
        Policy p = policy(c);
        Installer inst = new Installer(c, p.mirror, l);
        if (handle != null) handle[0] = inst;
        for (String id : ids) {
            if (!MapPacks.isInstalled(id) || stale(id, p.updateDays)) inst.install(id);
        }
        new MapPackManager(l).activate(ids);
        new File(c.getCacheDir(), "maps").delete();
        return ids.size() == 1 ? "Done. TAK Assist is using " + States.nameFor(ids.get(0)) + "."
                : "Done. TAK Assist is using " + ids.size() + " areas, joined together.";
    }
}
