package com.apexcsfc.takassist.maps;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.apexcsfc.takassist.maps.core.MapPacksView;
import com.apexcsfc.takassist.maps.core.States;

import java.util.List;

/**
 * TAK Assist Maps: puts offline map areas on the phone for the TAK Assist ATAK plugin.
 * Managed (MDM) phones get their areas automatically; the screen shows progress.
 * Unmanaged phones: tick states, Apply.
 */
public class MainActivity extends Activity {

    private MapPacksView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) {
            view.shutdown();
            view = null;
        }
        if (!Sync.canWrite()) {
            showPermission();
            return;
        }
        final Sync.Policy p = Sync.policy(this);
        Sync.schedule(this);
        // Packs pushed into Download/ by the MDM: install them now (quick: just unzipping).
        new Thread(new Runnable() {
            @Override
            public void run() {
                com.apexcsfc.takassist.maps.core.PackFiles.importAll(null);
            }
        }, "takassist-import").start();
        // Managed phone, opened by hand: start right away rather than waiting for the job.
        List<String> pend = Sync.pending(this);
        if (pend != null && !MapsService.running) MapsService.start(this, pend);
        // Test builds only: adb shell am start -n com.apexcsfc.takassist.maps/.MainActivity --es ids a,b
        String testIds = getIntent().getStringExtra("ids");
        if (testIds != null && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && !MapsService.running) {
            getIntent().removeExtra("ids");
            List<String> l = new java.util.ArrayList<>();
            for (String x : testIds.split(",")) if (!x.trim().isEmpty()) l.add(States.idFor(x));
            MapsService.start(this, l);
        }

        view = new MapPacksView(this, this, new MapPacksView.Hooks() {
            @Override
            public void apply(List<String> ids) {
                MapsService.start(MainActivity.this, ids);
            }

            @Override
            public void cancel() {
                MapsService.cancel();
            }

            @Override
            public Object[] status() {
                return MapsService.status();
            }

            @Override
            public String lockedNote() {
                if (!p.managed()) return null;
                StringBuilder sb = new StringBuilder("Set by your organization: ");
                for (int i = 0; i < p.regions.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(States.nameFor(p.regions.get(i)));
                }
                sb.append(". They install by themselves on Wi-Fi while charging.");
                return sb.toString();
            }
        });
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.parseColor("#1B1F24"));
        page.addView(view.getView(), new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(footer());
        setContentView(page);
    }

    @Override
    protected void onDestroy() {
        if (view != null) view.shutdown();
        super.onDestroy();
    }

    private void showPermission() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(Color.parseColor("#1B1F24"));
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        l.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setText("One-time setup\n\nTAK Assist Maps saves map areas in the atak folder, where TAK Assist "
                + "reads them. Android needs you to allow that once.\n\nTap Allow, turn on "
                + "\"Allow access to manage all files\", then come back.");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        l.addView(t, new LinearLayout.LayoutParams(-1, 0, 1));
        Button b = new Button(this);
        b.setText("Allow");
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        });
        l.addView(b, new LinearLayout.LayoutParams(-1, -2));
        l.addView(footer());
        setContentView(l);
    }

    private LinearLayout footer() {
        float d = getResources().getDisplayMetrics().density;
        int h = (int) (40 * d);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        row.setBackgroundColor(Color.BLACK);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        row.setContentDescription("TAK Assist Maps by ApexCSFC");

        TextView by = new TextView(this);
        by.setText("TAK Assist Maps by");
        by.setTextColor(Color.WHITE);
        by.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        by.setPadding(0, 0, (int) (8 * d), 0);
        row.addView(by, new LinearLayout.LayoutParams(-2, -2));

        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.apexcsfc_logo);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = h / 7;
        row.addView(iv, new LinearLayout.LayoutParams(-2, h - 2 * p));
        return row;
    }
}
