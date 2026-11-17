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
    private final List<Object> rows = new ArrayList<>();
    private final List<Category> cats = new ArrayList<>();
    private List<Entry> entries = new ArrayList<>();
    private Map<String, Integer> tags = new LinkedHashMap<>();
    private int total, trash, unfiled, starred;
    private LinearLayout root, toolbar, header, nav, batch;
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
        list.setClipToPadding(false);
        Design.pad(this, list, 0, 0, 12);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        header = Design.column(this);
        list.addHeaderView(header, null, false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        batch = Design.row(this);
        Design.pad(this, batch, 12, 4, 4);
        batch.setVisibility(View.GONE);
        body.addView(batch);
        root.addView(centered, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = Design.row(this);
        Design.pad(this, nav, 8, 2, 2);
        nav.setBackgroundColor(Design.color(this, R.color.c_card));
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
                        else openDetail(e.id);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    int index = position - list.getHeaderViewsCount();
                    if (loading || index < 0 || index >= rows.size()) return false;
                    Object item = rows.get(index);
                    if (item instanceof Entry) {
                        selecting = true;
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

    private void consumeFolderIntent(Intent intent) {
        if (intent.hasExtra("category")) {
            category = intent.getStringExtra("category");
            tab = 1;
            filter = 0;
            selecting = false;
            selected.clear();
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
        return "文件夹";
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
        int[] icons = {R.drawable.ic_magnet, R.drawable.ic_folder, R.drawable.ic_shield};
        for (int i = 0; i < labels.length; i++) {
            final int value = i;
            LinearLayout item = Design.column(this);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(Ui.dp(this, 56));
            Design.pad(this, item, 8, 6, 6);
            item.setBackgroundResource(R.drawable.bg_flat);
            item.setSelected(tab == i);
            item.setFocusable(true);
            item.setContentDescription(labels[i] + (tab == i ? "，已选中" : ""));
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(Design.color(this, tab == i ? R.color.c_accent : R.color.c_text2));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 21), Ui.dp(this, 21)));
            Design.gap(this, item, 3);
            TextView text =
                    Design.text(this, labels[i], 12, tab == i ? R.color.c_accent : R.color.c_text2);
            text.setTypeface(null, tab == i ? Typeface.BOLD : Typeface.NORMAL);
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

    private void buildToolbar() {
        toolbar.removeAllViews();
        LinearLayout bar = Design.row(this);
        Design.pad(this, bar, 8, 4, 4);
        bar.setMinimumHeight(Ui.dp(this, 56));
        if (selecting) {
            bar.addView(
                    tool(
                            R.drawable.ic_close,
                            "退出多选",
                            () -> {
                                selecting = false;
                                selected.clear();
                                selectionUi();
                            }));
            TextView count = Design.text(this, "已选 " + selected.size() + " 条", 17, R.color.c_text);
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
            if (tab == 1 && category != null)
                bar.addView(tool(R.drawable.ic_back, "返回上级", this::goUpOneLevel));
            LinearLayout copy = Design.column(this);
            Design.pad(this, copy, 8, 2, 2);
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
                                ? starred + " 条星标"
                                : filter == 2 ? unfiled + " 条未分类" : total + " 条收藏";
            else if (tab == 1)
                info = category == null ? rootFolderCount() + " 个文件夹" : pathName(category);
            else info = "磁力收藏夹";
            TextView sub = Design.text(this, info, 12, R.color.c_text2);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            copy.addView(sub);
            bar.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            if (tab != 2) {
                bar.addView(tool(R.drawable.ic_search, "搜索收藏", this::openSearch));
                if (tab == 0) bar.addView(tool(R.drawable.ic_add, "添加收藏", this::addEntry));
                bar.addView(
                        tool(R.drawable.ic_more, tab == 1 ? "文件夹操作" : "收藏操作", this::pageActions));
            }
        }
        toolbar.addView(bar);
        if (tab == 0 && !selecting) {
            LinearLayout filters = Design.row(this);
            Design.pad(this, filters, 16, 0, 0);
            String[] names = {"全部", "星标", "未分类"};
            for (int i = 0; i < names.length; i++) {
                final int f = i;
                LinearLayout option = Design.column(this);
                option.setGravity(Gravity.CENTER);
                option.setMinimumHeight(Ui.dp(this, 48));
                option.setBackgroundResource(R.drawable.bg_flat);
                option.setFocusable(true);
                option.setSelected(filter == i);
                TextView label =
                        Design.text(
                                this,
                                names[i],
                                14,
                                filter == i ? R.color.c_accent : R.color.c_text2);
                label.setTypeface(null, filter == i ? Typeface.BOLD : Typeface.NORMAL);
                label.setGravity(Gravity.CENTER);
                label.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                label.setSingleLine(true);
                option.addView(label, new LinearLayout.LayoutParams(-1, -2));
                Design.gap(this, option, 6);
                View line = new View(this);
                line.setBackgroundColor(Design.color(this, R.color.c_accent));
                line.setVisibility(filter == i ? View.VISIBLE : View.INVISIBLE);
                option.addView(
                        line, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 2)));
                option.setOnClickListener(
                        v -> {
                            rememberPosition();
                            filter = f;
                            reload();
                        });
                filters.addView(option, new LinearLayout.LayoutParams(0, -2, 1));
            }
            toolbar.addView(filters);
        }
    }

    private int rootFolderCount() {
        int n = 0;
        for (Category c : cats) if (c.parentId == null) n++;
        return n;
    }

    private void pageActions() {
        if (tab == 0 || category != null) {
            List<String> labels = new ArrayList<>(Arrays.asList("添加收藏（单条 / 批量）", "排序方式", "多选"));
            if (tab == 1) {
                labels.add("新建子分类");
                labels.add("管理当前分类");
            } else labels.add("分类与标签");
            Ui.menuSheet(
                    this,
                    tab == 1 ? categoryName() : "收藏操作",
                    labels.toArray(new String[0]),
                    null,
                    (d, w) -> {
                        if (w == 0) addEntry();
                        if (w == 1) chooseSort();
                        if (w == 2) {
                            selecting = true;
                            selectionUi();
                        }
                        if (w == 3) {
                            if (tab == 1) manageFolder("create", category);
                            else manage(false);
                        }
                        if (w == 4) manageFolder("edit", category);
                    });
        } else
            Ui.menuSheet(
                    this,
                    "文件夹操作",
                    new String[] {"新建分类", "分类管理", "标签管理", "添加收藏（单条 / 批量）"},
                    null,
                    (d, w) -> {
                        if (w == 0) manageFolder("create", null);
                        if (w == 1) manage(false);
                        if (w == 2) manage(true);
                        if (w == 3) openImport();
                    });
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
        Design.pad(this, header, 16, 0, 0);
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
        Design.gap(this, header, 40);
        String label =
                tab == 1
                        ? (category == null ? "还没有文件夹" : "这个文件夹还没有收藏")
                        : filter == 1 ? "还没有星标收藏" : filter == 2 ? "没有未分类收藏" : "还没有收藏";
        TextView empty = Design.text(this, label, 16, R.color.c_text2);
        empty.setGravity(Gravity.CENTER);
        header.addView(empty);
        Design.gap(this, header, 12);
        if (tab == 1 && category == null)
            header.addView(Design.textAction(this, "新建分类", () -> manageFolder("create", null)));
        else if (tab == 0 && filter != 0)
            header.addView(
                    Design.textAction(
                            this,
                            "查看全部收藏",
                            () -> {
                                rememberPosition();
                                filter = 0;
                                reload();
                            }));
        else header.addView(Design.textAction(this, "添加收藏", this::addEntry));
    }

    private void toggle(String id) {
        if (!selected.remove(id)) selected.add(id);
        selectionUi();
    }

    private void mineHeader() {
        Design.gap(this, header, 12);
        header.addView(
                Design.action(
                        this,
                        "备份与恢复",
                        ThemePrefs.lastBackupAt(this) == 0
                                ? "尚未备份"
                                : "最近备份 " + Ui.time(ThemePrefs.lastBackupAt(this)),
                        R.drawable.ic_cloud,
                        () -> settings("backup")));
        Design.gap(this, header, 8);
        header.addView(
                Design.action(
                        this,
                        "回收站",
                        trash + " 条收藏",
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
        Design.gap(this, header, 20);
        header.addView(
                Design.action(
                        this,
                        "设置",
                        "主题与备份偏好",
                        R.drawable.ic_settings,
                        () -> settings("appearance")));
        Design.gap(this, header, 8);
        header.addView(Design.action(this, "使用帮助", null, R.drawable.ic_link, this::help));
        Design.gap(this, header, 20);
        header.addView(Design.text(this, "磁力收藏夹 2.2.1", 12, R.color.c_text2));
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
                        "添加收藏：点右上角加号粘贴，一条或多条自动识别。预览清单可直接设置各条的分类、标签、标题和备注。\n\n"
                                + "迁移整理：清单内搜索、勾选可分组设置分类和标签，也支持粘贴带标题、链接、分类、标签列的表格。\n\n"
                                + "整理收藏：进入「整理」管理分类和标签。建错的标签可改名、合并或删除。\n\n"
                                + "批量操作：在更多菜单点「多选」或长按收藏，可移动分类、添加标签或移入回收站。\n\n"
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
        buildToolbar();
        if (selecting) {
            String[] labels = {"移动", "加标签", "删除"};
            for (int i = 0; i < labels.length; i++) {
                final int action = i;
                Button b = Design.textAction(this, labels[i], () -> batchAction(action));
                b.setEnabled(!selected.isEmpty());
                b.setAlpha(selected.isEmpty() ? .45f : 1f);
                batch.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void batchAction(int which) {
        List<String> ids = new ArrayList<>(selected);
        if (ids.isEmpty()) return;
        if (which == 0) {
            List<String> names = new ArrayList<>();
            List<String> catIds = new ArrayList<>();
            names.add("未分类");
            catIds.add(null);
            for (Category c : cats) {
                names.add(Repo.categoryLabel(this, c.id));
                catIds.add(c.id);
            }
            new AlertDialog.Builder(this)
                    .setTitle("将 " + ids.size() + " 条收藏移动到")
                    .setItems(
                            names.toArray(new String[0]),
                            (d, w) ->
                                    load(
                                            () -> {
                                                Repo.moveCategory(this, ids, catIds.get(w));
                                                return true;
                                            },
                                            ok -> batchDone("收藏已移动")))
                    .setNegativeButton("取消", null)
                    .show();
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
        if (which == 2)
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

    private void entryActions(Entry e) {
        Ui.menuSheet(
                this,
                e.displayTitle(),
                new String[] {"打开链接", "复制链接", "编辑收藏", "多选", "移入回收站"},
                new int[] {
                    R.drawable.ic_play,
                    R.drawable.ic_copy,
                    R.drawable.ic_edit,
                    R.drawable.ic_check,
                    R.drawable.ic_trash
                },
                (d, w) -> {
                    if (w == 0) Ui.openMagnet(this, e.magnet);
                    if (w == 1) Ui.copy(this, e.magnet);
                    if (w == 2) {
                        Intent i = new Intent(this, EditActivity.class);
                        i.putExtra(EditActivity.EXTRA_ENTRY_ID, e.id);
                        startActivity(i);
                    }
                    if (w == 3) {
                        selecting = true;
                        selected.add(e.id);
                        selectionUi();
                    }
                    if (w == 4)
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
                    Design.pad(MainActivity.this, wrap, 16, 0, 0);
                    LinearLayout line = Design.row(MainActivity.this);
                    Design.pad(MainActivity.this, line, 8, 8, 0);
                    line.setMinimumHeight(Ui.dp(MainActivity.this, 64));
                    line.setBackgroundResource(R.drawable.bg_flat);
                    ImageView icon = new ImageView(MainActivity.this);
                    icon.setImageResource(R.drawable.ic_folder);
                    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams ip =
                            new LinearLayout.LayoutParams(
                                    Ui.dp(MainActivity.this, 22), Ui.dp(MainActivity.this, 22));
                    ip.rightMargin = Ui.dp(MainActivity.this, 12);
                    line.addView(icon, ip);
                    LinearLayout copy = Design.column(MainActivity.this);
                    TextView name = Design.text(MainActivity.this, "", 15, R.color.c_text);
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
                    wrap.addView(line);
                    View divider = new View(MainActivity.this);
                    divider.setBackgroundColor(Design.color(MainActivity.this, R.color.c_divider));
                    LinearLayout.LayoutParams dp =
                            new LinearLayout.LayoutParams(-1, Ui.dp(MainActivity.this, 1));
                    dp.leftMargin = Ui.dp(MainActivity.this, 42);
                    wrap.addView(divider, dp);
                    wrap.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    FolderHolder h = new FolderHolder();
                    h.name = name;
                    h.detail = detail;
                    h.icon = icon;
                    h.more = more;
                    h.line = line;
                    wrap.setTag(h);
                    view = wrap;
                }
                FolderHolder h = (FolderHolder) view.getTag();
                h.name.setText(c.name);
                h.icon.setColorFilter(c.color);
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
            EntryHolder h;
            if (recycled == null || !(recycled.getTag() instanceof EntryHolder)) {
                recycled = getLayoutInflater().inflate(R.layout.item_entry, parent, false);
                h = new EntryHolder();
                h.title = recycled.findViewById(R.id.item_title);
                h.sub = recycled.findViewById(R.id.item_sub);
                h.star = recycled.findViewById(R.id.item_star);
                h.more = recycled.findViewById(R.id.item_more);
                h.icon = recycled.findViewById(R.id.item_icon);
                h.card = recycled.findViewById(R.id.entry_card);
                recycled.setTag(h);
            } else h = (EntryHolder) recycled.getTag();
            Entry e = (Entry) rows.get(pos);
            h.title.setText(e.displayTitle());
            String meta = e.categoryId == null ? "未分类" : e.categoryName;
            if (!e.tags.isEmpty()) meta += " · " + e.tagText();
            else
                meta +=
                        " · "
                                + new java.text.SimpleDateFormat("MM月dd日", Locale.CHINA)
                                        .format(new Date(e.createdAt));
            h.sub.setText(meta);
            boolean picked = selected.contains(e.id);
            h.card.setBackgroundResource(picked ? R.drawable.row_selected : R.drawable.bg_flat);
            h.icon.setImageResource(
                    selecting
                            ? (picked ? R.drawable.ic_check : R.drawable.ic_magnet)
                            : R.drawable.ic_magnet);
            h.star.setText(e.starred ? "★" : "☆");
            h.star.setContentDescription((e.starred ? "取消星标 " : "添加星标 ") + e.displayTitle());
            h.star.setVisibility(selecting ? View.GONE : View.VISIBLE);
            h.more.setVisibility(selecting ? View.GONE : View.VISIBLE);
            h.star.setOnClickListener(
                    v ->
                            load(
                                    () -> {
                                        Repo.setStar(MainActivity.this, e.id, !e.starred);
                                        return true;
                                    },
                                    ok -> reload()));
            h.more.setContentDescription("更多操作 " + e.displayTitle());
            h.more.setOnClickListener(v -> entryActions(e));
            recycled.setSelected(picked);
            return recycled;
        }
    }

    private static class FolderHolder {
        TextView name, detail;
        ImageView icon;
        ImageButton more;
        View line;
    }

    private static class EntryHolder {
        TextView title, sub, star;
        ImageView icon;
        View more, card;
    }
}
