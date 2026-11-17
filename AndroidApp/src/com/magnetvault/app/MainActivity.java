package com.magnetvault.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import java.util.*;

/** Content-first library with integrated Search, Folder/Tag Organizer, and Hub. */
public class MainActivity extends AppActivity {
    private static final String[] SORT_LABELS = {"最新添加", "最近修改", "标题 A → Z"};
    private static final String[] SORT_SUBS = {
        "按收藏创建时间从新到旧", "按最近编辑或修改时间", "按标题字母 A → Z 顺序"
    };
    private static final int[] SORT_ICONS = {
        R.drawable.ic_history, R.drawable.ic_edit, R.drawable.ic_sort
    };

    private int tab, filter, sort, request, orgMode;

    /** Folder location belongs to Organize → Folders; switching tabs preserves it. */
    private String category;

    private String orgTagQuery = "";
    private boolean selecting, loading;
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final LinkedHashSet<String> expanded = new LinkedHashSet<>();
    private final List<Object> rows = new ArrayList<>();
    private final List<Category> cats = new ArrayList<>();
    private List<Entry> entries = new ArrayList<>();
    private Map<String, Integer> tags = new LinkedHashMap<>();
    private int total, trash, unfiled, starred;
    private LinearLayout root, toolbar, header, nav, batch, fab, tagSearchBar;
    private EditText tagSearchInput;
    private ImageButton tagSearchClear;
    private ListView list;
    private RowAdapter adapter;
    private String positionKey;
    private final Map<String, Parcelable> positions = new LinkedHashMap<>();

    private static final class TagRow {
        final String name;
        final int count;

        TagRow(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sort = getPreferences(0).getInt("sort", Repo.SORT_ADDED);
        if (state != null) {
            tab = state.getInt("tab");
            filter = state.getInt("filter");
            sort = state.getInt("sort", sort);
            orgMode = state.getInt("orgMode");
            orgTagQuery = state.getString("orgTagQuery", "");
            category = state.getString("category");
            selecting = state.getBoolean("selecting");
            ArrayList<String> ids = state.getStringArrayList("selected");
            if (ids != null) selected.addAll(ids);
            ArrayList<String> exp = state.getStringArrayList("expanded");
            if (exp != null) expanded.addAll(exp);
            Bundle saved = state.getBundle("positions");
            if (saved != null)
                for (String k : saved.keySet()) positions.put(k, saved.getParcelable(k));
        }
        consumeFolderIntent(getIntent());
        root = Design.column(this);
        root.setBackgroundColor(Design.color(this, R.color.c_bg));
        FrameLayout centered = new FrameLayout(this);
        LinearLayout body = Design.column(this);
        int width = getResources().getConfiguration().screenWidthDp;
        centered.addView(
                body,
                new FrameLayout.LayoutParams(
                        width > 720 ? Ui.dp(this, 720) : -1, -1, Gravity.CENTER_HORIZONTAL));
        toolbar = Design.column(this);
        body.addView(toolbar);

        // Persistent tag filter bar under toolbar (keeps focus & cursor stable while typing)
        tagSearchBar = buildTagSearchBar();
        tagSearchBar.setVisibility(View.GONE);
        body.addView(tagSearchBar);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        Design.pad(this, list, 0, 4, 92);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        header = Design.column(this);
        list.addHeaderView(header, null, false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        // Floating pill FAB inside centered container
        fab = buildFab();
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(-2, Ui.dp(this, 52));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = Ui.dp(this, 18);
        fabLp.bottomMargin = Ui.dp(this, 16);
        centered.addView(fab, fabLp);

        // Floating batch selection bar
        batch = Design.row(this);
        batch.setBackgroundResource(R.drawable.bg_batch);
        batch.setElevation(Ui.dp(this, 8));
        Design.pad(this, batch, 8, 6, 6);
        batch.setVisibility(View.GONE);
        FrameLayout.LayoutParams batchLp = new FrameLayout.LayoutParams(-1, -2);
        batchLp.gravity = Gravity.BOTTOM;
        batchLp.leftMargin = Ui.dp(this, 14);
        batchLp.rightMargin = Ui.dp(this, 14);
        batchLp.bottomMargin = Ui.dp(this, 12);
        centered.addView(batch, batchLp);

        root.addView(centered, new LinearLayout.LayoutParams(-1, 0, 1));

        View navDivider = new View(this);
        navDivider.setBackgroundColor(Design.color(this, R.color.c_divider));
        root.addView(navDivider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        nav = Design.row(this);
        Design.pad(this, nav, 8, 4, 4);
        nav.setBackgroundColor(Design.color(this, R.color.c_nav_bg));
        root.addView(nav);
        setContentView(root);
        navigation();
        buildToolbar();
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    int index = position - list.getHeaderViewsCount();
                    if (loading || index < 0 || index >= rows.size()) return;
                    Object item = rows.get(index);
                    if (item instanceof Category) openFolder(((Category) item).id);
                    else if (item instanceof TagRow) openTagSearch(((TagRow) item).name);
                    else {
                        Entry e = (Entry) item;
                        if (selecting) toggle(e.id);
                        else toggleExpand(e.id);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    int index = position - list.getHeaderViewsCount();
                    if (loading || index < 0 || index >= rows.size()) return false;
                    Object item = rows.get(index);
                    if (item instanceof Entry) {
                        selecting = true;
                        expanded.clear();
                        selected.add(((Entry) item).id);
                        selectionUi();
                    } else if (item instanceof Category) {
                        folderActions((Category) item);
                    } else if (item instanceof TagRow) {
                        tagActions(((TagRow) item).name);
                    }
                    return true;
                });
        handleIntent(getIntent());
        int keep = ThemePrefs.trashKeepDays(this);
        if (keep > 0)
            DATA.execute(
                    () -> {
                        try {
                            Repo.purgeOlderThan(this, keep * 86400000L);
                        } catch (Exception e) {
                            android.util.Log.w("MagnetVault", "Cleanup deferred", e);
                        }
                    });
    }

    private LinearLayout buildTagSearchBar() {
        LinearLayout outer = Design.column(this);
        Design.pad(this, outer, 14, 0, 6);
        LinearLayout box = Design.row(this);
        box.setBackgroundResource(R.drawable.bg_input_box);
        box.setMinimumHeight(Ui.dp(this, 44));
        Design.pad(this, box, 12, 2, 2);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_search);
        icon.setColorFilter(Design.color(this, R.color.c_text2));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 17), Ui.dp(this, 17));
        ip.rightMargin = Ui.dp(this, 8);
        box.addView(icon, ip);

        tagSearchInput = new EditText(this);
        tagSearchInput.setHint("搜索或过滤标签…");
        tagSearchInput.setContentDescription("搜索标签");
        tagSearchInput.setTextSize(14);
        tagSearchInput.setTextColor(Design.color(this, R.color.c_text));
        tagSearchInput.setHintTextColor(Design.color(this, R.color.c_text2));
        tagSearchInput.setBackground(null);
        tagSearchInput.setSingleLine(true);
        tagSearchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        if (!orgTagQuery.isEmpty()) tagSearchInput.setText(orgTagQuery);
        box.addView(tagSearchInput, new LinearLayout.LayoutParams(0, -2, 1));

        tagSearchClear =
                Design.quietIcon(
                        this,
                        R.drawable.ic_close,
                        "清空标签搜索",
                        () -> tagSearchInput.setText(""));
        tagSearchClear.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        Design.pad(this, tagSearchClear, 9, 9, 9);
        tagSearchClear.setVisibility(orgTagQuery.isEmpty() ? View.GONE : View.VISIBLE);
        box.addView(tagSearchClear);
        Design.focusWrap(box, tagSearchInput);

        tagSearchInput.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    public void afterTextChanged(Editable s) {
                        orgTagQuery = s == null ? "" : s.toString();
                        tagSearchClear.setVisibility(orgTagQuery.isEmpty() ? View.GONE : View.VISIBLE);
                        if (tab == 1 && orgMode == 1 && !loading) {
                            refreshTagRowsOnly();
                        }
                    }
                });
        outer.addView(box);
        return outer;
    }

    private LinearLayout buildFab() {
        LinearLayout box = Design.row(this);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundResource(R.drawable.bg_fab);
        box.setElevation(Ui.dp(this, 6));
        Design.pad(this, box, 18, 0, 0);
        box.setFocusable(true);
        box.setClickable(true);
        box.setContentDescription("添加收藏");
        Design.tactile(box);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_add);
        icon.setColorFilter(Design.color(this, R.color.c_on_accent));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        ip.rightMargin = Ui.dp(this, 6);
        box.addView(icon, ip);
        TextView label = Design.text(this, "添加", 15, R.color.c_on_accent);
        label.setTypeface(null, Typeface.BOLD);
        box.addView(label);
        box.setOnClickListener(v -> addEntry());
        return box;
    }

    private void consumeFolderIntent(Intent intent) {
        if (intent.hasExtra("category")) {
            category = intent.getStringExtra("category");
            tab = 1;
            orgMode = 0;
            filter = 0;
            selecting = false;
            selected.clear();
            expanded.clear();
            intent.removeExtra("category");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        rememberPosition();
        setIntent(intent);
        consumeFolderIntent(intent);
        handleIntent(intent);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onPause() {
        rememberPosition();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        rememberPosition();
        state.putInt("tab", tab);
        state.putInt("filter", filter);
        state.putInt("sort", sort);
        state.putInt("orgMode", orgMode);
        state.putString("orgTagQuery", orgTagQuery);
        state.putString("category", category);
        state.putBoolean("selecting", selecting);
        state.putStringArrayList("selected", new ArrayList<>(selected));
        state.putStringArrayList("expanded", new ArrayList<>(expanded));
        Bundle saved = new Bundle();
        for (Map.Entry<String, Parcelable> e : positions.entrySet())
            saved.putParcelable(e.getKey(), e.getValue());
        state.putBundle("positions", saved);
        super.onSaveInstanceState(state);
    }

    private String key() {
        if (tab == 1) {
            return orgMode == 1 ? "1:tags" : "1:" + String.valueOf(category);
        }
        return tab + ":" + filter;
    }

    private void rememberPosition() {
        if (list != null && positionKey != null && !loading) {
            positions.put(positionKey, list.onSaveInstanceState());
            while (positions.size() > 100) positions.remove(positions.keySet().iterator().next());
        }
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            intent.setAction(null);
            if (text != null)
                startActivity(new Intent(this, ImportActivity.class).putExtra("text", text));
        }
    }

    private void reload() {
        rememberPosition();
        final int token = ++request;
        final int wantedTab = tab, wantedFilter = filter, wantedSort = sort, wantedOrgMode = orgMode;
        final String wantedCat = category;
        final String destination = key();
        loading = true;
        load(
                () -> {
                    Snapshot s = new Snapshot();
                    s.cats = Repo.categories(this, true);
                    s.tags = Repo.tagUsage(this);
                    s.total = Repo.countActive(this);
                    s.trash = Repo.countTrash(this);
                    s.unfiled = Repo.countUncategorized(this);
                    s.starred = Repo.countStarred(this);
                    if (wantedTab == 0) {
                        List<Entry> base =
                                Repo.listActive(
                                        this,
                                        null,
                                        wantedFilter == 1,
                                        wantedFilter == 2,
                                        null,
                                        wantedSort);
                        if (wantedFilter == 3 || wantedFilter == 4) {
                            List<Entry> filtered = new ArrayList<>();
                            boolean wantEd2k = wantedFilter == 4;
                            for (Entry e : base) {
                                if (MagnetParser.isEd2k(e.magnet) == wantEd2k) filtered.add(e);
                            }
                            s.entries = filtered;
                        } else {
                            s.entries = base;
                        }
                    } else if (wantedTab == 1 && wantedOrgMode == 0 && wantedCat != null) {
                        s.entries =
                                Repo.listActive(this, wantedCat, false, false, null, wantedSort);
                    } else {
                        s.entries = new ArrayList<>();
                    }
                    return s;
                },
                s -> {
                    if (token != request || !destination.equals(key())) return;
                    loading = false;
                    cats.clear();
                    cats.addAll(s.cats);
                    tags = s.tags;
                    entries = s.entries;
                    total = s.total;
                    trash = s.trash;
                    unfiled = s.unfiled;
                    starred = s.starred;
                    if (tab == 1 && orgMode == 0 && category != null && !hasCategory(category)) {
                        category = null;
                        reload();
                        return;
                    }
                    Set<String> valid = new HashSet<>();
                    for (Entry e : entries) valid.add(e.id);
                    selected.retainAll(valid);
                    expanded.retainAll(valid);
                    render();
                    positionKey = destination;
                    Parcelable restored = positions.get(destination);
                    if (restored == null) list.setSelection(0);
                    else
                        list.post(
                                () -> {
                                    if (token == request && !isDestroyed())
                                        list.onRestoreInstanceState(restored);
                                });
                },
                error -> {
                    if (token == request) {
                        loading = false;
                        Ui.toast(this, "暂时无法读取收藏，请重试");
                    }
                });
    }

    private boolean hasCategory(String id) {
        for (Category c : cats) if (c.id.equals(id)) return true;
        return false;
    }

    private Category findCategory(String id) {
        if (id == null) return null;
        for (Category c : cats) if (c.id.equals(id)) return c;
        return null;
    }

    private String categoryName() {
        Category c = findCategory(category);
        return c == null ? "整理收藏" : c.name;
    }

    private String pathName(String id) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (id != null && seen.add(id)) {
            Category found = findCategory(id);
            if (found == null) break;
            names.add(0, found.name);
            id = found.parentId;
        }
        return TextUtils.join(" / ", names);
    }

    private void switchTab(int value) {
        if (tab == value) return;
        rememberPosition();
        tab = value;
        selecting = false;
        selected.clear();
        expanded.clear();
        hideKeyboard();
        navigation();
        reload();
    }

    private void switchOrgMode(int mode) {
        if (orgMode == mode) return;
        rememberPosition();
        orgMode = mode;
        selecting = false;
        selected.clear();
        expanded.clear();
        if (mode == 0) hideKeyboard();
        reload();
    }

    private void openFolder(String id) {
        if (loading || selecting) return;
        rememberPosition();
        category = id;
        tab = 1;
        orgMode = 0;
        filter = 0;
        selected.clear();
        expanded.clear();
        reload();
    }

    private void goUpOneLevel() {
        if (tab != 1 || orgMode != 0 || category == null) return;
        rememberPosition();
        Category cur = findCategory(category);
        category = cur == null ? null : cur.parentId;
        selecting = false;
        selected.clear();
        expanded.clear();
        reload();
    }

    private void hideKeyboard() {
        if (tagSearchInput == null) return;
        try {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(tagSearchInput.getWindowToken(), 0);
            tagSearchInput.clearFocus();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            selecting = false;
            selected.clear();
            selectionUi();
            return;
        }
        if (tab == 1 && orgMode == 1) {
            if (!orgTagQuery.isEmpty() && tagSearchInput != null) {
                tagSearchInput.setText("");
                return;
            }
            hideKeyboard();
            switchOrgMode(0);
            return;
        }
        if (tab == 1 && category != null) {
            goUpOneLevel();
            return;
        }
        if (tab != 0) {
            switchTab(0);
            return;
        }
        if (filter != 0) {
            rememberPosition();
            filter = 0;
            reload();
            return;
        }
        super.onBackPressed();
    }

    private void navigation() {
        nav.removeAllViews();
        String[] labels = {"收藏", "整理", "我的"};
        int[] icons = {R.drawable.ic_magnet, R.drawable.ic_folder, R.drawable.ic_user};
        for (int i = 0; i < labels.length; i++) {
            final int value = i;
            boolean active = tab == i;
            LinearLayout item = Design.column(this);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(Ui.dp(this, 56));
            Design.pad(this, item, 6, 4, 4);
            item.setBackgroundResource(R.drawable.bg_flat);
            item.setSelected(active);
            item.setFocusable(true);
            item.setContentDescription(labels[i] + (active ? "，已选中" : ""));

            FrameLayout pill = new FrameLayout(this);
            if (active) pill.setBackgroundResource(R.drawable.bg_nav_pill);
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(Design.color(this, active ? R.color.c_accent : R.color.c_text2));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int sz = Ui.dp(this, 20);
            pill.addView(icon, new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER));
            item.addView(pill, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 30)));

            Design.gap(this, item, 3);
            TextView text =
                    Design.text(this, labels[i], 12, active ? R.color.c_accent : R.color.c_text2);
            text.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            text.setGravity(Gravity.CENTER);
            text.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            text.setSingleLine(true);
            item.addView(text, new LinearLayout.LayoutParams(-1, -2));
            item.setOnClickListener(v -> switchTab(value));
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private ImageButton tool(int icon, String label, Runnable action) {
        return Design.quietIcon(this, icon, label, action);
    }

    private String sortShortLabel() {
        if (sort == Repo.SORT_UPDATED) return "最近修改";
        if (sort == Repo.SORT_TITLE) return "标题";
        return "最新添加";
    }

    private void buildToolbar() {
        toolbar.removeAllViews();
        LinearLayout bar = Design.row(this);
        Design.pad(this, bar, 10, 6, 4);
        bar.setMinimumHeight(Ui.dp(this, 54));
        if (selecting) {
            ImageButton close =
                    tool(
                            R.drawable.ic_close,
                            "退出多选",
                            () -> {
                                selecting = false;
                                selected.clear();
                                selectionUi();
                            });
            close.setColorFilter(Design.color(this, R.color.c_text));
            bar.addView(close);
            TextView count = Design.text(this, "已选 " + selected.size() + " 条", 18, R.color.c_text);
            count.setTypeface(null, Typeface.BOLD);
            bar.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
            bar.addView(
                    Design.textAction(
                            this,
                            selected.size() == entries.size() && !entries.isEmpty() ? "取消全选" : "全选",
                            () -> {
                                if (selected.size() == entries.size()) selected.clear();
                                else for (Entry e : entries) selected.add(e.id);
                                selectionUi();
                            }));
        } else {
            if (tab == 1 && orgMode == 0 && category != null) {
                ImageButton back = tool(R.drawable.ic_back, "返回上级", this::goUpOneLevel);
                back.setColorFilter(Design.color(this, R.color.c_text));
                bar.addView(back);
            } else if (tab == 0 || tab == 1) {
                View badge =
                        Design.iconBadge(
                                this,
                                tab == 0 ? R.drawable.ic_cat : R.drawable.ic_folder,
                                Design.color(this, R.color.c_accent),
                                Design.color(this, R.color.c_accent_soft),
                                36,
                                19);
                LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
                bp.leftMargin = Ui.dp(this, 6);
                bp.rightMargin = Ui.dp(this, 6);
                bar.addView(badge, bp);
            } else {
                Design.hgap(this, bar, 6);
            }
            LinearLayout copy = Design.column(this);
            Design.pad(this, copy, 6, 2, 2);
            String titleStr =
                    tab == 0
                            ? "我的收藏"
                            : tab == 1 ? (orgMode == 1 ? "标签整理" : categoryName()) : "我的";
            TextView name = Design.text(this, titleStr, 20, R.color.c_text);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(name);
            String info;
            if (tab == 0) {
                if (filter == 1) info = starred + " 条星标收藏";
                else if (filter == 2) info = unfiled + " 条未分类收藏";
                else if (filter == 3) info = "仅显示 BT 磁力链接 · 共 " + entries.size() + " 条";
                else if (filter == 4) info = "仅显示 ED2K 文件链接 · 共 " + entries.size() + " 条";
                else info = total + " 条收藏 · " + starred + " 星标";
            } else if (tab == 1) {
                if (orgMode == 1) info = tags.size() + " 个标签 · 轻点查看相关收藏";
                else info = category == null ? rootFolderCount() + " 个根分类" : pathName(category);
            } else {
                info = "本地加密收藏夹 · v" + Design.versionName(this);
            }
            TextView sub = Design.text(this, info, 12, R.color.c_text2);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            copy.addView(sub);
            bar.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

            if (tab == 1) {
                bar.addView(tool(R.drawable.ic_search, "搜索收藏", this::openSearch));
                if (orgMode == 0) {
                    bar.addView(
                            tool(
                                    R.drawable.ic_add,
                                    "新建分类",
                                    () ->
                                            CategoryEditor.editCategory(
                                                    this, null, category, this::reload)));
                } else {
                    bar.addView(
                            tool(
                                    R.drawable.ic_add,
                                    "新建标签",
                                    () -> CategoryEditor.editTag(this, null, this::reload)));
                }
                bar.addView(tool(R.drawable.ic_more, "整理操作", this::pageActions));
            } else if (tab == 0) {
                bar.addView(tool(R.drawable.ic_more, "收藏操作", this::pageActions));
            }
        }
        toolbar.addView(bar);

        if (tab == 0 && !selecting) {
            // Prominent integrated search bar card
            LinearLayout searchWrap = Design.column(this);
            Design.pad(this, searchWrap, 14, 0, 8);
            LinearLayout searchBar = Design.row(this);
            searchBar.setBackgroundResource(R.drawable.bg_search);
            searchBar.setMinimumHeight(Ui.dp(this, 44));
            Design.pad(this, searchBar, 12, 4, 4);
            searchBar.setFocusable(true);
            searchBar.setClickable(true);
            searchBar.setContentDescription("搜索收藏");
            Design.tactile(searchBar);

            ImageView si = new ImageView(this);
            si.setImageResource(R.drawable.ic_search);
            si.setColorFilter(Design.color(this, R.color.c_text2));
            si.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams sip =
                    new LinearLayout.LayoutParams(Ui.dp(this, 17), Ui.dp(this, 17));
            sip.rightMargin = Ui.dp(this, 8);
            searchBar.addView(si, sip);

            TextView ph = Design.text(this, "搜索标题、番号、Hash、标签或备注…", 13, R.color.c_text2);
            ph.setSingleLine(true);
            ph.setEllipsize(TextUtils.TruncateAt.END);
            searchBar.addView(ph, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout tagBtn =
                    Design.pill(
                            this,
                            "按标签",
                            R.drawable.ic_tag,
                            false,
                            this::openSearchWithTags);
            Design.pad(this, tagBtn, 10, 4, 4);
            tagBtn.setMinimumHeight(Ui.dp(this, 32));
            tagBtn.setBackgroundResource(R.drawable.bg_card);
            searchBar.addView(tagBtn);
            searchBar.setOnClickListener(v -> openSearch());
            searchWrap.addView(searchBar);
            toolbar.addView(searchWrap);

            // Filter pills + layout toggle + sort
            LinearLayout filterBar = Design.row(this);
            Design.pad(this, filterBar, 14, 0, 8);
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout pills = Design.row(this);
            String[] names = {
                "全部 " + total, "星标 " + starred, "未分类 " + unfiled, "BT", "ED2K"
            };
            for (int i = 0; i < names.length; i++) {
                final int f = i;
                LinearLayout pill =
                        Design.pill(
                                this,
                                names[i],
                                0,
                                filter == i,
                                () -> {
                                    rememberPosition();
                                    filter = f;
                                    reload();
                                });
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
                pp.rightMargin = Ui.dp(this, 6);
                pills.addView(pill, pp);
            }
            hsv.addView(pills);
            filterBar.addView(hsv, new LinearLayout.LayoutParams(0, -2, 1));

            boolean compact = ThemePrefs.isCompactList(this);
            ImageButton densityBtn =
                    Design.icon(
                            this,
                            compact ? R.drawable.ic_view_compact : R.drawable.ic_view_full,
                            compact ? "当前：紧凑列表（点击切换完整标题）" : "当前：完整标题（点击切换紧凑）",
                            this::toggleCompactMode);
            densityBtn.setColorFilter(Design.color(this, R.color.c_accent));
            densityBtn.setBackgroundResource(R.drawable.bg_pill);
            densityBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
            Design.pad(this, densityBtn, 8, 8, 8);
            LinearLayout.LayoutParams dbp = (LinearLayout.LayoutParams) densityBtn.getLayoutParams();
            dbp.leftMargin = Ui.dp(this, 6);
            filterBar.addView(densityBtn, dbp);

            LinearLayout sortPill =
                    Design.pill(this, sortShortLabel(), R.drawable.ic_sort, false, this::chooseSort);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.leftMargin = Ui.dp(this, 6);
            filterBar.addView(sortPill, sp);
            toolbar.addView(filterBar);
        } else if (tab == 1 && !selecting) {
            LinearLayout segWrap = Design.column(this);
            Design.pad(this, segWrap, 14, 0, 8);
            segWrap.addView(
                    Design.segmented(
                            this,
                            new String[] {
                                "文件夹 (" + cats.size() + ")", "标签 (" + tags.size() + ")"
                            },
                            new int[] {R.drawable.ic_folder, R.drawable.ic_tag},
                            orgMode,
                            this::switchOrgMode));
            toolbar.addView(segWrap);
        }

        if (tagSearchBar != null) {
            tagSearchBar.setVisibility(tab == 1 && orgMode == 1 && !selecting ? View.VISIBLE : View.GONE);
        }
    }

    private int rootFolderCount() {
        int n = 0;
        for (Category c : cats) if (c.parentId == null) n++;
        return n;
    }

    private void pageActions() {
        boolean compact = ThemePrefs.isCompactList(this);
        String densityLabel = compact ? "切换为完整标题" : "切换为紧凑列表";
        if (tab == 0 || (tab == 1 && orgMode == 0 && category != null)) {
            List<String> labels =
                    new ArrayList<>(
                            Arrays.asList("添加收藏（批量 / 单条）", "排序方式", "多选批量管理", densityLabel));
            List<Integer> icons =
                    new ArrayList<>(
                            Arrays.asList(
                                    R.drawable.ic_add,
                                    R.drawable.ic_sort,
                                    R.drawable.ic_check,
                                    compact
                                            ? R.drawable.ic_view_full
                                            : R.drawable.ic_view_compact));
            if (tab == 1) {
                labels.add("新建子分类");
                icons.add(R.drawable.ic_folder);
                labels.add("管理当前文件夹");
                icons.add(R.drawable.ic_edit);
            } else {
                labels.add("管理分类与标签");
                icons.add(R.drawable.ic_tag);
            }
            int[] iconArr = new int[icons.size()];
            for (int i = 0; i < icons.size(); i++) iconArr[i] = icons.get(i);
            Ui.menuSheet(
                    this,
                    tab == 1 ? categoryName() : "收藏操作",
                    labels.toArray(new String[0]),
                    iconArr,
                    (d, w) -> {
                        if (w == 0) addEntry();
                        if (w == 1) chooseSort();
                        if (w == 2) {
                            selecting = true;
                            expanded.clear();
                            selectionUi();
                        }
                        if (w == 3) toggleCompactMode();
                        if (w == 4) {
                            if (tab == 1) CategoryEditor.editCategory(this, null, category, this::reload);
                            else switchTab(1);
                        }
                        if (w == 5) {
                            Category cur = findCategory(category);
                            if (cur != null) {
                                CategoryEditor.categoryActions(
                                        this, cur, cats, () -> {}, this::reload);
                            }
                        }
                    });
        } else if (tab == 1 && orgMode == 1) {
            Ui.menuSheet(
                    this,
                    "标签整理",
                    new String[] {"新建标签", "清理未使用的空标签", "去搜索筛选收藏"},
                    new int[] {R.drawable.ic_add, R.drawable.ic_trash, R.drawable.ic_search},
                    (d, w) -> {
                        if (w == 0) CategoryEditor.editTag(this, null, this::reload);
                        if (w == 1) CategoryEditor.cleanUnusedTags(this, this::reload);
                        if (w == 2) openSearchWithTags();
                    });
        } else {
            Ui.menuSheet(
                    this,
                    "文件夹整理",
                    new String[] {"新建分类", "查看所有标签", "添加收藏（批量 / 单条）", densityLabel},
                    new int[] {
                        R.drawable.ic_add,
                        R.drawable.ic_tag,
                        R.drawable.ic_import,
                        compact ? R.drawable.ic_view_full : R.drawable.ic_view_compact
                    },
                    (d, w) -> {
                        if (w == 0) CategoryEditor.editCategory(this, null, null, this::reload);
                        if (w == 1) switchOrgMode(1);
                        if (w == 2) addEntry();
                        if (w == 3) toggleCompactMode();
                    });
        }
    }

    private void toggleCompactMode() {
        boolean nextCompact = !ThemePrefs.isCompactList(this);
        ThemePrefs.setCompactList(this, nextCompact);
        Design.animate(list);
        buildToolbar();
        if (adapter != null) adapter.notifyDataSetChanged();
        Ui.toast(this, nextCompact ? "已切换为紧凑列表（点卡片展开全文）" : "已切换为完整标题显示");
    }

    private void folderActions(Category c) {
        CategoryEditor.categoryActions(this, c, cats, () -> openFolder(c.id), this::reload);
    }

    private void tagActions(String name) {
        CategoryEditor.tagActions(
                this, name, sortedTagNames(), () -> openTagSearch(name), this::reload);
    }

    private List<String> sortedTagNames() {
        List<String> names = new ArrayList<>(tags.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void render() {
        rows.clear();
        if (tab == 0) {
            rows.addAll(entries);
        } else if (tab == 1) {
            if (orgMode == 0) {
                for (Category c : cats) {
                    if (category == null ? c.parentId == null : category.equals(c.parentId))
                        rows.add(c);
                }
                rows.addAll(entries);
            } else {
                populateTagRows();
            }
        }
        rebuildHeader();
        navigation();
        selectionUi();
        adapter.notifyDataSetChanged();
    }

    private void refreshTagRowsOnly() {
        rows.clear();
        populateTagRows();
        rebuildHeader();
        adapter.notifyDataSetChanged();
    }

    private void populateTagRows() {
        String q = orgTagQuery.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> entry : Repo.sortByUsage(tags)) {
            if (q.isEmpty() || entry.getKey().toLowerCase(Locale.ROOT).contains(q)) {
                rows.add(new TagRow(entry.getKey(), entry.getValue()));
            }
        }
    }

    private void rebuildHeader() {
        header.removeAllViews();
        Design.pad(this, header, 14, 0, 0);
        if (tab == 1 && !selecting) {
            if (orgMode == 0) {
                buildFolderHeader();
            } else {
                buildTagHeader();
            }
        }
        if (tab == 2) mineHeader();
        else if (rows.isEmpty()) emptyState();
    }

    private void buildFolderHeader() {
        if (category != null) {
            // Breadcrumb bar showing path from 全部 down to current folder
            List<Category> trail = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String curId = category;
            while (curId != null && seen.add(curId)) {
                Category found = findCategory(curId);
                if (found == null) break;
                trail.add(0, found);
                curId = found.parentId;
            }
            HorizontalScrollView cs = new HorizontalScrollView(this);
            cs.setHorizontalScrollBarEnabled(false);
            LinearLayout crumbRow = Design.row(this);
            crumbRow.addView(Design.pill(this, "全部", 0, false, () -> openFolder(null)));
            for (int i = 0; i < trail.size(); i++) {
                final Category item = trail.get(i);
                final boolean last = i == trail.size() - 1;
                TextView sep = Design.text(this, "›", 14, R.color.c_text2);
                Design.pad(this, sep, 6, 0, 0);
                crumbRow.addView(sep);
                crumbRow.addView(
                        Design.pill(
                                this,
                                item.name,
                                0,
                                last,
                                last ? () -> {} : () -> openFolder(item.id)));
            }
            cs.addView(crumbRow);
            header.addView(cs);
            cs.post(() -> cs.fullScroll(View.FOCUS_RIGHT));
            Design.gap(this, header, 8);
        }

        // In-place quick action pills for folders
        HorizontalScrollView actScroll = new HorizontalScrollView(this);
        actScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actRow = Design.row(this);
        LinearLayout newFolder =
                Design.pill(
                        this,
                        category == null ? "新建分类" : "新建子分类",
                        R.drawable.ic_add,
                        false,
                        () -> CategoryEditor.editCategory(this, null, category, this::reload));
        LinearLayout.LayoutParams nfp = new LinearLayout.LayoutParams(-2, -2);
        nfp.rightMargin = Ui.dp(this, 8);
        actRow.addView(newFolder, nfp);

        if (category != null) {
            LinearLayout addHere =
                    Design.pill(
                            this, "添加收藏到当前", R.drawable.ic_import, true, this::addEntry);
            LinearLayout.LayoutParams ahp = new LinearLayout.LayoutParams(-2, -2);
            ahp.rightMargin = Ui.dp(this, 8);
            actRow.addView(addHere, ahp);

            Category curCat = findCategory(category);
            if (curCat != null) {
                LinearLayout manageCur =
                        Design.pill(
                                this,
                                "管理此文件夹",
                                R.drawable.ic_edit,
                                false,
                                () -> folderActions(curCat));
                actRow.addView(manageCur);
            }
        }
        actScroll.addView(actRow);
        header.addView(actScroll);
        Design.gap(this, header, 6);
    }

    private void buildTagHeader() {
        LinearLayout actRow = Design.row(this);
        LinearLayout addTag =
                Design.pill(
                        this,
                        "新建标签",
                        R.drawable.ic_add,
                        false,
                        () -> CategoryEditor.editTag(this, null, this::reload));
        LinearLayout.LayoutParams atp = new LinearLayout.LayoutParams(-2, -2);
        atp.rightMargin = Ui.dp(this, 8);
        actRow.addView(addTag, atp);

        LinearLayout cleanTag =
                Design.pill(
                        this,
                        "清理空标签",
                        R.drawable.ic_trash,
                        false,
                        () -> CategoryEditor.cleanUnusedTags(this, this::reload));
        actRow.addView(cleanTag);
        header.addView(actRow);
        Design.gap(this, header, 6);
    }

    private void emptyState() {
        if (tab == 1 && orgMode == 1) {
            if (!orgTagQuery.trim().isEmpty()) {
                header.addView(
                        Design.emptyState(
                                this,
                                R.drawable.ill_empty_search,
                                "没有找到匹配「" + orgTagQuery.trim() + "」的标签",
                                "可直接新建同名标签，或清空搜索词",
                                "新建标签",
                                () -> CategoryEditor.editTag(this, null, this::reload)));
            } else {
                header.addView(
                        Design.emptyState(
                                this,
                                R.drawable.ill_empty_folder,
                                "还没有标签",
                                "给收藏加上标签（如 待看、4K、蓝光），查找更快捷",
                                "创建第一个标签",
                                () -> CategoryEditor.editTag(this, null, this::reload)));
            }
        } else if (tab == 1 && category == null) {
            header.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_folder,
                            "还没有文件夹",
                            "用多级文件夹整理影片、剧集或常用合集",
                            "新建分类",
                            () -> CategoryEditor.editCategory(this, null, null, this::reload)));
        } else if (tab == 1) {
            header.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_folder,
                            "这个文件夹还没有收藏",
                            "可以粘贴链接直接保存到当前文件夹",
                            "添加收藏",
                            this::addEntry));
        } else if (filter == 1) {
            header.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_vault,
                            "还没有星标收藏",
                            "轻点条目展开后点击「星标」，把常用内容放在手边",
                            "查看全部收藏",
                            () -> {
                                rememberPosition();
                                filter = 0;
                                reload();
                            }));
        } else if (filter == 2) {
            header.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_vault,
                            "没有未分类收藏",
                            "所有收藏都已整齐归类",
                            "查看全部收藏",
                            () -> {
                                rememberPosition();
                                filter = 0;
                                reload();
                            }));
        } else if (filter == 3 || filter == 4) {
            header.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_search,
                            "没有" + (filter == 4 ? " ED2K " : " BT 磁力") + "收藏",
                            "当前筛选条件下暂无对应协议的链接",
                            "查看全部收藏",
                            () -> {
                                rememberPosition();
                                filter = 0;
                                reload();
                            }));
        } else {
            header.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_vault,
                            "收藏夹还是空的",
                            "支持 magnet 与 ED2K 链接，粘贴多条会自动识别去重",
                            "添加第一条收藏",
                            this::addEntry));
        }
    }

    private void toggle(String id) {
        if (!selected.remove(id)) selected.add(id);
        selectionUi();
    }

    private void mineHeader() {
        Design.gap(this, header, 8);
        // Hero vault card with SVG emblem + interactive stats
        LinearLayout hero = Design.column(this);
        hero.setBackgroundResource(R.drawable.bg_hero);
        Design.pad(this, hero, 16, 16, 14);

        LinearLayout top = Design.row(this);
        ImageView emblem = new ImageView(this);
        emblem.setImageResource(R.drawable.ill_hero_vault);
        emblem.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60));
        ep.rightMargin = Ui.dp(this, 14);
        top.addView(emblem, ep);

        LinearLayout info = Design.column(this);
        LinearLayout titleRow = Design.row(this);
        TextView appTitle = Design.text(this, "磁力收藏夹", 19, R.color.c_text);
        appTitle.setTypeface(null, Typeface.BOLD);
        titleRow.addView(appTitle);
        Design.hgap(this, titleRow, 8);
        TextView ver = Design.tagChip(this, "v" + Design.versionName(this), true, null);
        titleRow.addView(ver);
        info.addView(titleRow);
        Design.gap(this, info, 4);
        info.addView(
                Design.text(
                        this,
                        ThemePrefs.lastBackupAt(this) == 0
                                ? "本地加密存储 · 尚未备份"
                                : "最近备份 " + Ui.time(ThemePrefs.lastBackupAt(this)),
                        12,
                        R.color.c_text2));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(top);

        Design.gap(this, hero, 14);
        LinearLayout stats = Design.row(this);
        addStatCell(
                stats,
                String.valueOf(total),
                "收藏",
                () -> {
                    filter = 0;
                    switchTab(0);
                });
        Design.hgap(this, stats, 6);
        addStatCell(
                stats,
                String.valueOf(starred),
                "星标",
                () -> {
                    filter = 1;
                    switchTab(0);
                });
        Design.hgap(this, stats, 6);
        addStatCell(
                stats,
                String.valueOf(cats.size()),
                "分类",
                () -> {
                    orgMode = 0;
                    category = null;
                    switchTab(1);
                });
        Design.hgap(this, stats, 6);
        addStatCell(
                stats,
                String.valueOf(tags.size()),
                "标签",
                () -> {
                    orgMode = 1;
                    switchTab(1);
                });
        hero.addView(stats);
        header.addView(hero);

        Design.gap(this, header, 14);
        long lastBk = ThemePrefs.lastBackupAt(this);
        String backupSub =
                ThemePrefs.isDavConfigured(this)
                        ? (lastBk == 0
                                ? "已连接坚果云 · 支持自动备份与导出"
                                : "已连接坚果云 · 最近 " + Ui.time(lastBk))
                        : "本地加密 .mjbf 文件导出、恢复与坚果云同步";
        header.addView(
                Design.action(
                        this,
                        "备份与云端中心",
                        backupSub,
                        R.drawable.ic_cloud,
                        () -> startActivity(new Intent(this, CloudActivity.class))));
        Design.gap(this, header, 8);
        header.addView(
                Design.action(
                        this,
                        "回收站",
                        trash == 0 ? "空空如也，删除的收藏可随时恢复" : trash + " 条收藏等待处理",
                        R.drawable.ic_trash,
                        () -> startActivity(new Intent(this, TrashActivity.class))));
        Design.gap(this, header, 8);
        header.addView(
                Design.action(
                        this,
                        "外观与偏好",
                        "主题："
                                + ThemePrefs.themeLabel(this)
                                + " · 列表："
                                + (ThemePrefs.isCompactList(this) ? "紧凑标题" : "完整标题"),
                        R.drawable.ic_theme,
                        () -> settings("appearance")));
        Design.gap(this, header, 8);
        header.addView(
                Design.action(
                        this,
                        "使用指南",
                        "快速上手批量识别、分类整理与加密备份",
                        R.drawable.ic_info,
                        () -> Ui.guideSheet(this)));
        Design.gap(this, header, 20);
    }

    private void addStatCell(
            LinearLayout parent, String value, String label, Runnable onClick) {
        LinearLayout cell = Design.column(this);
        cell.setGravity(Gravity.CENTER);
        cell.setBackgroundResource(R.drawable.bg_card);
        Design.pad(this, cell, 6, 10, 10);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setContentDescription(label + " " + value);
        Design.tactile(cell);
        TextView num = Design.text(this, value, 17, R.color.c_text);
        num.setTypeface(null, Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        cell.addView(num);
        Design.gap(this, cell, 2);
        TextView sub = Design.text(this, label, 11, R.color.c_text2);
        sub.setGravity(Gravity.CENTER);
        cell.addView(sub);
        cell.setOnClickListener(v -> onClick.run());
        parent.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void settings(String section) {
        Intent i = new Intent(this, SettingsActivity.class);
        i.putExtra("section", section);
        startActivity(i);
    }

    private void addEntry() {
        openImport();
    }

    private void openImport() {
        Intent i = new Intent(this, ImportActivity.class);
        i.putExtra("category", tab == 1 && orgMode == 0 ? category : null);
        startActivity(i);
    }

    private void openSearch() {
        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra(SearchActivity.EXTRA_CATEGORY_ID, tab == 1 && orgMode == 0 ? category : null);
        i.putExtra(
                SearchActivity.EXTRA_CATEGORY_NAME,
                tab == 1 && orgMode == 0 && category != null ? categoryName() : "全部收藏");
        startActivity(i);
    }

    private void openSearchWithTags() {
        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra(SearchActivity.EXTRA_OPEN_TAG_PICKER, true);
        startActivity(i);
    }

    private void openTagSearch(String tagName) {
        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra("tag", tagName);
        startActivity(i);
    }

    private void openDetail(String id) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(DetailActivity.EXTRA_ENTRY_ID, id);
        startActivity(i);
    }

    private void chooseSort() {
        Ui.choiceSheet(
                this,
                "排序方式",
                SORT_LABELS,
                SORT_SUBS,
                SORT_ICONS,
                sort,
                w -> {
                    sort = w;
                    getPreferences(0).edit().putInt("sort", sort).apply();
                    reload();
                });
    }

    private void selectionUi() {
        batch.removeAllViews();
        batch.setVisibility(selecting ? View.VISIBLE : View.GONE);
        nav.setVisibility(selecting ? View.GONE : View.VISIBLE);
        if (fab != null) {
            boolean showFab = !selecting && (tab == 0 || tab == 1);
            fab.setVisibility(showFab ? View.VISIBLE : View.GONE);
        }
        buildToolbar();
        if (selecting) {
            String[] labels = {"移动", "加标签", "星标", "删除"};
            int[] icons = {
                R.drawable.ic_folder, R.drawable.ic_tag, R.drawable.ic_star, R.drawable.ic_trash
            };
            boolean enabled = !selected.isEmpty();
            for (int i = 0; i < labels.length; i++) {
                final int action = i;
                boolean danger = i == 3;
                LinearLayout btn = Design.column(this);
                btn.setGravity(Gravity.CENTER);
                btn.setMinimumHeight(Ui.dp(this, 48));
                Design.pad(this, btn, 4, 4, 4);
                btn.setBackgroundResource(R.drawable.bg_flat);
                btn.setEnabled(enabled);
                btn.setAlpha(enabled ? 1f : .4f);
                btn.setFocusable(true);
                btn.setClickable(enabled);
                Design.tactile(btn);
                ImageView iv = new ImageView(this);
                iv.setImageResource(icons[i]);
                iv.setColorFilter(
                        Design.color(
                                this,
                                danger
                                        ? R.color.c_danger
                                        : (i == 2 ? R.color.c_star : R.color.c_text)));
                iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                btn.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 19), Ui.dp(this, 19)));
                Design.gap(this, btn, 3);
                TextView tv =
                        Design.text(
                                this,
                                labels[i],
                                11,
                                danger ? R.color.c_danger : R.color.c_text);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setGravity(Gravity.CENTER);
                btn.addView(tv);
                btn.setOnClickListener(v -> batchAction(action));
                batch.addView(btn, new LinearLayout.LayoutParams(0, -2, 1));
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void batchAction(int which) {
        List<String> ids = new ArrayList<>(selected);
        if (ids.isEmpty()) return;
        if (which == 0) {
            CategoryPicker.show(
                    this,
                    "将 " + ids.size() + " 条收藏移动到",
                    tab == 1 ? category : null,
                    "未分类",
                    "移出文件夹，设为未分类",
                    null,
                    true,
                    catId ->
                            load(
                                    () -> {
                                        Repo.moveCategory(this, ids, catId);
                                        return true;
                                    },
                                    ok -> batchDone("收藏已移动")));
        }
        if (which == 1)
            TagPicker.show(
                    this,
                    "为 " + ids.size() + " 条收藏添加标签",
                    null,
                    t -> {
                        if (!t.isEmpty())
                            load(
                                    () -> Repo.addTagsToEntries(this, ids, t),
                                    n -> batchDone("标签已添加"));
                    });
        if (which == 2) {
            boolean allStarred = true;
            for (Entry e : entries) {
                if (selected.contains(e.id) && !e.starred) {
                    allStarred = false;
                    break;
                }
            }
            final boolean targetStar = !allStarred;
            load(
                    () -> {
                        for (String id : ids) Repo.setStar(this, id, targetStar);
                        return true;
                    },
                    ok -> batchDone(targetStar ? "已添加星标" : "已取消星标"));
        }
        if (which == 3)
            Ui.confirmSheet(
                    this,
                    "移入回收站？",
                    ids.size() + " 条收藏将移入回收站，可在「我的 → 回收站」随时恢复。",
                    "移入回收站",
                    true,
                    () ->
                            load(
                                    () -> Repo.batchSoftDelete(this, ids),
                                    n -> batchDone("已移入回收站 " + n + " 条")));
    }

    private void batchDone(String message) {
        selecting = false;
        selected.clear();
        Ui.toast(this, message);
        reload();
    }

    private void toggleExpand(String id) {
        if (!expanded.remove(id)) expanded.add(id);
        Design.animate(list);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void toggleStarEntry(Entry e) {
        final boolean next = !e.starred;
        e.starred = next;
        starred += next ? 1 : -1;
        if (starred < 0) starred = 0;
        if (tab == 0 && filter == 1 && !next) {
            expanded.remove(e.id);
            load(
                    () -> {
                        Repo.setStar(MainActivity.this, e.id, next);
                        return true;
                    },
                    ok -> reload());
        } else {
            buildToolbar();
            if (adapter != null) adapter.notifyDataSetChanged();
            load(
                    () -> {
                        Repo.setStar(MainActivity.this, e.id, next);
                        return true;
                    },
                    ok -> {});
        }
    }

    private final EntryCard.Actions entryActionsListener =
            new EntryCard.Actions() {
                @Override
                public void onOpen(Entry entry) {
                    Ui.openMagnet(MainActivity.this, entry.magnet);
                }

                @Override
                public void onCopy(Entry entry) {
                    Ui.copy(MainActivity.this, entry.magnet);
                }

                @Override
                public void onToggleStar(Entry entry) {
                    toggleStarEntry(entry);
                }

                @Override
                public void onDetail(Entry entry) {
                    openDetail(entry.id);
                }

                @Override
                public void onMore(Entry entry) {
                    entryActions(entry);
                }
            };

    private void entryActions(Entry e) {
        Ui.menuSheet(
                this,
                e.displayTitle(),
                new String[] {"编辑收藏", "分享链接", "多选", "移入回收站"},
                new int[] {
                    R.drawable.ic_edit, R.drawable.ic_share, R.drawable.ic_check, R.drawable.ic_trash
                },
                (d, w) -> {
                    if (w == 0) {
                        Intent i = new Intent(this, EditActivity.class);
                        i.putExtra(EditActivity.EXTRA_ENTRY_ID, e.id);
                        startActivity(i);
                    }
                    if (w == 1) Ui.share(this, e.magnet);
                    if (w == 2) {
                        selecting = true;
                        expanded.clear();
                        selected.add(e.id);
                        selectionUi();
                    }
                    if (w == 3)
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
                                                    reload();
                                                    Ui.toast(this, "已移入回收站");
                                                }));
                });
    }

    private static class Snapshot {
        List<Category> cats;
        List<Entry> entries;
        Map<String, Integer> tags;
        int total, trash, unfiled, starred;
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int p) {
            return rows.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public int getViewTypeCount() {
            return 3;
        }

        public int getItemViewType(int p) {
            Object item = rows.get(p);
            if (item instanceof Entry) return 0;
            if (item instanceof Category) return 1;
            return 2;
        }

        public View getView(int pos, View recycled, ViewGroup parent) {
            Object item = rows.get(pos);
            if (item instanceof Category) {
                Category c = (Category) item;
                View view = recycled;
                if (view == null || !(view.getTag() instanceof FolderHolder)) {
                    LinearLayout wrap = Design.column(MainActivity.this);
                    Design.pad(MainActivity.this, wrap, 14, 4, 4);
                    LinearLayout line = Design.row(MainActivity.this);
                    Design.pad(MainActivity.this, line, 12, 12, 12);
                    line.setMinimumHeight(Ui.dp(MainActivity.this, 68));
                    line.setBackgroundResource(R.drawable.bg_card);
                    Design.tactile(line);

                    FrameLayout badge =
                            Design.iconBadge(
                                    MainActivity.this,
                                    R.drawable.ic_folder,
                                    c.color,
                                    Design.softTint(c.color),
                                    40,
                                    20);
                    LinearLayout.LayoutParams ip =
                            (LinearLayout.LayoutParams) badge.getLayoutParams();
                    ip.rightMargin = Ui.dp(MainActivity.this, 12);
                    line.addView(badge, ip);

                    LinearLayout copy = Design.column(MainActivity.this);
                    TextView name = Design.text(MainActivity.this, "", 15, R.color.c_text);
                    name.setTypeface(null, Typeface.BOLD);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    copy.addView(name);
                    Design.gap(MainActivity.this, copy, 3);
                    TextView detail = Design.text(MainActivity.this, "", 12, R.color.c_text2);
                    detail.setSingleLine(true);
                    detail.setEllipsize(TextUtils.TruncateAt.END);
                    copy.addView(detail);
                    line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

                    ImageButton more = tool(R.drawable.ic_more, "管理分类", () -> {});
                    line.addView(more);
                    ImageView chev = new ImageView(MainActivity.this);
                    chev.setImageResource(R.drawable.ic_chevron_right);
                    chev.setColorFilter(Design.color(MainActivity.this, R.color.c_text2));
                    chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    line.addView(
                            chev,
                            new LinearLayout.LayoutParams(
                                    Ui.dp(MainActivity.this, 18), Ui.dp(MainActivity.this, 18)));

                    wrap.addView(line);
                    wrap.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    FolderHolder h = new FolderHolder();
                    h.name = name;
                    h.detail = detail;
                    h.badge = badge;
                    h.icon = (ImageView) badge.getChildAt(0);
                    h.more = more;
                    h.line = line;
                    wrap.setTag(h);
                    view = wrap;
                }
                FolderHolder h = (FolderHolder) view.getTag();
                h.name.setText(c.name);
                h.icon.setColorFilter(c.color);
                ((GradientDrawable) h.badge.getBackground()).setColor(Design.softTint(c.color));
                h.detail.setText(
                        c.count
                                + " 条收藏"
                                + (c.childCount > 0 ? " · " + c.childCount + " 个子文件夹" : ""));
                h.more.setContentDescription("管理分类 " + c.name);
                h.more.setOnClickListener(v -> folderActions(c));
                h.line.setOnClickListener(v -> openFolder(c.id));
                h.line.setOnLongClickListener(
                        v -> {
                            folderActions(c);
                            return true;
                        });
                return view;
            }
            if (item instanceof TagRow) {
                TagRow tr = (TagRow) item;
                View view = recycled;
                if (view == null || !(view.getTag() instanceof TagHolder)) {
                    LinearLayout wrap = Design.column(MainActivity.this);
                    Design.pad(MainActivity.this, wrap, 14, 4, 4);
                    LinearLayout line = Design.row(MainActivity.this);
                    Design.pad(MainActivity.this, line, 12, 12, 12);
                    line.setMinimumHeight(Ui.dp(MainActivity.this, 62));
                    line.setBackgroundResource(R.drawable.bg_card);
                    Design.tactile(line);

                    FrameLayout badge =
                            Design.iconBadge(
                                    MainActivity.this,
                                    R.drawable.ic_tag,
                                    Design.color(MainActivity.this, R.color.c_accent2),
                                    Design.color(MainActivity.this, R.color.c_accent2_soft),
                                    38,
                                    18);
                    LinearLayout.LayoutParams ip =
                            (LinearLayout.LayoutParams) badge.getLayoutParams();
                    ip.rightMargin = Ui.dp(MainActivity.this, 12);
                    line.addView(badge, ip);

                    LinearLayout copy = Design.column(MainActivity.this);
                    TextView name = Design.text(MainActivity.this, "", 15, R.color.c_text);
                    name.setTypeface(null, Typeface.BOLD);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    copy.addView(name);
                    Design.gap(MainActivity.this, copy, 2);
                    TextView detail = Design.text(MainActivity.this, "", 12, R.color.c_text2);
                    copy.addView(detail);
                    line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

                    ImageButton more = tool(R.drawable.ic_more, "管理标签", () -> {});
                    line.addView(more);
                    wrap.addView(line);
                    wrap.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    TagHolder h = new TagHolder();
                    h.name = name;
                    h.detail = detail;
                    h.badge = badge;
                    h.icon = (ImageView) badge.getChildAt(0);
                    h.more = more;
                    h.line = line;
                    wrap.setTag(h);
                    view = wrap;
                }
                TagHolder h = (TagHolder) view.getTag();
                h.name.setText("# " + tr.name);
                h.detail.setText(tr.count == 0 ? "尚未关联任何收藏" : tr.count + " 条收藏");
                int iconTint =
                        Design.color(
                                MainActivity.this,
                                tr.count > 0 ? R.color.c_accent2 : R.color.c_text2);
                int badgeBg =
                        Design.color(
                                MainActivity.this,
                                tr.count > 0 ? R.color.c_accent2_soft : R.color.c_card2);
                h.icon.setColorFilter(iconTint);
                ((GradientDrawable) h.badge.getBackground()).setColor(badgeBg);
                h.more.setContentDescription("管理标签 " + tr.name);
                h.more.setOnClickListener(v -> tagActions(tr.name));
                h.line.setOnClickListener(v -> openTagSearch(tr.name));
                h.line.setOnLongClickListener(
                        v -> {
                            tagActions(tr.name);
                            return true;
                        });
                return view;
            }
            View view = recycled;
            if (view == null || !(view.getTag() instanceof EntryCard.Holder)) {
                view = EntryCard.inflate(MainActivity.this, parent);
            }
            Entry e = (Entry) item;
            EntryCard.bind(
                    MainActivity.this,
                    view,
                    e,
                    ThemePrefs.isCompactList(MainActivity.this),
                    expanded.contains(e.id),
                    selecting,
                    selected.contains(e.id),
                    tab == 1 ? category : null,
                    entryActionsListener);
            return view;
        }
    }

    private static class FolderHolder {
        TextView name, detail;
        FrameLayout badge;
        ImageView icon;
        ImageButton more;
        View line;
    }

    private static class TagHolder {
        TextView name, detail;
        FrameLayout badge;
        ImageView icon;
        ImageButton more;
        View line;
    }
}
