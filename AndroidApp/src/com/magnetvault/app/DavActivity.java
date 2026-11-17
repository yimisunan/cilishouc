package com.magnetvault.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 坚果云 WebDAV 连接设置与连通性测试（含写入探测）。 */
public class DavActivity extends AppActivity {

    private static final String PROBE_NAME = "_mv_probe.bin";
    private static final byte[] PROBE_DATA =
            "MagnetVault-WebDAV-probe-32b!!".getBytes(StandardCharsets.UTF_8);

    private EditText inputUrl;
    private EditText inputUser;
    private EditText inputPass;
    private EditText inputDir;
    private TextView tvResult;
    private AlertDialog busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dav);

        inputUrl = findViewById(R.id.input_url);
        inputUser = findViewById(R.id.input_user);
        inputPass = findViewById(R.id.input_pass);
        inputDir = findViewById(R.id.input_dir);
        tvResult = findViewById(R.id.tv_result);

        inputUrl.setText(ThemePrefs.davUrl(this));
        inputUser.setText(ThemePrefs.davUser(this));
        inputDir.setText(ThemePrefs.davDir(this));
        String pwd = SecretStore.get(this, ThemePrefs.SECRET_DAV_PASSWORD);
        if (pwd != null && pwd.length() > 0) {
            inputPass.setText(pwd);
        }
        tvResult.setText(ThemePrefs.davLastResult(this));

        findViewById(R.id.btn_back)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                finish();
                            }
                        });
        findViewById(R.id.btn_test)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                runTest();
                            }
                        });
    }

    private void runTest() {
        final String url = inputUrl.getText().toString().trim();
        final String user = inputUser.getText().toString().trim();
        final String pass = inputPass.getText().toString();
        final String dir = inputDir.getText().toString().trim();

        if (TextUtils.isEmpty(user)) {
            Ui.toast(this, "请填写账号（邮箱）");
            return;
        }
        if (TextUtils.isEmpty(pass)) {
            Ui.toast(this, "请填写应用密码");
            return;
        }
        if (TextUtils.isEmpty(dir)) {
            Ui.toast(this, "请填写备份目录名");
            return;
        }

        showBusy("正在连接坚果云…");
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                StringBuilder sb = new StringBuilder();
                                boolean ok = true;
                                WebDavClient client = new WebDavClient(url, user, pass, 25000);
                                try {
                                    long t0 = System.currentTimeMillis();
                                    client.testRoot();
                                    sb.append("① 根目录可访问（")
                                            .append(System.currentTimeMillis() - t0)
                                            .append(" ms）\n");

                                    long t1 = System.currentTimeMillis();
                                    client.ensureCollection(dir);
                                    sb.append("② 目录「")
                                            .append(dir)
                                            .append("」就绪（")
                                            .append(System.currentTimeMillis() - t1)
                                            .append(" ms）\n");

                                    // 覆盖保护：坚果云会忽略 If-None-Match，所以先查存在性
                                    if (client.exists(dir, PROBE_NAME)) {
                                        client.delete(dir, PROBE_NAME);
                                    }

                                    long t2 = System.currentTimeMillis();
                                    int putCode = client.put(dir, PROBE_NAME, PROBE_DATA);
                                    sb.append("③ 上传探测文件 OK（HTTP ")
                                            .append(putCode)
                                            .append("，")
                                            .append(System.currentTimeMillis() - t2)
                                            .append(" ms）\n");

                                    List<WebDavClient.RemoteFile> files = client.list(dir);
                                    long remoteSize = -1;
                                    for (WebDavClient.RemoteFile f : files) {
                                        if (PROBE_NAME.equals(f.name)) {
                                            remoteSize = f.size;
                                        }
                                    }
                                    sb.append("④ 目录列表可见 ")
                                            .append(files.size())
                                            .append(" 个文件")
                                            .append(files.isEmpty() ? "" : "（含备份）")
                                            .append("\n");
                                    if (remoteSize != PROBE_DATA.length) {
                                        ok = false;
                                        sb.append("   校验失败：服务端大小 ")
                                                .append(remoteSize)
                                                .append("，本地 ")
                                                .append(PROBE_DATA.length)
                                                .append("\n");
                                    } else {
                                        sb.append("   校验通过：服务端大小 ")
                                                .append(remoteSize)
                                                .append(" 字节\n");
                                    }

                                    client.delete(dir, PROBE_NAME);
                                    sb.append("⑤ 探测文件已清理\n");

                                    // 保存配置（密码用 Keystore 加密）
                                    ThemePrefs.setDav(DavActivity.this, url, user, dir);
                                    boolean saved =
                                            SecretStore.put(
                                                    DavActivity.this,
                                                    ThemePrefs.SECRET_DAV_PASSWORD,
                                                    pass);
                                    sb.append("\n配置已保存")
                                            .append(
                                                    saved
                                                            ? "（密码已加密存本机）"
                                                            : "（密码未能记住："
                                                                    + SecretStore.lastError
                                                                    + "）");
                                    sb.append("\n结论：").append(ok ? "连接正常，可以读写" : "连接可用但校验异常");
                                } catch (WebDavClient.DavException e) {
                                    ok = false;
                                    sb.append("失败（HTTP ")
                                            .append(e.status)
                                            .append("）：\n")
                                            .append(e.userMessage);
                                } catch (Exception e) {
                                    ok = false;
                                    sb.append("异常：")
                                            .append(e.getClass().getSimpleName())
                                            .append(
                                                    e.getMessage() == null
                                                            ? ""
                                                            : "（" + e.getMessage() + "）");
                                }

                                final String text = sb.toString();
                                final boolean finalOk = ok;
                                ThemePrefs.setDavLastResult(
                                        DavActivity.this,
                                        (finalOk ? "上次测试：通过 · " : "上次测试：失败 · ")
                                                + Ui.time(System.currentTimeMillis()));
                                Repo.addAudit(
                                        DavActivity.this,
                                        "DAV_TEST",
                                        finalOk ? "SUCCESS" : "FAILED",
                                        text.replace('\n', ' ').trim(),
                                        0);

                                runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                if (isFinishing() || isDestroyed()) return;
                                                hideBusy();
                                                tvResult.setText(text);
                                                new AlertDialog.Builder(DavActivity.this)
                                                        .setTitle(finalOk ? "连接正常" : "连接失败")
                                                        .setMessage(text)
                                                        .setPositiveButton("知道了", null)
                                                        .show();
                                            }
                                        });
                            }
                        })
                .start();
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
