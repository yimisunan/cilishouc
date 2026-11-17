package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.*;

/** Category management displays one level at a time. Row taps browse; more buttons manage. */
public class CategoryActivity extends AppActivity {
    private boolean tags, searching, loading;
    private String parentId, query = "", positionKey, pendingAction, pendingTarget;
    private LinearLayout root, toolbar, content;
    private TextView summary, empty;
    private EditText search;
    private ListView list;
    private final List<Category> categories = new ArrayList<>();
    private final List<String> tagNames = new ArrayList<>();
    private Map<String, Integer> usage = new LinkedHashMap<>();
    private final List<Object> visible = new ArrayList<>();
    private final Map<String, Parcelable> positions = new LinkedHashMap<>();
    private final ManagerAdapter adapter = new ManagerAdapter();
    private int request;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        tags =
                state != null
                        ? state.getBoolean("tags")
                        : getIntent().getBooleanExtra("tags", false);
        parentId = state != null ? state.getString("parent") : getIntent().getStringExtra("parent");
        if (state != null) {
            query = state.getString("query", "");
            searching = state.getBoolean("searching");
            Bundle saved = state.getBundle("positions");
            if (saved != null)
                for (String k : saved.keySet()) positions.put(k, saved.getParcelable(k));
        } else {
            pendingAction = getIntent().getStringExtra("action");
            pendingTarget = getIntent().getStringExtra("target");
            getIntent().removeExtra("action");
        }
        root = Design.column(this);
        root.setBackgroundColor(Design.color(this, R.color.c_bg));
        toolbar = Design.column(this);
        root.addView(toolbar);
        content = Design.column(this);
        Design.pad(this, content, 14, 4, 0);
        LinearLayout tabs = Design.row(this);
        String[] names = {"分类", "标签"};
        for (int i = 0; i < names.length; i++) {
            final boolean value = i == 1;
            Button b = Design.chip(this, names[i], tags == value, () -> switchMode(value));
            b.setTag("mode:" + i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 8);
            tabs.addView(b, lp);
        }
        content.addView(tabs);
        Design.gap(this, content, 6);
        search = Design.input(this, "搜索当前层级分类", false);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setText(query);
        search.setVisibility(searching ? View.VISIBLE : View.GONE);
        content.addView(search);
        search.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    public void afterTextChanged(Editable s) {
                        query = s.toString();
                        filter(false);
                    }
                });
        summary = Design.text(this, "正在读取…", 12, R.color.c_text2);
        Design.pad(this, summary, 4, 6, 4);
        content.addView(summary);
        root.addView(content);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        Design.pad(this, list, 0, 2, 20);
        list.setAdapter(adapter);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = Design.text(this, "", 15, R.color.c_text2);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ill_empty_folder, 0, 0);
        empty.setCompoundDrawablePadding(Ui.dp(this, 12));
        Design.pad(this, empty, 24, 40, 24);
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -2));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        list.setOnItemClickListener(
                (p, v, pos, id) -> {
                    if (pos < 0 || pos >= visible.size() || loading) return;
                    Object item = visible.get(pos);
                    if (item instanceof Category) enter((Category) item);
                    else tagActions((String) item);
                });
        buildToolbar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onPause() {
        remember();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle s) {
        remember();
        s.putBoolean("tags", tags);
        s.putString("parent", parentId);
        s.putString("query", query);
        s.putBoolean("searching", searching);
        Bundle saved = new Bundle();
        for (Map.Entry<String, Parcelable> e : positions.entrySet())
            saved.putParcelable(e.getKey(), e.getValue());
        s.putBundle("positions", saved);
        super.onSaveInstanceState(s);
    }

    private String location() {
        return tags ? "tags" : "folder:" + parentId;
    }

    private void remember() {
        if (list != null && positionKey != null && !loading)
            positions.put(positionKey, list.onSaveInstanceState());
    }

    private Category find(String id) {
        if (id != null) for (Category c : categories) if (c.id.equals(id)) return c;
        return null;
    }

    private void reload() {
        remember();
        int token = ++request;
        loading = true;
        load(
                () -> new ManagerData(Repo.categories(this, true), Repo.tagUsage(this)),
                data -> {
                    if (token != request) return;
                    loading = false;
                    categories.clear();
                    categories.addAll(data.categories);
                    usage = data.usage;
                    tagNames.clear();
                    tagNames.addAll(usage.keySet());
                    Collections.sort(tagNames, String.CASE_INSENSITIVE_ORDER);
                    if (parentId != null && find(parentId) == null) parentId = null;
                    filter(true);
                    String action = pendingAction;
                    pendingAction = null;
                    if (action != null) {
                        Category target = find(pendingTarget);
                        if (action.equals("create")) {
                            if (pendingTarget != null && target == null) {
                                Ui.toast(this, "目标分类已不存在");
                                return;
                            }
                            edit(null, pendingTarget);
                        } else if (target != null) {
                            if (action.equals("edit")) edit(target, null);
                            if (action.equals("move")) move(target);
                            if (action.equals("delete")) delete(target);
                        } else Ui.toast(this, "分类已不存在");
                    }
                },
                error -> {
                    if (token == request) {
                        loading = false;
                        Ui.toast(this, "读取失败，请重试");
                    }
                });
    }

    private void buildToolbar() {
        toolbar.removeAllViews();
        LinearLayout bar = Design.row(this);
        Design.pad(this, bar, 8, 4, 4);
        bar.addView(
                Design.quietIcon(
                        this,
                        R.drawable.ic_back,
                        parentId != null && !tags ? "返回上级" : "返回",
                        this::onBackPressed));
        LinearLayout title = Design.column(this);
        Design.pad(this, title, 4, 0, 0);
        TextView heading =
                Design.text(
                        this,
                        tags
                                ? "标签管理"
                                : parentId == null
                                        ? "分类管理"
                                        : find(parentId) == null ? "分类管理" : find(parentId).name,
                        18,
                        R.color.c_text);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(heading);
        if (!tags && parentId != null && find(parentId) != null) {
            TextView path = Design.text(this, path(find(parentId)), 12, R.color.c_text2);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.addView(path);
        }
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(
                Design.quietIcon(
                        this, R.drawable.ic_search, tags ? "搜索标签" : "搜索当前层级", this::toggleSearch));
        bar.addView(
                Design.quietIcon(
                        this,
                        R.drawable.ic_add,
                        tags ? "新建标签" : "新建分类",
                        () -> {
                            if (tags) editTag(null);
                            else edit(null, parentId);
                        }));
        bar.addView(Design.quietIcon(this, R.drawable.ic_more, "管理选项", this::pageActions));
        toolbar.addView(bar);
        for (int i = 0; i < 2; i++) {
            Button b = content.findViewWithTag("mode:" + i);
            b.setSelected(tags == (i == 1));
            b.setTypeface(
                    null,
                    tags == (i == 1)
                            ? android.graphics.Typeface.BOLD
                            : android.graphics.Typeface.NORMAL);
            b.setTextColor(
                    Design.color(this, tags == (i == 1) ? R.color.c_accent : R.color.c_text2));
        }
        search.setHint(tags ? "搜索标签" : "搜索当前层级分类");
        search.setVisibility(searching ? View.VISIBLE : View.GONE);
    }

    private void toggleSearch() {
        searching = !searching;
        if (!searching) search.setText("");
        buildToolbar();
        if (searching) {
            search.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(
                            search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } else hideKeyboard();
    }

    private void hideKeyboard() {
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private void switchMode(boolean value) {
        if (tags == value) return;
        remember();
        tags = value;
        query = "";
        search.setText("");
        filter(true);
    }

    private void enter(Category c) {
        remember();
        hideKeyboard();
        query = "";
        search.setText("");
        parentId = c.id;
        filter(true);
    }

    private void filter(boolean restore) {
        visible.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (tags) {
            for (String name : tagNames)
                if (name.toLowerCase(Locale.ROOT).contains(q)) visible.add(name);
        } else
            for (Category c : categories)
                if ((parentId == null ? c.parentId == null : parentId.equals(c.parentId))
                        && c.name.toLowerCase(Locale.ROOT).contains(q)) visible.add(c);
        summary.setText(
                (q.isEmpty() ? "" : "搜索结果 · ") + visible.size() + " 个" + (tags ? "标签" : "分类"));
        empty.setText(!q.isEmpty() ? "没有匹配结果" : tags ? "还没有标签" : "当前层级还没有分类");
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        buildToolbar();
        positionKey = location();
        if (restore) {
            Parcelable position = positions.get(positionKey);
            if (position == null) list.setSelection(0);
            else
                list.post(
                        () -> {
                            if (!isDestroyed()) list.onRestoreInstanceState(position);
                        });
        }
    }

    private void pageActions() {
        if (tags)
            Ui.menuSheet(
                    this,
                    "标签管理",
                    new String[] {"新建标签", "清理未使用标签"},
                    null,
                    (d, w) -> {
                        if (w == 0) editTag(null);
                        else cleanUnused();
                    });
        else {
            Category current = find(parentId);
            String[] labels =
                    current == null
                            ? new String[] {"新建分类"}
                            : new String[] {"查看当前分类收藏", "新建子分类", "管理当前分类"};
            Ui.menuSheet(
                    this,
                    "分类管理",
                    labels,
                    null,
                    (d, w) -> {
                        if (current == null) {
                            edit(null, null);
                            return;
                        }
                        if (w == 0) showCollection(current.id);
                        if (w == 1) edit(null, parentId);
                        if (w == 2) categoryActions(current);
                    });
        }
    }

    private void showCollection(String id) {
        startActivity(new Intent(this, MainActivity.class).putExtra("category", id));
        finish();
    }

    @Override
    public void onBackPressed() {
        if (searching) {
            search.setText("");
            hideKeyboard();
            searching = false;
            buildToolbar();
            return;
        }
        if (!tags && parentId != null) {
            remember();
            Category current = find(parentId);
            parentId = current == null ? null : current.parentId;
            query = "";
            search.setText("");
            filter(true);
            return;
        }
        finish();
    }

    private static class ManagerData {
        List<Category> categories;
        Map<String, Integer> usage;

        ManagerData(List<Category> c, Map<String, Integer> u) {
            categories = c;
            usage = u;
        }
    }

    private String path(Category c) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Category cur = c;
        while (cur != null && seen.add(cur.id)) {
            names.add(0, cur.name);
            Category next = null;
            for (Category x : categories)
                if (x.id.equals(cur.parentId)) {
                    next = x;
                    break;
                }
            cur = next;
        }
        return android.text.TextUtils.join(" / ", names);
    }

    private void categoryActions(Category c) {
        Ui.menuSheet(
                this,
                path(c),
                new String[] {"查看收藏", "编辑名称与颜色", "新建子分类", "移动分类", "删除分类"},
                new int[] {
                    R.drawable.ic_folder,
                    R.drawable.ic_edit,
                    R.drawable.ic_add,
                    R.drawable.ic_move,
                    R.drawable.ic_trash
                },
                (d, w) -> {
                    if (w == 0) showCollection(c.id);
                    if (w == 1) edit(c, null);
                    if (w == 2) edit(null, c.id);
                    if (w == 3) move(c);
                    if (w == 4) delete(c);
                });
    }

    private void tagActions(String name) {
        Ui.menuSheet(
                this,
                name,
                new String[] {"查看相关收藏", "重命名标签", "合并到其他标签", "删除标签"},
                new int[] {
                    R.drawable.ic_search,
                    R.drawable.ic_edit,
                    R.drawable.ic_move,
                    R.drawable.ic_trash
                },
                (d, w) -> {
                    if (w == 0) {
                        Intent i = new Intent(this, SearchActivity.class);
                        i.putExtra("tag", name);
                        startActivity(i);
                    }
                    if (w == 1) editTag(name);
                    if (w == 2) merge(name);
                    if (w == 3) deleteTag(name);
                });
    }

    private void edit(Category c, String parent) {
        if (tags) {
            editTag(null);
            return;
        }
        LinearLayout box = Design.column(this);
        Design.pad(this, box, 24, 8, 16);
        Design.label(this, box, "分类名称");
        EditText input = Design.input(this, "例如：工作资料", false);
        box.addView(input);
        if (c != null) input.setText(c.name);
        Design.label(this, box, "颜色");
        Spinner colors = new Spinner(this);
        colors.setMinimumHeight(Ui.dp(this, 48));
        colors.setAdapter(
                new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_dropdown_item, Ui.COLOR_NAMES));
        if (c != null)
            for (int i = 0; i < Ui.COLORS.length; i++)
                if (Ui.COLORS[i] == c.color) colors.setSelection(i);
        box.addView(colors);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(c == null ? (parent == null ? "新建分类" : "新建子分类") : "编辑分类")
                        .setView(Design.scroll(this, box))
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", null)
                        .create();
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(-1)
                                .setOnClickListener(
                                        v -> {
                                            String name = input.getText().toString().trim();
                                            if (name.isEmpty()) {
                                                input.setError("请输入分类名称");
                                                input.requestFocus();
                                                return;
                                            }
                                            int color = Ui.COLORS[colors.getSelectedItemPosition()];
                                            dialog.getButton(-1).setEnabled(false);
                                            load(
                                                    () ->
                                                            c == null
                                                                    ? Repo.addCategory(
                                                                            this, name, color,
                                                                            parent)
                                                                    : Repo.updateCategory(
                                                                            this, c.id, name,
                                                                            color),
                                                    error -> {
                                                        dialog.getButton(-1).setEnabled(true);
                                                        if (error != null) {
                                                            input.setError(error);
                                                            return;
                                                        }
                                                        dialog.dismiss();
                                                        reload();
                                                        Ui.toast(this, "分类已保存");
                                                    },
                                                    failure -> {
                                                        dialog.getButton(-1).setEnabled(true);
                                                        input.setError("保存未完成，请重试");
                                                    });
                                        }));
        dialog.show();
    }

    private void editTag(String original) {
        LinearLayout box = Design.column(this);
        Design.pad(this, box, 24, 8, 16);
        Design.label(this, box, "标签名称");
        EditText input = Design.input(this, "例如：待看、高清", false);
        box.addView(input);
        if (original != null) input.setText(original);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(original == null ? "新建标签" : "重命名标签")
                        .setView(Design.scroll(this, box))
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", null)
                        .create();
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(-1)
                                .setOnClickListener(
                                        v -> {
                                            String name = input.getText().toString().trim();
                                            if (name.isEmpty()) {
                                                input.setError("请输入标签名称");
                                                return;
                                            }
                                            dialog.getButton(-1).setEnabled(false);
                                            load(
                                                    () ->
                                                            original == null
                                                                    ? Repo.addTag(this, name)
                                                                    : Repo.renameTag(
                                                                            this, original, name,
                                                                            false),
                                                    error -> {
                                                        dialog.getButton(-1).setEnabled(true);
                                                        if (error != null) {
                                                            input.setError(error);
                                                            return;
                                                        }
                                                        dialog.dismiss();
                                                        reload();
                                                        Ui.toast(this, "标签已保存");
                                                    },
                                                    failure -> {
                                                        dialog.getButton(-1).setEnabled(true);
                                                        input.setError("保存未完成，请重试");
                                                    });
                                        }));
        dialog.show();
    }

    private void move(Category source) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add(null);
        names.add("顶层分类");
        for (Category c : categories) {
            boolean descendant = false;
            Category cur = c;
            Set<String> seen = new HashSet<>();
            while (cur != null && seen.add(cur.id)) {
                if (cur.id.equals(source.id)) {
                    descendant = true;
                    break;
                }
                Category next = null;
                for (Category p : categories)
                    if (p.id.equals(cur.parentId)) {
                        next = p;
                        break;
                    }
                cur = next;
            }
            if (!descendant) {
                ids.add(c.id);
                names.add(path(c));
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("移动到")
                .setItems(
                        names.toArray(new String[0]),
                        (d, w) ->
                                load(
                                        () -> Repo.setCategoryParent(this, source.id, ids.get(w)),
                                        error -> {
                                            Ui.toast(this, error == null ? "已移动分类" : error);
                                            reload();
                                        }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void delete(Category c) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add(null);
        names.add("移到未分类");
        for (Category target : categories)
            if (!target.id.equals(c.id)) {
                ids.add(target.id);
                names.add("移到 " + path(target));
            }
        Spinner target = new Spinner(this);
        target.setMinimumHeight(Ui.dp(this, 48));
        target.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        LinearLayout box = Design.column(this);
        Design.pad(this, box, 24, 8, 16);
        box.addView(
                Design.text(
                        this,
                        c.count
                                + " 条收藏将移到下方位置。"
                                + (c.childCount > 0
                                        ? "\n" + c.childCount + " 个子分类会上移一级；重名时自动编号。"
                                        : ""),
                        15,
                        R.color.c_text2));
        Design.label(this, box, "收藏保留位置");
        box.addView(target);
        new AlertDialog.Builder(this)
                .setTitle("删除「" + c.name + "」？")
                .setView(Design.scroll(this, box))
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "删除分类",
                        (d, w) -> {
                            String id = ids.get(target.getSelectedItemPosition());
                            load(
                                    () -> {
                                        Repo.deleteCategory(this, c.id, id);
                                        return true;
                                    },
                                    ok -> {
                                        reload();
                                        Ui.toast(this, "分类已删除，收藏已保留");
                                    });
                        })
                .show();
    }

    private void deleteTag(String name) {
        load(
                () -> Repo.tagTotalUsage(this, name),
                count ->
                        new AlertDialog.Builder(this)
                                .setTitle("删除标签「" + name + "」？")
                                .setMessage("将从 " + count + " 条收藏中移除此标签（包括回收站）。收藏与其他标签会保留。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton(
                                        "删除标签",
                                        (d, w) ->
                                                load(
                                                        () -> {
                                                            Repo.deleteTag(this, name);
                                                            return true;
                                                        },
                                                        ok -> {
                                                            reload();
                                                            Ui.toast(this, "标签已删除");
                                                        }))
                                .show());
    }

    private void merge(String source) {
        List<String> targets = new ArrayList<>(tagNames);
        targets.remove(source);
        if (targets.isEmpty()) {
            Ui.toast(this, "先创建另一个标签，再选择合并");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("将「" + source + "」合并到")
                .setItems(
                        targets.toArray(new String[0]),
                        (d, w) ->
                                new AlertDialog.Builder(this)
                                        .setTitle("确认合并标签")
                                        .setMessage(
                                                "所有「"
                                                        + source
                                                        + "」关联会归入「"
                                                        + targets.get(w)
                                                        + "」，相同关联自动去重。")
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton(
                                                "合并",
                                                (x, y) ->
                                                        load(
                                                                () ->
                                                                        Repo.renameTag(
                                                                                this,
                                                                                source,
                                                                                targets.get(w),
                                                                                true),
                                                                error -> {
                                                                    Ui.toast(
                                                                            this,
                                                                            error == null
                                                                                    ? "标签已合并"
                                                                                    : error);
                                                                    reload();
                                                                }))
                                        .show())
                .setNegativeButton("取消", null)
                .show();
    }

    private void cleanUnused() {
        new AlertDialog.Builder(this)
                .setTitle("清理未使用标签？")
                .setMessage("只清理未关联任何收藏的标签。回收站中仍使用的标签会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "清理",
                        (d, w) ->
                                load(
                                        () -> Repo.deleteUnusedTags(this),
                                        n -> {
                                            Ui.toast(this, "已清理 " + n + " 个标签");
                                            reload();
                                        }))
                .show();
    }

    private class ManagerAdapter extends BaseAdapter {
        public int getCount() {
            return visible.size();
        }

        public Object getItem(int p) {
            return visible.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(int pos, View recycled, ViewGroup parent) {
            Holder h;
            if (recycled == null) {
                LinearLayout wrap = Design.column(CategoryActivity.this);
                Design.pad(CategoryActivity.this, wrap, 14, 4, 4);
                LinearLayout row = Design.row(CategoryActivity.this);
                Design.pad(CategoryActivity.this, row, 12, 12, 12);
                row.setMinimumHeight(Ui.dp(CategoryActivity.this, 66));
                row.setBackgroundResource(R.drawable.bg_card);

                FrameLayout badge =
                        Design.iconBadge(
                                CategoryActivity.this,
                                R.drawable.ic_folder,
                                Design.color(CategoryActivity.this, R.color.c_accent),
                                Design.color(CategoryActivity.this, R.color.c_accent_soft),
                                40,
                                20);
                LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams) badge.getLayoutParams();
                ip.rightMargin = Ui.dp(CategoryActivity.this, 12);
                row.addView(badge, ip);

                LinearLayout copy = Design.column(CategoryActivity.this);
                TextView name = Design.text(CategoryActivity.this, "", 15, R.color.c_text);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(name);
                Design.gap(CategoryActivity.this, copy, 3);
                TextView detail = Design.text(CategoryActivity.this, "", 12, R.color.c_text2);
                detail.setSingleLine(true);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(detail);
                row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                ImageButton more =
                        Design.quietIcon(CategoryActivity.this, R.drawable.ic_more, "管理", () -> {});
                row.addView(more);
                ImageView chev = new ImageView(CategoryActivity.this);
                chev.setImageResource(R.drawable.ic_chevron_right);
                chev.setColorFilter(Design.color(CategoryActivity.this, R.color.c_text2));
                chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(
                        chev,
                        new LinearLayout.LayoutParams(
                                Ui.dp(CategoryActivity.this, 18),
                                Ui.dp(CategoryActivity.this, 18)));
                wrap.addView(row);
                wrap.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                h = new Holder();
                h.name = name;
                h.detail = detail;
                h.badge = badge;
                h.icon = (ImageView) badge.getChildAt(0);
                h.more = more;
                h.chev = chev;
                h.row = row;
                wrap.setTag(h);
                recycled = wrap;
            } else h = (Holder) recycled.getTag();
            Object item = visible.get(pos);
            boolean tag = item instanceof String;
            String title = tag ? (String) item : ((Category) item).name;
            h.name.setText(title);
            h.icon.setImageResource(tag ? R.drawable.ic_tag : R.drawable.ic_folder);
            int tint =
                    tag
                            ? Design.color(CategoryActivity.this, R.color.c_accent2)
                            : ((Category) item).color;
            int bg =
                    tag
                            ? Design.color(CategoryActivity.this, R.color.c_accent2_soft)
                            : Design.softTint(((Category) item).color);
            h.icon.setColorFilter(tint);
            ((android.graphics.drawable.GradientDrawable) h.badge.getBackground()).setColor(bg);
            h.chev.setVisibility(tag ? View.GONE : View.VISIBLE);
            h.detail.setText(
                    tag
                            ? usage.get(title) + " 条收藏"
                            : ((Category) item).count
                                    + " 条收藏"
                                    + (((Category) item).childCount > 0
                                            ? " · " + ((Category) item).childCount + " 个子分类"
                                            : ""));
            h.row.setOnClickListener(
                    v -> {
                        if (tag) tagActions(title);
                        else enter((Category) item);
                    });
            h.more.setContentDescription("管理" + title);
            h.more.setOnClickListener(
                    v -> {
                        if (tag) tagActions(title);
                        else categoryActions((Category) item);
                    });
            return recycled;
        }
    }

    private static class Holder {
        TextView name, detail;
        FrameLayout badge;
        ImageView icon, chev;
        ImageButton more;
        View row;
    }
}
