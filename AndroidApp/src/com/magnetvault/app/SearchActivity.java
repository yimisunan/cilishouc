package com.magnetvault.app;

import android.content.Intent;
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
            EXTRA_CATEGORY_NAME = "categoryName",
            EXTRA_OPEN_TAG_PICKER = "openTagPicker";
    private String category;
    private boolean current;
    private int scope, request;
    private final List<String> selectedTags = new ArrayList<>();
    private final LinkedHashSet<String> expanded = new LinkedHashSet<>();
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
            ArrayList<String> exp = state.getStringArrayList("expanded");
            if (exp != null) expanded.addAll(exp);
        } else if (getIntent().hasExtra("tag")) selectedTags.add(getIntent().getStringExtra("tag"));

        LinearLayout root = Design.column(this);
        root.setBackgroundColor(Design.color(this, R.color.c_bg));
        root.addView(Design.toolbar(this, "搜索收藏", this::finish));

        LinearLayout head = Design.column(this);
        Design.pad(this, head, 14, 4, 8);

        // Search input box with leading search icon, focus ring + trailing clear button
        LinearLayout searchBox = Design.row(this);
        searchBox.setBackgroundResource(R.drawable.bg_input_box);
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
        Design.focusWrap(searchBox, input);
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
                    if (pos < 0 || pos >= results.size()) return;
                    String entryId = results.get(pos).id;
                    if (!expanded.remove(entryId)) expanded.add(entryId);
                    Design.animate(list);
                    adapter.notifyDataSetChanged();
                });
        list.setOnItemLongClickListener(
                (p, v, pos, id) -> {
                    if (pos < 0 || pos >= results.size()) return false;
                    openDetail(results.get(pos).id);
                    return true;
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
        if (state == null && getIntent().getBooleanExtra(EXTRA_OPEN_TAG_PICKER, false)) {
            root.post(this::openTagPicker);
        }
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
        s.putStringArrayList("expanded", new ArrayList<>(expanded));
        super.onSaveInstanceState(s);
    }

    private void chip(String label, int icon, boolean selected, Runnable action) {
        LinearLayout b = Design.pill(this, label, icon, selected, action);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = Ui.dp(this, 8);
        chips.addView(b, p);
    }

    private void openTagPicker() {
        TagPicker.showFilter(
                this,
                "按标签筛选（任一匹配）",
                selectedTags,
                t -> {
                    selectedTags.clear();
                    selectedTags.addAll(t);
                    styleFilters();
                    search();
                });
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
        String[] scopeSubs = {
            "同时匹配标题、链接、Hash、标签、备注与分类",
            "只在收藏名称与片名中查找",
            "只按已关联的标签名称查找",
            "只在自定义备注文本中查找"
        };
        int[] scopeIcons = {
            R.drawable.ic_search, R.drawable.ic_magnet, R.drawable.ic_tag, R.drawable.ic_edit
        };
        chip(
                scope == 0 ? "搜索范围" : "仅" + scopes[scope],
                R.drawable.ic_chevron_down,
                scope != 0,
                () ->
                        Ui.choiceSheet(
                                this,
                                "搜索范围",
                                scopes,
                                scopeSubs,
                                scopeIcons,
                                scope,
                                w -> {
                                    scope = w;
                                    styleFilters();
                                    search();
                                }));
        chip(
                selectedTags.isEmpty() ? "筛选标签" : "标签 · " + selectedTags.size(),
                R.drawable.ic_tag,
                !selectedTags.isEmpty(),
                this::openTagPicker);
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
            expanded.clear();
            adapter.notifyDataSetChanged();
            status.setText("输入关键字，或按标签筛选");
            showEmpty(
                    "查找收藏",
                    "支持搜索标题、Hash、标签、备注或来源",
                    "按标签筛选",
                    this::openTagPicker);
            return;
        }
        status.setText("正在查找…");
        load(
                () -> Repo.search(this, q, searchScope, tags, cat, false, Repo.SORT_ADDED),
                data -> {
                    if (token != request) return;
                    results.clear();
                    results.addAll(data);
                    Set<String> valid = new HashSet<>();
                    for (Entry e : data) valid.add(e.id);
                    expanded.retainAll(valid);
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

    private void openDetail(String id) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(DetailActivity.EXTRA_ENTRY_ID, id);
        startActivity(i);
    }

    private void toggleStar(Entry e) {
        final boolean next = !e.starred;
        e.starred = next;
        adapter.notifyDataSetChanged();
        load(
                () -> {
                    Repo.setStar(SearchActivity.this, e.id, next);
                    return true;
                },
                ok -> {});
    }

    private void moreActions(Entry e) {
        Ui.menuSheet(
                this,
                e.displayTitle(),
                new String[] {"编辑收藏", "分享链接", "移入回收站"},
                new int[] {R.drawable.ic_edit, R.drawable.ic_share, R.drawable.ic_trash},
                (d, w) -> {
                    if (w == 0) {
                        Intent i = new Intent(this, EditActivity.class);
                        i.putExtra(EditActivity.EXTRA_ENTRY_ID, e.id);
                        startActivity(i);
                    } else if (w == 1) {
                        Ui.share(this, e.magnet);
                    } else if (w == 2) {
                        Ui.confirmSheet(
                                this,
                                "移入回收站？",
                                e.displayTitle() + "\n之后可在「我的 → 回收站」恢复。",
                                "移入回收站",
                                true,
                                () ->
                                        load(
                                                () -> {
                                                    Repo.softDelete(this, e.id);
                                                    return true;
                                                },
                                                ok -> {
                                                    expanded.remove(e.id);
                                                    search();
                                                    Ui.toast(this, "已移入回收站");
                                                }));
                    }
                });
    }

    private void quickMoveFolder(Entry e) {
        CategoryPicker.show(
                this,
                "归档「" + e.displayTitle() + "」",
                e.categoryId,
                "未分类",
                "移出文件夹，设为未分类",
                null,
                true,
                catId ->
                        load(
                                () -> {
                                    Repo.moveCategory(
                                            SearchActivity.this,
                                            java.util.Collections.singletonList(e.id),
                                            catId);
                                    return true;
                                },
                                ok -> {
                                    search();
                                    Ui.toast(SearchActivity.this, "已更新分类");
                                }));
    }

    private void quickEditTags(Entry e) {
        TagPicker.show(
                this,
                "标签 · " + e.displayTitle(),
                e.tags,
                picked ->
                        load(
                                () -> {
                                    Entry fresh = Repo.byId(SearchActivity.this, e.id);
                                    if (fresh != null) {
                                        fresh.tags.clear();
                                        fresh.tags.addAll(picked);
                                        Repo.save(SearchActivity.this, fresh);
                                    }
                                    return true;
                                },
                                ok -> {
                                    search();
                                    Ui.toast(SearchActivity.this, "已更新标签");
                                }));
    }

    private final EntryCard.Actions searchActions =
            new EntryCard.Actions() {
                @Override
                public void onOpen(Entry entry) {
                    Ui.openMagnet(SearchActivity.this, entry.magnet);
                }

                @Override
                public void onCopy(Entry entry) {
                    Ui.copy(SearchActivity.this, entry.magnet);
                }

                @Override
                public void onToggleStar(Entry entry) {
                    toggleStar(entry);
                }

                @Override
                public void onDetail(Entry entry) {
                    openDetail(entry.id);
                }

                @Override
                public void onMore(Entry entry) {
                    moreActions(entry);
                }

                @Override
                public void onQuickFolder(Entry entry) {
                    quickMoveFolder(entry);
                }

                @Override
                public void onQuickTag(Entry entry) {
                    quickEditTags(entry);
                }
            };

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
            if (v == null || !(v.getTag() instanceof EntryCard.Holder)) {
                v = EntryCard.inflate(SearchActivity.this, parent);
            }
            Entry e = results.get(pos);
            EntryCard.bind(
                    SearchActivity.this,
                    v,
                    e,
                    ThemePrefs.isCompactList(SearchActivity.this),
                    expanded.contains(e.id),
                    false,
                    false,
                    current ? category : null,
                    searchActions);
            return v;
        }
    }
}
