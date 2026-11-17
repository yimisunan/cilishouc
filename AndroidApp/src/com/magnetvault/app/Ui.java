package com.magnetvault.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 小工具集合。 */
public final class Ui {

    public static final String[] COLOR_NAMES = {"蓝", "红", "橙", "绿", "紫", "青", "灰"};
    public static final int[] COLORS = {
        0xFF1565C0, 0xFFD32F2F, 0xFFEF6C00, 0xFF2E7D32, 0xFF6A1B9A, 0xFF00838F, 0xFF616161
    };

    private Ui() {}

    public static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    public static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    public static String time(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(ts));
    }

    /** 人类可读体积，如 12.3 MB。 */
    public static String sizeText(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.CHINA, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static void copy(Context ctx, String text) {
        if (copySilent(ctx, text)) {
            toast(ctx, "已复制链接");
        }
    }

    /** 复制但不弹 toast（用于"复制 + 打开下载器"组合动作）。 */
    public static boolean copySilent(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast(ctx, "复制失败：剪贴板不可用");
            return false;
        }
        cm.setPrimaryClip(ClipData.newPlainText("收藏链接", text));
        return true;
    }

    /** MoePal（自动识别剪贴板里的链接并下载）。 */
    public static final String MOEPAL_PKG = "com.moecloud.helper";

    /** 打开收藏链接：磁力与 ED2K 都先放入剪贴板，再启动 MoePal 识别； 没装 MoePal 时才退回系统选择器，再没有就提示。 */
    public static void openMagnet(Context ctx, String link) {
        if (!MagnetParser.isValid(link)) {
            toast(ctx, "链接格式不正确");
            return;
        }
        if (!copySilent(ctx, link)) {
            return;
        }
        boolean launched = launchApp(ctx, MOEPAL_PKG);
        android.util.Log.i("MagnetVault", "openMagnet: copy=ok launchMoePal=" + launched);
        if (launched) {
            toast(ctx, "已复制链接并打开 MoePal");
            return;
        }
        // 没装 MoePal：走系统选择器
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            if (intent.resolveActivity(ctx.getPackageManager()) == null) {
                toast(ctx, "未安装 MoePal，也没有可打开该链接的应用（链接已复制）");
                return;
            }
            ctx.startActivity(intent);
        } catch (Exception ex) {
            toast(ctx, "打开失败：" + ex.getClass().getSimpleName() + "（链接已复制）");
        }
    }

    /** 启动指定包的主界面；未安装/被禁用返回 false。 */
    public static boolean launchApp(Context ctx, String pkg) {
        Intent intent = launchIntentFor(ctx.getPackageManager(), pkg);
        if (intent == null) return false;
        try {
            ctx.startActivity(intent);
            return true;
        } catch (Exception e) {
            android.util.Log.i("MagnetVault", "launchApp failed: " + e);
            return false;
        }
    }

    /** 只构造启动意图，不启动应用；便于在无副作用的情况下验证打开目标。 */
    static Intent launchIntentFor(PackageManager pm, String pkg) {
        try {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception ignored) {
        }
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN);
            probe.setPackage(pkg);
            List<ResolveInfo> list = pm.queryIntentActivities(probe, 0);
            if (list != null && !list.isEmpty()) {
                ActivityInfo ai = list.get(0).activityInfo;
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName(ai.packageName, ai.name));
                return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        } catch (Exception ignored) {
        }
        // 显式组件启动不受包可见性过滤影响；是否真的存在由 startActivity 决定。
        Intent explicit = new Intent(Intent.ACTION_MAIN);
        explicit.setComponent(new ComponentName(pkg, pkg + ".MainActivity"));
        return explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static void share(Context ctx, String link) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, link);
            ctx.startActivity(Intent.createChooser(send, "分享收藏链接"));
        } catch (Exception ex) {
            toast(ctx, "分享失败：" + ex.getClass().getSimpleName());
        }
    }

    /** 用浏览器打开一个网址（来源链接等）。 */
    public static void openUrl(Context ctx, String url) {
        if (url == null || url.trim().length() == 0) {
            return;
        }
        String fixed = url.trim();
        if (!fixed.startsWith("http://") && !fixed.startsWith("https://")) {
            fixed = "https://" + fixed;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(fixed));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            toast(ctx, "没有可用的浏览器");
        }
    }

    // ==================== 底部操作面板（带图标的菜单） ====================

    /** 从底部弹出的操作面板：一行一个功能，左侧图标 + 文字。 视觉上比 AlertDialog 的平台列表更统一，也更方便一次看全。 */
    public static Dialog menuSheet(
            final Activity act,
            String title,
            final String[] labels,
            final int[] icons,
            final DialogInterface.OnClickListener onPick) {
        final Dialog dialog = new Dialog(act, R.style.SheetDialog);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet);
        root.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 18));

        // 顶部小抓手
        View handle = new View(act);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(act, 38), dp(act, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(act, 2);
        hp.bottomMargin = dp(act, 14);
        android.graphics.drawable.GradientDrawable hd =
                new android.graphics.drawable.GradientDrawable();
        hd.setColor(act.getResources().getColor(R.color.c_line));
        hd.setCornerRadius(dp(act, 2));
        handle.setBackground(hd);
        root.addView(handle, hp);

        if (title != null && title.trim().length() > 0) {
            TextView head = new TextView(act);
            head.setText(title);
            head.setTextSize(17);
            head.setTextColor(act.getResources().getColor(R.color.c_text));
            head.setTypeface(null, android.graphics.Typeface.BOLD);
            head.setMaxLines(2);
            head.setEllipsize(android.text.TextUtils.TruncateAt.END);
            head.setPadding(dp(act, 6), 0, dp(act, 6), dp(act, 12));
            root.addView(head);
        }

        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(act, 12), dp(act, 12), dp(act, 14), dp(act, 12));
            row.setMinimumHeight(dp(act, 54));
            row.setFocusable(true);
            row.setClickable(true);
            row.setContentDescription(labels[i]);
            row.setBackground(act.getDrawable(R.drawable.bg_card_soft));
            LinearLayout.LayoutParams rp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(act, 8);
            row.setLayoutParams(rp);

            boolean danger =
                    labels[i].startsWith("删除")
                            || labels[i].contains("移入回收站")
                            || labels[i].startsWith("清空")
                            || labels[i].startsWith("永久");

            int iconRes = (icons != null && i < icons.length) ? icons[i] : 0;
            if (iconRes != 0) {
                View badge =
                        Design.iconBadge(
                                act,
                                iconRes,
                                act.getResources().getColor(danger ? R.color.c_danger : R.color.c_accent),
                                act.getResources()
                                        .getColor(danger ? R.color.c_danger_soft : R.color.c_accent_soft),
                                34,
                                18);
                LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
                bp.rightMargin = dp(act, 12);
                row.addView(badge, bp);
            }

            TextView tv = new TextView(act);
            tv.setText(labels[i]);
            tv.setTextSize(15);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setTextColor(
                    act.getResources().getColor(danger ? R.color.c_danger : R.color.c_text));
            if (iconRes == 0) tv.setPadding(dp(act, 4), 0, 0, 0);
            LinearLayout.LayoutParams tp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(tp);
            row.addView(tv);

            row.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            onPick.onClick(dialog, index);
                        }
                    });
            list.addView(row);
        }

        ScrollView sc = new ScrollView(act);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(list);
        root.addView(
                sc,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int maxHeight = act.getResources().getDisplayMetrics().heightPixels * 3 / 4;
        sc.setLayoutParams(
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.min(maxHeight - dp(act, 96), labels.length * dp(act, 68))));
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            if (android.animation.ValueAnimator.areAnimatorsEnabled())
                w.setWindowAnimations(android.R.style.Animation_Dialog);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (w != null)
            w.setLayout(
                    Math.min(act.getResources().getDisplayMetrics().widthPixels, dp(act, 640)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        return dialog;
    }
}
