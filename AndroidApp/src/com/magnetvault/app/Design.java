package com.magnetvault.app;

import android.animation.AnimatorInflater;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native components with unified spacing, typography, SVGs, and tactile interaction system. */
public final class Design {
    private Design() {}

    /** Wrapper holder for an EditText with eye password toggle and focus ring border. */
    public static final class PasswordField {
        public final LinearLayout container;
        public final EditText input;
        public final ImageButton toggle;

        PasswordField(LinearLayout container, EditText input, ImageButton toggle) {
            this.container = container;
            this.input = input;
            this.toggle = toggle;
        }
    }

    public static int color(Activity a, int id) {
        return a.getColor(id);
    }

    @SuppressWarnings("deprecation")
    public static void allowImeResize(Window w, boolean showIme) {
        if (w == null) return;
        int mode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (showIme) mode |= WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        w.setSoftInputMode(mode);
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

    /** Tactile press-scale feedback using StateListAnimator; respects system animation toggle. */
    public static void tactile(View v) {
        if (v == null || !ValueAnimator.areAnimatorsEnabled()) return;
        try {
            StateListAnimator sla =
                    AnimatorInflater.loadStateListAnimator(v.getContext(), R.animator.press_scale);
            v.setStateListAnimator(sla);
        } catch (Exception ignored) {
        }
    }

    /** Smooth layout transition for expand/collapse/filter changes inside a container. */
    public static void animate(ViewGroup container) {
        if (container == null || !ValueAnimator.areAnimatorsEnabled()) return;
        try {
            TransitionSet set = new TransitionSet();
            set.setOrdering(TransitionSet.ORDERING_TOGETHER);
            ChangeBounds bounds = new ChangeBounds();
            bounds.setDuration(190);
            bounds.setInterpolator(new DecelerateInterpolator());
            Fade fadeIn = new Fade(Fade.IN);
            fadeIn.setDuration(160);
            set.addTransition(bounds);
            set.addTransition(fadeIn);
            TransitionManager.beginDelayedTransition(container, set);
        } catch (Exception ignored) {
        }
    }

    /** Playful overshoot pop bounce for star toggles and confirmation badges. */
    public static void popBounce(View v) {
        if (v == null || !ValueAnimator.areAnimatorsEnabled()) return;
        v.animate().cancel();
        v.setScaleX(0.84f);
        v.setScaleY(0.84f);
        v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(new OvershootInterpolator(2.4f))
                .start();
    }

    /** Smooth rotation animation (e.g. accordion chevrons). */
    public static void rotateTo(View v, float degrees) {
        if (v == null) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            v.setRotation(degrees);
            return;
        }
        v.animate()
                .rotation(degrees)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** Links focus state of an EditText to its wrapper's activated background state. */
    public static void focusWrap(View wrapper, EditText edit) {
        if (wrapper == null || edit == null) return;
        wrapper.setActivated(edit.hasFocus());
        edit.setOnFocusChangeListener((v, hasFocus) -> wrapper.setActivated(hasFocus));
    }

    public static Button button(
            Activity a, String label, int icon, boolean primary, Runnable action) {
        Button b = new Button(a);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setAllCaps(false);
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
        tactile(b);
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
        tactile(pill);
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
            tactile(tv);
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

    /** Segmented control pill switcher (e.g. Folders|Tags, Batch|Single). */
    public static LinearLayout segmented(
            Activity a, String[] labels, int[] icons, int activeIndex, Consumer<Integer> onPick) {
        LinearLayout track = row(a);
        track.setBackgroundResource(R.drawable.bg_seg_track);
        pad(a, track, 4, 4, 4);
        track.setMinimumHeight(Ui.dp(a, 46));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean active = idx == activeIndex;
            LinearLayout seg = row(a);
            seg.setGravity(Gravity.CENTER);
            seg.setMinimumHeight(Ui.dp(a, 38));
            pad(a, seg, 10, 6, 6);
            seg.setBackgroundResource(active ? R.drawable.bg_seg_thumb : R.drawable.bg_flat);
            if (active) seg.setElevation(Ui.dp(a, 1));
            seg.setFocusable(true);
            seg.setClickable(true);
            seg.setSelected(active);
            if (icons != null && i < icons.length && icons[i] != 0) {
                ImageView iv = new ImageView(a);
                iv.setImageResource(icons[i]);
                iv.setColorFilter(color(a, active ? R.color.c_accent : R.color.c_text2));
                iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams ip =
                        new LinearLayout.LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16));
                ip.rightMargin = Ui.dp(a, 6);
                seg.addView(iv, ip);
            }
            TextView tv = text(a, labels[i], 13, active ? R.color.c_accent : R.color.c_text2);
            tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            seg.addView(tv);
            seg.setOnClickListener(
                    v -> {
                        if (idx != activeIndex) onPick.accept(idx);
                    });
            tactile(seg);
            track.addView(seg, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return track;
    }

    /** Visual circular color swatches replacing plain-text Spinner dropdowns. */
    public static HorizontalScrollView colorSwatches(
            Activity a, int initialColor, Consumer<Integer> onPick) {
        final int[] picked = {initialColor};
        LinearLayout row = row(a);
        pad(a, row, 2, 4, 4);
        final List<FrameLayout> dots = new ArrayList<>();
        for (int i = 0; i < Ui.COLORS.length; i++) {
            final int c = Ui.COLORS[i];
            FrameLayout dot = new FrameLayout(a);
            int size = Ui.dp(a, 36);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) dp.leftMargin = Ui.dp(a, 10);
            dot.setLayoutParams(dp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(c);
            dot.setBackground(gd);

            ImageView check = new ImageView(a);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(Color.WHITE);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            dot.addView(
                    check,
                    new FrameLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18), Gravity.CENTER));
            dot.setFocusable(true);
            dot.setClickable(true);
            dot.setContentDescription("颜色 " + Ui.COLOR_NAMES[i]);
            tactile(dot);
            dots.add(dot);
            dot.setOnClickListener(
                    v -> {
                        picked[0] = c;
                        syncSwatches(a, dots, c);
                        popBounce(dot);
                        onPick.accept(c);
                    });
            row.addView(dot);
        }
        syncSwatches(a, dots, initialColor);
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private static void syncSwatches(Activity a, List<FrameLayout> dots, int selectedColor) {
        int ring = color(a, R.color.c_card);
        for (int i = 0; i < dots.size() && i < Ui.COLORS.length; i++) {
            boolean on = Ui.COLORS[i] == selectedColor;
            FrameLayout dot = dots.get(i);
            dot.getChildAt(0).setVisibility(on ? View.VISIBLE : View.GONE);
            dot.setScaleX(on ? 1.12f : 1f);
            dot.setScaleY(on ? 1.12f : 1f);
            if (dot.getBackground() instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) dot.getBackground();
                gd.setStroke(on ? Ui.dp(a, 2) : 0, on ? ring : Color.TRANSPARENT);
            }
        }
    }

    /** Password input wrapper with focus ring and SVG eye visibility toggle. */
    public static PasswordField passwordBox(Activity a, String hint) {
        LinearLayout box = row(a);
        box.setBackgroundResource(R.drawable.bg_input_box);
        box.setMinimumHeight(Ui.dp(a, 52));
        pad(a, box, 12, 2, 2);

        EditText et = new EditText(a);
        et.setHint(hint);
        et.setContentDescription(hint);
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setTextColor(color(a, R.color.c_text));
        et.setHintTextColor(color(a, R.color.c_text2));
        et.setBackground(null);
        pad(a, et, 4, 12, 12);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(et, new LinearLayout.LayoutParams(0, -2, 1));

        final boolean[] visible = {false};
        ImageButton eye =
                quietIcon(
                        a,
                        R.drawable.ic_eye,
                        "显示密码",
                        () -> {});
        eye.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 42)));
        pad(a, eye, 10, 10, 10);
        eye.setOnClickListener(
                v -> {
                    visible[0] = !visible[0];
                    int selStart = et.getSelectionStart();
                    int selEnd = et.getSelectionEnd();
                    et.setInputType(
                            InputType.TYPE_CLASS_TEXT
                                    | (visible[0]
                                            ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                            : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                    if (selStart >= 0 && selEnd >= 0) et.setSelection(selStart, selEnd);
                    eye.setImageResource(visible[0] ? R.drawable.ic_eye_off : R.drawable.ic_eye);
                    eye.setColorFilter(
                            color(a, visible[0] ? R.color.c_accent : R.color.c_text2));
                    eye.setContentDescription(visible[0] ? "隐藏密码" : "显示密码");
                });
        box.addView(eye);
        focusWrap(box, et);
        return new PasswordField(box, et, eye);
    }

    /** Clean modern pill switch indicator for preference / backup cards. */
    public static LinearLayout toggleBadge(Activity a, boolean on, String labelOn, String labelOff) {
        LinearLayout pill = row(a);
        pill.setGravity(Gravity.CENTER);
        pill.setBackgroundResource(on ? R.drawable.bg_pill_on : R.drawable.bg_pill);
        pad(a, pill, 10, 5, 5);
        ImageView dot = new ImageView(a);
        dot.setImageResource(on ? R.drawable.ic_check : R.drawable.ic_circle);
        dot.setColorFilter(color(a, on ? R.color.c_accent : R.color.c_text2));
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14));
        dp.rightMargin = Ui.dp(a, 4);
        pill.addView(dot, dp);
        TextView tv =
                text(a, on ? labelOn : labelOff, 12, on ? R.color.c_accent : R.color.c_text2);
        tv.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        pill.addView(tv);
        return pill;
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
        tactile(b);
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
        b.setTextSize(14);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(color(a, R.color.c_accent));
        b.setBackgroundResource(R.drawable.bg_flat);
        b.setMinHeight(Ui.dp(a, 44));
        b.setMinimumHeight(Ui.dp(a, 44));
        pad(a, b, 12, 8, 8);
        b.setOnClickListener(v -> action.run());
        tactile(b);
        return b;
    }

    public static LinearLayout card(Activity a) {
        LinearLayout v = column(a);
        v.setBackgroundResource(R.drawable.bg_card);
        pad(a, v, 14, 14, 14);
        return v;
    }

    public static void section(Activity a, LinearLayout parent, String title, String subtitle) {
        gap(a, parent, 18);
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
        tactile(box);
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
        v.setBackgroundResource(R.drawable.bg_input_box);
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
        return "2.5.0";
    }

    /** Soft alpha version of an arbitrary folder color for icon badges. */
    public static int softTint(int color) {
        return (0x24 << 24) | (color & 0x00FFFFFF);
    }
}
