package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 云端备份：上传、列表、删除（下载与恢复在 S3.5/S3.6）。 */
public class CloudActivity extends AppActivity {

    private static final int TIMEOUT_MS = 30000;
    private static final String BACKUP_PWD_KEY = CloudBackup.PWD_KEY;

    private final List<WebDavClient.RemoteFile> files = new ArrayList<>();
    private CloudAdapter adapter;
    private TextView tvSummary;
    private TextView tvEmpty;
    private AlertDialog busy;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud);

        tvSummary = findViewById(R.id.tv_summary);
        tvEmpty = findViewById(R.id.tv_empty);
        ListView list = findViewById(R.id.list);
        adapter = new CloudAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_back)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                finish();
                            }
                        });
        findViewById(R.id.btn_refresh)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                refresh();
                            }
                        });
        findViewById(R.id.btn_backup)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                startBackup();
                            }
                        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSummary();
        refresh();
    }

    private void updateSummary() {
        long last = ThemePrefs.lastBackupAt(this);
        tvSummary.setText(
                "目录："
                        + ThemePrefs.davDir(this)
                        + "\n账号："
                        + (TextUtils.isEmpty(ThemePrefs.davUser(this))
                                ? "未配置"
                                : ThemePrefs.davUser(this))
                        + "\n最近备份："
                        + (last == 0 ? "从未" : Ui.time(last))
                        + (files.isEmpty() ? "" : "\n云端文件：" + files.size() + " 个"));
    }

    private WebDavClient client() {
        String pwd = SecretStore.get(this, ThemePrefs.SECRET_DAV_PASSWORD);
        return new WebDavClient(
                ThemePrefs.davUrl(this),
                ThemePrefs.davUser(this),
                pwd == null ? "" : pwd,
                TIMEOUT_MS);
    }

    // ==================== 列表 ====================

    private void refresh() {
        if (refreshing) return;
        if (!ThemePrefs.isDavConfigured(this)) {
            files.clear();
            adapter.notifyDataSetChanged();
            tvEmpty.setText("尚未配置坚果云\n请先到 设置 → 坚果云备份 → 连接设置与测试");
            tvEmpty.setVisibility(View.VISIBLE);
            updateSummary();
            return;
        }
        refreshing = true;
        tvEmpty.setText("正在读取云端备份…");
        tvEmpty.setVisibility(View.VISIBLE);
        findViewById(R.id.btn_refresh).setEnabled(false);
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                String error = null;
                                List<WebDavClient.RemoteFile> result = new ArrayList<>();
                                try {
                                    WebDavClient c = client();
                                    List<WebDavClient.RemoteFile> all =
                                            c.list(ThemePrefs.davDir(CloudActivity.this));
                                    for (WebDavClient.RemoteFile f : all) {
                                        String n = f.name.toLowerCase(java.util.Locale.US);
                                        if (n.endsWith(BackupManager.EXT) || n.endsWith(".bin")) {
                                            result.add(f);
                                        }
                                    }
                                    Collections.sort(
                                            result,
                                            new Comparator<WebDavClient.RemoteFile>() {
                                                @Override
                                                public int compare(
                                                        WebDavClient.RemoteFile a,
                                                        WebDavClient.RemoteFile b) {
                                                    return Long.compare(
                                                            b.lastModified, a.lastModified);
                                                }
                                            });
                                } catch (Exception e) {
                                    error =
                                            e instanceof WebDavClient.DavException
                                                    ? ((WebDavClient.DavException) e).userMessage
                                                    : e.getClass().getSimpleName();
                                }
                                final String err = error;
                                final List<WebDavClient.RemoteFile> data = result;
                                runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                if (isFinishing() || isDestroyed()) return;
                                                refreshing = false;
                                                findViewById(R.id.btn_refresh).setEnabled(true);
                                                files.clear();
                                                files.addAll(data);
                                                adapter.notifyDataSetChanged();
                                                if (err != null) {
                                                    tvEmpty.setText("读取失败：\n" + err);
                                                    tvEmpty.setVisibility(View.VISIBLE);
                                                } else if (data.isEmpty()) {
                                                    tvEmpty.setText("云端还没有备份\n点上面的按钮上传第一份");
                                                    tvEmpty.setVisibility(View.VISIBLE);
                                                } else {
                                                    tvEmpty.setVisibility(View.GONE);
                                                }
                                                updateSummary();
                                            }
                                        });
                            }
                        })
                .start();
    }

    // ==================== 上传 ====================

    private void startBackup() {
        if (!ThemePrefs.isDavConfigured(this)) {
            startActivity(new android.content.Intent(this, DavActivity.class));
            Ui.toast(this, "先连接坚果云，再创建云端备份");
            return;
        }
        String remembered = SecretStore.get(this, BACKUP_PWD_KEY);
        if (remembered != null && remembered.length() > 0) {
            doBackup(remembered.toCharArray());
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("备份密码");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("设置备份密码")
                        .setMessage("恢复备份需要使用同一个密码，请妥善保存。")
                        .setView(input)
                        .setPositiveButton("开始备份", null)
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
                                                return;
                                            }
                                            ThemePrefs.setBackupPasswordSet(this, true);
                                            SecretStore.put(this, BACKUP_PWD_KEY, password);
                                            dialog.dismiss();
                                            doBackup(password.toCharArray());
                                        }));
        dialog.show();
    }

    private void doBackup(final char[] password) {
        showBusy("正在加密并上传…");
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                final CloudBackup.Result r =
                                        CloudBackup.upload(CloudActivity.this, password);

                                runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                if (isFinishing() || isDestroyed()) return;
                                                hideBusy();
                                                if (r.error != null) {
                                                    new AlertDialog.Builder(CloudActivity.this)
                                                            .setTitle("备份失败")
                                                            .setMessage("本地数据未改动。\n\n" + r.error)
                                                            .setPositiveButton("知道了", null)
                                                            .show();
                                                } else {
                                                    new AlertDialog.Builder(CloudActivity.this)
                                                            .setTitle("备份完成")
                                                            .setMessage(
                                                                    "已上传："
                                                                            + r.name
                                                                            + "\n大小："
                                                                            + r.bytes
                                                                            + " 字节（已回读校验）")
                                                            .setPositiveButton("知道了", null)
                                                            .show();
                                                }
                                                refresh();
                                            }
                                        });
                            }
                        })
                .start();
    }

    // ==================== 删除 ====================

    private void confirmDelete(final WebDavClient.RemoteFile f) {
        new AlertDialog.Builder(this)
                .setTitle("删除云端备份")
                .setMessage(f.name + "\n\n将从坚果云彻底删除，不可恢复。")
                .setPositiveButton(
                        "删除",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                doDelete(f);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(final WebDavClient.RemoteFile f) {
        showBusy("正在删除…");
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                String error = null;
                                try {
                                    client().delete(ThemePrefs.davDir(CloudActivity.this), f.name);
                                } catch (Exception e) {
                                    error =
                                            e instanceof WebDavClient.DavException
                                                    ? ((WebDavClient.DavException) e).userMessage
                                                    : e.getClass().getSimpleName();
                                }
                                final String err = error;
                                runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                if (isFinishing() || isDestroyed()) return;
                                                hideBusy();
                                                if (err != null) {
                                                    Ui.toast(CloudActivity.this, "删除失败：" + err);
                                                } else {
                                                    Repo.addAudit(
                                                            CloudActivity.this,
                                                            "DELETE_REMOTE",
                                                            "SUCCESS",
                                                            f.name,
                                                            0);
                                                    Ui.toast(CloudActivity.this, "已删除");
                                                }
                                                refresh();
                                            }
                                        });
                            }
                        })
                .start();
    }

    // ==================== 适配器 ====================

    private class CloudAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public Object getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView =
                        LayoutInflater.from(CloudActivity.this)
                                .inflate(R.layout.item_cloud, parent, false);
            }
            final WebDavClient.RemoteFile f = files.get(position);
            TextView name = convertView.findViewById(R.id.cloud_name);
            TextView sub = convertView.findViewById(R.id.cloud_sub);
            View action = convertView.findViewById(R.id.cloud_action);

            name.setText(f.name);
            sub.setText(sizeText(f.size) + " · " + Ui.time(f.lastModified));
            convertView.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            downloadAndRestore(f);
                        }
                    });
            action.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            confirmDelete(f);
                        }
                    });
            return convertView;
        }
    }

    // ==================== 下载 → 校验 → 恢复 ====================

    private void downloadAndRestore(final WebDavClient.RemoteFile f) {
        showBusy("正在下载 " + f.name + "…");
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                String error = null;
                                byte[] data = null;
                                try {
                                    data =
                                            client().get(
                                                            ThemePrefs.davDir(CloudActivity.this),
                                                            f.name);
                                    if (f.size > 0 && data.length != f.size) {
                                        // 容器校验第一层：传输长度必须和目录里声明的一致
                                        error =
                                                "下载不完整：服务端声明 "
                                                        + f.size
                                                        + " 字节，实际收到 "
                                                        + data.length
                                                        + " 字节";
                                        data = null;
                                    }
                                } catch (Exception e) {
                                    error =
                                            e instanceof WebDavClient.DavException
                                                    ? ((WebDavClient.DavException) e).userMessage
                                                    : e.getClass().getSimpleName();
                                }
                                final String err = error;
                                final byte[] bytes = data;
                                runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                if (isFinishing() || isDestroyed()) return;
                                                hideBusy();
                                                if (err != null) {
                                                    Repo.addAudit(
                                                            CloudActivity.this,
                                                            "DOWNLOAD",
                                                            "FAILED",
                                                            err,
                                                            0);
                                                    new AlertDialog.Builder(CloudActivity.this)
                                                            .setTitle("下载失败")
                                                            .setMessage(err)
                                                            .setPositiveButton("知道了", null)
                                                            .show();
                                                    return;
                                                }
                                                Repo.addAudit(
                                                        CloudActivity.this,
                                                        "DOWNLOAD",
                                                        "SUCCESS",
                                                        f.name + " · " + bytes.length + " B",
                                                        0);
                                                RestoreFlow.start(
                                                        CloudActivity.this,
                                                        bytes,
                                                        new RestoreFlow.Done() {
                                                            @Override
                                                            public void onDone() {
                                                                refresh();
                                                            }
                                                        });
                                            }
                                        });
                            }
                        })
                .start();
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private void showBusy(String msg) {
        hideBusy();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        busy = new AlertDialog.Builder(this).setMessage(msg).setCancelable(false).create();
        busy.show();
    }

    private void hideBusy() {
        if (busy != null) {
            try {
                busy.dismiss();
            } catch (Exception ignored) {
            }
            busy = null;
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    @Override
    protected void onDestroy() {
        hideBusy();
        super.onDestroy();
    }
}
