package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.*;
import android.view.Gravity;
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
    private ImageButton clearBtn;
    private TextView status;
    private LinearLayout chips, emptyBox;
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
        Design.pad(this, head, 14, 4, 8);

        // Search input box with leading search icon + trailing clear button
        LinearLayout searchBox = Design.row(this);
        searchBox.setBackgroundResource(R.drawable.bg_card);
        searchBox.setMinimumHeight(Ui.dp(this, 50));
        Design.pad(this, searchBox, 12, 2, 2);
        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setColorFilter(Design.color(this, R.color.c_text2));
        searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams sip =
                new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18));
        sip.rightMargin = Ui.dp(this, 8);
        searchBox.addView(searchIcon, sip);

        input = new EditText(this);
        input.setId(0x7f0a7002);
        input.setHint("搜索标题、标签、备注或链接");
        input.setContentDescription("搜索标题、标签、备注或链接");
        input.setTextSize(15);
        input.setTextColor(Design.color(this, R.color.c_text));
        input.setHintTextColor(Design.color(this, R.color.c_text2));
        input.setBackground(null);
        input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        clearBtn =
                Design.quietIcon(
                        this,
                        R.drawable.ic_close,
                        "清空搜索词",
                        () -> {
                            input.setText("");
                            input.requestFocus();
                        });
        clearBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        Design.pad(this, clearBtn, 10, 10, 10);
        clearBtn.setVisibility(View.GONE);
        searchBox.addView(clearBtn);
        head.addView(searchBox);

        Design.gap(this, head, 10);
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        chips = Design.row(this);
        sc.addView(chips);
        head.addView(sc);
        Design.gap(this, head, 10);
        status = Design.text(this, "输入关键字，或按标签筛选", 13, R.color.c_text2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Design.pad(this, status, 2, 0, 0);
        head.addView(status);
        root.addView(head);

        FrameLayout content = new FrameLayout(this);
        emptyBox = Design.column(this);
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(emptyBox, new FrameLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        Design.pad(this, list, 0, 4, 24);
        adapter = new ResultAdapter();
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        list.setOnItemClickListener(
                (p, v, pos, id) -> {
                    Intent i = new Intent(this, DetailActivity.class);
                    i.putExtra(DetailActivity.EXTRA_ENTRY_ID, results.get(pos).id);
                    startActivity(i);
                });
        input.setText(state == null ? "" : state.getString("query", ""));
        clearBtn.setVisibility(input.getText().length() > 0 ? View.VISIBLE : View.GONE);
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    public void afterTextChanged(Editable s) {
                        clearBtn.setVisibility(
                                s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
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

    private void chip(String label, int icon, boolean selected, Runnable action) {
        LinearLayout b = Design.pill(this, label, icon, selected, action);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = Ui.dp(this, 8);
        chips.addView(b, p);
    }

    private void styleFilters() {
        chips.removeAllViews();
        if (category != null)
            chip(
                    current ? "当前分类" : "全部收藏",
                    R.drawable.ic_folder,
                    current,
                    () -> {
                        current = !current;
                        styleFilters();
                        search();
                    });
        String[] scopes = {"全部字段", "标题", "标签", "备注"};
        chip(
                scope == 0 ? "搜索范围" : "仅" + scopes[scope],
                R.drawable.ic_chevron_down,
                scope != 0,
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
                R.drawable.ic_tag,
                !selectedTags.isEmpty(),
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
                    R.drawable.ic_close,
                    false,
                    () -> {
                        selectedTags.clear();
                        scope = 0;
                        current = false;
                        styleFilters();
                        search();
                    });
    }

    private void showEmpty(String title, String sub, String ctaLabel, Runnable cta) {
        emptyBox.removeAllViews();
        emptyBox.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
        emptyBox.addView(
                Design.emptyState(
                        this, R.drawable.ill_empty_search, title, sub, ctaLabel, cta));
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
            showEmpty(
                    "查找收藏",
                    "支持搜索标题、Hash、标签、备注或来源",
                    "按标签筛选",
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
                    if (data.isEmpty()) {
                        status.setText("没有匹配的收藏");
                        boolean hasFilter = !tags.isEmpty() || scope != 0 || current;
                        showEmpty(
                                "没有找到匹配的收藏",
                                "试试更换关键词" + (hasFilter ? "或清除当前筛选条件" : ""),
                                hasFilter ? "清除筛选" : null,
                                hasFilter
                                        ? () -> {
                                            selectedTags.clear();
                                            scope = 0;
                                            current = false;
                                            styleFilters();
                                            search();
                                        }
                                        : null);
                    } else {
                        emptyBox.setVisibility(View.GONE);
                        list.setVisibility(View.VISIBLE);
                        status.setText(
                                "找到 "
                                        + data.size()
                                        + " 条收藏"
                                        + (tags.isEmpty()
                                                ? ""
                                                : " · 标签：" + TextUtils.join("、", tags)));
                    }
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
            boolean ed2k = MagnetParser.isEd2k(e.magnet);

            ((TextView) v.findViewById(R.id.item_title)).setText(e.displayTitle());

            TextView proto = v.findViewById(R.id.item_proto);
            proto.setText(ed2k ? "ED2K" : "BT");
            proto.setTextColor(Design.color(SearchActivity.this, ed2k ? R.color.c_accent2 : R.color.c_accent));
            proto.setBackgroundResource(ed2k ? R.drawable.bg_badge_ed2k : R.drawable.bg_badge_magnet);

            View badge = v.findViewById(R.id.item_badge);
            badge.setBackgroundResource(ed2k ? R.drawable.bg_badge_ed2k : R.drawable.bg_badge_magnet);
            ImageView icon = v.findViewById(R.id.item_icon);
            icon.setImageResource(ed2k ? R.drawable.ic_file : R.drawable.ic_magnet);
            icon.setColorFilter(Design.color(SearchActivity.this, ed2k ? R.color.c_accent2 : R.color.c_accent));

            String meta = e.categoryId == null ? "未分类" : e.categoryName;
            if (ed2k) {
                long sz = MagnetParser.xlSize(e.magnet);
                if (sz > 0) meta += " · " + Ui.sizeText(sz);
            }
            if (!e.tags.isEmpty()) meta += " · " + e.tagText();
            ((TextView) v.findViewById(R.id.item_sub)).setText(meta);

            ImageView star = v.findViewById(R.id.item_star);
            star.setImageResource(e.starred ? R.drawable.ic_star : R.drawable.ic_star_outline);
            star.setColorFilter(
                    Design.color(SearchActivity.this, e.starred ? R.color.c_star : R.color.c_text2));
            star.setContentDescription((e.starred ? "取消星标 " : "添加星标 ") + e.displayTitle());
            star.setOnClickListener(
                    view -> {
                        final boolean next = !e.starred;
                        e.starred = next;
                        notifyDataSetChanged();
                        load(
                                () -> {
                                    Repo.setStar(SearchActivity.this, e.id, next);
                                    return true;
                                },
                                ok -> {});
                    });

            ImageView copy = v.findViewById(R.id.item_more);
            copy.setImageResource(R.drawable.ic_copy);
            copy.setColorFilter(Design.color(SearchActivity.this, R.color.c_accent));
            copy.setContentDescription("复制链接 " + e.displayTitle());
            copy.setOnClickListener(view -> Ui.copy(SearchActivity.this, e.magnet));

            return v;
        }
    }
}
