package com.magnetvault.app;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;

/** 收藏详情：查看、复制、分享、打开、快捷改分类/标签、编辑、星标、移入回收站。 */
public class DetailActivity extends AppActivity {

    public static final String EXTRA_ENTRY_ID = "entryId";

    private String entryId;
    private Entry entry;
    private String resolvedCategoryLabel = "未分类";

    private LinearLayout detailContent;
    private TextView tvTitle;
    private TextView tvMeta;
    private TextView tvProtoBadge;
    private TextView tvCatBadge;
    private TextView tvHash;
    private TextView tvMagnet;
    private TextView tvNote;
    private TextView tvSource;
    private ChipFlow tagFlow;
    private ImageView btnStar;

    private static final class EntrySnap {
        Entry entry;
        String catLabel;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        detailContent = findViewById(R.id.detail_content);
        tvTitle = findViewById(R.id.tv_title);
        tvMeta = findViewById(R.id.tv_meta);
        tvProtoBadge = findViewById(R.id.tv_proto_badge);
        tvCatBadge = findViewById(R.id.tv_cat_badge);
        tvHash = findViewById(R.id.tv_hash);
        tvMagnet = findViewById(R.id.tv_magnet);
        tvNote = findViewById(R.id.tv_note);
        tvSource = findViewById(R.id.tv_source);
        tagFlow = findViewById(R.id.tag_flow);
        btnStar = findViewById(R.id.btn_star);

        View btnEdit = findViewById(R.id.btn_edit);
        View btnCopy = findViewById(R.id.btn_copy);
        View btnShare = findViewById(R.id.btn_share);
        View btnOpen = findViewById(R.id.btn_open);
        View btnDelete = findViewById(R.id.btn_delete);
        TextView btnAdvanced = findViewById(R.id.detail_advanced);

        Design.tactile(tvCatBadge);
        Design.tactile(btnStar);
        Design.tactile(btnEdit);
        Design.tactile(btnCopy);
        Design.tactile(btnShare);
        Design.tactile(btnOpen);
        Design.tactile(btnAdvanced);
        Design.tactile(btnDelete);

        entryId = getIntent().getStringExtra(EXTRA_ENTRY_ID);

        btnAdvanced.setOnClickListener(
                v -> {
                    View raw = findViewById(R.id.detail_raw);
                    boolean open = raw.getVisibility() != View.VISIBLE;
                    Design.animate(detailContent);
                    raw.setVisibility(open ? View.VISIBLE : View.GONE);
                    btnAdvanced.setText(open ? "收起链接与技术信息" : "查看链接与技术信息");
                    setAdvancedIcon(btnAdvanced, open);
                });

        tvCatBadge.setOnClickListener(
                v -> {
                    if (entry == null) return;
                    CategoryPicker.show(
                            DetailActivity.this,
                            "调整所属分类",
                            entry.categoryId,
                            "未分类",
                            "移出文件夹，设为未分类",
                            null,
                            true,
                            pickedId ->
                                    load(
                                            () -> {
                                                Repo.moveCategory(
                                                        DetailActivity.this,
                                                        Collections.singletonList(entryId),
                                                        pickedId);
                                                return true;
                                            },
                                            ok -> {
                                                reloadEntry();
                                                Ui.toast(DetailActivity.this, "已更新所属分类");
                                            }));
                });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnEdit.setOnClickListener(
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
                    Design.popBounce(btnStar);
                    load(
                            () -> {
                                Repo.setStar(DetailActivity.this, entry.id, next);
                                return true;
                            },
                            ok -> Ui.toast(DetailActivity.this, next ? "已添加星标" : "已取消星标"));
                });
        btnCopy.setOnClickListener(
                v -> {
                    if (entry != null) Ui.copy(DetailActivity.this, entry.magnet);
                });
        btnShare.setOnClickListener(
                v -> {
                    if (entry != null) Ui.share(DetailActivity.this, entry.magnet);
                });
        btnOpen.setOnClickListener(
                v -> {
                    if (entry != null) Ui.openMagnet(DetailActivity.this, entry.magnet);
                });
        btnDelete.setOnClickListener(v -> confirmDelete());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadEntry();
    }

    private void reloadEntry() {
        if (TextUtils.isEmpty(entryId)) {
            finish();
            return;
        }
        load(
                () -> {
                    EntrySnap s = new EntrySnap();
                    s.entry = Repo.byId(this, entryId);
                    if (s.entry != null) {
                        s.catLabel = Repo.categoryLabel(this, s.entry.categoryId);
                    }
                    return s;
                },
                s -> {
                    if (s.entry == null || s.entry.deletedAt != null) {
                        Ui.toast(this, "该收藏已不存在");
                        finish();
                        return;
                    }
                    entry = s.entry;
                    resolvedCategoryLabel = TextUtils.isEmpty(s.catLabel) ? "未分类" : s.catLabel;
                    bind();
                });
    }

    private void setAdvancedIcon(TextView view, boolean open) {
        Drawable chev =
                getDrawable(open ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right)
                        .mutate();
        chev.setTint(Design.color(this, R.color.c_accent));
        int sz = Ui.dp(this, 18);
        chev.setBounds(0, 0, sz, sz);
        view.setCompoundDrawablesRelative(null, null, chev, null);
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
        tvProtoBadge.setText(
                ed2k ? (size > 0 ? "ED2K · " + Ui.sizeText(size) : "ED2K 文件") : "BT 磁力");
        tvProtoBadge.setTextColor(Design.color(this, ed2k ? R.color.c_accent2 : R.color.c_accent));
        tvProtoBadge.setBackgroundResource(
                ed2k ? R.drawable.bg_badge_ed2k : R.drawable.bg_badge_magnet);

        tvCatBadge.setText(resolvedCategoryLabel);
        int folderTint =
                entry.categoryId != null && entry.categoryColor != 0
                        ? entry.categoryColor
                        : Design.color(this, R.color.c_text2);
        Drawable folderIcon = getDrawable(R.drawable.ic_folder).mutate();
        folderIcon.setTint(folderTint);
        int fsz = Ui.dp(this, 14);
        folderIcon.setBounds(0, 0, fsz, fsz);
        Drawable chevIcon = getDrawable(R.drawable.ic_chevron_right).mutate();
        chevIcon.setTint(Design.color(this, R.color.c_text2));
        int csz = Ui.dp(this, 13);
        chevIcon.setBounds(0, 0, csz, csz);
        tvCatBadge.setCompoundDrawablesRelative(folderIcon, null, chevIcon, null);

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
        tagFlow.addView(
                Design.tagChip(
                        this,
                        entry.tags.isEmpty() ? "+ 添加标签" : "+ 调整标签",
                        true,
                        this::openTagEditor));

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

    private void openTagEditor() {
        if (entry == null) return;
        TagPicker.show(
                this,
                "调整收藏标签",
                entry.tags,
                picked -> {
                    entry.tags.clear();
                    entry.tags.addAll(picked);
                    load(
                            () -> {
                                Repo.save(DetailActivity.this, entry);
                                return true;
                            },
                            ok -> {
                                reloadEntry();
                                Ui.toast(DetailActivity.this, "已更新标签");
                            });
                });
    }

    private void confirmDelete() {
        if (entry == null) return;
        int keep = ThemePrefs.trashKeepDays(this);
        Ui.confirmSheet(
                this,
                "移入回收站",
                "「"
                        + entry.displayTitle()
                        + "」将移入回收站，"
                        + (keep > 0
                                ? keep + " 天内可随时恢复。"
                                : "之后可在「我的 → 回收站」恢复。"),
                "移入回收站",
                true,
                () ->
                        load(
                                () -> {
                                    Repo.softDelete(DetailActivity.this, entryId);
                                    return true;
                                },
                                ok -> {
                                    Ui.toast(DetailActivity.this, "已移入回收站");
                                    finish();
                                }));
    }
}
