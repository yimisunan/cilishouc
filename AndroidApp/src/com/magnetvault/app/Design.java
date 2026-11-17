package com.magnetvault.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Native components with one spacing, typography and interaction system. */
public final class Design {
    private Design() {}

    public static int color(Activity a, int id) {
        return a.getResources().getColor(id);
    }

    public static LinearLayout column(Activity a) {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    public static LinearLayout row(Activity a) {
        LinearLayout v = new LinearLayout(a);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    public static TextView text(Activity a, String s, int size, int color) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color(a, color));
        v.setIncludeFontPadding(false);
        v.setLineSpacing(Ui.dp(a, 3), 1);
        return v;
    }

    public static TextView heading(Activity a, String s) {
        TextView v = text(a, s, 24, R.color.c_text);
        v.setTypeface(null, Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) v.setAccessibilityHeading(true);
        return v;
    }

    public static void pad(Activity a, View v, int h, int top, int bottom) {
        v.setPadding(Ui.dp(a, h), Ui.dp(a, top), Ui.dp(a, h), Ui.dp(a, bottom));
    }

    public static void gap(Activity a, LinearLayout parent, int dp) {
        parent.addView(new View(a), new LinearLayout.LayoutParams(1, Ui.dp(a, dp)));
    }

    public static void hgap(Activity a, LinearLayout parent, int dp) {
        parent.addView(new View(a), new LinearLayout.LayoutParams(Ui.dp(a, dp), 1));
    }

    public static Button button(
            Activity a, String label, int icon, boolean primary, Runnable action) {
        Button b = new Button(a);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setMinHeight(Ui.dp(a, 48));
        b.setMinimumHeight(Ui.dp(a, 48));
        b.setGravity(Gravity.CENTER);
        pad(a, b, 16, 8, 8);
        b.setTextColor(color(a, primary ? R.color.c_on_accent : R.color.c_accent));
        b.setBackgroundResource(primary ? R.drawable.bg_fab : R.drawable.bg_btn);
        if (icon != 0) {
            Drawable d = a.getDrawable(icon).mutate();
            d.setBounds(0, 0, Ui.dp(a, 19), Ui.dp(a, 19));
            d.setTint(color(a, primary ? R.color.c_on_accent : R.color.c_accent));
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(Ui.dp(a, 8));
        }
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Compact filter pill; keeps a 48dp touch target while reading lighter. */
    public static Button chip(Activity a, String label, boolean selected, Runnable action) {
        Button b = button(a, label, 0, false, action);
        b.setTextSize(13);
        b.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        b.setMinHeight(Ui.dp(a, 40));
        b.setMinimumHeight(Ui.dp(a, 40));
        pad(a, b, 14, 6, 6);
        b.setTextColor(color(a, selected ? R.color.c_accent : R.color.c_text2));
        b.setBackgroundResource(selected ? R.drawable.bg_pill_on : R.drawable.bg_pill);
        b.setSelected(selected);
        return b;
    }

    /** Compact pill with optional icon for filter bars & quick actions. */
    public static LinearLayout pill(
            Activity a, String label, int icon, boolean selected, Runnable action) {
        LinearLayout pill = row(a);
        pill.setGravity(Gravity.CENTER);
        pill.setMinimumHeight(Ui.dp(a, 36));
        pad(a, pill, 13, 7, 7);
        pill.setBackgroundResource(selected ? R.drawable.bg_pill_on : R.drawable.bg_pill);
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setSelected(selected);
        if (icon != 0) {
            ImageView iv = new ImageView(a);
            iv.setImageResource(icon);
            iv.setColorFilter(color(a, selected ? R.color.c_accent : R.color.c_text2));
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 15), Ui.dp(a, 15));
            ip.rightMargin = Ui.dp(a, 5);
            pill.addView(iv, ip);
        }
        TextView tv = text(a, label, 13, selected ? R.color.c_accent : R.color.c_text2);
        tv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setSingleLine(true);
        pill.addView(tv);
        pill.setOnClickListener(v -> action.run());
        return pill;
    }

    /** Compact tag pill for ChipFlow tag walls. */
    public static TextView tagChip(Activity a, String label, boolean accent, Runnable action) {
        TextView tv = text(a, label, 12, accent ? R.color.c_accent : R.color.c_chip_text);
        tv.setTypeface(null, accent ? Typeface.BOLD : Typeface.NORMAL);
        tv.setBackgroundResource(accent ? R.drawable.bg_pill_on : R.drawable.bg_tag_pill);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        pad(a, tv, 11, 6, 6);
        if (action != null) {
            tv.setFocusable(true);
            tv.setClickable(true);
            tv.setOnClickListener(v -> action.run());
        }
        return tv;
    }

    /** Rounded square tinted icon badge (used in folders, action rows, and list cards). */
    public static FrameLayout iconBadge(
            Context ctx, int iconRes, int tintColor, int bgColor, int boxDp, int iconDp) {
        FrameLayout box = new FrameLayout(ctx);
        int size = Ui.dp(ctx, boxDp);
        box.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(Ui.dp(ctx, Math.max(10, boxDp / 3)));
        box.setBackground(gd);
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(iconRes);
        iv.setColorFilter(tintColor);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int ic = Ui.dp(ctx, iconDp);
        box.addView(iv, new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER));
        return box;
    }

    public static ImageButton icon(Activity a, int icon, String label, Runnable action) {
        ImageButton b = new ImageButton(a);
        b.setImageResource(icon);
        b.setColorFilter(color(a, R.color.c_text2));
        b.setContentDescription(label);
        b.setBackgroundResource(R.drawable.bg_card_soft);
        pad(a, b, 12, 12, 12);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    public static ImageButton quietIcon(Activity a, int icon, String label, Runnable action) {
        ImageButton b = icon(a, icon, label, action);
        b.setBackgroundResource(R.drawable.bg_flat);
        return b;
    }

    public static Button textAction(Activity a, String label, Runnable action) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setTextSize(14);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(color(a, R.color.c_accent));
        b.setBackgroundResource(R.drawable.bg_flat);
        b.setMinHeight(Ui.dp(a, 44));
        b.setMinimumHeight(Ui.dp(a, 44));
        pad(a, b, 12, 8, 8);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    public static LinearLayout card(Activity a) {
        LinearLayout v = column(a);
        v.setBackgroundResource(R.drawable.bg_card);
        pad(a, v, 14, 14, 14);
        return v;
    }

    public static void section(Activity a, LinearLayout parent, String title, String subtitle) {
        gap(a, parent, 20);
        TextView t = text(a, title, 15, R.color.c_text);
        t.setTypeface(null, Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        parent.addView(t);
        if (subtitle != null) {
            gap(a, parent, 3);
            parent.addView(text(a, subtitle, 12, R.color.c_text2));
        }
        gap(a, parent, 10);
    }

    public static LinearLayout action(
            Activity a, String title, String detail, int icon, Runnable action) {
        LinearLayout box = row(a);
        box.setBackgroundResource(R.drawable.bg_card);
        pad(a, box, 14, 12, 12);
        box.setMinimumHeight(Ui.dp(a, 66));

        FrameLayout badge =
                iconBadge(
                        a,
                        icon,
                        color(a, R.color.c_accent),
                        color(a, R.color.c_accent_soft),
                        38,
                        20);
        LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams) badge.getLayoutParams();
        ip.rightMargin = Ui.dp(a, 14);
        box.addView(badge, ip);

        LinearLayout copy = column(a);
        TextView name = text(a, title, 15, R.color.c_text);
        name.setTypeface(null, Typeface.BOLD);
        copy.addView(name);
        if (detail != null) {
            gap(a, copy, 3);
            copy.addView(text(a, detail, 12, R.color.c_text2));
        }
        box.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView arrow = new ImageView(a);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setColorFilter(color(a, R.color.c_text2));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)));

        box.setFocusable(true);
        box.setClickable(true);
        box.setOnClickListener(v -> action.run());
        return box;
    }

    /** Centered SVG empty-state block with optional CTA pill. */
    public static LinearLayout emptyState(
            Activity a,
            int illustrationRes,
            String title,
            String subtitle,
            String actionLabel,
            Runnable action) {
        LinearLayout box = column(a);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        pad(a, box, 28, 36, 24);
        if (illustrationRes != 0) {
            ImageView ill = new ImageView(a);
            ill.setImageResource(illustrationRes);
            ill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int sz = Ui.dp(a, 128);
            box.addView(ill, new LinearLayout.LayoutParams(sz, sz));
            gap(a, box, 14);
        }
        TextView t = text(a, title, 16, R.color.c_text);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        if (!TextUtils.isEmpty(subtitle)) {
            gap(a, box, 6);
            TextView sub = text(a, subtitle, 13, R.color.c_text2);
            sub.setGravity(Gravity.CENTER);
            box.addView(sub);
        }
        if (!TextUtils.isEmpty(actionLabel) && action != null) {
            gap(a, box, 16);
            box.addView(
                    pill(a, actionLabel, 0, true, action),
                    new LinearLayout.LayoutParams(-2, -2));
        }
        return box;
    }

    public static ScrollView scroll(Activity a, View child) {
        ScrollView s = new ScrollView(a);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.addView(child, new ScrollView.LayoutParams(-1, -2));
        return s;
    }

    public static LinearLayout toolbar(Activity a, String title, Runnable back) {
        LinearLayout row = row(a);
        pad(a, row, 10, 6, 6);
        row.setMinimumHeight(Ui.dp(a, 54));
        ImageButton b = quietIcon(a, R.drawable.ic_back, "返回", back);
        b.setColorFilter(color(a, R.color.c_text));
        row.addView(b);
        TextView h = text(a, title, 18, R.color.c_text);
        h.setTypeface(null, Typeface.BOLD);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.leftMargin = Ui.dp(a, 6);
        row.addView(h, p);
        return row;
    }

    public static void label(Activity a, LinearLayout box, String label) {
        gap(a, box, 16);
        TextView t = text(a, label, 13, R.color.c_text2);
        t.setTypeface(null, Typeface.BOLD);
        box.addView(t);
        gap(a, box, 8);
    }

    public static EditText input(Activity a, String label, boolean multiline) {
        EditText v = new EditText(a);
        v.setTextSize(15);
        v.setHint(label);
        v.setContentDescription(label);
        v.setTextColor(color(a, R.color.c_text));
        v.setHintTextColor(color(a, R.color.c_text2));
        v.setBackgroundResource(R.drawable.bg_card);
        pad(a, v, 14, 13, 13);
        v.setMinHeight(Ui.dp(a, 50));
        v.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        v.setSingleLine(!multiline);
        if (multiline) v.setGravity(Gravity.TOP);
        return v;
    }

    public static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (pi != null && pi.versionName != null) return pi.versionName;
        } catch (Exception ignored) {
        }
        return "2.4.1";
    }

    /** Soft alpha version of an arbitrary folder color for icon badges. */
    public static int softTint(int color) {
        return (0x24 << 24) | (color & 0x00FFFFFF);
    }
}
