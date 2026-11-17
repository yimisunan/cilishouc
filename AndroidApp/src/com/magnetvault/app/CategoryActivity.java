package com.magnetvault.app;

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

/** Category & tag manager displaying one level at a time, delegating CRUD to CategoryEditor sheets. */
public class CategoryActivity extends AppActivity {
    private boolean tags, searching, loading;
    private String parentId, query = "", positionKey, pendingAction, pendingTarget;
    private LinearLayout root, toolbar, content, searchWrap;
    private FrameLayout modeSlot;
    private TextView summary, empty;
    private EditText search;
    private ImageButton clearSearch;
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

        modeSlot = new FrameLayout(this);
        content.addView(modeSlot);
        Design.gap(this, content, 8);

        searchWrap = Design.row(this);
        searchWrap.setBackgroundResource(R.drawable.bg_input_box);
        searchWrap.setMinimumHeight(Ui.dp(this, 44));
        Design.pad(this, searchWrap, 12, 2, 2);
        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setColorFilter(Design.color(this, R.color.c_text2));
        searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams sip =
                new LinearLayout.LayoutParams(Ui.dp(this, 17), Ui.dp(this, 17));
        sip.rightMargin = Ui.dp(this, 8);
        searchWrap.addView(searchIcon, sip);

        search = new EditText(this);
        search.setHint("搜索当前层级分类");
        search.setContentDescription("搜索");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(Design.color(this, R.color.c_text));
        search.setHintTextColor(Design.color(this, R.color.c_text2));
        search.setBackground(null);
        search.setText(query);
        searchWrap.addView(search, new LinearLayout.LayoutParams(0, -2, 1));

        clearSearch =
                Design.quietIcon(this, R.drawable.ic_close, "清空搜索", () -> search.setText(""));
        clearSearch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        Design.pad(this, clearSearch, 9, 9, 9);
        clearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        searchWrap.addView(clearSearch);
        Design.focusWrap(searchWrap, search);
        searchWrap.setVisibility(searching ? View.VISIBLE : View.GONE);
        content.addView(searchWrap);

        search.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    public void afterTextChanged(Editable s) {
                        query = s.toString();
                        clearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                        filter(false);
                    }
                });
        summary = Design.text(this, "正在读取…", 12, R.color.c_text2);
        Design.pad(this, summary, 4, 8, 4);
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
                            CategoryEditor.editCategory(this, null, pendingTarget, this::reload);
                        } else if (target != null) {
                            if (action.equals("edit"))
                                CategoryEditor.editCategory(this, target, null, this::reload);
                            if (action.equals("move"))
                                CategoryEditor.moveCategory(this, target, categories, this::reload);
                            if (action.equals("delete"))
                                CategoryEditor.deleteCategory(
                                        this, target, categories, this::reload);
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
                            if (tags) CategoryEditor.editTag(this, null, this::reload);
                            else CategoryEditor.editCategory(this, null, parentId, this::reload);
                        }));
        bar.addView(Design.quietIcon(this, R.drawable.ic_more, "管理选项", this::pageActions));
        toolbar.addView(bar);

        modeSlot.removeAllViews();
        modeSlot.addView(
                Design.segmented(
                        this,
                        new String[] {"分类", "标签"},
                        new int[] {R.drawable.ic_folder, R.drawable.ic_tag},
                        tags ? 1 : 0,
                        idx -> switchMode(idx == 1)));
        search.setHint(tags ? "搜索标签" : "搜索当前层级分类");
        searchWrap.setVisibility(searching ? View.VISIBLE : View.GONE);
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
                    new int[] {R.drawable.ic_add, R.drawable.ic_trash},
                    (d, w) -> {
                        if (w == 0) CategoryEditor.editTag(this, null, this::reload);
                        else CategoryEditor.cleanUnusedTags(this, this::reload);
                    });
        else {
            Category current = find(parentId);
            String[] labels =
                    current == null
                            ? new String[] {"新建分类"}
                            : new String[] {"查看当前分类收藏", "新建子分类", "管理当前分类"};
            int[] icons =
                    current == null
                            ? new int[] {R.drawable.ic_add}
                            : new int[] {R.drawable.ic_folder, R.drawable.ic_add, R.drawable.ic_edit};
            Ui.menuSheet(
                    this,
                    "分类管理",
                    labels,
                    icons,
                    (d, w) -> {
                        if (current == null) {
                            CategoryEditor.editCategory(this, null, null, this::reload);
                            return;
                        }
                        if (w == 0) showCollection(current.id);
                        if (w == 1)
                            CategoryEditor.editCategory(this, null, parentId, this::reload);
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
        return TextUtils.join(" / ", names);
    }

    private void categoryActions(Category c) {
        CategoryEditor.categoryActions(
                this, c, categories, () -> showCollection(c.id), this::reload);
    }

    private void tagActions(String name) {
        CategoryEditor.tagActions(
                this,
                name,
                tagNames,
                () -> {
                    Intent i = new Intent(this, SearchActivity.class);
                    i.putExtra("tag", name);
                    startActivity(i);
                },
                this::reload);
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
                Design.tactile(row);

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
