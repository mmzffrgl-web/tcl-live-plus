package io.tcllive.overlay;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * TCL blocks BOOT_COMPLETED for sideloaded apps, so a persisted JobScheduler
 * job is used instead: the system restores it after every reboot without the
 * app being launched, and the 15 minute period also re-heals the accessibility
 * flag if the firmware clears it later.
 */
public class BootJobService extends JobService {

    private static final String TAG = "TCLP";
    private static final int JOB_ID = 4711;

    static void ensure(Context ctx) {
        try {
            JobScheduler js = (JobScheduler)
                    ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_ID) != null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, BootJobService.class))
                    .setPersisted(true)
                    .setPeriodic(15 * 60 * 1000L)
                    .build();
            int r = js.schedule(job);
            Log.i(TAG, "job schedule -> " + r);
        } catch (Throwable e) {
            Log.e(TAG, "job schedule failed: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "job run");
        BootReceiver.enable(this);
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
