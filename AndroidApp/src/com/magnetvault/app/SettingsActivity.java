package com.magnetvault.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 外观与偏好设置：主题卡片切换、列表排版密度、回收站保留时间、以及常用功能快捷跳转。 */
public class SettingsActivity extends AppActivity {

    private static final String[] THEME_NAMES = {"跟随系统", "霜银浅色", "暗夜黑曜"};
    private static final int[] THEME_ICONS = {
        R.drawable.ic_theme_auto, R.drawable.ic_theme_light, R.drawable.ic_theme_dark
    };

    private static final int[] TRASH_DAYS = {0, 7, 30, 90};
    private static final String[] TRASH_LABELS = {"不自动清理", "保留 7 天", "保留 30 天（默认）", "保留 90 天"};
    private static final String[] TRASH_SUBS = {
        "所有删掉的收藏都会一直留在回收站，直到手动清空",
        "适合定期清理的轻量保留周期",
        "兼顾误删找回与空间整洁",
        "适合长期归档后再统一清理"
    };
    private static final int[] TRASH_ICONS = {
        R.drawable.ic_shield, R.drawable.ic_history, R.drawable.ic_trash, R.drawable.ic_history
    };

    private LinearLayout content;
    private final List<OptionCard> densityCards = new ArrayList<>();
    private TextView trashSubTv;

    private static final class OptionCard {
        final LinearLayout root;
        final TextView title;
        final TextView sub;

        OptionCard(LinearLayout root, TextView title, TextView sub) {
            this.root = root;
            this.title = title;
            this.sub = sub;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if ("backup".equals(getIntent().getStringExtra("section"))) {
            Intent i = new Intent(this, CloudActivity.class);
            i.putExtra(EXTRA_NO_ANIM, true);
            startActivity(i);
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        buildAppearanceUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (trashSubTv != null) {
            syncTrashCardText();
            syncDensityCards(ThemePrefs.isCompactList(this));
        }
    }

    private void buildAppearanceUi() {
        LinearLayout root = Design.column(this);
        root.setBackgroundColor(Design.color(this, R.color.c_bg));
        root.addView(Design.toolbar(this, "外观与偏好", this::finish));

        content = Design.column(this);
        Design.pad(this, content, 16, 4, 28);

        // 1. 主题模式三选一视觉卡片
        Design.section(this, content, "主题外观", "异瞳黑猫视觉系统：霜银浅色与暗夜黑曜自由切换");
        LinearLayout themeRow = Design.row(this);
        int curMode = ThemePrefs.getMode(this);
        for (int i = 0; i < THEME_NAMES.length; i++) {
            final int mode = i;
            boolean picked = curMode == mode;
            LinearLayout card = Design.column(this);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setBackgroundResource(picked ? R.drawable.bg_card_selected : R.drawable.bg_card);
            Design.pad(this, card, 10, 14, 14);
            card.setFocusable(true);
            card.setClickable(true);
            Design.tactile(card);

            ImageView preview = new ImageView(this);
            preview.setImageResource(THEME_ICONS[i]);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(preview, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 42)));

            Design.gap(this, card, 10);
            TextView t =
                    Design.text(
                            this, THEME_NAMES[i], 13, picked ? R.color.c_accent : R.color.c_text);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            card.addView(t);
            Design.gap(this, card, 2);
            TextView s =
                    Design.text(
                            this,
                            picked ? "当前使用" : "轻点切换",
                            11,
                            picked ? R.color.c_accent : R.color.c_text2);
            s.setGravity(Gravity.CENTER);
            card.addView(s);

            card.setOnClickListener(
                    v -> {
                        if (ThemePrefs.getMode(this) == mode) return;
                        ThemePrefs.setMode(this, mode);
                        recreate();
                    });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) tp.leftMargin = Ui.dp(this, 8);
            themeRow.addView(card, tp);
        }
        content.addView(themeRow);

        // 2. 列表标题显示密度视觉双卡
        Design.section(this, content, "列表标题显示", "面对长片名和参数时，切换最舒服的阅读密度");
        LinearLayout densityRow = Design.row(this);
        densityCards.clear();
        densityCards.add(
                buildDensityOption(
                        R.drawable.ic_layout_compact,
                        "紧凑标题",
                        "提取番号规格，默认 2 行",
                        () -> applyDensity(true)));
        densityCards.add(
                buildDensityOption(
                        R.drawable.ic_density_full,
                        "完整标题",
                        "展开显示全名，一目了然",
                        () -> applyDensity(false)));
        densityRow.addView(densityCards.get(0).root, new LinearLayout.LayoutParams(0, -2, 1));
        Design.hgap(this, densityRow, 8);
        densityRow.addView(densityCards.get(1).root, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(densityRow);
        syncDensityCards(ThemePrefs.isCompactList(this));

        // 3. 回收站自动清理策略
        Design.section(this, content, "数据与清理", null);
        LinearLayout trashCard =
                Design.action(
                        this,
                        "回收站保留时间",
                        "",
                        R.drawable.ic_trash,
                        this::chooseTrashDays);
        // Locate the subtitle TextView inside Design.action copy column
        LinearLayout trashCopy = (LinearLayout) trashCard.getChildAt(1);
        trashSubTv = (TextView) trashCopy.getChildAt(trashCopy.getChildCount() - 1);
        content.addView(trashCard);
        syncTrashCardText();

        // 4. 常用功能快捷入口
        Design.section(this, content, "常用功能", null);
        content.addView(
                Design.action(
                        this,
                        "备份与云端中心",
                        "本地加密导出 .mjbf、坚果云同步与恢复回滚",
                        R.drawable.ic_cloud,
                        () -> startActivity(new Intent(this, CloudActivity.class))));
        Design.gap(this, content, 8);
        content.addView(
                Design.action(
                        this,
                        "回收站",
                        "找回或彻底清理最近删除的收藏",
                        R.drawable.ic_trash,
                        () -> startActivity(new Intent(this, TrashActivity.class))));
        Design.gap(this, content, 8);
        content.addView(
                Design.action(
                        this,
                        "使用指南 · 快速上手",
                        "查看批量识别、多级分类整理与端到端加密说明",
                        R.drawable.ic_info,
                        () -> Ui.guideSheet(this)));

        Design.gap(this, content, 24);
        TextView ver =
                Design.text(
                        this,
                        "磁力收藏夹 · v" + Design.versionName(this) + " · 本地离线加密保存",
                        12,
                        R.color.c_text2);
        ver.setGravity(Gravity.CENTER);
        content.addView(ver, new LinearLayout.LayoutParams(-1, -2));

        root.addView(Design.scroll(this, content), new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private OptionCard buildDensityOption(
            int iconRes, String titleStr, String subStr, Runnable onClick) {
        LinearLayout card = Design.column(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        Design.pad(this, card, 12, 14, 14);
        card.setFocusable(true);
        card.setClickable(true);
        Design.tactile(card);

        ImageView preview = new ImageView(this);
        preview.setImageResource(iconRes);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(preview, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 44)));

        Design.gap(this, card, 10);
        TextView title = Design.text(this, titleStr, 14, R.color.c_text);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        Design.gap(this, card, 3);
        TextView sub = Design.text(this, subStr, 11, R.color.c_text2);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub);

        card.setOnClickListener(v -> onClick.run());
        return new OptionCard(card, title, sub);
    }

    private void applyDensity(boolean compact) {
        if (ThemePrefs.isCompactList(this) == compact) return;
        ThemePrefs.setCompactList(this, compact);
        Design.animate(content);
        syncDensityCards(compact);
        Ui.toast(
                this,
                compact
                        ? "已切换为紧凑列表（轻点卡片可展开完整标题）"
                        : "已切换为完整标题显示");
    }

    private void syncDensityCards(boolean compact) {
        if (densityCards.size() < 2) return;
        boolean[] picked = {compact, !compact};
        for (int i = 0; i < 2; i++) {
            OptionCard c = densityCards.get(i);
            boolean on = picked[i];
            c.root.setBackgroundResource(on ? R.drawable.bg_card_selected : R.drawable.bg_card);
            c.title.setTextColor(Design.color(this, on ? R.color.c_accent : R.color.c_text));
        }
    }

    private void syncTrashCardText() {
        int keepDays = ThemePrefs.trashKeepDays(this);
        trashSubTv.setText(
                keepDays > 0
                        ? "当前：" + keepDays + " 天后自动清理，避免占用空间"
                        : "当前：不自动清理（需手动在回收站清空）");
    }

    private void chooseTrashDays() {
        int current = ThemePrefs.trashKeepDays(this);
        int idx = 2;
        for (int i = 0; i < TRASH_DAYS.length; i++) {
            if (TRASH_DAYS[i] == current) idx = i;
        }
        Ui.choiceSheet(
                this,
                "回收站保留时间",
                TRASH_LABELS,
                TRASH_SUBS,
                TRASH_ICONS,
                idx,
                w -> {
                    ThemePrefs.setTrashKeepDays(this, TRASH_DAYS[w]);
                    syncTrashCardText();
                    Ui.toast(
                            this,
                            TRASH_DAYS[w] == 0
                                    ? "已设为不自动清理"
                                    : "回收站条目将在 " + TRASH_DAYS[w] + " 天后自动清理");
                });
    }
}
