package com.magnetvault.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 备份解密与恢复交互流（云端下载与本地 .mjbf 均共用）：
 * 读容器头 → 获取密码 → 后台解密 → 摘要对比（合并 / 完整替换）→ 自动快照 → 恢复 → 结果汇报。
 */
public final class RestoreFlow {

    private static final String PWD_KEY = CloudBackup.PWD_KEY;

    private RestoreFlow() {}

    public interface Done {
        void onDone();
    }

    // ==================== 入口：解析加密容器头 ====================

    public static void start(final Activity host, final byte[] container, final Done done) {
        BackupCrypto.Result header = BackupCrypto.readHeader(container);
        if (header.error != BackupCrypto.Error.NONE) {
            alert(host, "无法读取备份", header.message, true, done);
            return;
        }
        String info =
                "备份时间："
                        + Ui.time(header.createdAt)
                        + "\n文件大小："
                        + Ui.sizeText(container.length)
                        + (header.appVersion == null || header.appVersion.isEmpty()
                                ? ""
                                : "\n应用版本：" + header.appVersion);

        askPassword(
                host,
                "输入密码解密备份",
                info,
                true,
                (password, fromRemembered) ->
                        decryptAndConfirm(host, container, info, password, fromRemembered, done));
    }

    // ==================== 解密 ====================

    private static void decryptAndConfirm(
            final Activity host,
            final byte[] container,
            final String info,
            final char[] password,
            final boolean fromRemembered,
            final Done done) {
        final Dialog busy = Ui.busyDialog(host, "正在安全解密备份…");
        new Thread(
                        () -> {
                            final BackupCrypto.Result result =
                                    BackupCrypto.decrypt(password, container);
                            host.runOnUiThread(
                                    () -> {
                                        Ui.dismissDialog(busy);
                                        if (host.isFinishing() || host.isDestroyed()) {
                                            Arrays.fill(password, '\0');
                                            return;
                                        }
                                        if (result.error != BackupCrypto.Error.NONE) {
                                            Arrays.fill(password, '\0');
                                            if (fromRemembered
                                                    && result.error
                                                            == BackupCrypto.Error.WRONG_PASSWORD) {
                                                askPassword(
                                                        host,
                                                        "输入该备份的加密密码",
                                                        "本机记住的密码无法解开此备份，请输入当时加密使用的密码。\n\n"
                                                                + info,
                                                        false,
                                                        (manualPwd, r2) ->
                                                                decryptAndConfirm(
                                                                        host,
                                                                        container,
                                                                        info,
                                                                        manualPwd,
                                                                        false,
                                                                        done));
                                                return;
                                            }
                                            Repo.addAudit(
                                                    host,
                                                    "DECRYPT",
                                                    result.error.name(),
                                                    result.message,
                                                    0);
                                            alert(host, "解密失败", result.message, true, done);
                                            return;
                                        }
                                        String json =
                                                new String(result.payload, StandardCharsets.UTF_8);
                                        try {
                                            BackupManager.Summary s = BackupManager.summarize(json);
                                            chooseMode(host, json, password, s, done);
                                        } catch (Exception e) {
                                            Arrays.fill(password, '\0');
                                            Repo.addAudit(
                                                    host,
                                                    "DECRYPT",
                                                    "FAILED",
                                                    "payload 不是合法 JSON",
                                                    0);
                                            alert(
                                                    host,
                                                    "解密失败",
                                                    "备份内容已损坏或格式无效。",
                                                    true,
                                                    done);
                                        }
                                    });
                        })
                .start();
    }

    // ==================== 摘要对比与选择恢复方式 ====================

    private static void chooseMode(
            final Activity host,
            final String json,
            final char[] password,
            BackupManager.Summary summary,
            final Done done) {
        LinearLayout root = Ui.sheetContainer(host);

        LinearLayout header = Design.row(host);
        FrameLayout badge =
                Design.iconBadge(
                        host,
                        R.drawable.ic_restore,
                        Design.color(host, R.color.c_accent),
                        Design.color(host, R.color.c_accent_soft),
                        38,
                        20);
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        bp.rightMargin = Ui.dp(host, 12);
        header.addView(badge, bp);
        LinearLayout headCopy = Design.column(host);
        TextView head = Design.text(host, "选择恢复方式", 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        headCopy.addView(head);
        Design.gap(host, headCopy, 2);
        headCopy.addView(
                Design.text(host, "导出于 " + Ui.time(summary.exportedAt), 12, R.color.c_text2));
        header.addView(headCopy, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        Design.gap(host, root, 12);

        // Backup vs Local count summary card
        LinearLayout comp = Design.row(host);
        comp.setBackgroundResource(R.drawable.bg_card_soft);
        Design.pad(host, comp, 12, 10, 10);

        LinearLayout left = Design.column(host);
        left.addView(Design.text(host, "备份内容", 11, R.color.c_text2));
        Design.gap(host, left, 3);
        TextView bStat =
                Design.text(
                        host,
                        summary.entries + " 条 · " + summary.categories + " 分类",
                        14,
                        R.color.c_accent);
        bStat.setTypeface(null, Typeface.BOLD);
        left.addView(bStat);
        left.addView(
                Design.text(
                        host,
                        summary.tags + " 标签 · 回收站 " + summary.trashed + " 条",
                        11,
                        R.color.c_text2));
        comp.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = Design.column(host);
        right.addView(Design.text(host, "本机当前", 11, R.color.c_text2));
        Design.gap(host, right, 3);
        TextView lStat =
                Design.text(
                        host,
                        Repo.countActive(host) + " 条收藏",
                        14,
                        R.color.c_text);
        lStat.setTypeface(null, Typeface.BOLD);
        right.addView(lStat);
        right.addView(
                Design.text(host, "回收站 " + Repo.countTrash(host) + " 条", 11, R.color.c_text2));
        comp.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(comp);

        Design.gap(host, root, 12);

        final Dialog[] ref = {null};
        final boolean[] handedOff = {false};

        // Option 1: Merge (recommended)
        LinearLayout mergeCard = Design.row(host);
        mergeCard.setBackgroundResource(R.drawable.bg_card_selected);
        Design.pad(host, mergeCard, 12, 12, 12);
        mergeCard.setFocusable(true);
        mergeCard.setClickable(true);
        Design.tactile(mergeCard);
        FrameLayout mb =
                Design.iconBadge(
                        host,
                        R.drawable.ic_sparkle,
                        Design.color(host, R.color.c_accent),
                        Design.color(host, R.color.c_accent_soft),
                        36,
                        18);
        LinearLayout.LayoutParams mbp = (LinearLayout.LayoutParams) mb.getLayoutParams();
        mbp.rightMargin = Ui.dp(host, 12);
        mergeCard.addView(mb, mbp);
        LinearLayout mc = Design.column(host);
        TextView mt = Design.text(host, "合并到现有数据（推荐）", 15, R.color.c_accent);
        mt.setTypeface(null, Typeface.BOLD);
        mc.addView(mt);
        Design.gap(host, mc, 3);
        mc.addView(
                Design.text(
                        host,
                        "备份较新的条目更新本机，本机更新的条目保留，不丢失任何内容",
                        12,
                        R.color.c_text2));
        mergeCard.addView(mc, new LinearLayout.LayoutParams(0, -2, 1));
        mergeCard.setOnClickListener(
                v -> {
                    handedOff[0] = true;
                    if (ref[0] != null) ref[0].dismiss();
                    doRestore(host, json, password, false, done);
                });
        root.addView(mergeCard);

        Design.gap(host, root, 8);

        // Option 2: Replace
        LinearLayout replaceCard = Design.row(host);
        replaceCard.setBackgroundResource(R.drawable.bg_card_soft);
        Design.pad(host, replaceCard, 12, 12, 12);
        replaceCard.setFocusable(true);
        replaceCard.setClickable(true);
        Design.tactile(replaceCard);
        FrameLayout rb =
                Design.iconBadge(
                        host,
                        R.drawable.ic_trash,
                        Design.color(host, R.color.c_danger),
                        Design.color(host, R.color.c_danger_soft),
                        36,
                        18);
        LinearLayout.LayoutParams rbp = (LinearLayout.LayoutParams) rb.getLayoutParams();
        rbp.rightMargin = Ui.dp(host, 12);
        replaceCard.addView(rb, rbp);
        LinearLayout rc = Design.column(host);
        TextView rt = Design.text(host, "完整替换本机条目", 15, R.color.c_text);
        rt.setTypeface(null, Typeface.BOLD);
        rc.addView(rt);
        Design.gap(host, rc, 3);
        rc.addView(
                Design.text(
                        host,
                        "清空本机条目后按这份备份重建（分类与标签字典保留，操作前会自动留快照）",
                        12,
                        R.color.c_text2));
        replaceCard.addView(rc, new LinearLayout.LayoutParams(0, -2, 1));
        replaceCard.setOnClickListener(
                v -> {
                    handedOff[0] = true;
                    if (ref[0] != null) ref[0].dismiss();
                    confirmReplace(host, json, password, done);
                });
        root.addView(replaceCard);

        Design.gap(host, root, 14);
        Button cancel =
                Design.button(
                        host,
                        "取消",
                        0,
                        false,
                        () -> {
                            if (ref[0] != null) ref[0].cancel();
                        });
        root.addView(cancel, new LinearLayout.LayoutParams(-1, -2));

        ref[0] = Ui.showSheet(host, root, false);
        ref[0].setOnDismissListener(
                d -> {
                    if (!handedOff[0]) Arrays.fill(password, '\0');
                });
    }

    private static void confirmReplace(
            final Activity host, final String json, final char[] password, final Done done) {
        Dialog d =
                Ui.confirmSheet(
                        host,
                        "确认完整替换？",
                        "将删除本机全部收藏条目（分类与标签字典保留），再用此备份完整重建。\n\n恢复前会自动保存一份本机加密快照，后悔时可从备份中心「撤销最近一次恢复」还原。",
                        "确认替换",
                        true,
                        () -> doRestore(host, json, password, true, done));
        d.setOnCancelListener(dlg -> Arrays.fill(password, '\0'));
    }

    // ==================== 执行恢复 ====================

    private static void doRestore(
            final Activity host,
            final String json,
            final char[] password,
            final boolean replace,
            final Done done) {
        final Dialog busy = Ui.busyDialog(host, replace ? "正在快照并替换恢复…" : "正在合并恢复收藏…");
        new Thread(
                        () -> {
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
                            } finally {
                                Arrays.fill(password, '\0');
                            }
                            final String err = error;
                            final BackupManager.RestoreResult result = rr;
                            final File snap = snapshot;

                            host.runOnUiThread(
                                    () -> {
                                        Ui.dismissDialog(busy);
                                        if (host.isFinishing() || host.isDestroyed()) return;
                                        if (err != null) {
                                            Repo.addAudit(host, "RESTORE", "FAILED", err, 0);
                                            alert(
                                                    host,
                                                    "恢复失败",
                                                    "本次数据库操作已撤销，本机收藏未受影响。\n\n错误："
                                                            + err
                                                            + (snap == null
                                                                    ? ""
                                                                    : "\n\n恢复前快照已保存，可通过「撤销最近一次恢复」还原。"),
                                                    true,
                                                    done);
                                            return;
                                        }
                                        Repo.addAudit(
                                                host,
                                                replace ? "RESTORE_REPLACE" : "RESTORE_MERGE",
                                                "SUCCESS",
                                                "",
                                                result.added + result.updated);
                                        alert(
                                                host,
                                                "恢复完成",
                                                result.describe()
                                                        + "\n\n当前本机共有 "
                                                        + Repo.countActive(host)
                                                        + " 条收藏（回收站 "
                                                        + Repo.countTrash(host)
                                                        + " 条）",
                                                false,
                                                done);
                                    });
                        })
                .start();
    }

    // ==================== 回滚 ====================

    public static void rollbackLatestSnapshot(final Activity host, final Done done) {
        final File snap = BackupManager.latestSnapshot(host);
        if (snap == null) {
            alert(host, "还没有可撤销的快照", "每次执行备份恢复前，应用都会自动为您留一份安全快照。", false, done);
            return;
        }
        final String info =
                "快照时间：" + Ui.time(snap.lastModified()) + "\n将还原到此时间点前的完整本地数据。";
        askPassword(
                host,
                "撤销最近一次恢复",
                info,
                true,
                (password, fromRemembered) ->
                        doRollback(host, snap, info, password, fromRemembered, done));
    }

    private static void doRollback(
            final Activity host,
            final File snap,
            final String info,
            final char[] password,
            final boolean fromRemembered,
            final Done done) {
        final Dialog busy = Ui.busyDialog(host, "正在还原恢复前快照…");
        new Thread(
                        () -> {
                            String error = null;
                            boolean wrongPassword = false;
                            BackupManager.RestoreResult rr = null;
                            try {
                                byte[] data;
                                try (java.io.InputStream in = new java.io.FileInputStream(snap)) {
                                    data = BackupManager.readAll(in);
                                }
                                BackupCrypto.Result r = BackupCrypto.decrypt(password, data);
                                if (r.error != BackupCrypto.Error.NONE) {
                                    wrongPassword = r.error == BackupCrypto.Error.WRONG_PASSWORD;
                                    throw new IllegalStateException(r.message);
                                }
                                String json = new String(r.payload, StandardCharsets.UTF_8);
                                rr = BackupManager.restore(host, json, true);
                            } catch (Exception e) {
                                error =
                                        e.getMessage() == null
                                                ? e.getClass().getSimpleName()
                                                : e.getMessage();
                            } finally {
                                Arrays.fill(password, '\0');
                            }
                            final String err = error;
                            final boolean retryManual = fromRemembered && wrongPassword;
                            final BackupManager.RestoreResult result = rr;
                            host.runOnUiThread(
                                    () -> {
                                        Ui.dismissDialog(busy);
                                        if (host.isFinishing() || host.isDestroyed()) return;
                                        if (retryManual) {
                                            askPassword(
                                                    host,
                                                    "输入快照的加密密码",
                                                    "本机记住的密码无法解开此快照，请输入当时恢复备份使用的密码。\n\n"
                                                            + info,
                                                    false,
                                                    (manualPwd, r2) ->
                                                            doRollback(
                                                                    host,
                                                                    snap,
                                                                    info,
                                                                    manualPwd,
                                                                    false,
                                                                    done));
                                            return;
                                        }
                                        if (err != null) {
                                            Repo.addAudit(host, "ROLLBACK", "FAILED", err, 0);
                                            alert(host, "撤回失败", err, true, done);
                                            return;
                                        }
                                        Repo.addAudit(
                                                host,
                                                "ROLLBACK",
                                                "SUCCESS",
                                                snap.getName(),
                                                result.added);
                                        alert(
                                                host,
                                                "已撤销并还原快照",
                                                "已还原 "
                                                        + result.added
                                                        + " 条记录\n\n当前本机共有 "
                                                        + Repo.countActive(host)
                                                        + " 条收藏",
                                                false,
                                                done);
                                    });
                        })
                .start();
    }

    // ==================== 密码输入 ====================

    private interface PasswordCallback {
        void onPassword(char[] password, boolean fromRemembered);
    }

    private static void askPassword(
            final Activity host,
            final String title,
            final String info,
            boolean allowRemembered,
            final PasswordCallback cb) {
        if (allowRemembered) {
            String remembered = SecretStore.get(host, PWD_KEY);
            if (remembered != null && remembered.length() > 0) {
                cb.onPassword(remembered.toCharArray(), true);
                return;
            }
        }
        Ui.passwordSheet(
                host,
                title,
                info,
                "输入备份密码",
                false,
                false,
                "解密并继续",
                null,
                null,
                pwd -> cb.onPassword(pwd, false));
    }

    private static void alert(
            final Activity host, String title, String msg, boolean warn, final Done done) {
        if (host.isFinishing() || host.isDestroyed()) return;
        Ui.infoSheet(
                host,
                title,
                msg,
                warn ? R.drawable.ic_info : R.drawable.ic_shield_ok,
                warn,
                "知道了",
                done == null ? null : done::onDone);
    }
}
