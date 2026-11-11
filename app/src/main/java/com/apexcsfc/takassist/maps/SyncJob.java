package com.apexcsfc.takassist.maps;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import com.apexcsfc.takassist.maps.core.PackFiles;

import java.util.List;

/**
 * Background check (Wi-Fi + charging): installs map packs dropped into Download/ by the MDM,
 * then, if the administrator's areas still aren't on the phone, builds them from Geofabrik in
 * the foreground service. If Android doesn't allow starting that from the background, the
 * work runs here instead (a partly done download resumes on the next run).
 */
public class SyncJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean retry = false;
                try {
                    if (Sync.canWrite()) PackFiles.importAll(null);
                    List<String> ids = Sync.pending(getApplicationContext());
                    if (ids != null && !MapsService.running) {
                        try {
                            MapsService.start(getApplicationContext(), ids);
                        } catch (Exception notAllowed) {
                            Log.i(Sync.TAG, "running maps in the job: " + notAllowed);
                            Sync.run(getApplicationContext(), ids, null, null);
                        }
                    }
                } catch (Exception e) {
                    Log.w(Sync.TAG, "maps job failed", e);
                    retry = true;
                }
                jobFinished(params, retry);
            }
        }, "takassist-maps-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
