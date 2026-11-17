package com.magnetvault.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Searchable & multi-select tag bottom sheet; creation is explicit and disabled in filter mode. */
public final class TagPicker {
    public interface Listener {
        void onPicked(List<String> tags);
    }

    private TagPicker() {}

    public static void show(Activity host, String title, List<String> initial, Listener listener) {
        show(host, title, initial, true, Collections.emptyList(), listener);
    }

    public static void showFilter(
            Activity host, String title, List<String> initial, Listener listener) {
        show(host, title, initial, false, Collections.emptyList(), listener);
    }

    public static void showDraft(
            Activity host,
            String title,
            List<String> initial,
            List<String> draftTags,
            Listener listener) {
        show(host, title, initial, true, draftTags, listener);
    }

    private static void show(
            Activity host,
            String title,
            List<String> initial,
            boolean allowCreate,
            List<String> draftTags,
            Listener listener) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (initial != null) selected.addAll(initial);
        List<String> pool = new ArrayList<>(Repo.allTagNames(host));
        for (String name : draftTags) if (!pool.contains(name)) pool.add(name);
        for (String name : selected) if (!pool.contains(name)) pool.add(name);

        LinearLayout root = Ui.sheetContainer(host);

        // Header: title + live selected badge + clear action
        LinearLayout header = Design.row(host);
        TextView head = Design.text(host, title, 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(head, new LinearLayout.LayoutParams(0, -2, 1));

        TextView countBadge = Design.text(host, "", 12, R.color.c_accent);
        countBadge.setTypeface(null, Typeface.BOLD);
        countBadge.setGravity(Gravity.CENTER);
        Design.pad(host, countBadge, 10, 4, 4);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-2, -2);
        cbp.leftMargin = Ui.dp(host, 8);
        header.addView(countBadge, cbp);

        Button clearBtn = Design.textAction(host, "清空", () -> {});
        clearBtn.setMinHeight(Ui.dp(host, 36));
        clearBtn.setMinimumHeight(Ui.dp(host, 36));
        Design.pad(host, clearBtn, 8, 4, 4);
        header.addView(clearBtn);
        root.addView(header);

        Design.gap(host, root, 10);

        // Search input box with focus ring + clear icon
        LinearLayout searchWrap = Design.row(host);
        searchWrap.setBackgroundResource(R.drawable.bg_input_box);
        searchWrap.setMinimumHeight(Ui.dp(host, 46));
        Design.pad(host, searchWrap, 12, 2, 2);

        ImageView searchIcon = new ImageView(host);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setColorFilter(Design.color(host, R.color.c_text2));
        searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams sip =
                new LinearLayout.LayoutParams(Ui.dp(host, 17), Ui.dp(host, 17));
        sip.rightMargin = Ui.dp(host, 8);
        searchWrap.addView(searchIcon, sip);

        EditText input = new EditText(host);
        input.setHint(allowCreate ? "搜索已有标签，或输入新名称创建" : "搜索已有标签…");
        input.setContentDescription(allowCreate ? "搜索或创建标签" : "搜索标签");
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(Design.color(host, R.color.c_text));
        input.setHintTextColor(Design.color(host, R.color.c_text2));
        input.setBackground(null);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchWrap.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton clearSearch =
                Design.quietIcon(host, R.drawable.ic_close, "清空搜索", () -> input.setText(""));
        clearSearch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(host, 36), Ui.dp(host, 36)));
        Design.pad(host, clearSearch, 9, 9, 9);
        clearSearch.setVisibility(View.GONE);
        searchWrap.addView(clearSearch);
        Design.focusWrap(searchWrap, input);
        root.addView(searchWrap);

        Design.gap(host, root, 10);

        // Scrollable tag list area
        LinearLayout options = Design.column(host);
        ScrollView scroll = new ScrollView(host);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(options);
        int maxH =
                Math.min(
                        Ui.dp(host, 280),
                        host.getResources().getDisplayMetrics().heightPixels * 40 / 100);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, maxH));

        Design.gap(host, root, 12);

        // Bottom actions
        final Dialog[] ref = {null};
        LinearLayout actions = Design.row(host);
        Button cancel =
                Design.button(
                        host,
                        "取消",
                        0,
                        false,
                        () -> {
                            if (ref[0] != null) ref[0].dismiss();
                        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.rightMargin = Ui.dp(host, 10);
        actions.addView(cancel, cp);

        Button done =
                Design.button(
                        host,
                        "确定",
                        R.drawable.ic_check,
                        true,
                        () -> {
                            if (ref[0] != null) ref[0].dismiss();
                            listener.onPicked(new ArrayList<>(selected));
                        });
        actions.addView(done, new LinearLayout.LayoutParams(0, -2, 1.4f));
        root.addView(actions);

        final Runnable[] render = {null};
        Runnable syncHeader =
                () -> {
                    int n = selected.size();
                    countBadge.setText(n > 0 ? "已选 " + n + " 个" : "未选择");
                    countBadge.setBackgroundResource(n > 0 ? R.drawable.bg_pill_on : R.drawable.bg_pill);
                    countBadge.setTextColor(
                            Design.color(host, n > 0 ? R.color.c_accent : R.color.c_text2));
                    clearBtn.setEnabled(n > 0);
                    clearBtn.setAlpha(n > 0 ? 1f : 0.35f);
                    done.setText(n > 0 ? "确定 (" + n + ")" : "确定");
                };

        render[0] =
                () -> {
                    options.removeAllViews();
                    String q = input.getText().toString().trim();
                    clearSearch.setVisibility(input.getText().length() > 0 ? View.VISIBLE : View.GONE);

                    boolean existsExact = false;
                    for (String name : pool) {
                        if (name.equalsIgnoreCase(q)) {
                            existsExact = true;
                            break;
                        }
                    }

                    if (allowCreate && !q.isEmpty() && !existsExact && q.length() <= 40) {
                        LinearLayout createCard = Design.row(host);
                        createCard.setBackgroundResource(R.drawable.bg_card_selected);
                        createCard.setMinimumHeight(Ui.dp(host, 50));
                        Design.pad(host, createCard, 12, 10, 10);
                        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(-1, -2);
                        ccp.bottomMargin = Ui.dp(host, 8);

                        FrameLayout cBadge =
                                Design.iconBadge(
                                        host,
                                        R.drawable.ic_add,
                                        Design.color(host, R.color.c_accent),
                                        Design.color(host, R.color.c_accent_soft),
                                        32,
                                        17);
                        LinearLayout.LayoutParams cbp2 =
                                (LinearLayout.LayoutParams) cBadge.getLayoutParams();
                        cbp2.rightMargin = Ui.dp(host, 10);
                        createCard.addView(cBadge, cbp2);

                        TextView createTxt =
                                Design.text(host, "创建并选中「" + q + "」", 14, R.color.c_accent);
                        createTxt.setTypeface(null, Typeface.BOLD);
                        createCard.addView(createTxt, new LinearLayout.LayoutParams(0, -2, 1));
                        createCard.setFocusable(true);
                        createCard.setClickable(true);
                        Design.tactile(createCard);
                        createCard.setOnClickListener(
                                v -> {
                                    Design.animate(options);
                                    pool.add(0, q);
                                    selected.add(q);
                                    input.setText("");
                                    render[0].run();
                                });
                        options.addView(createCard, ccp);
                    }

                    int matches = 0;
                    String lowerQ = q.toLowerCase(Locale.ROOT);
                    for (String name : pool) {
                        if (!name.toLowerCase(Locale.ROOT).contains(lowerQ)) continue;
                        matches++;
                        boolean picked = selected.contains(name);
                        LinearLayout card = Design.row(host);
                        card.setBackgroundResource(
                                picked ? R.drawable.bg_card_selected : R.drawable.bg_card_soft);
                        card.setMinimumHeight(Ui.dp(host, 50));
                        Design.pad(host, card, 12, 10, 10);
                        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                        rp.bottomMargin = Ui.dp(host, 6);

                        FrameLayout badge =
                                Design.iconBadge(
                                        host,
                                        R.drawable.ic_tag,
                                        Design.color(
                                                host, picked ? R.color.c_accent : R.color.c_text2),
                                        Design.color(
                                                host,
                                                picked ? R.color.c_accent_soft : R.color.c_card),
                                        32,
                                        16);
                        LinearLayout.LayoutParams bp =
                                (LinearLayout.LayoutParams) badge.getLayoutParams();
                        bp.rightMargin = Ui.dp(host, 10);
                        card.addView(badge, bp);

                        TextView nameTv =
                                Design.text(
                                        host, name, 15, picked ? R.color.c_accent : R.color.c_text);
                        nameTv.setTypeface(null, picked ? Typeface.BOLD : Typeface.NORMAL);
                        nameTv.setSingleLine(true);
                        nameTv.setEllipsize(TextUtils.TruncateAt.END);
                        card.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1));

                        ImageView stateIcon = new ImageView(host);
                        stateIcon.setImageResource(picked ? R.drawable.ic_check : R.drawable.ic_circle);
                        stateIcon.setColorFilter(
                                Design.color(
                                        host, picked ? R.color.c_accent : R.color.c_line));
                        stateIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                        LinearLayout.LayoutParams ip =
                                new LinearLayout.LayoutParams(Ui.dp(host, 20), Ui.dp(host, 20));
                        ip.leftMargin = Ui.dp(host, 8);
                        card.addView(stateIcon, ip);

                        card.setFocusable(true);
                        card.setClickable(true);
                        Design.tactile(card);
                        card.setOnClickListener(
                                v -> {
                                    if (!selected.remove(name)) selected.add(name);
                                    boolean now = selected.contains(name);
                                    card.setBackgroundResource(
                                            now
                                                    ? R.drawable.bg_card_selected
                                                    : R.drawable.bg_card_soft);
                                    nameTv.setTextColor(
                                            Design.color(
                                                    host,
                                                    now ? R.color.c_accent : R.color.c_text));
                                    nameTv.setTypeface(
                                            null, now ? Typeface.BOLD : Typeface.NORMAL);
                                    ImageView badgeIcon = (ImageView) badge.getChildAt(0);
                                    badgeIcon.setColorFilter(
                                            Design.color(
                                                    host,
                                                    now ? R.color.c_accent : R.color.c_text2));
                                    ((android.graphics.drawable.GradientDrawable)
                                                    badge.getBackground())
                                            .setColor(
                                                    Design.color(
                                                            host,
                                                            now
                                                                    ? R.color.c_accent_soft
                                                                    : R.color.c_card));
                                    stateIcon.setImageResource(
                                            now ? R.drawable.ic_check : R.drawable.ic_circle);
                                    stateIcon.setColorFilter(
                                            Design.color(
                                                    host, now ? R.color.c_accent : R.color.c_line));
                                    if (now) Design.popBounce(stateIcon);
                                    syncHeader.run();
                                });
                        options.addView(card, rp);
                    }

                    if (matches == 0 && !(allowCreate && !q.isEmpty() && !existsExact)) {
                        LinearLayout empty = Design.column(host);
                        empty.setGravity(Gravity.CENTER_HORIZONTAL);
                        Design.pad(host, empty, 20, 28, 20);
                        empty.addView(
                                Design.iconBadge(
                                        host,
                                        R.drawable.ic_tag,
                                        Design.color(host, R.color.c_text2),
                                        Design.color(host, R.color.c_card2),
                                        44,
                                        20));
                        Design.gap(host, empty, 10);
                        empty.addView(
                                Design.text(
                                        host,
                                        q.isEmpty() ? "还没有标签" : "没有匹配的标签",
                                        14,
                                        R.color.c_text2));
                        options.addView(empty);
                    }
                    syncHeader.run();
                };

        clearBtn.setOnClickListener(
                v -> {
                    if (selected.isEmpty()) return;
                    selected.clear();
                    render[0].run();
                });

        input.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        render[0].run();
                    }
                });

        render[0].run();
        ref[0] = Ui.showSheet(host, root, true);
    }
}
