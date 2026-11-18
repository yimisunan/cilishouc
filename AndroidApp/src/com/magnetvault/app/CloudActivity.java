package com.magnetvault.app;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 备份与云端中心：密码保护、本地 .mjbf 导出恢复、坚果云自动/手动备份、历史存档、操作日志与快照回滚。 */
public class CloudActivity extends AppActivity {

    private static final String BACKUP_PWD_KEY = CloudBackup.PWD_KEY;
    private static final int REQ_EXPORT = 2001;
    private static final int REQ_IMPORT = 2002;

    private final List<WebDavClient.RemoteFile> files = new ArrayList<>();
    private CloudAdapter adapter;
    private ListView list;
    private View btnRefresh;
    private Dialog busy;
    private boolean refreshing;
    private String lastListError;
    private String pendingName;

    private LinearLayout headerView;
    private LinearLayout footerView;
    private TextView heroStatusPill;
    private TextView heroStatsTv;
    private MgmtCard pwdCard;
    private MgmtCard davCard;
    private MgmtCard autoCard;
    private TextView cloudSectionSub;
    private LinearLayout emptyCloudCard;

    private LinearLayout auditBody;
    private TextView auditSummaryTv;
    private ImageView auditChev;
    private boolean auditExpanded;
    private MgmtCard rollbackCard;

    private static final class MgmtCard {
        final LinearLayout root;
        final FrameLayout badge;
        final ImageView icon;
        final TextView title;
        final TextView sub;
        final FrameLayout rightSlot;
        final ImageView chev;

        MgmtCard(
                LinearLayout root,
                FrameLayout badge,
                ImageView icon,
                TextView title,
                TextView sub,
                FrameLayout rightSlot,
                ImageView chev) {
            this.root = root;
            this.badge = badge;
            this.icon = icon;
            this.title = title;
            this.sub = sub;
            this.rightSlot = rightSlot;
            this.chev = chev;
        }
    }

    private static final class DashSnap {
        int activeCount;
        int trashCount;
        List<Repo.AuditItem> audits;
        File snapshot;
    }

    private static final class CloudHolder {
        final View card;
        final TextView name;
        final TextView sub;
        final View action;

        CloudHolder(View root) {
            card = root.findViewById(R.id.cloud_card);
            name = root.findViewById(R.id.cloud_name);
            sub = root.findViewById(R.id.cloud_sub);
            action = root.findViewById(R.id.cloud_action);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingName = savedInstanceState.getString("pendingName");
        }
        setContentView(R.layout.activity_cloud);

        list = findViewById(R.id.list);
        btnRefresh = findViewById(R.id.btn_refresh);
        Design.tactile(btnRefresh);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(
                v -> {
                    refreshDashboard();
                    refresh();
                });

        headerView = buildHeader();
        headerView.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        footerView = buildFooter();
        footerView.setLayoutParams(new AbsListView.LayoutParams(-1, -2));

        list.addHeaderView(headerView, null, false);
        list.addFooterView(footerView, null, false);

        adapter = new CloudAdapter();
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
        refresh();
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

    // ==================== 统一头部与底部构建 ====================

    private LinearLayout buildHeader() {
        LinearLayout box = Design.column(this);
        Design.pad(this, box, 14, 10, 6);

        // Hero card (Noir Cat Obsidian Stage)
        LinearLayout hero = Design.column(this);
        hero.setBackgroundResource(R.drawable.bg_cat_stage);
        Design.pad(this, hero, 16, 16, 16);

        LinearLayout heroRow = Design.row(this);
        ImageView ill = new ImageView(this);
        ill.setImageResource(R.drawable.ill_cloud_sync);
        ill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 68));
        ip.rightMargin = Ui.dp(this, 14);
        heroRow.addView(ill, ip);

        LinearLayout heroInfo = Design.column(this);
        heroStatusPill = Design.text(this, "检测状态…", 11, R.color.c_cat_eye_left);
        heroStatusPill.setTypeface(null, Typeface.BOLD);
        heroStatusPill.setBackgroundResource(R.drawable.bg_pill_on);
        Design.pad(this, heroStatusPill, 10, 4, 4);
        heroInfo.addView(heroStatusPill, new LinearLayout.LayoutParams(-2, -2));

        Design.gap(this, heroInfo, 8);
        TextView heroTitle = Design.text(this, "加密备份与云端同步", 18, R.color.c_cat_stage_text);
        heroTitle.setTypeface(null, Typeface.BOLD);
        heroInfo.addView(heroTitle);

        Design.gap(this, heroInfo, 4);
        heroStatsTv = Design.text(this, "正在统计本地收藏与同步记录…", 12, R.color.c_cat_stage_sub);
        heroInfo.addView(heroStatsTv);

        heroRow.addView(heroInfo, new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(heroRow);
        box.addView(hero);

        Design.gap(this, box, 12);

        // Management cards: password, WebDAV account, daily auto-backup
        pwdCard = buildMgmtCard(R.drawable.ic_lock, this::editBackupPassword);
        box.addView(pwdCard.root);
        Design.gap(this, box, 8);

        davCard =
                buildMgmtCard(
                        R.drawable.ic_link,
                        () -> startActivity(new Intent(CloudActivity.this, DavActivity.class)));
        box.addView(davCard.root);
        Design.gap(this, box, 8);

        autoCard = buildMgmtCard(R.drawable.ic_history, this::toggleAutoBackup);
        autoCard.chev.setVisibility(View.GONE);
        box.addView(autoCard.root);

        Design.gap(this, box, 10);

        // Dual local .mjbf cards
        LinearLayout dualRow = Design.row(this);
        dualRow.addView(
                buildQuickCard(
                        R.drawable.ic_import,
                        R.color.c_accent,
                        R.color.c_accent_soft,
                        "导出加密文件",
                        "保存本地 .mjbf 密文",
                        this::startExport),
                new LinearLayout.LayoutParams(0, -2, 1));
        Design.hgap(this, dualRow, 8);
        dualRow.addView(
                buildQuickCard(
                        R.drawable.ic_restore,
                        R.color.c_accent2,
                        R.color.c_accent2_soft,
                        "从文件恢复",
                        "导入本地 .mjbf 备份",
                        this::startImport),
                new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(dualRow);

        Design.gap(this, box, 12);

        Button backupBtn =
                Design.button(this, "立即备份到坚果云", R.drawable.ic_cloud, true, this::startBackup);
        box.addView(backupBtn, new LinearLayout.LayoutParams(-1, -2));

        // Cloud list section heading
        Design.gap(this, box, 20);
        TextView secTitle = Design.text(this, "云端历史备份", 15, R.color.c_text);
        secTitle.setTypeface(null, Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) secTitle.setAccessibilityHeading(true);
        box.addView(secTitle);
        Design.gap(this, box, 3);
        cloudSectionSub = Design.text(this, "连接坚果云后可查看并轻点恢复任意存档", 12, R.color.c_text2);
        box.addView(cloudSectionSub);
        Design.gap(this, box, 10);

        emptyCloudCard = Design.column(this);
        emptyCloudCard.setBackgroundResource(R.drawable.bg_card_soft);
        box.addView(emptyCloudCard);

        return box;
    }

    private LinearLayout buildFooter() {
        LinearLayout box = Design.column(this);
        Design.pad(this, box, 14, 12, 8);

        // Expandable audit timeline card
        LinearLayout auditCard = Design.column(this);
        auditCard.setBackgroundResource(R.drawable.bg_card);

        LinearLayout auditHead = Design.row(this);
        Design.pad(this, auditHead, 12, 12, 12);
        auditHead.setMinimumHeight(Ui.dp(this, 62));
        auditHead.setFocusable(true);
        auditHead.setClickable(true);
        Design.tactile(auditHead);

        FrameLayout aBadge =
                Design.iconBadge(
                        this,
                        R.drawable.ic_history,
                        Design.color(this, R.color.c_accent2),
                        Design.color(this, R.color.c_accent2_soft),
                        38,
                        19);
        LinearLayout.LayoutParams abp = (LinearLayout.LayoutParams) aBadge.getLayoutParams();
        abp.rightMargin = Ui.dp(this, 12);
        auditHead.addView(aBadge, abp);

        LinearLayout aCopy = Design.column(this);
        TextView aTitle = Design.text(this, "最近同步与操作记录", 15, R.color.c_text);
        aTitle.setTypeface(null, Typeface.BOLD);
        aCopy.addView(aTitle);
        Design.gap(this, aCopy, 2);
        auditSummaryTv = Design.text(this, "轻点展开最近同步与恢复记录", 12, R.color.c_text2);
        auditSummaryTv.setSingleLine(true);
        auditSummaryTv.setEllipsize(TextUtils.TruncateAt.END);
        aCopy.addView(auditSummaryTv);
        auditHead.addView(aCopy, new LinearLayout.LayoutParams(0, -2, 1));

        auditChev = new ImageView(this);
        auditChev.setImageResource(R.drawable.ic_chevron_down);
        auditChev.setColorFilter(Design.color(this, R.color.c_text2));
        auditChev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        auditHead.addView(auditChev, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));

        auditHead.setOnClickListener(
                v -> {
                    auditExpanded = !auditExpanded;
                    Design.animate(footerView);
                    auditBody.setVisibility(auditExpanded ? View.VISIBLE : View.GONE);
                    Design.rotateTo(auditChev, auditExpanded ? 180f : 0f);
                });
        auditCard.addView(auditHead);

        auditBody = Design.column(this);
        Design.pad(this, auditBody, 12, 0, 12);
        auditBody.setVisibility(View.GONE);
        auditCard.addView(auditBody);

        box.addView(auditCard);
        Design.gap(this, box, 10);

        // Rollback snapshot card
        rollbackCard =
                buildMgmtCard(
                        R.drawable.ic_restore,
                        () ->
                                RestoreFlow.rollbackLatestSnapshot(
                                        this,
                                        () -> {
                                            refreshDashboard();
                                            refresh();
                                        }));
        rollbackCard.title.setText("撤销最近一次恢复");
        rollbackCard.sub.setText("每次恢复前自动留存一份安全快照");
        box.addView(rollbackCard.root);
        Design.gap(this, box, 12);

        // Security explanation card
        LinearLayout secCard = Design.row(this);
        secCard.setBackgroundResource(R.drawable.bg_card_soft);
        Design.pad(this, secCard, 14, 12, 12);

        ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.ic_shield_ok);
        shield.setColorFilter(Design.color(this, R.color.c_accent));
        shield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        sp.rightMargin = Ui.dp(this, 12);
        sp.gravity = Gravity.TOP;
        sp.topMargin = Ui.dp(this, 2);
        secCard.addView(shield, sp);

        LinearLayout secCopy = Design.column(this);
        TextView secHead = Design.text(this, "端到端客户端加密", 13, R.color.c_text);
        secHead.setTypeface(null, Typeface.BOLD);
        secCopy.addView(secHead);
        Design.gap(this, secCopy, 3);
        secCopy.addView(
                Design.text(
                        this,
                        "所有云端与本地 .mjbf 备份均在本机以 PBKDF2 + AES-256-GCM 加密，坚果云仅存储密文；上传后自动回读核验文件大小。",
                        12,
                        R.color.c_text2));
        secCard.addView(secCopy, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(secCard);

        return box;
    }

    private MgmtCard buildMgmtCard(int iconRes, Runnable onClick) {
        LinearLayout card = Design.row(this);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setMinimumHeight(Ui.dp(this, 64));
        Design.pad(this, card, 12, 11, 11);
        card.setFocusable(true);
        card.setClickable(true);
        Design.tactile(card);

        FrameLayout badge =
                Design.iconBadge(
                        this,
                        iconRes,
                        Design.color(this, R.color.c_accent),
                        Design.color(this, R.color.c_accent_soft),
                        38,
                        19);
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        bp.rightMargin = Ui.dp(this, 12);
        card.addView(badge, bp);

        LinearLayout copy = Design.column(this);
        TextView title = Design.text(this, "", 15, R.color.c_text);
        title.setTypeface(null, Typeface.BOLD);
        copy.addView(title);
        Design.gap(this, copy, 2);
        TextView sub = Design.text(this, "", 12, R.color.c_text2);
        copy.addView(sub);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout rightSlot = new FrameLayout(this);
        LinearLayout.LayoutParams rsp = new LinearLayout.LayoutParams(-2, -2);
        rsp.leftMargin = Ui.dp(this, 8);
        card.addView(rightSlot, rsp);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(Design.color(this, R.color.c_text2));
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18));
        cp.leftMargin = Ui.dp(this, 4);
        card.addView(chev, cp);

        card.setOnClickListener(v -> onClick.run());
        return new MgmtCard(
                card, badge, (ImageView) badge.getChildAt(0), title, sub, rightSlot, chev);
    }

    private LinearLayout buildQuickCard(
            int iconRes,
            int tintRes,
            int bgRes,
            String titleStr,
            String subStr,
            Runnable onClick) {
        LinearLayout card = Design.row(this);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setMinimumHeight(Ui.dp(this, 64));
        Design.pad(this, card, 12, 11, 11);
        card.setFocusable(true);
        card.setClickable(true);
        Design.tactile(card);

        FrameLayout badge =
                Design.iconBadge(
                        this,
                        iconRes,
                        Design.color(this, tintRes),
                        Design.color(this, bgRes),
                        34,
                        18);
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        bp.rightMargin = Ui.dp(this, 10);
        card.addView(badge, bp);

        LinearLayout copy = Design.column(this);
        TextView t = Design.text(this, titleStr, 14, R.color.c_text);
        t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(t);
        Design.gap(this, copy, 2);
        TextView s = Design.text(this, subStr, 11, R.color.c_text2);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(s);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        card.setOnClickListener(v -> onClick.run());
        return card;
    }

    private void refreshDashboard() {
        final String rememberedPwd = SecretStore.get(this, BACKUP_PWD_KEY);
        final boolean pwdSaved = rememberedPwd != null && rememberedPwd.length() > 0;
        final boolean pwdSet = pwdSaved || ThemePrefs.isBackupPasswordSet(this);
        final boolean davOk = ThemePrefs.isDavConfigured(this);
        final boolean autoOn = BackupJob.isScheduled(this) || ThemePrefs.autoBackup(this);
        final long lastBackup = ThemePrefs.lastBackupAt(this);

        load(
                () -> {
                    DashSnap s = new DashSnap();
                    s.activeCount = Repo.countActive(this);
                    s.trashCount = Repo.countTrash(this);
                    s.audits = Repo.recentAudits(this, 8);
                    s.snapshot = BackupManager.latestSnapshot(this);
                    return s;
                },
                s -> {
                    // Hero pill & summary
                    if (pwdSet && davOk) {
                        heroStatusPill.setText("双重加密同步已就绪");
                        heroStatusPill.setBackgroundResource(R.drawable.bg_pill_on);
                        heroStatusPill.setTextColor(Design.color(this, R.color.c_cat_eye_left));
                    } else if (pwdSet) {
                        heroStatusPill.setText("本地加密密码已就绪");
                        heroStatusPill.setBackgroundResource(R.drawable.bg_pill_on);
                        heroStatusPill.setTextColor(Design.color(this, R.color.c_cat_eye_left));
                    } else {
                        heroStatusPill.setText("待设置备份密码");
                        heroStatusPill.setBackgroundResource(R.drawable.bg_pill_on);
                        heroStatusPill.setTextColor(Design.color(this, R.color.c_cat_eye_right));
                    }
                    String cloudInfo =
                            files.isEmpty() ? "" : " · 云端 " + files.size() + " 份";
                    heroStatsTv.setText(
                            "收藏 "
                                    + s.activeCount
                                    + " 条 · 回收站 "
                                    + s.trashCount
                                    + " 条"
                                    + cloudInfo
                                    + "\n"
                                    + (lastBackup == 0
                                            ? "尚未上传过云端备份"
                                            : "最近同步：" + Ui.time(lastBackup)));

                    // Password card
                    styleBadge(
                            pwdCard,
                            pwdSaved ? R.color.c_accent : R.color.c_accent2,
                            pwdSaved ? R.color.c_accent_soft : R.color.c_accent2_soft);
                    pwdCard.title.setText(
                            pwdSaved
                                    ? "备份密码 · 已在本机记住"
                                    : (pwdSet ? "备份密码 · 已设置" : "设置备份密码"));
                    pwdCard.sub.setText(
                            pwdSaved
                                    ? "采用 Keystore 硬件级保护 · 点击修改或清除"
                                    : (pwdSet
                                            ? "备份与恢复时需手动输入密码 · 点击重新设置"
                                            : "先设置 8 位以上密码，即可导出或同步加密备份"));

                    // Dav card
                    styleBadge(
                            davCard,
                            davOk ? R.color.c_accent : R.color.c_text2,
                            davOk ? R.color.c_accent_soft : R.color.c_card2);
                    davCard.title.setText(davOk ? "坚果云已连接" : "连接坚果云 WebDAV");
                    davCard.sub.setText(
                            davOk
                                    ? ThemePrefs.davUser(this) + " · 目录 /" + ThemePrefs.davDir(this)
                                    : "填入坚果云账号与应用密码，开启安全同步");

                    // Auto backup card
                    styleBadge(
                            autoCard,
                            autoOn ? R.color.c_accent : R.color.c_text2,
                            autoOn ? R.color.c_accent_soft : R.color.c_card2);
                    autoCard.title.setText("每日自动备份");
                    autoCard.sub.setText(
                            autoOn
                                    ? "有网络时每天自动加密上传 · 失败可在下方记录查看"
                                    : "开启后每天自动同步一份加密备份到坚果云");
                    autoCard.rightSlot.removeAllViews();
                    autoCard.rightSlot.addView(Design.toggleBadge(this, autoOn, "已开启", "已关闭"));

                    // Cloud section sub
                    cloudSectionSub.setText(
                            davOk
                                    ? "目录：/"
                                            + ThemePrefs.davDir(this)
                                            + (files.isEmpty()
                                                    ? ""
                                                    : " · 共 " + files.size() + " 份（轻点恢复）")
                                    : "连接坚果云后可查看并恢复历史加密存档");

                    // Audit timeline
                    if (s.audits.isEmpty()) {
                        auditSummaryTv.setText("暂无同步与操作记录");
                    } else {
                        Repo.AuditItem latest = s.audits.get(0);
                        auditSummaryTv.setText(latest.title + " · " + latest.time);
                    }
                    renderAuditTimeline(s.audits);

                    // Rollback card
                    if (s.snapshot != null) {
                        styleBadge(rollbackCard, R.color.c_accent, R.color.c_accent_soft);
                        rollbackCard.sub.setText(
                                "最近安全快照：" + Ui.time(s.snapshot.lastModified()) + " · 点击还原");
                    } else {
                        styleBadge(rollbackCard, R.color.c_text2, R.color.c_card2);
                        rollbackCard.sub.setText("每次恢复前自动保存加密快照，防止误操作");
                    }
                });
    }

    private void styleBadge(MgmtCard card, int tintRes, int bgRes) {
        card.icon.setColorFilter(Design.color(this, tintRes));
        ((GradientDrawable) card.badge.getBackground()).setColor(Design.color(this, bgRes));
    }

    private void renderAuditTimeline(List<Repo.AuditItem> audits) {
        auditBody.removeAllViews();
        View div = new View(this);
        div.setBackgroundColor(Design.color(this, R.color.c_divider));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
        dlp.bottomMargin = Ui.dp(this, 10);
        auditBody.addView(div, dlp);

        if (audits.isEmpty()) {
            auditBody.addView(Design.text(this, "暂时还没有备份或同步记录", 13, R.color.c_text2));
            return;
        }
        for (int i = 0; i < audits.size(); i++) {
            Repo.AuditItem item = audits.get(i);
            LinearLayout row = Design.row(this);
            Design.pad(this, row, 10, 9, 9);
            row.setBackgroundResource(R.drawable.bg_card_soft);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            if (i < audits.size() - 1) rp.bottomMargin = Ui.dp(this, 6);

            View dot = new View(this);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(Design.color(this, item.ok ? R.color.c_accent : R.color.c_danger));
            dot.setBackground(gd);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8));
            dp.rightMargin = Ui.dp(this, 10);
            dp.gravity = Gravity.TOP;
            dp.topMargin = Ui.dp(this, 5);
            row.addView(dot, dp);

            LinearLayout copy = Design.column(this);
            LinearLayout top = Design.row(this);
            TextView t =
                    Design.text(
                            this,
                            item.title,
                            13,
                            item.ok ? R.color.c_text : R.color.c_danger);
            t.setTypeface(null, Typeface.BOLD);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            Design.hgap(this, top, 8);
            top.addView(Design.text(this, item.time, 11, R.color.c_text2));
            copy.addView(top);
            if (!TextUtils.isEmpty(item.detail)) {
                Design.gap(this, copy, 2);
                TextView d = Design.text(this, item.detail, 12, R.color.c_text2);
                d.setMaxLines(2);
                d.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(d);
            }
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            auditBody.addView(row, rp);
        }
    }

    // ==================== 密码与自动备份设置 ====================

    private void editBackupPassword() {
        boolean hasSaved = SecretStore.has(this, BACKUP_PWD_KEY);
        Ui.passwordSheet(
                this,
                "设置备份密码",
                "备份文件采用高强度加密；密码不会上传，忘记后无法找回。",
                "输入备份密码（至少 8 位）",
                true,
                true,
                "保存并记住",
                hasSaved ? "清除本机记住的密码" : null,
                hasSaved ? this::confirmClearPassword : null,
                chars -> {
                    String plain = new String(chars);
                    Arrays.fill(chars, '\0');
                    boolean saved = SecretStore.put(this, BACKUP_PWD_KEY, plain);
                    ThemePrefs.setBackupPasswordSet(this, true);
                    refreshDashboard();
                    Ui.toast(
                            this,
                            saved
                                    ? "备份密码已保存并在本机安全记住"
                                    : "密码已设置（本机保存失败，下次需手动输入）");
                });
    }

    private void confirmClearPassword() {
        Ui.confirmSheet(
                this,
                "清除本机记住的密码？",
                "已导出的备份不受影响；之后手动备份、导出或自动备份时需要重新输入。",
                "清除",
                true,
                () -> {
                    SecretStore.clear(this, BACKUP_PWD_KEY);
                    ThemePrefs.setBackupPasswordSet(this, false);
                    refreshDashboard();
                    Ui.toast(this, "已清除本机记住的密码");
                });
    }

    private void withBackupPassword(
            String title, String message, Consumer<char[]> callback) {
        String remembered = SecretStore.get(this, BACKUP_PWD_KEY);
        if (remembered != null && remembered.length() > 0) {
            callback.accept(remembered.toCharArray());
            return;
        }
        boolean firstSetup = !ThemePrefs.isBackupPasswordSet(this);
        Ui.passwordSheet(
                this,
                title,
                message,
                firstSetup ? "设置备份密码（至少 8 位）" : "输入备份密码",
                firstSetup,
                true,
                "加密并继续",
                null,
                null,
                chars -> {
                    boolean saved = SecretStore.put(this, BACKUP_PWD_KEY, new String(chars));
                    ThemePrefs.setBackupPasswordSet(this, true);
                    if (!saved) {
                        Ui.toast(this, "本机记住密码失败，本次继续使用");
                    }
                    refreshDashboard();
                    callback.accept(chars);
                });
    }

    private void toggleAutoBackup() {
        boolean on = BackupJob.isScheduled(this) || ThemePrefs.autoBackup(this);
        if (!on) {
            if (!ThemePrefs.isDavConfigured(this)) {
                Ui.confirmSheet(
                        this,
                        "尚未连接坚果云",
                        "开启每日自动备份前，请先配置坚果云账号与应用密码。",
                        "前往连接",
                        false,
                        () -> startActivity(new Intent(this, DavActivity.class)));
                return;
            }
            String pwd = SecretStore.get(this, BACKUP_PWD_KEY);
            if (pwd == null || pwd.length() < 8) {
                Ui.confirmSheet(
                        this,
                        "需要先设置备份密码",
                        "后台自动备份需要使用本机加密记住的密码，请先设置 8 位以上备份密码。",
                        "设置密码",
                        false,
                        this::editBackupPassword);
                return;
            }
            Ui.confirmSheet(
                    this,
                    "开启每日自动备份",
                    "每天在有网络时自动加密上传一份备份到坚果云。\n\n· 使用本机 Keystore 加密保存的密码\n· 失败不会弹窗打扰，可在「最近同步与操作记录」查看",
                    "开启自动备份",
                    false,
                    () -> {
                        boolean ok = BackupJob.enable(this);
                        refreshDashboard();
                        Ui.toast(
                                this,
                                ok
                                        ? "已开启每日自动备份"
                                        : "开启失败：当前系统限制了后台定时任务");
                    });
        } else {
            Ui.confirmSheet(
                    this,
                    "关闭每日自动备份",
                    "关闭后将停止每日自动上传，您仍可随时手动备份或导出 .mjbf 文件。",
                    "关闭自动备份",
                    true,
                    () -> {
                        BackupJob.disable(this);
                        refreshDashboard();
                        Ui.toast(this, "已关闭每日自动备份");
                    });
        }
    }

    // ==================== 本地 .mjbf 导出 / 导入 ====================

    private File pendingExportFile() {
        return new File(getCacheDir(), "pending-export.mjbf");
    }

    private void startExport() {
        withBackupPassword(
                "导出加密备份",
                "将把全部收藏、分类与标签打包加密为 .mjbf 文件；恢复时需使用同一个密码。",
                password -> {
                    showBusy("正在加密收藏，请稍候…");
                    load(
                            () -> {
                                try {
                                    byte[] encrypted =
                                            BackupManager.buildEncrypted(
                                                    getApplicationContext(), password);
                                    try (OutputStream out =
                                            new FileOutputStream(pendingExportFile())) {
                                        out.write(encrypted);
                                    }
                                    return BackupManager.defaultFileName();
                                } finally {
                                    Arrays.fill(password, '\0');
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
        File file = pendingExportFile();
        if (!file.exists()) {
            Ui.toast(this, "待导出的备份已失效，请重新导出");
            return;
        }
        showBusy("正在保存加密文件…");
        String name = pendingName != null ? pendingName : BackupManager.defaultFileName();
        load(
                () -> {
                    long length = file.length();
                    try (InputStream in = new FileInputStream(file);
                            OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IOException("无法打开目标位置");
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        out.flush();
                    }
                    try {
                        DocumentsContract.renameDocument(getContentResolver(), uri, name);
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
                    Ui.toast(this, "已导出加密备份（" + formatFileSize(length) + "）");
                    refreshDashboard();
                },
                error -> {
                    hideBusy();
                    Ui.toast(this, "文件未保存，请检查存储权限后重试");
                });
    }

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
                        if (in == null) throw new IOException("无法读取所选文件");
                        return BackupManager.readAll(in);
                    }
                },
                bytes -> {
                    hideBusy();
                    RestoreFlow.start(
                            this,
                            bytes,
                            () -> {
                                refreshDashboard();
                                refresh();
                            });
                },
                error -> {
                    hideBusy();
                    Ui.toast(this, "读取失败，请选择有效的 .mjbf 备份文件");
                });
    }

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

    // ==================== 云端列表与刷新 ====================

    private void refresh() {
        if (refreshing) return;
        lastListError = null;
        if (!ThemePrefs.isDavConfigured(this)) {
            files.clear();
            adapter.notifyDataSetChanged();
            updateCloudEmptyCard(false);
            refreshDashboard();
            return;
        }
        refreshing = true;
        btnRefresh.setEnabled(false);
        updateCloudEmptyCard(true);
        new Thread(
                        () -> {
                            String error = null;
                            List<WebDavClient.RemoteFile> result = new ArrayList<>();
                            try {
                                WebDavClient c = CloudBackup.client(CloudActivity.this);
                                List<WebDavClient.RemoteFile> all =
                                        c.list(ThemePrefs.davDir(CloudActivity.this));
                                for (WebDavClient.RemoteFile f : all) {
                                    String n = f.name.toLowerCase(Locale.US);
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
                                                return Long.compare(b.lastModified, a.lastModified);
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
                                    () -> {
                                        if (isFinishing() || isDestroyed()) return;
                                        refreshing = false;
                                        btnRefresh.setEnabled(true);
                                        lastListError = err;
                                        files.clear();
                                        files.addAll(data);
                                        adapter.notifyDataSetChanged();
                                        updateCloudEmptyCard(false);
                                        refreshDashboard();
                                    });
                        })
                .start();
    }

    private void updateCloudEmptyCard(boolean loadingNow) {
        emptyCloudCard.removeAllViews();
        if (!ThemePrefs.isDavConfigured(this)) {
            emptyCloudCard.setVisibility(View.VISIBLE);
            emptyCloudCard.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_cloud,
                            "尚未连接坚果云",
                            "配置坚果云账号与应用密码后，可一键同步并随时在多端恢复备份",
                            "前往连接坚果云",
                            () -> startActivity(new Intent(this, DavActivity.class))));
            return;
        }
        if (loadingNow && files.isEmpty()) {
            emptyCloudCard.setVisibility(View.VISIBLE);
            LinearLayout box = Design.row(this);
            Design.pad(this, box, 16, 22, 22);
            box.setGravity(Gravity.CENTER);
            box.addView(
                    Design.iconBadge(
                            this,
                            R.drawable.ic_cloud,
                            Design.color(this, R.color.c_accent),
                            Design.color(this, R.color.c_accent_soft),
                            36,
                            18));
            Design.hgap(this, box, 10);
            box.addView(Design.text(this, "正在同步云端备份列表…", 13, R.color.c_text2));
            emptyCloudCard.addView(box);
            return;
        }
        if (lastListError != null && files.isEmpty()) {
            emptyCloudCard.setVisibility(View.VISIBLE);
            emptyCloudCard.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_cloud,
                            "读取云端备份失败",
                            lastListError,
                            "重新获取",
                            this::refresh));
            return;
        }
        if (files.isEmpty()) {
            emptyCloudCard.setVisibility(View.VISIBLE);
            emptyCloudCard.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_cloud,
                            "云端还没有备份",
                            "点击上方「立即备份到坚果云」即可上传第一份加密存档",
                            null,
                            null));
            return;
        }
        emptyCloudCard.setVisibility(View.GONE);
    }

    // ==================== 云端上传 ====================

    private void startBackup() {
        if (!ThemePrefs.isDavConfigured(this)) {
            Ui.confirmSheet(
                    this,
                    "先连接坚果云",
                    "请先设置坚果云账号与应用密码，再创建云端加密备份。",
                    "前往连接",
                    false,
                    () -> startActivity(new Intent(this, DavActivity.class)));
            return;
        }
        withBackupPassword(
                "上传到坚果云",
                "备份将在本机加密后上传，恢复时需要使用同一个密码。",
                this::doBackup);
    }

    private void doBackup(final char[] password) {
        showBusy("正在加密并上传到坚果云…");
        new Thread(
                        () -> {
                            final CloudBackup.Result r;
                            try {
                                r = CloudBackup.upload(CloudActivity.this, password);
                            } finally {
                                Arrays.fill(password, '\0');
                            }
                            runOnUiThread(
                                    () -> {
                                        if (isFinishing() || isDestroyed()) return;
                                        hideBusy();
                                        if (r.error != null) {
                                            Ui.infoSheet(
                                                    CloudActivity.this,
                                                    "云端备份未完成",
                                                    "本地收藏未受影响。\n\n" + r.error,
                                                    R.drawable.ic_info,
                                                    true,
                                                    "知道了",
                                                    null);
                                        } else {
                                            Ui.infoSheet(
                                                    CloudActivity.this,
                                                    "云端备份完成",
                                                    "已上传："
                                                            + r.name
                                                            + "\n大小："
                                                            + formatFileSize(r.bytes)
                                                            + "（已完成回读完整性校验）",
                                                    R.drawable.ic_shield_ok,
                                                    false,
                                                    "太好了",
                                                    null);
                                        }
                                        refreshDashboard();
                                        refresh();
                                    });
                        })
                .start();
    }

    // ==================== 云端删除 ====================

    private void confirmDelete(final WebDavClient.RemoteFile f) {
        Ui.confirmSheet(
                this,
                "删除云端备份",
                f.name + "\n\n删除后无法从坚果云找回此备份，本机收藏不受影响。",
                "确认删除",
                true,
                () -> doDelete(f));
    }

    private void doDelete(final WebDavClient.RemoteFile f) {
        showBusy("正在删除云端备份…");
        new Thread(
                        () -> {
                            String error = null;
                            try {
                                CloudBackup.client(CloudActivity.this)
                                        .delete(ThemePrefs.davDir(CloudActivity.this), f.name);
                            } catch (Exception e) {
                                error =
                                        e instanceof WebDavClient.DavException
                                                ? ((WebDavClient.DavException) e).userMessage
                                                : e.getClass().getSimpleName();
                            }
                            final String err = error;
                            runOnUiThread(
                                    () -> {
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
                                            Ui.toast(CloudActivity.this, "已从坚果云删除");
                                        }
                                        refreshDashboard();
                                        refresh();
                                    });
                        })
                .start();
    }

    // ==================== 下载并恢复 ====================

    private void downloadAndRestore(final WebDavClient.RemoteFile f) {
        showBusy("正在安全下载 " + f.name + "…");
        new Thread(
                        () -> {
                            String error = null;
                            byte[] data = null;
                            try {
                                data =
                                        CloudBackup.client(CloudActivity.this)
                                                .get(
                                                        ThemePrefs.davDir(CloudActivity.this),
                                                        f.name);
                                if (f.size > 0 && data.length != f.size) {
                                    error =
                                            "下载不完整：云端大小 "
                                                    + formatFileSize(f.size)
                                                    + "，实际收到 "
                                                    + formatFileSize(data.length);
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
                                    () -> {
                                        if (isFinishing() || isDestroyed()) return;
                                        hideBusy();
                                        if (err != null) {
                                            Repo.addAudit(
                                                    CloudActivity.this,
                                                    "DOWNLOAD",
                                                    "FAILED",
                                                    err,
                                                    0);
                                            Ui.infoSheet(
                                                    CloudActivity.this,
                                                    "下载失败",
                                                    err,
                                                    R.drawable.ic_info,
                                                    true,
                                                    "知道了",
                                                    null);
                                            refreshDashboard();
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
                                                () -> {
                                                    refreshDashboard();
                                                    refresh();
                                                });
                                    });
                        })
                .start();
    }

    // ==================== 适配器与工具 ====================

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
            CloudHolder h;
            if (convertView == null || !(convertView.getTag() instanceof CloudHolder)) {
                convertView =
                        LayoutInflater.from(CloudActivity.this)
                                .inflate(R.layout.item_cloud, parent, false);
                h = new CloudHolder(convertView);
                Design.tactile(h.card);
                Design.tactile(h.action);
                convertView.setTag(h);
            } else {
                h = (CloudHolder) convertView.getTag();
            }
            final WebDavClient.RemoteFile f = files.get(position);
            h.name.setText(f.name);
            h.sub.setText(formatFileSize(f.size) + " · " + Ui.time(f.lastModified));
            h.card.setOnClickListener(v -> downloadAndRestore(f));
            h.action.setOnClickListener(v -> confirmDelete(f));
            return convertView;
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String sized = Ui.sizeText(bytes);
        return sized.isEmpty() ? String.format(Locale.CHINA, "%d B", bytes) : sized;
    }

    private void showBusy(String msg) {
        hideBusy();
        busy = Ui.busyDialog(this, msg);
    }

    private void hideBusy() {
        Ui.dismissDialog(busy);
        busy = null;
    }
}
