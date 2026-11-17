package com.magnetvault.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 每日自动备份（JobScheduler，系统 API，零依赖）。
 * 触发条件：有网 + 距离上次备份超过 20 小时；不满足就顺延，不做任何 UI 打扰。
 */
public class BackupJob extends JobService {

    public static final int JOB_ID = 1001;
    private static final long INTERVAL_MS = 24L * 60 * 60 * 1000;

    public static boolean enable(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return false;
        }
        try {
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, BackupJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .build();
            int r = js.schedule(job);
            if (r != JobScheduler.RESULT_SUCCESS) {
                return false;
            }
            ThemePrefs.setAutoBackup(ctx, true);
            return true;
        } catch (Throwable t) {
            // 缺 ACCESS_NETWORK_STATE / 厂商裁剪 JobScheduler 等
            ThemePrefs.setAutoBackup(ctx, false);
            return false;
        }
    }

    public static void disable(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            js.cancel(JOB_ID);
        }
        ThemePrefs.setAutoBackup(ctx, false);
    }

    public static boolean isScheduled(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        return js != null && js.getPendingJob(JOB_ID) != null;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String pwd = SecretStore.get(BackupJob.this, CloudBackup.PWD_KEY);
                    if (pwd == null || pwd.length() < 8) {
                        Repo.addAudit(BackupJob.this, "AUTO_BACKUP", "SKIPPED",
                                "本机没有记住备份密码，跳过（请在备份页手动备份一次并记住密码）", 0);
                    } else if (!ThemePrefs.isDavConfigured(BackupJob.this)) {
                        Repo.addAudit(BackupJob.this, "AUTO_BACKUP", "SKIPPED",
                                "坚果云未配置，跳过", 0);
                    } else {
                        CloudBackup.Result r = CloudBackup.upload(BackupJob.this, pwd.toCharArray());
                        if (r.error == null) {
                            Repo.addAudit(BackupJob.this, "AUTO_BACKUP", "SUCCESS",
                                    r.name + " · " + r.bytes + " B", 0);
                        }
                    }
                } catch (Throwable t) {
                    Repo.addAudit(BackupJob.this, "AUTO_BACKUP", "FAILED",
                            t.getClass().getSimpleName(), 0);
                } finally {
                    jobFinished(params, false);
                }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}