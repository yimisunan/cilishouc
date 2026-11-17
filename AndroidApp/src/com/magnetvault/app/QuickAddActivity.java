package com.magnetvault.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 外部一键添加入口（无界面，处理完就关）。
 *
 * <p>支持： magnetvault://add?magnet=<链接>&title=<名字>&source=<出处> magnetvault://import-clipboard
 * 读剪贴板批量导入 magnetvault://add?magnet=... （参数名也接受 m / t / s）
 */
public class QuickAddActivity extends Activity {

    public static final String SCHEME = "magnetvault";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemePrefs.apply(this);
        try {
            handle(getIntent());
        } catch (Throwable t) {
            Ui.toast(this, "导入失败：" + t.getClass().getSimpleName());
            Repo.addAudit(this, "IMPORT_INTENT", "FAILED", String.valueOf(t), 0);
        }
        finish();
    }

    private void handle(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        String host =
                data == null ? "" : String.valueOf(data.getHost() == null ? "" : data.getHost());
        String path = data == null || data.getPath() == null ? "" : data.getPath().replace("/", "");

        if (SCHEME.equals(data == null ? null : data.getScheme())) {
            if ("import-clipboard".equals(host)
                    || "import".equals(host)
                    || "import-clipboard".equals(path)) {
                String text = ClipImporter.readClipboard(this);
                ClipImporter.Result r = ClipImporter.importFromText(this, text);
                if (r.added == 0 && r.duplicate == 0 && r.invalid == 0) {
                    Ui.toast(this, "剪贴板里没找到磁力或 ED2K 文件链接");
                } else {
                    Ui.toast(this, "已从剪贴板导入：" + r.describe());
                }
                return;
            }
            // 单条添加
            String magnet =
                    firstNonEmpty(
                            data.getQueryParameter("magnet"),
                            data.getQueryParameter("m"),
                            data.getQueryParameter("url"));
            if (magnet == null) {
                // 没带参数：当作"从剪贴板导入"
                String text = ClipImporter.readClipboard(this);
                ClipImporter.Result r = ClipImporter.importFromText(this, text);
                Ui.toast(this, r.added > 0 ? "已从剪贴板导入：" + r.describe() : "剪贴板里没找到磁力或 ED2K 文件链接");
                return;
            }
            magnet = magnet.trim();
            if (!MagnetParser.isValid(magnet)) {
                Ui.toast(this, "链接无效，未添加");
                return;
            }
            String title =
                    firstNonEmpty(data.getQueryParameter("title"), data.getQueryParameter("t"));
            String source =
                    firstNonEmpty(
                            data.getQueryParameter("source"),
                            data.getQueryParameter("s"),
                            data.getQueryParameter("ref"));

            if (Repo.findDuplicate(this, MagnetParser.normalize(magnet), magnet, null) != null) {
                Ui.toast(this, "这条已经收藏过了");
                return;
            }
            Entry e = new Entry();
            e.id = java.util.UUID.randomUUID().toString();
            e.magnet = magnet;
            e.normalized = MagnetParser.normalize(magnet);
            e.infoHash = MagnetParser.infoHash(magnet);
            e.title =
                    title == null || title.trim().isEmpty()
                            ? MagnetParser.titleFromLink(magnet)
                            : title.trim();
            e.source = source == null ? "" : source.trim();
            e.note = "";
            e.createdAt = System.currentTimeMillis();
            e.updatedAt = e.createdAt;
            Repo.save(this, e);
            Repo.addAudit(this, "IMPORT_INTENT", "SUCCESS", e.displayTitle(), 1);
            Ui.toast(this, "已收藏：" + e.displayTitle());
            return;
        }

        // 兜底：文本里带磁力（比如别的应用分享过来的文字）
        String text = intent == null ? null : intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) {
            text = ClipImporter.readClipboard(this);
        }
        ClipImporter.Result r = ClipImporter.importFromText(this, text);
        Ui.toast(this, r.added > 0 ? "已导入：" + r.describe() : "没有可导入的磁力或 ED2K 文件链接");
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && v.trim().length() > 0) {
                return v;
            }
        }
        return null;
    }
}
