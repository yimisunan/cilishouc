package com.magnetvault.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;

import java.util.*;

/** Searchable choice list; creation is explicit and disabled in filter mode. */
public final class TagPicker {
    public interface Listener {
        void onPicked(List<String> tags);
    }

    public static void show(Activity host, String title, List<String> initial, Listener listener) {
        show(host, title, initial, true, Collections.emptyList(), listener);
    }

    public static void showFilter(
            Activity host, String title, List<String> initial, Listener listener) {
        show(host, title, initial, false, Collections.emptyList(), listener);
    }

    public static void showDraft(Activity host, String title, List<String> initial, List<String> draftTags, Listener listener) {
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
        LinearLayout box = Design.column(host);
        Design.pad(host, box, 20, 8, 12);
        EditText input = Design.input(host, allowCreate ? "搜索或输入新标签" : "搜索已有标签", false);
        box.addView(input);
        Design.gap(host, box, 12);
        TextView count = Design.text(host, "已选 " + selected.size() + " 个标签", 14, R.color.c_text2);
        box.addView(count);
        Design.gap(host, box, 8);
        LinearLayout options = Design.column(host);
        ScrollView scroll = Design.scroll(host, options);
        int height =
                Math.min(
                        Ui.dp(host, 240), host.getResources().getDisplayMetrics().heightPixels / 3);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, height));
        Button create = Design.button(host, "创建并选中", R.drawable.ic_add, false, () -> {});
        if (allowCreate) box.addView(create);
        Runnable[] render = {null};
        render[0] =
                () -> {
                    options.removeAllViews();
                    String q = input.getText().toString().trim();
                    int matches = 0;
                    for (String name : pool) {
                        if (!name.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                            continue;
                        matches++;
                        CheckBox check = new CheckBox(host);
                        check.setText(name);
                        check.setTextColor(Design.color(host, R.color.c_text));
                        check.setTextSize(16);
                        check.setMinHeight(Ui.dp(host, 48));
                        check.setChecked(selected.contains(name));
                        check.setOnCheckedChangeListener(
                                (v, on) -> {
                                    if (on) selected.add(name);
                                    else selected.remove(name);
                                    count.setText("已选 " + selected.size() + " 个标签");
                                });
                        options.addView(check);
                    }
                    if (matches == 0)
                        options.addView(
                                Design.text(
                                        host,
                                        q.isEmpty() ? "还没有标签" : "没有匹配的标签",
                                        15,
                                        R.color.c_text2));
                    boolean exists = false;
                    for (String name : pool) if (name.equalsIgnoreCase(q)) exists = true;
                    create.setEnabled(!q.isEmpty() && !exists && q.length() <= 40);
                    count.setText("已选 " + selected.size() + " 个标签");
                };
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    public void afterTextChanged(Editable s) {
                        render[0].run();
                    }
                });
        create.setOnClickListener(
                v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    pool.add(0, name);
                    selected.add(name);
                    input.setText("");
                    render[0].run();
                });
        render[0].run();
        AlertDialog dialog =
                new AlertDialog.Builder(host)
                        .setTitle(title)
                        .setView(box)
                        .setPositiveButton(
                                "完成", (d, w) -> listener.onPicked(new ArrayList<>(selected)))
                        .setNegativeButton("取消", null)
                        .setNeutralButton("清空选择", null)
                        .create();
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(-3)
                                .setOnClickListener(
                                        v -> {
                                            selected.clear();
                                            render[0].run();
                                        }));
        dialog.show();
    }
}
