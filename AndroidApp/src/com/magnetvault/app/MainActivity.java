package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.*;

/** Content-first library. Folder browsing and management stay in their current hierarchy. */
public class MainActivity extends AppActivity {
    private int tab, filter, sort, request;

    /** Folder location belongs to the Organize tab; switching tabs does not discard it. */
    private String category;

    private boolean selecting, loading;
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final LinkedHashSet<String> expanded = new LinkedHashSet<>();
    private final List<Object> rows = new ArrayList<>();
    private final List<Category> cats = new ArrayList<>();
    private List<Entry> entries = new ArrayList<>();
    private Map<String, Integer> tags = new LinkedHashMap<>();
    private int total, trash, unfiled, starred;
    private LinearLayout root, toolbar, header, nav, batch, fab;
    private ListView list;
    private RowAdapter adapter;
    private String positionKey;
    private final Map<String, Parcelable> positions = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sort = getPreferences(0).getInt("sort", Repo.SORT_ADDED);
        if (state != null) {
            tab = state.getInt("tab");
            filter = state.getInt("filter");
            sort = state.getInt("sort", sort);
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
                    } else folderActions((Category) item);
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

    private LinearLayout buildFab() {
        LinearLayout box = Design.row(this);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundResource(R.drawable.bg_fab);
        box.setElevation(Ui.dp(this, 6));
        Design.pad(this, box, 18, 0, 0);
        box.setFocusable(true);
        box.setClickable(true);
        box.setContentDescription("添加收藏");
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
        return tab + ":" + (tab == 1 ? String.valueOf(category) : String.valueOf(filter));
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
        final int wantedTab = tab, wantedFilter = filter, wantedSort = sort;
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
                    s.entries =
                            wantedTab == 0
                                    ? Repo.listActive(
                                            this,
                                            null,
                                            wantedFilter == 1,
                                            wantedFilter == 2,
                                            null,
                                            wantedSort)
                                    : wantedTab == 1 && wantedCat != null
                                            ? Repo.listActive(
                                                    this, wantedCat, false, false, null, wantedSort)
                                            : new ArrayList<>();
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
                    if (tab == 1 && category != null && !hasCategory(category)) {
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

    private String categoryName() {
        for (Category c : cats) if (c.id.equals(category)) return c.name;
        return "整理收藏";
    }

    private String pathName(String id) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (id != null && seen.add(id)) {
            Category found = null;
            for (Category c : cats)
                if (c.id.equals(id)) {
                    found = c;
                    break;
                }
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
        navigation();
        reload();
    }

    private void openFolder(String id) {
        if (loading || selecting) return;
        rememberPosition();
        category = id;
        tab = 1;
        filter = 0;
        selected.clear();
        expanded.clear();
        reload();
    }

    private void goUpOneLevel() {
        if (tab != 1 || category == null) return;
        rememberPosition();
        String parent = null;
        for (Category c : cats) if (c.id.equals(category)) parent = c.parentId;
        category = parent;
        selecting = false;
        selected.clear();
        expanded.clear();
        reload();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            selecting = false;
            selected.clear();
            selectionUi();
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
        Design.pad(this, bar, 10, 8, 6);
        bar.setMinimumHeight(Ui.dp(this, 56));
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
            if (tab == 1 && category != null) {
                ImageButton back = tool(R.drawable.ic_back, "返回上级", this::goUpOneLevel);
                back.setColorFilter(Design.color(this, R.color.c_text));
                bar.addView(back);
            } else if (tab == 0) {
                View badge =
                        Design.iconBadge(
                                this,
                                R.drawable.ic_cat,
                                Design.color(this, R.color.c_accent),
                                Design.color(this, R.color.c_accent_soft),
                                36,
                                19);
                LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
                bp.leftMargin = Ui.dp(this, 6);
                bp.rightMargin = Ui.dp(this, 6);
                bar.addView(badge, bp);
            }
            LinearLayout copy = Design.column(this);
            Design.pad(this, copy, 6, 2, 2);
            TextView name =
                    Design.text(
                            this,
                            tab == 0 ? "我的收藏" : tab == 1 ? categoryName() : "我的",
                            20,
                            R.color.c_text);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(name);
            String info;
            if (tab == 0)
                info =
                        filter == 1
                                ? starred + " 条星标收藏"
                                : filter == 2 ? unfiled + " 条未分类" : total + " 条收藏 · " + starred + " 星标";
            else if (tab == 1)
                info = category == null ? rootFolderCount() + " 个根分类" : pathName(category);
            else info = "磁力收藏夹 · 本地加密库";
            TextView sub = Design.text(this, info, 12, R.color.c_text2);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            copy.addView(sub);
            bar.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            if (tab != 2) {
                bar.addView(tool(R.drawable.ic_search, "搜索收藏", this::openSearch));
                if (tab == 1) bar.addView(tool(R.drawable.ic_add, "添加收藏", this::addEntry));
                bar.addView(
                        tool(R.drawable.ic_more, tab == 1 ? "文件夹操作" : "收藏操作", this::pageActions));
            }
        }
        toolbar.addView(bar);

        if (tab == 0 && !selecting) {
            LinearLayout filterBar = Design.row(this);
            Design.pad(this, filterBar, 14, 2, 8);
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout pills = Design.row(this);
            String[] names = {"全部 " + total, "星标 " + starred, "未分类 " + unfiled};
            for (int i = 0; i < names.length; i++) {
                final int f = i;
                LinearLayout pill = Design.pill(this, names[i], 0, filter == i, () -> {
                    rememberPosition();
                    filter = f;
                    reload();
                });
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
                pp.rightMargin = Ui.dp(this, 8);
                pills.addView(pill, pp);
            }
            hsv.addView(pills);
            filterBar.addView(hsv, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout sortPill =
                    Design.pill(this, sortShortLabel(), R.drawable.ic_sort, false, this::chooseSort);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.leftMargin = Ui.dp(this, 8);
            filterBar.addView(sortPill, sp);
            toolbar.addView(filterBar);
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
        if (tab == 0 || category != null) {
            List<String> labels =
                    new ArrayList<>(
                            Arrays.asList("添加收藏（单条 / 批量）", "排序方式", "多选", densityLabel));
            List<Integer> icons =
                    new ArrayList<>(
                            Arrays.asList(
                                    R.drawable.ic_add,
                                    R.drawable.ic_sort,
                                    R.drawable.ic_check,
                                    R.drawable.ic_theme));
            if (tab == 1) {
                labels.add("新建子分类");
                icons.add(R.drawable.ic_folder);
                labels.add("管理当前分类");
                icons.add(R.drawable.ic_edit);
            } else {
                labels.add("分类与标签");
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
                            if (tab == 1) manageFolder("create", category);
                            else manage(false);
                        }
                        if (w == 5) manageFolder("edit", category);
                    });
        } else
            Ui.menuSheet(
                    this,
                    "文件夹操作",
                    new String[] {"新建分类", "分类管理", "标签管理", "添加收藏（单条 / 批量）", densityLabel},
                    new int[] {
                        R.drawable.ic_add,
                        R.drawable.ic_folder,
                        R.drawable.ic_tag,
                        R.drawable.ic_import,
                        R.drawable.ic_theme
                    },
                    (d, w) -> {
                        if (w == 0) manageFolder("create", null);
                        if (w == 1) manage(false);
                        if (w == 2) manage(true);
                        if (w == 3) openImport();
                        if (w == 4) toggleCompactMode();
                    });
    }

    private void toggleCompactMode() {
        boolean nextCompact = !ThemePrefs.isCompactList(this);
        ThemePrefs.setCompactList(this, nextCompact);
        if (adapter != null) adapter.notifyDataSetChanged();
        Ui.toast(this, nextCompact ? "已切换为紧凑列表（点卡片展开全文）" : "已切换为完整标题显示");
    }

    private void folderActions(Category c) {
        Ui.menuSheet(
                this,
                c.name,
                new String[] {"打开文件夹", "重命名与颜色", "新建子分类", "移动分类", "删除分类"},
                new int[] {
                    R.drawable.ic_folder,
                    R.drawable.ic_edit,
                    R.drawable.ic_add,
                    R.drawable.ic_move,
                    R.drawable.ic_trash
                },
                (d, w) -> {
                    if (w == 0) openFolder(c.id);
                    if (w == 1) manageFolder("edit", c.id);
                    if (w == 2) manageFolder("create", c.id);
                    if (w == 3) manageFolder("move", c.id);
                    if (w == 4) manageFolder("delete", c.id);
                });
    }

    private void manage(boolean tag) {
        startActivity(
                new Intent(this, CategoryActivity.class)
                        .putExtra("tags", tag)
                        .putExtra("parent", tab == 1 ? category : null));
    }

    private void manageFolder(String action, String id) {
        startActivity(
                new Intent(this, CategoryActivity.class)
                        .putExtra("action", action)
                        .putExtra("target", id)
                        .putExtra("parent", tab == 1 ? category : null));
    }

    private void render() {
        header.removeAllViews();
        Design.pad(this, header, 14, 0, 0);
        rows.clear();
        if (tab == 0) rows.addAll(entries);
        if (tab == 1) {
            for (Category c : cats)
                if (category == null ? c.parentId == null : category.equals(c.parentId))
                    rows.add(c);
            rows.addAll(entries);
        }
        if (tab == 2) mineHeader();
        else if (rows.isEmpty()) emptyState();
        navigation();
        selectionUi();
        adapter.notifyDataSetChanged();
    }

    private void emptyState() {
        if (tab == 1 && category == null) {
            header.addView(
                    Design.emptyState(
                            this,
                            R.drawable.ill_empty_folder,
                            "还没有文件夹",
                            "用多级文件夹整理影片、剧集或常用合集",
                            "新建分类",
                            () -> manageFolder("create", null)));
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
        // Hero vault card with SVG emblem + live stats
        LinearLayout hero = Design.card(this);
        LinearLayout top = Design.row(this);
        ImageView emblem = new ImageView(this);
        emblem.setImageResource(R.drawable.ill_vault_badge);
        emblem.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56));
        ep.rightMargin = Ui.dp(this, 14);
        top.addView(emblem, ep);

        LinearLayout info = Design.column(this);
        LinearLayout titleRow = Design.row(this);
        TextView appTitle = Design.text(this, "磁力收藏夹", 18, R.color.c_text);
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
        stats.setBackgroundResource(R.drawable.bg_card_soft);
        Design.pad(this, stats, 8, 10, 10);
        addStatCell(stats, String.valueOf(total), "收藏");
        addStatCell(stats, String.valueOf(starred), "星标");
        addStatCell(stats, String.valueOf(cats.size()), "分类");
        addStatCell(stats, String.valueOf(tags.size()), "标签");
        hero.addView(stats);
        header.addView(hero);

        Design.gap(this, header, 14);
        header.addView(
                Design.action(
                        this,
                        "备份与恢复",
                        ThemePrefs.isDavConfigured(this)
                                ? "已连接坚果云 · 支持加密导出与自动备份"
                                : "导出加密 .mjbf 文件或连接坚果云",
                        R.drawable.ic_cloud,
                        () -> settings("backup")));
        Design.gap(this, header, 8);
        header.addView(
                Design.action(
                        this,
                        "回收站",
                        trash == 0 ? "空空如也" : trash + " 条收藏等待处理",
                        R.drawable.ic_trash,
                        () -> startActivity(new Intent(this, TrashActivity.class))));
        Design.gap(this, header, 8);
        header.addView(
                Design.action(
                        this,
                        "分类与标签",
                        cats.size() + " 个分类 · " + tags.size() + " 个标签",
                        R.drawable.ic_folder,
                        () -> manage(false)));
        Design.gap(this, header, 16);
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
                Design.action(this, "使用帮助", "快速上手导入、批量整理与链接打开", R.drawable.ic_info, this::help));
        Design.gap(this, header, 20);
    }

    private void addStatCell(LinearLayout parent, String value, String label) {
        LinearLayout cell = Design.column(this);
        cell.setGravity(Gravity.CENTER);
        TextView num = Design.text(this, value, 17, R.color.c_text);
        num.setTypeface(null, Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        cell.addView(num);
        Design.gap(this, cell, 2);
        TextView sub = Design.text(this, label, 11, R.color.c_text2);
        sub.setGravity(Gravity.CENTER);
        cell.addView(sub);
        parent.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void settings(String section) {
        Intent i = new Intent(this, SettingsActivity.class);
        i.putExtra("section", section);
        startActivity(i);
    }

    private void help() {
        new AlertDialog.Builder(this)
                .setTitle("更快用好收藏夹")
                .setMessage(
                        "添加收藏：点右下角「添加」粘贴，一条或多条自动识别。预览清单可直接设置各条的分类、标签、标题和备注。\n\n"
                                + "浏览与展开：列表默认紧凑显示编号与标签，轻点任意条目可展开完整标题、备注及「打开 / 复制 / 星标 / 详情」按钮。\n\n"
                                + "迁移整理：清单内搜索、勾选可分组设置分类和标签，也支持粘贴带标题、链接、分类、标签列的表格。\n\n"
                                + "整理收藏：进入「整理」管理分类和标签。建错的标签可改名、合并或删除。\n\n"
                                + "批量操作：在更多菜单点「多选」或长按收藏，可移动分类、添加标签、星标或移入回收站。\n\n"
                                + "打开链接：磁力与 ED2K 都先复制，再打开 MoePal 识别。\n\n"
                                + "备份与恢复：在「我的」中导出加密文件或连接坚果云。请妥善保存备份密码。\n\n"
                                + "本应用只保存链接与文字信息。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void addEntry() {
        openImport();
    }

    private void openImport() {
        Intent i = new Intent(this, ImportActivity.class);
        i.putExtra("category", tab == 1 ? category : null);
        startActivity(i);
    }

    private void openSearch() {
        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra(SearchActivity.EXTRA_CATEGORY_ID, tab == 1 ? category : null);
        i.putExtra(
                SearchActivity.EXTRA_CATEGORY_NAME,
                tab == 1 && category != null ? categoryName() : "全部收藏");
        startActivity(i);
    }

    private void openDetail(String id) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(DetailActivity.EXTRA_ENTRY_ID, id);
        startActivity(i);
    }

    private void chooseSort() {
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(
                        new String[] {"最新添加", "最近修改", "标题 A → Z"},
                        sort,
                        (d, w) -> {
                            sort = w;
                            getPreferences(0).edit().putInt("sort", sort).apply();
                            d.dismiss();
                            reload();
                        })
                .setNegativeButton("取消", null)
                .show();
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
            new AlertDialog.Builder(this)
                    .setTitle("移入回收站？")
                    .setMessage(ids.size() + " 条收藏将移入回收站，可在「我的 → 回收站」恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                            "移入回收站",
                            (d, w) ->
                                    load(
                                            () -> Repo.batchSoftDelete(this, ids),
                                            n -> batchDone("已移入回收站 " + n + " 条")))
                    .show();
    }

    private void batchDone(String message) {
        selecting = false;
        selected.clear();
        Ui.toast(this, message);
        reload();
    }

    private void toggleExpand(String id) {
        if (!expanded.remove(id)) expanded.add(id);
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
                        new AlertDialog.Builder(this)
                                .setTitle("移入回收站？")
                                .setMessage(e.displayTitle() + "\n可在「我的 → 回收站」恢复。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton(
                                        "移入回收站",
                                        (x, y) ->
                                                load(
                                                        () -> {
                                                            Repo.softDelete(this, e.id);
                                                            return true;
                                                        },
                                                        ok -> {
                                                            expanded.remove(e.id);
                                                            reload();
                                                            Ui.toast(this, "已移入回收站");
                                                        }))
                                .show();
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
            return 2;
        }

        public int getItemViewType(int p) {
            return rows.get(p) instanceof Entry ? 0 : 1;
        }

        public View getView(int pos, View recycled, ViewGroup parent) {
            if (rows.get(pos) instanceof Category) {
                Category c = (Category) rows.get(pos);
                View view = recycled;
                if (view == null || !(view.getTag() instanceof FolderHolder)) {
                    LinearLayout wrap = Design.column(MainActivity.this);
                    Design.pad(MainActivity.this, wrap, 14, 4, 4);
                    LinearLayout line = Design.row(MainActivity.this);
                    Design.pad(MainActivity.this, line, 12, 12, 12);
                    line.setMinimumHeight(Ui.dp(MainActivity.this, 68));
                    line.setBackgroundResource(R.drawable.bg_card);

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
                    line.addView(chev, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 18), Ui.dp(MainActivity.this, 18)));

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
                ((android.graphics.drawable.GradientDrawable) h.badge.getBackground())
                        .setColor(Design.softTint(c.color));
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
            View view = recycled;
            if (view == null || !(view.getTag() instanceof EntryCard.Holder)) {
                view = EntryCard.inflate(MainActivity.this, parent);
            }
            Entry e = (Entry) rows.get(pos);
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
}
