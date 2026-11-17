package com.magnetvault.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 解密 + 恢复的完整交互流程（云端下载的和本地文件选中的都走这里）： 读 header → 取密码 → 解密 → 摘要确认 →（合并 | 完整替换）→ 快照 → 恢复 → 结果统计。
 * 另外提供「回滚到最近快照」。
 */
public final class RestoreFlow {

    private static final String PWD_KEY = "backupPwd";

    private RestoreFlow() {}

    public interface Done {
        void onDone();
    }

    // ==================== 入口：一个加密容器 ====================

    public static void start(final Activity host, final byte[] container, final Done done) {
        BackupCrypto.Result header = BackupCrypto.readHeader(container);
        if (header.error != BackupCrypto.Error.NONE) {
            alert(host, "无法读取备份", header.message, done);
            return;
        }
        String info =
                "备份时间："
                        + Ui.time(header.createdAt)
                        + "\n应用版本："
                        + header.appVersion
                        + "\n文件大小："
                        + container.length
                        + " 字节";

        askPassword(
                host,
                "解密备份",
                info,
                new PasswordCallback() {
                    @Override
                    public void onPassword(char[] password) {
                        decryptAndConfirm(host, container, password, done);
                    }
                });
    }

    // ==================== 解密 ====================

    private static void decryptAndConfirm(
            final Activity host, final byte[] container, final char[] password, final Done done) {
        final AlertDialog busy = busy(host, "正在解密…");
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                final BackupCrypto.Result result =
                                        BackupCrypto.decrypt(password, container);
                                host.runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                dismiss(busy);
                                                if (host.isFinishing() || host.isDestroyed())
                                                    return;
                                                if (result.error != BackupCrypto.Error.NONE) {
                                                    Repo.addAudit(
                                                            host,
                                                            "DECRYPT",
                                                            result.error.name(),
                                                            result.message,
                                                            0);
                                                    alert(host, "解密失败", result.message, done);
                                                    return;
                                                }
                                                String json =
                                                        new String(
                                                                result.payload,
                                                                StandardCharsets.UTF_8);
                                                try {
                                                    BackupManager.Summary s =
                                                            BackupManager.summarize(json);
                                                    chooseMode(host, json, password, s, done);
                                                } catch (Exception e) {
                                                    Repo.addAudit(
                                                            host,
                                                            "DECRYPT",
                                                            "FAILED",
                                                            "payload 不是合法 JSON",
                                                            0);
                                                    alert(
                                                            host,
                                                            "解密失败",
                                                            "备份内容不是有效 JSON，可能已损坏。",
                                                            done);
                                                }
                                            }
                                        });
                            }
                        })
                .start();
    }

    // ==================== 摘要 + 选择恢复方式 ====================

    private static void chooseMode(
            final Activity host,
            final String json,
            final char[] password,
            BackupManager.Summary summary,
            final Done done) {
        new AlertDialog.Builder(host)
                .setTitle("确认恢复")
                .setMessage(
                        summary.describe()
                                + "\n\n当前本地："
                                + Repo.countActive(host)
                                + " 条（回收站 "
                                + Repo.countTrash(host)
                                + " 条）"
                                + "\n\n两种方式的区别：\n"
                                + "· 合并：备份中更新的条目覆盖本地，本地更新的保留，不会删东西\n"
                                + "· 完整替换：清空本地全部条目后按备份重建（分类与标签字典保留）")
                .setPositiveButton(
                        "合并到现有数据",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                doRestore(host, json, password, false, done);
                            }
                        })
                .setNeutralButton(
                        "完整替换",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                confirmReplace(host, json, password, done);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void confirmReplace(
            final Activity host, final String json, final char[] password, final Done done) {
        new AlertDialog.Builder(host)
                .setTitle("确认完整替换？")
                .setMessage(
                        "将删除本机全部收藏条目（分类与标签字典保留），再用备份重建。\n\n" + "恢复前会自动写一份加密快照，失败或后悔时可从「回滚到最近快照」还原。")
                .setPositiveButton(
                        "替换",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                doRestore(host, json, password, true, done);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 执行恢复 ====================

    private static void doRestore(
            final Activity host,
            final String json,
            final char[] password,
            final boolean replace,
            final Done done) {
        final AlertDialog busy = busy(host, replace ? "正在替换…" : "正在合并…");
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                String error = null;
                                BackupManager.RestoreResult rr = null;
                                File snapshot = null;
                                try {
                                    snapshot = BackupManager.saveSnapshot(host, password);
                                    rr = BackupManager.restore(host, json, replace);
                                } catch (Exception e) {
                                    error =
                                            e.getClass().getSimpleName()
                                                    + (e.getMessage() == null
                                                            ? ""
                                                            : "：" + e.getMessage());
                                }
                                final String err = error;
                                final BackupManager.RestoreResult result = rr;
                                final File snap = snapshot;

                                host.runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                dismiss(busy);
                                                if (host.isFinishing() || host.isDestroyed())
                                                    return;
                                                if (err != null) {
                                                    Repo.addAudit(
                                                            host, "RESTORE", "FAILED", err, 0);
                                                    alert(
                                                            host,
                                                            "恢复失败",
                                                            "本次数据库操作已撤销，本机收藏已保留。\n\n错误："
                                                                    + err
                                                                    + (snap == null
                                                                            ? ""
                                                                            : "\n\n恢复前快照已写入：\n"
                                                                                    + snap.getName()
                                                                                    + "\n"
                                                                                    + "可用「回滚到最近快照」还原。"),
                                                            done);
                                                    return;
                                                }
                                                Repo.addAudit(
                                                        host,
                                                        replace
                                                                ? "RESTORE_REPLACE"
                                                                : "RESTORE_MERGE",
                                                        "SUCCESS",
                                                        "",
                                                        result.added + result.updated);
                                                new AlertDialog.Builder(host)
                                                        .setTitle("恢复完成")
                                                        .setMessage(
                                                                result.describe()
                                                                        + "\n\n当前本地："
                                                                        + Repo.countActive(host)
                                                                        + " 条（回收站 "
                                                                        + Repo.countTrash(host)
                                                                        + " 条）")
                                                        .setPositiveButton(
                                                                "知道了",
                                                                new DialogInterface
                                                                        .OnClickListener() {
                                                                    @Override
                                                                    public void onClick(
                                                                            DialogInterface d,
                                                                            int which) {
                                                                        if (done != null) {
                                                                            done.onDone();
                                                                        }
                                                                    }
                                                                })
                                                        .show();
                                            }
                                        });
                            }
                        })
                .start();
    }

    // ==================== 回滚 ====================

    public static void rollbackLatestSnapshot(final Activity host, final Done done) {
        final File snap = BackupManager.latestSnapshot(host);
        if (snap == null) {
            alert(host, "没有快照", "本机还没有任何恢复前快照。", done);
            return;
        }
        askPassword(
                host,
                "回滚到恢复前快照",
                "快照文件：" + snap.getName() + "\n时间：" + Ui.time(snap.lastModified()),
                new PasswordCallback() {
                    @Override
                    public void onPassword(char[] password) {
                        doRollback(host, snap, password, done);
                    }
                });
    }

    private static void doRollback(
            final Activity host, final File snap, final char[] password, final Done done) {
        final AlertDialog busy = busy(host, "正在回滚…");
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                String error = null;
                                BackupManager.RestoreResult rr = null;
                                try {
                                    byte[] data;
                                    try (java.io.InputStream in =
                                            new java.io.FileInputStream(snap)) {
                                        data = BackupManager.readAll(in);
                                    }
                                    BackupCrypto.Result r = BackupCrypto.decrypt(password, data);
                                    if (r.error != BackupCrypto.Error.NONE) {
                                        throw new IllegalStateException(r.message);
                                    }
                                    String json = new String(r.payload, StandardCharsets.UTF_8);
                                    rr = BackupManager.restore(host, json, true);
                                } catch (Exception e) {
                                    error =
                                            e.getMessage() == null
                                                    ? e.getClass().getSimpleName()
                                                    : e.getMessage();
                                }
                                final String err = error;
                                final BackupManager.RestoreResult result = rr;
                                host.runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                dismiss(busy);
                                                if (host.isFinishing() || host.isDestroyed())
                                                    return;
                                                if (err != null) {
                                                    Repo.addAudit(
                                                            host, "ROLLBACK", "FAILED", err, 0);
                                                    alert(host, "回滚失败", err, done);
                                                    return;
                                                }
                                                Repo.addAudit(
                                                        host,
                                                        "ROLLBACK",
                                                        "SUCCESS",
                                                        snap.getName(),
                                                        result.added);
                                                new AlertDialog.Builder(host)
                                                        .setTitle("回滚完成")
                                                        .setMessage(
                                                                "已用快照重建："
                                                                        + result.added
                                                                        + " 条"
                                                                        + "\n\n当前本地："
                                                                        + Repo.countActive(host)
                                                                        + " 条")
                                                        .setPositiveButton(
                                                                "知道了",
                                                                new DialogInterface
                                                                        .OnClickListener() {
                                                                    @Override
                                                                    public void onClick(
                                                                            DialogInterface d,
                                                                            int which) {
                                                                        if (done != null) {
                                                                            done.onDone();
                                                                        }
                                                                    }
                                                                })
                                                        .show();
                                            }
                                        });
                            }
                        })
                .start();
    }

    // ==================== 密码输入 ====================

    private interface PasswordCallback {
        void onPassword(char[] password);
    }

    private static void askPassword(
            final Activity host, final String title, final String info, final PasswordCallback cb) {
        String remembered = SecretStore.get(host, PWD_KEY);
        if (remembered != null && remembered.length() > 0) {
            cb.onPassword(remembered.toCharArray());
            return;
        }
        final EditText input = new EditText(host);
        input.setHint("备份密码");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        AlertDialog dialog =
                new AlertDialog.Builder(host)
                        .setTitle(title)
                        .setMessage(info)
                        .setView(input)
                        .setPositiveButton("解密", null)
                        .setNegativeButton("取消", null)
                        .create();
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(-1)
                                .setOnClickListener(
                                        v -> {
                                            String value = input.getText().toString();
                                            if (value.isEmpty()) {
                                                input.setError("请输入备份密码");
                                                return;
                                            }
                                            dialog.dismiss();
                                            cb.onPassword(value.toCharArray());
                                        }));
        dialog.show();
    }

    // ==================== 小工具 ====================

    private static AlertDialog busy(Activity host, String msg) {
        int orientation = host.getRequestedOrientation();
        host.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        AlertDialog d = new AlertDialog.Builder(host).setMessage(msg).setCancelable(false).create();
        d.setOnDismissListener(
                v -> {
                    if (!host.isFinishing() && !host.isDestroyed())
                        host.setRequestedOrientation(orientation);
                });
        d.show();
        return d;
    }

    private static void dismiss(AlertDialog d) {
        if (d != null) {
            try {
                d.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    private static void alert(final Activity host, String title, String msg, final Done done) {
        if (host.isFinishing() || host.isDestroyed()) return;
        new AlertDialog.Builder(host)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(
                        "知道了",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (done != null) {
                                    done.onDone();
                                }
                            }
                        })
                .show();
    }
}
