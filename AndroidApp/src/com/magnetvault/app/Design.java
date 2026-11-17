package com.magnetvault.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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

    public static Button button(
            Activity a, String label, int icon, boolean primary, Runnable action) {
        Button b = new Button(a);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinHeight(Ui.dp(a, 48));
        b.setMinimumHeight(Ui.dp(a, 48));
        b.setGravity(Gravity.CENTER);
        pad(a, b, 14, 8, 8);
        b.setTextColor(color(a, primary ? R.color.c_on_accent : R.color.c_accent));
        b.setBackgroundResource(primary ? R.drawable.bg_fab : R.drawable.bg_btn);
        if (icon != 0) {
            Drawable d = a.getDrawable(icon).mutate();
            d.setBounds(0, 0, Ui.dp(a, 20), Ui.dp(a, 20));
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
        pad(a, b, 14, 6, 6);
        if (selected) {
            b.setTextColor(color(a, R.color.c_on_accent));
            b.setBackgroundResource(R.drawable.bg_fab);
        }
        b.setSelected(selected);
        return b;
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
        b.setTextSize(14);
        b.setTextColor(color(a, R.color.c_accent));
        b.setBackgroundResource(R.drawable.bg_flat);
        b.setMinHeight(Ui.dp(a, 48));
        b.setMinimumHeight(Ui.dp(a, 48));
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
        TextView t = text(a, title, 16, R.color.c_text);
        t.setTypeface(null, Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        parent.addView(t);
        if (subtitle != null) {
            gap(a, parent, 4);
            parent.addView(text(a, subtitle, 12, R.color.c_text2));
        }
        gap(a, parent, 10);
    }

    public static LinearLayout action(
            Activity a, String title, String detail, int icon, Runnable action) {
        LinearLayout box = row(a);
        box.setBackgroundResource(R.drawable.bg_card);
        pad(a, box, 14, 12, 12);
        box.setMinimumHeight(Ui.dp(a, 64));
        ImageView image = new ImageView(a);
        image.setImageResource(icon);
        image.setColorFilter(color(a, R.color.c_accent));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22));
        ip.rightMargin = Ui.dp(a, 14);
        box.addView(image, ip);
        LinearLayout copy = column(a);
        TextView name = text(a, title, 15, R.color.c_text);
        name.setTypeface(null, Typeface.BOLD);
        copy.addView(name);
        if (detail != null) {
            gap(a, copy, 3);
            copy.addView(text(a, detail, 12, R.color.c_text2));
        }
        box.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text(a, "›", 22, R.color.c_text2);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(arrow);
        box.setFocusable(true);
        box.setClickable(true);
        box.setOnClickListener(v -> action.run());
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
        pad(a, row, 16, 8, 8);
        ImageButton b = icon(a, R.drawable.ic_back, "返回", back);
        b.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        row.addView(b);
        TextView h = text(a, title, 18, R.color.c_text);
        h.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.leftMargin = Ui.dp(a, 12);
        row.addView(h, p);
        return row;
    }

    public static void label(Activity a, LinearLayout box, String label) {
        gap(a, box, 16);
        TextView t = text(a, label, 14, R.color.c_text2);
        box.addView(t);
        gap(a, box, 8);
    }

    public static EditText input(Activity a, String label, boolean multiline) {
        EditText v = new EditText(a);
        v.setTextSize(16);
        v.setHint(label);
        v.setContentDescription(label);
        v.setTextColor(color(a, R.color.c_text));
        v.setHintTextColor(color(a, R.color.c_text2));
        v.setBackgroundResource(R.drawable.bg_card);
        pad(a, v, 14, 14, 14);
        v.setMinHeight(Ui.dp(a, 52));
        v.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        v.setSingleLine(!multiline);
        if (multiline) v.setGravity(Gravity.TOP);
        return v;
    }
}
