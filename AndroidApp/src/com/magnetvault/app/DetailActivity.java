package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** 收藏详情：查看、复制、分享、打开、编辑、星标、删除。 */
public class DetailActivity extends AppActivity {

    public static final String EXTRA_ENTRY_ID = "entryId";

    private String entryId;
    private Entry entry;

    private TextView tvTitle;
    private TextView tvMeta;
    private TextView tvProtoBadge;
    private TextView tvCatBadge;
    private TextView tvHash;
    private TextView tvMagnet;
    private TextView tvTags;
    private TextView tvNote;
    private TextView tvSource;
    private ChipFlow tagFlow;
    private ImageView btnStar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        tvTitle = findViewById(R.id.tv_title);
        tvMeta = findViewById(R.id.tv_meta);
        tvProtoBadge = findViewById(R.id.tv_proto_badge);
        tvCatBadge = findViewById(R.id.tv_cat_badge);
        tvHash = findViewById(R.id.tv_hash);
        tvMagnet = findViewById(R.id.tv_magnet);
        tvTags = findViewById(R.id.tv_tags);
        tvNote = findViewById(R.id.tv_note);
        tvSource = findViewById(R.id.tv_source);
        tagFlow = findViewById(R.id.tag_flow);
        btnStar = findViewById(R.id.btn_star);

        entryId = getIntent().getStringExtra(EXTRA_ENTRY_ID);
        findViewById(R.id.detail_advanced)
                .setOnClickListener(
                        v -> {
                            View raw = findViewById(R.id.detail_raw);
                            boolean open = raw.getVisibility() != View.VISIBLE;
                            raw.setVisibility(open ? View.VISIBLE : View.GONE);
                            ((TextView) v).setText(open ? "收起链接与技术信息" : "查看链接与技术信息");
                        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_edit)
                .setOnClickListener(
                        v -> {
                            Intent i = new Intent(DetailActivity.this, EditActivity.class);
                            i.putExtra(EditActivity.EXTRA_ENTRY_ID, entryId);
                            startActivity(i);
                        });
        btnStar.setOnClickListener(
                v -> {
                    if (entry == null) return;
                    final boolean next = !entry.starred;
                    entry.starred = next;
                    updateStarIcon();
                    load(
                            () -> {
                                Repo.setStar(DetailActivity.this, entry.id, next);
                                return true;
                            },
                            ok -> Ui.toast(DetailActivity.this, next ? "已添加星标" : "已取消星标"));
                });
        findViewById(R.id.btn_copy)
                .setOnClickListener(
                        v -> {
                            if (entry != null) Ui.copy(DetailActivity.this, entry.magnet);
                        });
        findViewById(R.id.btn_share)
                .setOnClickListener(
                        v -> {
                            if (entry != null) Ui.share(DetailActivity.this, entry.magnet);
                        });
        findViewById(R.id.btn_open)
                .setOnClickListener(
                        v -> {
                            if (entry != null) Ui.openMagnet(DetailActivity.this, entry.magnet);
                        });
        findViewById(R.id.btn_delete).setOnClickListener(v -> confirmDelete());
    }

    @Override
    protected void onResume() {
        super.onResume();
        entry = Repo.byId(this, entryId);
        if (entry == null || entry.deletedAt != null) {
            Ui.toast(this, "该收藏已不存在");
            finish();
            return;
        }
        bind();
    }

    private void updateStarIcon() {
        if (entry == null) return;
        btnStar.setImageResource(entry.starred ? R.drawable.ic_star : R.drawable.ic_star_outline);
        btnStar.setColorFilter(
                Design.color(this, entry.starred ? R.color.c_star : R.color.c_text2));
        btnStar.setContentDescription(entry.starred ? "取消星标" : "添加星标");
    }

    private void bind() {
        updateStarIcon();
        tvTitle.setText(entry.displayTitle());

        boolean ed2k = MagnetParser.isEd2k(entry.magnet);
        long size = ed2k ? MagnetParser.xlSize(entry.magnet) : 0;
        tvProtoBadge.setText(ed2k ? (size > 0 ? "ED2K · " + Ui.sizeText(size) : "ED2K 文件") : "BT 磁力");
        tvProtoBadge.setTextColor(Design.color(this, ed2k ? R.color.c_accent2 : R.color.c_accent));
        tvProtoBadge.setBackgroundResource(
                ed2k ? R.drawable.bg_badge_ed2k : R.drawable.bg_badge_magnet);

        tvCatBadge.setText(entry.categoryId == null ? "未分类" : entry.categoryName);

        StringBuilder meta = new StringBuilder();
        meta.append("收藏于 ").append(Ui.time(entry.createdAt));
        if (entry.updatedAt != entry.createdAt) {
            meta.append(" · 更新于 ").append(Ui.time(entry.updatedAt));
        }
        tvMeta.setText(meta.toString());

        ((TextView) findViewById(R.id.link_type_label)).setText(ed2k ? "ED2K 文件链接" : "磁力链接");
        ((TextView) findViewById(R.id.hash_type_label))
                .setText(ed2k ? "ED2K 文件哈希（MD4）" : "INFO HASH（BTIH）");

        tvHash.setText(TextUtils.isEmpty(entry.infoHash) ? "（未能解析出文件哈希）" : entry.infoHash);
        tvMagnet.setText(entry.magnet);

        tagFlow.removeAllViews();
        if (entry.tags.isEmpty()) {
            tagFlow.setVisibility(View.GONE);
            tvTags.setVisibility(View.VISIBLE);
            tvTags.setText("未添加标签");
        } else {
            tvTags.setVisibility(View.GONE);
            tagFlow.setVisibility(View.VISIBLE);
            for (final String tag : entry.tags) {
                tagFlow.addView(
                        Design.tagChip(
                                this,
                                "# " + tag,
                                false,
                                () -> {
                                    Intent i = new Intent(DetailActivity.this, SearchActivity.class);
                                    i.putExtra("tag", tag);
                                    startActivity(i);
                                }));
            }
        }

        boolean hasNote = !TextUtils.isEmpty(entry.note);
        tvNote.setText(hasNote ? entry.note : "未添加备注");
        tvNote.setTextColor(Design.color(this, hasNote ? R.color.c_text : R.color.c_text2));

        if (TextUtils.isEmpty(entry.source)) {
            tvSource.setText("未添加来源");
            tvSource.setTextColor(Design.color(this, R.color.c_text2));
            tvSource.setBackgroundResource(0);
            tvSource.setPadding(0, 0, 0, 0);
            tvSource.setOnClickListener(null);
            tvSource.setOnLongClickListener(null);
        } else {
            boolean web = entry.source.trim().matches("(?i)^https?://.*");
            tvSource.setText(entry.source);
            tvSource.setTextColor(Design.color(this, R.color.c_accent));
            tvSource.setBackgroundResource(R.drawable.bg_source_chip);
            Design.pad(this, tvSource, 12, 10, 10);
            tvSource.setOnClickListener(
                    web ? v -> Ui.openUrl(DetailActivity.this, entry.source) : null);
            tvSource.setOnLongClickListener(
                    v -> {
                        Ui.copy(DetailActivity.this, entry.source);
                        return true;
                    });
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("移入回收站")
                .setMessage(
                        "「"
                                + entry.displayTitle()
                                + "」将移入回收站，"
                                + (ThemePrefs.trashKeepDays(this) > 0
                                        ? ThemePrefs.trashKeepDays(this) + " 天内可以恢复。"
                                        : "之后可在回收站恢复（未开启自动清理）。"))
                .setPositiveButton(
                        "移入回收站",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Repo.softDelete(DetailActivity.this, entryId);
                                Ui.toast(DetailActivity.this, "已移入回收站");
                                finish();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }
}
