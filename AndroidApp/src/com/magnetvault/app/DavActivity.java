package com.magnetvault.app;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 坚果云 WebDAV 连接设置与连通性验证（支持分步引导与密码明暗切换）。 */
public class DavActivity extends AppActivity {

    private static final String PROBE_NAME = "_mv_probe.bin";
    private static final byte[] PROBE_DATA =
            "MagnetVault-WebDAV-probe-32b!!".getBytes(StandardCharsets.UTF_8);

    private LinearLayout davContent;
    private LinearLayout guideBody;
    private ImageView guideChev;
    private boolean guideOpen;
    private boolean passVisible;

    private EditText inputUrl;
    private EditText inputUser;
    private EditText inputPass;
    private EditText inputDir;
    private ImageButton btnTogglePass;
    private TextView tvResult;
    private Dialog busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dav);

        davContent = findViewById(R.id.dav_content);
        guideBody = findViewById(R.id.guide_body);
        guideChev = findViewById(R.id.guide_chev);
        LinearLayout passBox = findViewById(R.id.pass_box);

        inputUrl = findViewById(R.id.input_url);
        inputUser = findViewById(R.id.input_user);
        inputPass = findViewById(R.id.input_pass);
        inputDir = findViewById(R.id.input_dir);
        btnTogglePass = findViewById(R.id.btn_toggle_pass);
        tvResult = findViewById(R.id.tv_result);

        inputUrl.setText(ThemePrefs.davUrl(this));
        inputUser.setText(ThemePrefs.davUser(this));
        inputDir.setText(ThemePrefs.davDir(this));
        String pwd = SecretStore.get(this, ThemePrefs.SECRET_DAV_PASSWORD);
        if (pwd != null && pwd.length() > 0) {
            inputPass.setText(pwd);
        }
        tvResult.setText(ThemePrefs.davLastResult(this));

        Design.focusWrap(passBox, inputPass);
        View guideToggle = findViewById(R.id.guide_toggle);
        View btnTest = findViewById(R.id.btn_test);
        Design.tactile(guideToggle);
        Design.tactile(btnTest);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        guideToggle.setOnClickListener(
                v -> {
                    guideOpen = !guideOpen;
                    Design.animate(davContent);
                    guideBody.setVisibility(guideOpen ? View.VISIBLE : View.GONE);
                    Design.rotateTo(guideChev, guideOpen ? 180f : 0f);
                });
        btnTogglePass.setOnClickListener(v -> togglePasswordVisibility());
        btnTest.setOnClickListener(v -> runTest());
    }

    private void togglePasswordVisibility() {
        passVisible = !passVisible;
        int selStart = inputPass.getSelectionStart();
        int selEnd = inputPass.getSelectionEnd();
        inputPass.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (passVisible
                                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        if (selStart >= 0 && selEnd >= 0) inputPass.setSelection(selStart, selEnd);
        btnTogglePass.setImageResource(passVisible ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        btnTogglePass.setColorFilter(
                Design.color(this, passVisible ? R.color.c_accent : R.color.c_text2));
        btnTogglePass.setContentDescription(passVisible ? "隐藏密码" : "显示密码");
    }

    private void runTest() {
        final String url = inputUrl.getText().toString().trim();
        final String user = inputUser.getText().toString().trim();
        final String pass = inputPass.getText().toString();
        final String dir = inputDir.getText().toString().trim();

        if (TextUtils.isEmpty(user)) {
            Ui.toast(this, "请填写坚果云账号（邮箱）");
            inputUser.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(pass)) {
            Ui.toast(this, "请填写坚果云应用密码");
            inputPass.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(dir)) {
            Ui.toast(this, "请填写备份目录名");
            inputDir.requestFocus();
            return;
        }

        showBusy("① 正在连接并验证账号…");
        new Thread(
                        () -> {
                            StringBuilder sb = new StringBuilder();
                            boolean ok = true;
                            WebDavClient client = new WebDavClient(url, user, pass, 25000);
                            try {
                                long t0 = System.currentTimeMillis();
                                client.testRoot();
                                sb.append("① 账号验证与网络连通（")
                                        .append(System.currentTimeMillis() - t0)
                                        .append(" ms）\n");

                                runOnUiThread(() -> Ui.updateBusy(busy, "② 正在准备云端备份目录…"));
                                long t1 = System.currentTimeMillis();
                                client.ensureCollection(dir);
                                sb.append("② 备份目录「")
                                        .append(dir)
                                        .append("」已就绪（")
                                        .append(System.currentTimeMillis() - t1)
                                        .append(" ms）\n");

                                if (client.exists(dir, PROBE_NAME)) {
                                    client.delete(dir, PROBE_NAME);
                                }

                                runOnUiThread(() -> Ui.updateBusy(busy, "③ 正在验证写入与回读完整性…"));
                                long t2 = System.currentTimeMillis();
                                client.put(dir, PROBE_NAME, PROBE_DATA);
                                sb.append("③ 上传安全探测文件成功（")
                                        .append(System.currentTimeMillis() - t2)
                                        .append(" ms）\n");

                                List<WebDavClient.RemoteFile> files = client.list(dir);
                                long remoteSize = -1;
                                for (WebDavClient.RemoteFile f : files) {
                                    if (PROBE_NAME.equals(f.name)) {
                                        remoteSize = f.size;
                                    }
                                }
                                sb.append("④ 云端目录可见 ")
                                        .append(files.size())
                                        .append(" 个文件\n");
                                if (remoteSize != PROBE_DATA.length) {
                                    ok = false;
                                    sb.append("   校验未通过：云端大小 ")
                                            .append(remoteSize)
                                            .append(" B，本地 ")
                                            .append(PROBE_DATA.length)
                                            .append(" B\n");
                                } else {
                                    sb.append("   完整性校验通过\n");
                                }

                                client.delete(dir, PROBE_NAME);
                                sb.append("⑤ 临时探测数据已自动清理\n");

                                ThemePrefs.setDav(DavActivity.this, url, user, dir);
                                boolean saved =
                                        SecretStore.put(
                                                DavActivity.this,
                                                ThemePrefs.SECRET_DAV_PASSWORD,
                                                pass);
                                sb.append("\n配置已保存")
                                        .append(
                                                saved
                                                        ? "（应用密码已加密存本机）"
                                                        : "（本机保存密码失败："
                                                                + SecretStore.lastError
                                                                + "）");
                                sb.append("\n结果：")
                                        .append(ok ? "连接顺畅，读写正常" : "可访问但校验不一致");
                            } catch (WebDavClient.DavException e) {
                                ok = false;
                                sb.append("连接未通过：").append(e.userMessage);
                            } catch (Exception e) {
                                ok = false;
                                sb.append("连接异常：")
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
                                    (finalOk ? "上次测试：连接正常 · " : "上次测试：失败 · ")
                                            + Ui.time(System.currentTimeMillis()));
                            Repo.addAudit(
                                    DavActivity.this,
                                    "DAV_TEST",
                                    finalOk ? "SUCCESS" : "FAILED",
                                    text.replace('\n', ' ').trim(),
                                    0);

                            runOnUiThread(
                                    () -> {
                                        if (isFinishing() || isDestroyed()) return;
                                        hideBusy();
                                        tvResult.setText(text);
                                        Ui.infoSheet(
                                                DavActivity.this,
                                                finalOk ? "坚果云连接成功" : "连接未能通过",
                                                text,
                                                finalOk
                                                        ? R.drawable.ic_shield_ok
                                                        : R.drawable.ic_info,
                                                !finalOk,
                                                "知道了",
                                                null);
                                    });
                        })
                .start();
    }

    private void showBusy(String msg) {
        hideBusy();
        busy = Ui.busyDialog(this, msg);
    }

    private void hideBusy() {
        Ui.dismissDialog(busy);
        busy = null;
    }

    @Override
    protected void onDestroy() {
        hideBusy();
        super.onDestroy();
    }
}
