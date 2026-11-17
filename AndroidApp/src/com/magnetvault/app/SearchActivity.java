package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.*;

/** Debounced search, clear filters, retained results and visible recovery actions. */
public class SearchActivity extends AppActivity {
    public static final String EXTRA_CATEGORY_ID = "categoryId",
            EXTRA_CATEGORY_NAME = "categoryName";
    private String category;
    private boolean current;
    private int scope, request;
    private final List<String> selectedTags = new ArrayList<>();
    private final List<Entry> results = new ArrayList<>();
    private EditText input;
    private TextView status;
    private LinearLayout chips;
    private ListView list;
    private ResultAdapter adapter;
    private final Runnable search = this::search;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        category = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        current = category != null;
        if (state != null) {
            current = state.getBoolean("current");
            scope = state.getInt("scope");
            ArrayList<String> t = state.getStringArrayList("tags");
            if (t != null) selectedTags.addAll(t);
        } else if (getIntent().hasExtra("tag")) selectedTags.add(getIntent().getStringExtra("tag"));
        LinearLayout root = Design.column(this);
        root.setBackgroundColor(Design.color(this, R.color.c_bg));
        root.addView(Design.toolbar(this, "搜索收藏", this::finish));
        LinearLayout head = Design.column(this);
        Design.pad(this, head, 20, 8, 8);
        input = Design.input(this, "搜索标题、标签、备注或链接", false);
        input.setId(0x7f0a7002);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        head.addView(input);
        Design.gap(this, head, 12);
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        chips = Design.row(this);
        sc.addView(chips);
        head.addView(sc);
        Design.gap(this, head, 12);
        status = Design.text(this, "输入关键字，或按标签筛选", 14, R.color.c_text2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        head.addView(status);
        root.addView(head);
        list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        Design.pad(this, list, 0, 4, 24);
        adapter = new ResultAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        list.setOnItemClickListener(
                (p, v, pos, id) -> {
                    Intent i = new Intent(this, DetailActivity.class);
                    i.putExtra(DetailActivity.EXTRA_ENTRY_ID, results.get(pos).id);
                    startActivity(i);
                });
        input.setText(state == null ? "" : state.getString("query", ""));
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    public void afterTextChanged(Editable s) {
                        request++;
                        MAIN.removeCallbacks(search);
                        MAIN.postDelayed(search, 200);
                    }
                });
        input.setOnEditorActionListener(
                (v, action, e) -> {
                    MAIN.removeCallbacks(search);
                    search();
                    ((android.view.inputmethod.InputMethodManager)
                                    getSystemService(INPUT_METHOD_SERVICE))
                            .hideSoftInputFromWindow(input.getWindowToken(), 0);
                    return true;
                });
        styleFilters();
    }

    @Override
    protected void onResume() {
        super.onResume();
        search();
    }

    @Override
    protected void onDestroy() {
        MAIN.removeCallbacks(search);
        request++;
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle s) {
        s.putString("query", input.getText().toString());
        s.putBoolean("current", current);
        s.putInt("scope", scope);
        s.putStringArrayList("tags", new ArrayList<>(selectedTags));
        super.onSaveInstanceState(s);
    }

    private void chip(String label, Runnable action) {
        Button b = Design.button(this, label, 0, false, action);
        b.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = Ui.dp(this, 8);
        chips.addView(b, p);
    }

    private void styleFilters() {
        chips.removeAllViews();
        if (category != null)
            chip(
                    current ? "当前分类" : "全部收藏",
                    () -> {
                        current = !current;
                        styleFilters();
                        search();
                    });
        String[] scopes = {"全部字段", "标题", "标签", "备注"};
        chip(
                scopes[scope],
                () ->
                        new AlertDialog.Builder(this)
                                .setTitle("搜索哪些内容")
                                .setSingleChoiceItems(
                                        scopes,
                                        scope,
                                        (d, w) -> {
                                            scope = w;
                                            d.dismiss();
                                            styleFilters();
                                            search();
                                        })
                                .setNegativeButton("取消", null)
                                .show());
        chip(
                selectedTags.isEmpty() ? "筛选标签" : "标签 · " + selectedTags.size(),
                () ->
                        TagPicker.showFilter(
                                this,
                                "按标签筛选（任一匹配）",
                                selectedTags,
                                t -> {
                                    selectedTags.clear();
                                    selectedTags.addAll(t);
                                    styleFilters();
                                    search();
                                }));
        if (!selectedTags.isEmpty() || scope != 0 || current)
            chip(
                    "清除筛选",
                    () -> {
                        selectedTags.clear();
                        scope = 0;
                        current = false;
                        styleFilters();
                        search();
                    });
    }

    private void search() {
        String q = input.getText().toString().trim();
        int token = ++request;
        String cat = current ? category : null;
        int searchScope = scope;
        List<String> tags = new ArrayList<>(selectedTags);
        if (q.isEmpty() && tags.isEmpty()) {
            results.clear();
            adapter.notifyDataSetChanged();
            status.setText("输入关键字，或按标签筛选");
            return;
        }
        status.setText("正在查找…");
        load(
                () -> Repo.search(this, q, searchScope, tags, cat, false, Repo.SORT_ADDED),
                data -> {
                    if (token != request) return;
                    results.clear();
                    results.addAll(data);
                    adapter.notifyDataSetChanged();
                    status.setText(
                            data.isEmpty()
                                    ? "没有匹配的收藏，试试其他关键词或清除筛选"
                                    : "找到 "
                                            + data.size()
                                            + " 条收藏"
                                            + (tags.isEmpty()
                                                    ? ""
                                                    : " · 标签：" + TextUtils.join("、", tags)));
                });
    }

    private class ResultAdapter extends BaseAdapter {
        public int getCount() {
            return results.size();
        }

        public Object getItem(int p) {
            return results.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_entry, parent, false);
            Entry e = results.get(pos);
            ((TextView) v.findViewById(R.id.item_title)).setText(e.displayTitle());
            ((TextView) v.findViewById(R.id.item_sub))
                    .setText(
                            (e.categoryId == null ? "未分类" : e.categoryName)
                                    + (e.tags.isEmpty() ? "" : " · " + e.tagText()));
            v.findViewById(R.id.item_more).setVisibility(View.GONE);
            TextView star = v.findViewById(R.id.item_star);
            star.setText(e.starred ? "★" : "");
            star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return v;
        }
    }
}
