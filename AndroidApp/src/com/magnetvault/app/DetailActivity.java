package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

/** 收藏详情：查看、复制、分享、打开、编辑、删除。 */
public class DetailActivity extends AppActivity {

    public static final String EXTRA_ENTRY_ID = "entryId";

    private String entryId;
    private Entry entry;

    private TextView tvTitle;
    private TextView tvMeta;
    private TextView tvHash;
    private TextView tvMagnet;
    private TextView tvTags;
    private TextView tvNote;
    private TextView tvSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        tvTitle = findViewById(R.id.tv_title);
        tvMeta = findViewById(R.id.tv_meta);
        tvHash = findViewById(R.id.tv_hash);
        tvMagnet = findViewById(R.id.tv_magnet);
        tvTags = findViewById(R.id.tv_tags);
        tvNote = findViewById(R.id.tv_note);
        tvSource = findViewById(R.id.tv_source);

        entryId = getIntent().getStringExtra(EXTRA_ENTRY_ID);
        findViewById(R.id.detail_advanced)
                .setOnClickListener(
                        v -> {
                            View raw = findViewById(R.id.detail_raw);
                            boolean open = raw.getVisibility() != View.VISIBLE;
                            raw.setVisibility(open ? View.VISIBLE : View.GONE);
                            ((TextView) v).setText(open ? "收起链接与技术信息" : "查看链接与技术信息");
                        });

        findViewById(R.id.btn_back)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                finish();
                            }
                        });
        findViewById(R.id.btn_edit)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Intent i = new Intent(DetailActivity.this, EditActivity.class);
                                i.putExtra(EditActivity.EXTRA_ENTRY_ID, entryId);
                                startActivity(i);
                            }
                        });
        findViewById(R.id.btn_copy)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (entry != null) {
                                    Ui.copy(DetailActivity.this, entry.magnet);
                                }
                            }
                        });
        findViewById(R.id.btn_share)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (entry != null) {
                                    Ui.share(DetailActivity.this, entry.magnet);
                                }
                            }
                        });
        findViewById(R.id.btn_open)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (entry != null) {
                                    Ui.openMagnet(DetailActivity.this, entry.magnet);
                                }
                            }
                        });
        findViewById(R.id.btn_delete)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                confirmDelete();
                            }
                        });
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

    private void bind() {
        tvTitle.setText(entry.displayTitle());
        StringBuilder meta = new StringBuilder();
        meta.append(entry.categoryId == null ? "未分类" : entry.categoryName);
        meta.append(" · ").append(Ui.time(entry.createdAt));
        if (entry.updatedAt != entry.createdAt) {
            meta.append(" · 修改于 ").append(Ui.time(entry.updatedAt));
        }
        if (entry.starred) {
            meta.append(" · ★ 星标");
        }
        boolean ed2k = MagnetParser.isEd2k(entry.magnet);
        if (ed2k) {
            meta.append(" · ED2K");
            long size = MagnetParser.xlSize(entry.magnet);
            if (size > 0) meta.append(" · ").append(Ui.sizeText(size));
        }
        tvMeta.setText(meta.toString());
        ((TextView) findViewById(R.id.link_type_label)).setText(ed2k ? "ED2K 文件链接" : "磁力链接");
        ((TextView) findViewById(R.id.hash_type_label))
                .setText(ed2k ? "ED2K 文件哈希（MD4）" : "INFO HASH（BTIH）");

        tvHash.setText(TextUtils.isEmpty(entry.infoHash) ? "（未能解析出文件哈希）" : entry.infoHash);
        tvMagnet.setText(entry.magnet);
        tvTags.setText(entry.tags.isEmpty() ? "未添加标签" : entry.tagText());
        tvNote.setText(TextUtils.isEmpty(entry.note) ? "未添加备注" : entry.note);
        if (TextUtils.isEmpty(entry.source)) {
            tvSource.setText("未添加来源");
            tvSource.setTextColor(getResources().getColor(R.color.c_text2));
            tvSource.setBackgroundResource(0);
            tvSource.setOnClickListener(null);
            tvSource.setOnLongClickListener(null);
        } else {
            boolean web = entry.source.trim().matches("(?i)^https?://.*");
            tvSource.setText(entry.source);
            tvSource.setTextColor(getResources().getColor(R.color.c_accent));
            tvSource.setBackgroundResource(R.drawable.bg_source_chip);
            tvSource.setOnClickListener(
                    web
                            ? new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    Ui.openUrl(DetailActivity.this, entry.source);
                                }
                            }
                            : null);
            tvSource.setOnLongClickListener(
                    new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            Ui.copy(DetailActivity.this, entry.source);
                            return true;
                        }
                    });
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除收藏")
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
