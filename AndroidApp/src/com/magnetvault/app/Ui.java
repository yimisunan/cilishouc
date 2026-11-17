package com.magnetvault.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 小工具集合与统一底部弹窗组件库。 */
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

    // ==================== 底部弹窗基础框架 ====================

    /** 创建并配置标准圆角底部弹窗窗口。 */
    public static Dialog showSheet(Activity act, View root, boolean hasInput) {
        Dialog dialog = new Dialog(act, R.style.SheetDialog);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (!ValueAnimator.areAnimatorsEnabled()) w.setWindowAnimations(0);
            if (hasInput) {
                Design.allowImeResize(w, true);
            }
        }
        dialog.show();
        if (w != null) {
            w.setLayout(
                    Math.min(act.getResources().getDisplayMetrics().widthPixels, dp(act, 640)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    /** 构造带顶部拖动手柄的底部面板容器。 */
    public static LinearLayout sheetContainer(Activity act) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet);
        root.setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 18));
        View handle = new View(act);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(act, 38), dp(act, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(act, 2);
        hp.bottomMargin = dp(act, 14);
        GradientDrawable hd = new GradientDrawable();
        hd.setColor(act.getColor(R.color.c_line));
        hd.setCornerRadius(dp(act, 2));
        handle.setBackground(hd);
        root.addView(handle, hp);
        return root;
    }

    private static Button dangerButton(Activity act, String label, Runnable action) {
        Button b = new Button(act);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setAllCaps(false);
        b.setMinHeight(dp(act, 48));
        b.setMinimumHeight(dp(act, 48));
        b.setGravity(Gravity.CENTER);
        Design.pad(act, b, 16, 8, 8);
        b.setTextColor(Design.color(act, R.color.c_danger));
        b.setBackgroundResource(R.drawable.bg_danger);
        b.setOnClickListener(v -> action.run());
        Design.tactile(b);
        return b;
    }

    // ==================== 底部操作面板（带图标的菜单） ====================

    public static Dialog menuSheet(
            final Activity act,
            String title,
            final String[] labels,
            final int[] icons,
            final DialogInterface.OnClickListener onPick) {
        LinearLayout root = sheetContainer(act);
        if (title != null && title.trim().length() > 0) {
            TextView head = Design.text(act, title, 17, R.color.c_text);
            head.setTypeface(null, Typeface.BOLD);
            head.setMaxLines(2);
            head.setEllipsize(TextUtils.TruncateAt.END);
            Design.pad(act, head, 4, 0, 12);
            root.addView(head);
        }

        LinearLayout list = Design.column(act);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = Design.row(act);
            Design.pad(act, row, 12, 12, 12);
            row.setMinimumHeight(dp(act, 54));
            row.setFocusable(true);
            row.setClickable(true);
            row.setContentDescription(labels[i]);
            row.setBackgroundResource(R.drawable.bg_card_soft);
            Design.tactile(row);
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
                                Design.color(act, danger ? R.color.c_danger : R.color.c_accent),
                                Design.color(
                                        act,
                                        danger ? R.color.c_danger_soft : R.color.c_accent_soft),
                                34,
                                18);
                LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
                bp.rightMargin = dp(act, 12);
                row.addView(badge, bp);
            }

            TextView tv = Design.text(act, labels[i], 15, danger ? R.color.c_danger : R.color.c_text);
            tv.setTypeface(null, Typeface.BOLD);
            if (iconRes == 0) Design.pad(act, tv, 4, 0, 0);
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
            list.addView(row);
        }

        ScrollView sc = new ScrollView(act);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(list);
        int maxHeight = act.getResources().getDisplayMetrics().heightPixels * 3 / 4;
        root.addView(
                sc,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.min(maxHeight - dp(act, 96), labels.length * dp(act, 66))));

        final Dialog dialog = showSheet(act, root, false);
        for (int i = 0; i < list.getChildCount(); i++) {
            final int index = i;
            list.getChildAt(i)
                    .setOnClickListener(
                            v -> {
                                dialog.dismiss();
                                onPick.onClick(dialog, index);
                            });
        }
        return dialog;
    }

    // ==================== 确认底部面板 ====================

    public static Dialog confirmSheet(
            Activity act,
            String title,
            String message,
            String positiveLabel,
            boolean danger,
            Runnable onConfirm) {
        return confirmSheet(act, title, message, positiveLabel, null, danger, onConfirm, null);
    }

    public static Dialog confirmSheet(
            final Activity act,
            String title,
            String message,
            String positiveLabel,
            String neutralLabel,
            boolean danger,
            final Runnable onConfirm,
            final Runnable onNeutral) {
        LinearLayout root = sheetContainer(act);

        LinearLayout header = Design.row(act);
        FrameLayout badge =
                Design.iconBadge(
                        act,
                        danger ? R.drawable.ic_trash : R.drawable.ic_info,
                        Design.color(act, danger ? R.color.c_danger : R.color.c_accent),
                        Design.color(act, danger ? R.color.c_danger_soft : R.color.c_accent_soft),
                        38,
                        20);
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        bp.rightMargin = dp(act, 12);
        header.addView(badge, bp);
        TextView head = Design.text(act, title, 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        header.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        if (!TextUtils.isEmpty(message)) {
            Design.gap(act, root, 12);
            TextView body = Design.text(act, message, 14, R.color.c_text2);
            body.setBackgroundResource(R.drawable.bg_card_soft);
            Design.pad(act, body, 14, 12, 12);
            root.addView(body);
        }

        final Dialog[] ref = {null};
        if (!TextUtils.isEmpty(neutralLabel) && onNeutral != null) {
            Design.gap(act, root, 12);
            Button mid =
                    Design.button(
                            act,
                            neutralLabel,
                            0,
                            false,
                            () -> {
                                if (ref[0] != null) ref[0].dismiss();
                                onNeutral.run();
                            });
            root.addView(mid, new LinearLayout.LayoutParams(-1, -2));
        }

        Design.gap(act, root, 16);
        LinearLayout actions = Design.row(act);
        Button cancel =
                Design.button(
                        act,
                        "取消",
                        0,
                        false,
                        () -> {
                            if (ref[0] != null) ref[0].cancel();
                        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.rightMargin = dp(act, 10);
        actions.addView(cancel, cp);

        Runnable fire =
                () -> {
                    if (ref[0] != null) ref[0].dismiss();
                    if (onConfirm != null) onConfirm.run();
                };
        Button ok =
                danger
                        ? dangerButton(act, positiveLabel, fire)
                        : Design.button(act, positiveLabel, 0, true, fire);
        actions.addView(ok, new LinearLayout.LayoutParams(0, -2, 1.3f));
        root.addView(actions);

        ref[0] = showSheet(act, root, false);
        return ref[0];
    }

    // ==================== 单选底部面板 ====================

    public static Dialog choiceSheet(
            Activity act,
            String title,
            String[] labels,
            String[] subtitles,
            int[] icons,
            int selectedIndex,
            Consumer<Integer> onPick) {
        LinearLayout root = sheetContainer(act);
        TextView head = Design.text(act, title, 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        Design.pad(act, head, 4, 0, 12);
        root.addView(head);

        LinearLayout list = Design.column(act);
        final Dialog[] ref = {null};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean picked = i == selectedIndex;
            LinearLayout row = Design.row(act);
            Design.pad(act, row, 12, 12, 12);
            row.setMinimumHeight(dp(act, 56));
            row.setBackgroundResource(picked ? R.drawable.bg_card_selected : R.drawable.bg_card_soft);
            row.setFocusable(true);
            row.setClickable(true);
            Design.tactile(row);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(act, 8);

            if (icons != null && i < icons.length && icons[i] != 0) {
                View badge =
                        Design.iconBadge(
                                act,
                                icons[i],
                                Design.color(act, picked ? R.color.c_accent : R.color.c_text2),
                                Design.color(act, picked ? R.color.c_accent_soft : R.color.c_card),
                                36,
                                18);
                LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
                bp.rightMargin = dp(act, 12);
                row.addView(badge, bp);
            }

            LinearLayout copy = Design.column(act);
            TextView tv = Design.text(act, labels[i], 15, picked ? R.color.c_accent : R.color.c_text);
            tv.setTypeface(null, Typeface.BOLD);
            copy.addView(tv);
            if (subtitles != null && i < subtitles.length && !TextUtils.isEmpty(subtitles[i])) {
                Design.gap(act, copy, 2);
                copy.addView(Design.text(act, subtitles[i], 12, R.color.c_text2));
            }
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

            if (picked) {
                ImageView check = new ImageView(act);
                check.setImageResource(R.drawable.ic_check);
                check.setColorFilter(Design.color(act, R.color.c_accent));
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(check, new LinearLayout.LayoutParams(dp(act, 20), dp(act, 20)));
            }

            row.setOnClickListener(
                    v -> {
                        if (ref[0] != null) ref[0].dismiss();
                        onPick.accept(idx);
                    });
            list.addView(row, rp);
        }

        ScrollView sc = new ScrollView(act);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(list);
        int maxHeight = act.getResources().getDisplayMetrics().heightPixels * 2 / 3;
        root.addView(
                sc,
                new LinearLayout.LayoutParams(-1, Math.min(maxHeight, labels.length * dp(act, 72))));
        ref[0] = showSheet(act, root, false);
        return ref[0];
    }

    // ==================== 密码输入底部面板（含可见性切换） ====================

    public static Dialog passwordSheet(
            Activity act,
            String title,
            String message,
            String hint,
            boolean confirmTwice,
            boolean enforceMinLen,
            String primaryLabel,
            String forgetLabel,
            Runnable onForget,
            Consumer<char[]> onSubmit) {
        LinearLayout root = sheetContainer(act);

        LinearLayout header = Design.row(act);
        FrameLayout badge =
                Design.iconBadge(
                        act,
                        R.drawable.ic_lock,
                        Design.color(act, R.color.c_accent),
                        Design.color(act, R.color.c_accent_soft),
                        38,
                        20);
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        bp.rightMargin = dp(act, 12);
        header.addView(badge, bp);
        TextView head = Design.text(act, title, 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        header.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        if (!TextUtils.isEmpty(message)) {
            Design.gap(act, root, 10);
            TextView desc = Design.text(act, message, 13, R.color.c_text2);
            desc.setBackgroundResource(R.drawable.bg_card_soft);
            Design.pad(act, desc, 12, 10, 10);
            root.addView(desc);
        }

        Design.gap(act, root, 14);
        final Design.PasswordField p1 = Design.passwordBox(act, hint);
        root.addView(p1.container);

        final Design.PasswordField p2 =
                confirmTwice ? Design.passwordBox(act, "再次输入密码确认") : null;
        if (p2 != null) {
            Design.gap(act, root, 10);
            root.addView(p2.container);
        }

        final TextView errorView = Design.text(act, "", 12, R.color.c_danger);
        errorView.setVisibility(View.GONE);
        Design.pad(act, errorView, 4, 8, 0);
        root.addView(errorView);

        final Dialog[] ref = {null};
        if (!TextUtils.isEmpty(forgetLabel) && onForget != null) {
            Design.gap(act, root, 8);
            Button forget =
                    Design.textAction(
                            act,
                            forgetLabel,
                            () -> {
                                if (ref[0] != null) ref[0].dismiss();
                                onForget.run();
                            });
            root.addView(forget, new LinearLayout.LayoutParams(-2, -2));
        }

        Design.gap(act, root, 16);
        LinearLayout actions = Design.row(act);
        Button cancel =
                Design.button(
                        act,
                        "取消",
                        0,
                        false,
                        () -> {
                            if (ref[0] != null) ref[0].dismiss();
                        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.rightMargin = dp(act, 10);
        actions.addView(cancel, cp);

        Button submit =
                Design.button(
                        act,
                        primaryLabel,
                        R.drawable.ic_check,
                        true,
                        () -> {
                            String v1 = p1.input.getText().toString();
                            if (v1.isEmpty()) {
                                errorView.setText("请输入密码");
                                errorView.setVisibility(View.VISIBLE);
                                p1.input.requestFocus();
                                return;
                            }
                            if (enforceMinLen && v1.length() < 8) {
                                errorView.setText("为了数据安全，备份密码至少需要 8 位");
                                errorView.setVisibility(View.VISIBLE);
                                p1.input.requestFocus();
                                return;
                            }
                            if (p2 != null) {
                                String v2 = p2.input.getText().toString();
                                if (!v1.equals(v2)) {
                                    errorView.setText("两次输入的密码不一致，请检查");
                                    errorView.setVisibility(View.VISIBLE);
                                    p2.input.requestFocus();
                                    return;
                                }
                            }
                            if (ref[0] != null) ref[0].dismiss();
                            onSubmit.accept(v1.toCharArray());
                        });
        actions.addView(submit, new LinearLayout.LayoutParams(0, -2, 1.4f));
        root.addView(actions);

        ref[0] = showSheet(act, root, true);
        p1.input.requestFocus();
        return ref[0];
    }

    // ==================== 信息通知面板 ====================

    public static Dialog infoSheet(
            Activity act,
            String title,
            String message,
            int iconRes,
            boolean warn,
            String buttonLabel,
            Runnable onAck) {
        LinearLayout root = sheetContainer(act);
        LinearLayout header = Design.row(act);
        FrameLayout badge =
                Design.iconBadge(
                        act,
                        iconRes == 0 ? R.drawable.ic_shield_ok : iconRes,
                        Design.color(act, warn ? R.color.c_danger : R.color.c_accent),
                        Design.color(act, warn ? R.color.c_danger_soft : R.color.c_accent_soft),
                        40,
                        22);
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        bp.rightMargin = dp(act, 12);
        header.addView(badge, bp);
        TextView head = Design.text(act, title, 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        header.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        if (!TextUtils.isEmpty(message)) {
            Design.gap(act, root, 12);
            TextView body = Design.text(act, message, 14, R.color.c_text2);
            body.setBackgroundResource(R.drawable.bg_card_soft);
            Design.pad(act, body, 14, 12, 12);
            ScrollView sc = new ScrollView(act);
            sc.setVerticalScrollBarEnabled(false);
            sc.addView(body);
            int maxH = act.getResources().getDisplayMetrics().heightPixels / 2;
            root.addView(sc, new LinearLayout.LayoutParams(-1, -2));
            sc.post(
                    () -> {
                        if (sc.getHeight() > maxH) {
                            sc.getLayoutParams().height = maxH;
                            sc.requestLayout();
                        }
                    });
        }

        Design.gap(act, root, 16);
        final Dialog[] ref = {null};
        Button ack =
                Design.button(
                        act,
                        TextUtils.isEmpty(buttonLabel) ? "知道了" : buttonLabel,
                        0,
                        true,
                        () -> {
                            if (ref[0] != null) ref[0].dismiss();
                            if (onAck != null) onAck.run();
                        });
        root.addView(ack, new LinearLayout.LayoutParams(-1, -2));
        ref[0] = showSheet(act, root, false);
        return ref[0];
    }

    // ==================== 优雅的旋转等待窗 ====================

    public static Dialog busyDialog(Activity act, String msg) {
        LinearLayout card = Design.row(act);
        card.setBackgroundResource(R.drawable.bg_center_dialog);
        Design.pad(act, card, 20, 18, 18);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumWidth(dp(act, 240));

        ImageView spinner = new ImageView(act);
        spinner.setImageResource(R.drawable.ic_spinner_ring);
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(act, 36), dp(act, 36));
        sp.rightMargin = dp(act, 14);
        card.addView(spinner, sp);

        TextView tv = Design.text(act, msg, 15, R.color.c_text);
        tv.setId(android.R.id.message);
        tv.setTypeface(null, Typeface.BOLD);
        card.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));

        ObjectAnimator spin = null;
        if (ValueAnimator.areAnimatorsEnabled()) {
            spin = ObjectAnimator.ofFloat(spinner, "rotation", 0f, 360f);
            spin.setDuration(900);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.start();
        }

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setContentView(card);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(
                    Math.min(
                            act.getResources().getDisplayMetrics().widthPixels - dp(act, 48),
                            dp(act, 340)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        final ObjectAnimator animRef = spin;
        dialog.setOnDismissListener(
                d -> {
                    if (animRef != null) animRef.cancel();
                });
        dialog.show();
        return dialog;
    }

    public static void updateBusy(Dialog d, String msg) {
        if (d == null) return;
        TextView tv = d.findViewById(android.R.id.message);
        if (tv != null) tv.setText(msg);
    }

    public static void dismissDialog(Dialog d) {
        if (d != null) {
            try {
                d.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 使用指南底部面板 ====================

    public static Dialog guideSheet(Activity act) {
        LinearLayout root = sheetContainer(act);

        LinearLayout header = Design.row(act);
        ImageView ill = new ImageView(act);
        ill.setImageResource(R.drawable.ill_hero_vault);
        ill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(act, 44), dp(act, 44));
        ip.rightMargin = dp(act, 12);
        header.addView(ill, ip);

        LinearLayout copy = Design.column(act);
        TextView head = Design.text(act, "使用指南 · 快速上手", 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        copy.addView(head);
        Design.gap(act, copy, 2);
        copy.addView(Design.text(act, "本地离线加密管理，极简高效的收藏流程", 12, R.color.c_text2));
        header.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        Design.gap(act, root, 12);

        String[][] items = {
            {"智能粘贴与双模式录入", "右下角点「添加」可在「批量智能识别」与「单条精细录入」之间随心切换，支持带表格或文字块的文本导入。", "0"},
            {"聚焦浏览与轻点展开", "列表自动提炼番号、分辨率与编码标签；轻点任意收藏卡片即可展开完整标题、备注与操作按钮。", "1"},
            {"分类文件夹与标签整理", "在「整理」页可一站式建多级文件夹、改名改色、拖拽移动位置，以及重命名或合并标签。", "2"},
            {"长按多选批量处理", "长按任意条目进入多选模式，一次完成跨文件夹移动、批量加标签、批量星标或移入回收站。", "3"},
            {"一键复制到 MoePal 下载", "点击卡片内的「打开」或「复制」都会安全复制链接并唤起关联下载工具。", "4"},
            {"零知识加密本地 / 云端备份", "导出加密 .mjbf 文件或绑定坚果云（WebDAV）每日自动备份，恢复前自动留快照可随时撤回。", "5"}
        };
        int[] icons = {
            R.drawable.ic_sparkle,
            R.drawable.ic_magnet,
            R.drawable.ic_folder,
            R.drawable.ic_check,
            R.drawable.ic_play,
            R.drawable.ic_shield_ok
        };

        LinearLayout list = Design.column(act);
        for (int i = 0; i < items.length; i++) {
            LinearLayout card = Design.row(act);
            card.setBackgroundResource(R.drawable.bg_card_soft);
            card.setGravity(Gravity.TOP);
            Design.pad(act, card, 12, 12, 12);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(act, 8);

            FrameLayout badge =
                    Design.iconBadge(
                            act,
                            icons[i],
                            Design.color(act, R.color.c_accent),
                            Design.color(act, R.color.c_accent_soft),
                            34,
                            18);
            LinearLayout.LayoutParams bpp = (LinearLayout.LayoutParams) badge.getLayoutParams();
            bpp.rightMargin = dp(act, 12);
            card.addView(badge, bpp);

            LinearLayout txt = Design.column(act);
            TextView t = Design.text(act, items[i][0], 14, R.color.c_text);
            t.setTypeface(null, Typeface.BOLD);
            txt.addView(t);
            Design.gap(act, txt, 3);
            txt.addView(Design.text(act, items[i][1], 12, R.color.c_text2));
            card.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            list.addView(card, cp);
        }

        ScrollView sc = new ScrollView(act);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(list);
        int maxH = act.getResources().getDisplayMetrics().heightPixels * 58 / 100;
        root.addView(sc, new LinearLayout.LayoutParams(-1, maxH));

        Design.gap(act, root, 12);
        final Dialog[] ref = {null};
        Button ok =
                Design.button(
                        act,
                        "开始使用",
                        0,
                        true,
                        () -> {
                            if (ref[0] != null) ref[0].dismiss();
                        });
        root.addView(ok, new LinearLayout.LayoutParams(-1, -2));
        ref[0] = showSheet(act, root, false);
        return ref[0];
    }
}
