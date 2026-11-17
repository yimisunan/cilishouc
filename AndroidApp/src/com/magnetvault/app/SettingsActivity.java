package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;

/** 设置：主题、数据概览、加密备份（导出/恢复）。 */
public class SettingsActivity extends AppActivity {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final String SECRET_BACKUP_PWD = "backupPwd";

    private TextView tvStats;
    private TextView tvPwdStatus;
    private TextView tvAudit;
    private TextView tvDavStatus;

    private String pendingName;
    private AlertDialog busyDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildSettings();
        if (savedInstanceState != null) pendingName = savedInstanceState.getString("pendingName");

        tvStats = findViewById(R.id.tv_stats);
        tvPwdStatus = findViewById(R.id.tv_pwd_status);
        tvAudit = findViewById(R.id.tv_audit);
        tvDavStatus = findViewById(R.id.tv_dav_status);

        findViewById(R.id.btn_back)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                finish();
                            }
                        });
        findViewById(R.id.row_theme)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                chooseTheme();
                            }
                        });
        findViewById(R.id.row_trash)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                chooseTrashDays();
                            }
                        });
        findViewById(R.id.row_pwd)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                editPassword();
                            }
                        });
        findViewById(R.id.row_export)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                startExport();
                            }
                        });
        findViewById(R.id.row_restore)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                startImport();
                            }
                        });
        findViewById(R.id.row_dav)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                startActivity(new Intent(SettingsActivity.this, DavActivity.class));
                            }
                        });
        findViewById(R.id.row_cloud)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                startActivity(
                                        new Intent(SettingsActivity.this, CloudActivity.class));
                            }
                        });
        findViewById(R.id.row_auto_backup)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                toggleAutoBackup();
                            }
                        });
        findViewById(R.id.row_rollback)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                RestoreFlow.rollbackLatestSnapshot(
                                        SettingsActivity.this,
                                        new RestoreFlow.Done() {
                                            @Override
                                            public void onDone() {
                                                refresh();
                                            }
                                        });
                            }
                        });

        ((TextView) findViewById(R.id.tv_note))
                .setText(
                        "· 备份文件是客户端加密的密文（扩展名 .mjbf），云端或任何网盘只能看到密文。\n\n"
                                + "· 备份密码不会被保存为可还原的形式：勾选记住时，只有用本机 Keystore"
                                + " 里不可导出的密钥加密后的密文。换设备后需重新输入密码。\n\n"
                                + "· 忘记备份密码且没有其他备份 = 数据无法恢复。本应用不提供任何找回通道。\n\n"
                                + "· 恢复采用「合并」时按条目 id 与修改时间判断：备份更新则覆盖，本地更新则保留。");
    }

    private void buildSettings() {
        LinearLayout root = Design.column(this);
        root.setBackgroundColor(Design.color(this, R.color.c_bg));
        root.addView(Design.toolbar(this, "设置与备份", this::finish));
        // Retain the back id used by the existing controller.
        android.widget.Button back = new android.widget.Button(this);
        back.setId(R.id.btn_back);
        back.setVisibility(View.GONE);
        root.addView(back);
        LinearLayout content = Design.column(this);
        Design.pad(this, content, 16, 4, 24);
        Design.section(this, content, "外观与习惯", null);
        settingsRow(content, R.id.row_theme, "主题", R.drawable.ic_theme);
        settingsRow(content, R.id.row_trash, "回收站保留时间", R.drawable.ic_trash);

        int backupSectionStart = content.getChildCount();
        Design.section(this, content, "备份与恢复", "本地导出加密 .mjbf 文件，支持合并与回滚");
        final View anchor = content.getChildAt(backupSectionStart + 1);
        settingsPanel(content, R.id.tv_pwd_status);
        settingsRow(content, R.id.row_pwd, "设置备份密码", R.drawable.ic_lock);
        settingsRow(content, R.id.row_export, "导出加密备份", R.drawable.ic_import);
        settingsRow(content, R.id.row_restore, "从文件恢复", R.drawable.ic_restore);

        Design.section(this, content, "云端备份", "连接坚果云，保存加密后的备份文件");
        settingsPanel(content, R.id.tv_dav_status);
        settingsRow(content, R.id.row_dav, "连接坚果云", R.drawable.ic_link);
        settingsRow(content, R.id.row_cloud, "管理云端备份", R.drawable.ic_cloud);
        settingsRow(content, R.id.row_auto_backup, "每日自动备份", R.drawable.ic_history);

        Design.section(this, content, "更多", null);
        android.widget.Button advanced =
                Design.button(this, "数据详情与恢复工具", R.drawable.ic_shield, false, () -> {});
        content.addView(advanced);
        LinearLayout tools = Design.column(this);
        tools.setVisibility(View.GONE);
        content.addView(tools);
        Design.section(this, tools, "数据概况", null);
        settingsPanel(tools, R.id.tv_stats);
        settingsRow(tools, R.id.row_rollback, "撤销最近一次恢复", R.drawable.ic_history);
        Design.section(this, tools, "最近操作", null);
        settingsPanel(tools, R.id.tv_audit);
        TextView note = Design.text(this, "", 13, R.color.c_text2);
        note.setId(R.id.tv_note);
        Design.gap(this, tools, 14);
        tools.addView(note);
        advanced.setOnClickListener(
                v -> {
                    boolean show = tools.getVisibility() != View.VISIBLE;
                    tools.setVisibility(show ? View.VISIBLE : View.GONE);
                    advanced.setText(show ? "收起数据与恢复工具" : "数据详情与恢复工具");
                });
        android.widget.ScrollView scroll = Design.scroll(this, content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if ("backup".equals(getIntent().getStringExtra("section")) && anchor != null)
            scroll.post(
                    () -> scroll.smoothScrollTo(0, Math.max(0, anchor.getTop() - Ui.dp(this, 12))));
    }

    private void settingsRow(LinearLayout content, int id, String label, int icon) {
        TextView row = Design.text(this, label, 15, R.color.c_text);
        row.setTypeface(null, android.graphics.Typeface.BOLD);
        row.setId(id);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setMinHeight(Ui.dp(this, 56));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Design.pad(this, row, 14, 14, 14);
        android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
        d.setTint(Design.color(this, R.color.c_accent));
        d.setBounds(0, 0, Ui.dp(this, 20), Ui.dp(this, 20));
        android.graphics.drawable.Drawable chev = getDrawable(R.drawable.ic_chevron_right).mutate();
        chev.setTint(Design.color(this, R.color.c_text2));
        chev.setBounds(0, 0, Ui.dp(this, 18), Ui.dp(this, 18));
        row.setCompoundDrawablesRelative(d, null, chev, null);
        row.setCompoundDrawablePadding(Ui.dp(this, 12));
        row.setFocusable(true);
        row.setClickable(true);
        content.addView(row);
        Design.gap(this, content, 8);
    }

    private void settingsPanel(LinearLayout content, int id) {
        TextView panel = Design.text(this, "", 13, R.color.c_text2);
        panel.setId(id);
        panel.setBackgroundResource(R.drawable.bg_card_soft);
        Design.pad(this, panel, 14, 12, 12);
        content.addView(panel);
        Design.gap(this, content, 8);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        tvStats.setText(
                "收藏 "
                        + Repo.countActive(this)
                        + " 条\n"
                        + "回收站 "
                        + Repo.countTrash(this)
                        + " 条\n"
                        + "分类 "
                        + Repo.categories(this, false).size()
                        + " 个\n"
                        + "标签 "
                        + Repo.allTagNames(this).size()
                        + " 个\n"
                        + "数据库 "
                        + Repo.dbSizeText(this));

        String remembered = SecretStore.get(this, SECRET_BACKUP_PWD);
        if (remembered != null && remembered.length() > 0) {
            tvPwdStatus.setText("备份密码已设置，并已在这台设备安全保存");
        } else if (ThemePrefs.isBackupPasswordSet(this)) {
            tvPwdStatus.setText("备份密码已设置，备份与恢复时需要输入");
        } else {
            tvPwdStatus.setText("备份密码：尚未设置");
        }
        ((TextView) findViewById(R.id.row_theme)).setText("主题 · " + ThemePrefs.themeLabel(this));
        int keepDays = ThemePrefs.trashKeepDays(this);
        ((TextView) findViewById(R.id.row_trash))
                .setText("回收站保留 · " + (keepDays > 0 ? keepDays + " 天" : "不自动清理"));
        tvAudit.setText(Repo.lastAuditText(this));
        boolean auto = BackupJob.isScheduled(this) || ThemePrefs.autoBackup(this);
        ((TextView) findViewById(R.id.row_auto_backup))
                .setText("每日自动备份 · " + (auto ? "开启" : "关闭") + (auto ? "（有网时每天一次）" : "（点这里开启）"));

        if (ThemePrefs.isDavConfigured(this)) {
            long last = ThemePrefs.lastBackupAt(this);
            tvDavStatus.setText(
                    "账号："
                            + ThemePrefs.davUser(this)
                            + "\n目录："
                            + ThemePrefs.davDir(this)
                            + "\n最近备份："
                            + (last == 0 ? "从未" : Ui.time(last))
                            + "\n"
                            + ThemePrefs.davLastResult(this));
        } else {
            tvDavStatus.setText("连接后，可以上传加密备份并开启自动备份。");
        }
    }

    // ==================== 主题 ====================

    // ==================== 每日自动备份 ====================

    private void toggleAutoBackup() {
        boolean on = BackupJob.isScheduled(this) || ThemePrefs.autoBackup(this);
        if (!on) {
            if (!ThemePrefs.isDavConfigured(this)) {
                Ui.toast(this, "请先配置坚果云连接");
                return;
            }
            String pwd = SecretStore.get(this, CloudBackup.PWD_KEY);
            if (pwd == null || pwd.length() < 8) {
                Ui.toast(this, "请先在备份页手动备份一次（会记住备份密码），再开启自动备份");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("开启每日自动备份")
                    .setMessage(
                            "每天在空闲且有网络时自动上传一次加密备份到坚果云。\n\n"
                                    + "· 密码用本机加密保存的那份，不会明文落盘\n"
                                    + "· 失败不会打扰你，只写进「最近记录」\n"
                                    + "· 云端文件按天累积，可用备份页清理")
                    .setPositiveButton(
                            "开启",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    boolean ok = BackupJob.enable(SettingsActivity.this);
                                    refresh();
                                    Ui.toast(
                                            SettingsActivity.this,
                                            ok ? "已开启每日自动备份" : "开启失败：系统不允许注册定时任务");
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("关闭每日自动备份")
                    .setMessage("关闭后就不会再自动上传，手动备份仍然可用。")
                    .setPositiveButton(
                            "关闭",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    BackupJob.disable(SettingsActivity.this);
                                    refresh();
                                    Ui.toast(SettingsActivity.this, "已关闭每日自动备份");
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private void chooseTheme() {
        final String[] items = {"跟随系统", "浅色", "深色"};
        new AlertDialog.Builder(this)
                .setTitle("主题")
                .setSingleChoiceItems(
                        items,
                        ThemePrefs.getMode(this),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                ThemePrefs.setMode(SettingsActivity.this, which);
                                d.dismiss();
                                recreate();
                            }
                        })
                .show();
    }

    private void chooseTrashDays() {
        final int[] days = {0, 7, 30, 90};
        final String[] labels = {"不自动清理", "7 天", "30 天（默认）", "90 天"};
        int current = ThemePrefs.trashKeepDays(this);
        int idx = 2;
        for (int i = 0; i < days.length; i++) {
            if (days[i] == current) {
                idx = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("回收站保留天数")
                .setSingleChoiceItems(
                        labels,
                        idx,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                ThemePrefs.setTrashKeepDays(SettingsActivity.this, days[which]);
                                d.dismiss();
                                refresh();
                                Ui.toast(
                                        SettingsActivity.this,
                                        days[which] == 0
                                                ? "已关闭自动清理"
                                                : "回收站条目将在 " + days[which] + " 天后自动清理");
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 密码 ====================

    private void editPassword() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText pwd1 = passwordField("备份密码（至少 8 位）");
        final EditText pwd2 = passwordField("再输入一次");
        box.addView(pwd1);
        box.addView(pwd2);

        final AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("设置备份密码")
                        .setMessage("密码只用于加密备份文件。忘记后无法找回。")
                        .setView(box)
                        .setPositiveButton("保存", null)
                        .setNegativeButton("取消", null)
                        .setNeutralButton(
                                "忘记密码（清除记住）",
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        SecretStore.clear(SettingsActivity.this, SECRET_BACKUP_PWD);
                                        ThemePrefs.setBackupPasswordSet(
                                                SettingsActivity.this, false);
                                        refresh();
                                        Ui.toast(SettingsActivity.this, "已清除本机记住的密码");
                                    }
                                })
                        .create();

        dialog.setOnShowListener(
                new DialogInterface.OnShowListener() {
                    @Override
                    public void onShow(DialogInterface d) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        new View.OnClickListener() {
                                            @Override
                                            public void onClick(View v) {
                                                String a = pwd1.getText().toString();
                                                String b = pwd2.getText().toString();
                                                if (a.length() < 8) {
                                                    pwd1.setError("密码至少 8 位");
                                                    return;
                                                }
                                                if (!a.equals(b)) {
                                                    pwd2.setError("两次输入不一致");
                                                    return;
                                                }
                                                boolean saved =
                                                        SecretStore.put(
                                                                SettingsActivity.this,
                                                                SECRET_BACKUP_PWD,
                                                                a);
                                                ThemePrefs.setBackupPasswordSet(
                                                        SettingsActivity.this, true);
                                                dialog.dismiss();
                                                refresh();
                                                Ui.toast(
                                                        SettingsActivity.this,
                                                        saved
                                                                ? "已保存，并在本机记住"
                                                                : "已设置（但本机记住失败，下次需手动输入）");
                                            }
                                        });
                    }
                });
        dialog.show();
    }

    private EditText passwordField(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return et;
    }

    /** 取密码：优先用本机记住的；否则弹框让用户输入。 */
    private void withPassword(final String title, final PasswordCallback callback) {
        String remembered = SecretStore.get(this, SECRET_BACKUP_PWD);
        if (remembered != null && remembered.length() > 0) {
            callback.onPassword(remembered.toCharArray());
            return;
        }
        final EditText input = passwordField("备份密码");
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(input)
                        .setPositiveButton("确定", null)
                        .setNegativeButton("取消", null)
                        .create();
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(-1)
                                .setOnClickListener(
                                        v -> {
                                            String password = input.getText().toString();
                                            if (password.length() < 8) {
                                                input.setError("密码至少 8 位");
                                                input.requestFocus();
                                                return;
                                            }
                                            ThemePrefs.setBackupPasswordSet(this, true);
                                            dialog.dismiss();
                                            callback.onPassword(password.toCharArray());
                                        }));
        dialog.show();
    }

    private interface PasswordCallback {
        void onPassword(char[] password);
    }

    // ==================== 导出 ====================

    private java.io.File pendingExportFile() {
        return new java.io.File(getCacheDir(), "pending-export.mjbf");
    }

    private void startExport() {
        withPassword(
                "导出备份",
                password -> {
                    showBusy("正在加密收藏，请稍候…");
                    load(
                            () -> {
                                try {
                                    byte[] encrypted =
                                            BackupManager.buildEncrypted(
                                                    getApplicationContext(), password);
                                    try (OutputStream out =
                                            new java.io.FileOutputStream(pendingExportFile())) {
                                        out.write(encrypted);
                                    }
                                    return BackupManager.defaultFileName();
                                } finally {
                                    java.util.Arrays.fill(password, '\0');
                                }
                            },
                            name -> {
                                hideBusy();
                                pendingName = name;
                                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                intent.addCategory(Intent.CATEGORY_OPENABLE);
                                intent.setType("application/octet-stream");
                                intent.putExtra(Intent.EXTRA_TITLE, name);
                                startActivityForResult(intent, REQ_EXPORT);
                            },
                            error -> {
                                hideBusy();
                                Ui.toast(this, "加密失败，请重试");
                            });
                });
    }

    private void writeExport(Uri uri) {
        java.io.File file = pendingExportFile();
        if (!file.exists()) {
            Ui.toast(this, "待导出的备份已失效，请重新导出");
            return;
        }
        showBusy("正在保存备份…");
        String name = pendingName != null ? pendingName : BackupManager.defaultFileName();
        load(
                () -> {
                    long length = file.length();
                    try (InputStream in = new java.io.FileInputStream(file);
                            OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new java.io.IOException("无法打开目标文件");
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        out.flush();
                    }
                    try {
                        android.provider.DocumentsContract.renameDocument(
                                getContentResolver(), uri, name);
                    } catch (Exception ignored) {
                    }
                    Repo.addAudit(
                            this,
                            "EXPORT",
                            "SUCCESS",
                            name + " · " + length + " B",
                            Repo.countActive(this));
                    file.delete();
                    return length;
                },
                length -> {
                    hideBusy();
                    Ui.toast(this, "已保存加密备份（" + Ui.sizeText(length) + "）");
                    refresh();
                },
                error -> {
                    hideBusy();
                    Ui.toast(this, "文件未保存，请检查目标位置后重新导出");
                });
    }

    // ==================== 从文件恢复 ====================

    private void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void handleImport(Uri uri) {
        showBusy("正在读取备份文件…");
        load(
                () -> {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new java.io.IOException("无法读取文件");
                        return BackupManager.readAll(in);
                    }
                },
                bytes -> {
                    hideBusy();
                    RestoreFlow.start(this, bytes, this::refresh);
                },
                error -> {
                    hideBusy();
                    Ui.toast(this, "读取失败，请重新选择可访问的备份文件");
                });
    }

    // ==================== SAF 回调 ====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_EXPORT) pendingExportFile().delete();
            pendingName = null;
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            writeExport(uri);
        } else if (requestCode == REQ_IMPORT) {
            handleImport(uri);
        }
    }

    // ==================== 忙碌框 ====================

    private void showBusy(String msg) {
        hideBusy();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        busyDialog = new AlertDialog.Builder(this).setMessage(msg).setCancelable(false).create();
        busyDialog.show();
    }

    private void hideBusy() {
        if (busyDialog != null) {
            try {
                busyDialog.dismiss();
            } catch (Exception ignored) {
            }
            busyDialog = null;
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("pendingName", pendingName);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        hideBusy();
        super.onDestroy();
    }
}
