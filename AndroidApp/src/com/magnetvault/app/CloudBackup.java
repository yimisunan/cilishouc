package com.magnetvault.app;

import android.content.Context;

/**
 * 云端备份的实际动作（提取出来给 CloudActivity 和定时任务 BackupJob 共用）。
 * 不含任何 UI，异常都收敛成 Result.error。
 */
public final class CloudBackup {

    /** 备份密码在本机 SecretStore 里的 key（用 Keystore 加密保存，不可导出）。 */
    public static final String PWD_KEY = "backupPwd";
    public static final int TIMEOUT_MS = 30000;

    private CloudBackup() {
    }

    public static class Result {
        public String error;
        public String name;
        public int bytes;
    }

    public static WebDavClient client(Context ctx) {
        String pwd = SecretStore.get(ctx, ThemePrefs.SECRET_DAV_PASSWORD);
        return new WebDavClient(ThemePrefs.davUrl(ctx), ThemePrefs.davUser(ctx),
                pwd == null ? "" : pwd, TIMEOUT_MS);
    }

    /** 加密 → 上传 → 回读校验。返回 name/bytes 或 error。 */
    public static Result upload(Context ctx, char[] password) {
        Result r = new Result();
        try {
            WebDavClient c = client(ctx);
            String dir = ThemePrefs.davDir(ctx);
            c.ensureCollection(dir);

            byte[] data = BackupManager.buildEncrypted(ctx, password);
            r.bytes = data.length;

            String base = BackupManager.defaultFileName();
            String candidate = base;
            for (int i = 2; i <= 20; i++) {
                if (!c.exists(dir, candidate)) {
                    break;
                }
                // 坚果云不支持 If-None-Match，只能自己换名字
                candidate = base.substring(0, base.length() - BackupManager.EXT.length())
                        + "-" + i + BackupManager.EXT;
            }
            r.name = candidate;

            c.put(dir, candidate, data);

            long remote = -1;
            for (WebDavClient.RemoteFile f : c.list(dir)) {
                if (candidate.equals(f.name)) {
                    remote = f.size;
                }
            }
            if (remote != data.length) {
                r.error = "上传后校验失败：服务端 " + remote + " 字节，本地 " + data.length + " 字节";
            }
        } catch (WebDavClient.DavException e) {
            r.error = e.userMessage;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage());
        }
        if (r.error == null) {
            ThemePrefs.setLastBackupAt(ctx, System.currentTimeMillis());
            Repo.addAudit(ctx, "UPLOAD", "SUCCESS", r.name + " · " + r.bytes + " B",
                    Repo.countActive(ctx) + Repo.countTrash(ctx));
        } else {
            Repo.addAudit(ctx, "UPLOAD", "FAILED", r.error, 0);
        }
        return r;
    }
}