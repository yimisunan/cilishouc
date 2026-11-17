package com.magnetvault.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import java.util.*;

/** Hierarchical drill-down & searchable category picker bottom sheet, plus unified selector card UI. */
public final class CategoryPicker {
    public interface Listener {
        void onPicked(String categoryId);
    }

    public interface PathListener {
        void onPickedPath(String fullPath);
    }

    /** Clickable form card replacing native Spinners for category selection. */
    public static final class Card {
        public final LinearLayout root;
        final FrameLayout badge;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;

        Card(LinearLayout root, FrameLayout badge, ImageView icon, TextView title, TextView subtitle) {
            this.root = root;
            this.badge = badge;
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private static final class Node {
        final String key;
        String parentKey;
        final String name;
        final String fullPath;
        final int color;
        final int entryCount;
        final boolean draftOnly;
        int visibleChildCount;

        Node(
                String key,
                String parentKey,
                String name,
                String fullPath,
                int color,
                int entryCount,
                boolean draftOnly) {
            this.key = key;
            this.parentKey = parentKey;
            this.name = name;
            this.fullPath = fullPath;
            this.color = color;
            this.entryCount = entryCount;
            this.draftOnly = draftOnly;
        }
    }

    private static final class Row {
        static final int TYPE_ROOT = 0;
        static final int TYPE_CURRENT_SELF = 1;
        static final int TYPE_NODE = 2;

        final int type;
        final Node node;

        Row(int type, Node node) {
            this.type = type;
            this.node = node;
        }
    }

    private CategoryPicker() {}

    // ==================== Reusable Selector Card ====================

    public static Card selectorCard(Activity a, Runnable onClick) {
        LinearLayout box = Design.row(a);
        box.setBackgroundResource(R.drawable.bg_card);
        box.setMinimumHeight(Ui.dp(a, 58));
        Design.pad(a, box, 12, 11, 11);

        FrameLayout badge =
                Design.iconBadge(
                        a,
                        R.drawable.ic_cat,
                        Design.color(a, R.color.c_text2),
                        Design.color(a, R.color.c_card2),
                        38,
                        19);
        LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams) badge.getLayoutParams();
        ip.rightMargin = Ui.dp(a, 12);
        box.addView(badge, ip);

        LinearLayout copy = Design.column(a);
        TextView title = Design.text(a, "未分类", 15, R.color.c_text);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(title);

        Design.gap(a, copy, 2);
        TextView subtitle = Design.text(a, "", 12, R.color.c_text2);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        copy.addView(subtitle);
        box.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView arrow = new ImageView(a);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setColorFilter(Design.color(a, R.color.c_text2));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ap =
                new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18));
        ap.leftMargin = Ui.dp(a, 6);
        box.addView(arrow, ap);

        box.setFocusable(true);
        box.setClickable(true);
        if (onClick != null) box.setOnClickListener(v -> onClick.run());
        Design.tactile(box);
        return new Card(box, badge, (ImageView) badge.getChildAt(0), title, subtitle);
    }

    public static void bindSelectorCard(
            Activity a,
            Card card,
            List<Category> cats,
            String categoryId,
            String emptyTitle,
            String emptySubtitle) {
        if (card == null) return;
        Category picked = findCategory(cats, categoryId);
        if (picked == null) {
            card.title.setText(emptyTitle);
            card.subtitle.setText(emptySubtitle);
            card.subtitle.setVisibility(
                    TextUtils.isEmpty(emptySubtitle) ? View.GONE : View.VISIBLE);
            card.icon.setImageResource(R.drawable.ic_cat);
            card.icon.setColorFilter(Design.color(a, R.color.c_text2));
            ((GradientDrawable) card.badge.getBackground())
                    .setColor(Design.color(a, R.color.c_card2));
            card.root.setContentDescription("分类：" + emptyTitle);
            return;
        }
        String parentPath = parentPathOf(cats, picked);
        card.title.setText(picked.name);
        card.subtitle.setText(
                TextUtils.isEmpty(parentPath) ? "顶层分类" : "位于：" + parentPath);
        card.subtitle.setVisibility(View.VISIBLE);
        card.icon.setImageResource(R.drawable.ic_folder);
        card.icon.setColorFilter(picked.color);
        ((GradientDrawable) card.badge.getBackground()).setColor(Design.softTint(picked.color));
        card.root.setContentDescription(
                "分类：" + (TextUtils.isEmpty(parentPath) ? picked.name : parentPath + " / " + picked.name));
    }

    /** Convenience binder for one-off dialogs (loads current categories synchronously). */
    public static void bindSelectorCardById(
            Activity a, Card card, String categoryId, String emptyTitle, String emptySubtitle) {
        bindSelectorCard(
                a, card, Repo.categories(a, false), categoryId, emptyTitle, emptySubtitle);
    }

    // ==================== Bottom Sheet Entrypoints ====================

    public static Dialog show(
            Activity host,
            String title,
            String selectedCategoryId,
            String rootLabel,
            String rootSubtitle,
            Set<String> excludedIds,
            boolean allowCreate,
            Listener listener) {
        return showInternal(
                host,
                title,
                false,
                selectedCategoryId,
                rootLabel == null ? "未分类" : rootLabel,
                rootSubtitle,
                excludedIds,
                null,
                allowCreate,
                listener,
                null);
    }

    public static Dialog showPath(
            Activity host,
            String title,
            String selectedPath,
            Collection<String> extraPaths,
            PathListener listener) {
        String normalized = ImportTextParser.normalizePath(selectedPath);
        return showInternal(
                host,
                title,
                true,
                normalized.isEmpty() ? null : normalized,
                "未分类",
                "不归入任何文件夹",
                null,
                extraPaths,
                true,
                null,
                listener);
    }

    // ==================== Sheet Implementation ====================

    private static Dialog showInternal(
            final Activity host,
            final String title,
            final boolean pathMode,
            final String selectedKey,
            final String rootLabel,
            final String rootSubtitle,
            final Set<String> excludedIds,
            final Collection<String> extraPaths,
            final boolean allowCreate,
            final Listener dbListener,
            final PathListener pathListener) {

        final Map<String, Node> nodesByKey = new LinkedHashMap<>();
        final List<Node> allNodes = new ArrayList<>();
        buildTree(host, pathMode, excludedIds, extraPaths, nodesByKey, allNodes);

        // Canonicalize selectedKey in pathMode so case variations match cleanly
        String resolvedSelected = selectedKey;
        if (pathMode && resolvedSelected != null) {
            for (Node n : allNodes) {
                if (n.key.equalsIgnoreCase(resolvedSelected)) {
                    resolvedSelected = n.key;
                    break;
                }
            }
        } else if (!pathMode && resolvedSelected != null && !nodesByKey.containsKey(resolvedSelected)) {
            resolvedSelected = null;
        }
        final String effectiveSelected = resolvedSelected;

        // Track ancestors of currently selected node for visual indicator at root
        final Set<String> selectedAncestors = new HashSet<>();
        if (effectiveSelected != null && nodesByKey.containsKey(effectiveSelected)) {
            Node cur = nodesByKey.get(effectiveSelected);
            int guard = 0;
            while (cur != null && cur.parentKey != null && guard++ < 32) {
                selectedAncestors.add(cur.parentKey);
                cur = nodesByKey.get(cur.parentKey);
            }
        }

        final String[] currentParentKey = {null};
        final String[] currentQuery = {""};
        final List<Row> visibleRows = new ArrayList<>();

        final EditText[] searchBoxRef = {null};
        final Runnable[] refreshRef = {null};

        final Dialog dialog =
                new Dialog(host, R.style.SheetDialog) {
                    @Override
                    public void onBackPressed() {
                        if (currentQuery[0].length() > 0 && searchBoxRef[0] != null) {
                            searchBoxRef[0].setText("");
                            return;
                        }
                        if (currentParentKey[0] != null) {
                            Node p = nodesByKey.get(currentParentKey[0]);
                            currentParentKey[0] = p == null ? null : p.parentKey;
                            refreshRef[0].run();
                            return;
                        }
                        super.onBackPressed();
                    }
                };
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = Design.column(host);
        root.setBackgroundResource(R.drawable.bg_sheet);
        root.setPadding(Ui.dp(host, 14), Ui.dp(host, 10), Ui.dp(host, 14), Ui.dp(host, 14));
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        // Top drag handle
        View handle = new View(host);
        LinearLayout.LayoutParams hp =
                new LinearLayout.LayoutParams(Ui.dp(host, 38), Ui.dp(host, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = Ui.dp(host, 2);
        hp.bottomMargin = Ui.dp(host, 10);
        GradientDrawable hd = new GradientDrawable();
        hd.setColor(Design.color(host, R.color.c_line));
        hd.setCornerRadius(Ui.dp(host, 2));
        handle.setBackground(hd);
        root.addView(handle, hp);

        // Header bar
        LinearLayout header = Design.row(host);
        header.setMinimumHeight(Ui.dp(host, 42));

        ImageButton backBtn =
                Design.quietIcon(
                        host,
                        R.drawable.ic_back,
                        "返回上级分类",
                        () -> {
                            Node cur = nodesByKey.get(currentParentKey[0]);
                            currentParentKey[0] = cur == null ? null : cur.parentKey;
                            refreshRef[0].run();
                        });
        backBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(host, 40), Ui.dp(host, 40)));
        Design.pad(host, backBtn, 9, 9, 9);
        backBtn.setColorFilter(Design.color(host, R.color.c_text));
        backBtn.setVisibility(View.GONE);
        header.addView(backBtn);

        TextView headTitle = Design.text(host, title, 17, R.color.c_text);
        headTitle.setTypeface(null, Typeface.BOLD);
        headTitle.setSingleLine(true);
        headTitle.setEllipsize(TextUtils.TruncateAt.END);
        Design.pad(host, headTitle, 4, 0, 0);
        header.addView(headTitle, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout createBtn = null;
        if (allowCreate) {
            createBtn =
                    Design.pill(
                            host,
                            "新建",
                            R.drawable.ic_add,
                            true,
                            () ->
                                    showCreateDialog(
                                            host,
                                            pathMode,
                                            nodesByKey.get(currentParentKey[0]),
                                            "",
                                            pickedKey -> {
                                                dialog.dismiss();
                                                deliver(pathMode, dbListener, pathListener, pickedKey);
                                            }));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.rightMargin = Ui.dp(host, 4);
            header.addView(createBtn, cp);
        }

        ImageButton closeBtn =
                Design.quietIcon(host, R.drawable.ic_close, "关闭", dialog::dismiss);
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(host, 40), Ui.dp(host, 40)));
        Design.pad(host, closeBtn, 10, 10, 10);
        header.addView(closeBtn);
        root.addView(header);

        Design.gap(host, root, 8);

        // Search input pill
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

        EditText searchInput = new EditText(host);
        searchBoxRef[0] = searchInput;
        searchInput.setHint("搜索分类名称…");
        searchInput.setContentDescription("搜索分类名称");
        searchInput.setTextSize(14);
        searchInput.setTextColor(Design.color(host, R.color.c_text));
        searchInput.setHintTextColor(Design.color(host, R.color.c_text2));
        searchInput.setBackground(null);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchWrap.addView(searchInput, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton clearSearch =
                Design.quietIcon(
                        host,
                        R.drawable.ic_close,
                        "清空搜索",
                        () -> searchInput.setText(""));
        clearSearch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(host, 36), Ui.dp(host, 36)));
        Design.pad(host, clearSearch, 9, 9, 9);
        clearSearch.setVisibility(View.GONE);
        searchWrap.addView(clearSearch);
        Design.focusWrap(searchWrap, searchInput);
        root.addView(searchWrap);

        // Quick-jump banner when already having a nested selection
        final Node initialSelectedNode =
                effectiveSelected == null ? null : nodesByKey.get(effectiveSelected);
        LinearLayout jumpBanner = Design.row(host);
        jumpBanner.setBackgroundResource(R.drawable.bg_pill_on);
        jumpBanner.setMinimumHeight(Ui.dp(host, 38));
        Design.pad(host, jumpBanner, 12, 7, 7);
        LinearLayout.LayoutParams jbp = new LinearLayout.LayoutParams(-1, -2);
        jbp.topMargin = Ui.dp(host, 8);
        jumpBanner.setLayoutParams(jbp);
        if (initialSelectedNode != null && initialSelectedNode.parentKey != null) {
            ImageView jf = new ImageView(host);
            jf.setImageResource(R.drawable.ic_folder);
            jf.setColorFilter(initialSelectedNode.color);
            jf.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams jfp =
                    new LinearLayout.LayoutParams(Ui.dp(host, 15), Ui.dp(host, 15));
            jfp.rightMargin = Ui.dp(host, 6);
            jumpBanner.addView(jf, jfp);

            TextView jt =
                    Design.text(
                            host,
                            "当前：" + initialSelectedNode.fullPath,
                            12,
                            R.color.c_accent);
            jt.setTypeface(null, Typeface.BOLD);
            jt.setSingleLine(true);
            jt.setEllipsize(TextUtils.TruncateAt.START);
            jumpBanner.addView(jt, new LinearLayout.LayoutParams(0, -2, 1));

            TextView jAction = Design.text(host, "定位到所在层 ›", 12, R.color.c_accent);
            jAction.setTypeface(null, Typeface.BOLD);
            Design.hgap(host, jumpBanner, 8);
            jumpBanner.addView(jAction);
            jumpBanner.setFocusable(true);
            jumpBanner.setClickable(true);
            jumpBanner.setOnClickListener(
                    v -> {
                        currentParentKey[0] = initialSelectedNode.parentKey;
                        refreshRef[0].run();
                    });
            Design.tactile(jumpBanner);
        } else {
            jumpBanner.setVisibility(View.GONE);
        }
        root.addView(jumpBanner);

        // Breadcrumb bar (shown when inside a folder & not searching)
        HorizontalScrollView crumbScroll = new HorizontalScrollView(host);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams csp = new LinearLayout.LayoutParams(-1, -2);
        csp.topMargin = Ui.dp(host, 8);
        crumbScroll.setLayoutParams(csp);
        LinearLayout crumbRow = Design.row(host);
        crumbScroll.addView(crumbRow);
        crumbScroll.setVisibility(View.GONE);
        root.addView(crumbScroll);

        Design.gap(host, root, 8);

        // List container with keyboard-friendly height
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        int listH = Math.min(Ui.dp(host, 300), Math.max(Ui.dp(host, 190), screenH * 36 / 100));
        FrameLayout listWrap = new FrameLayout(host);
        listWrap.setLayoutParams(new LinearLayout.LayoutParams(-1, listH));

        ListView listView = new ListView(host);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setClipToPadding(false);
        listView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        listWrap.addView(listView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout emptyBox = Design.column(host);
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyBox.setVisibility(View.GONE);
        listWrap.addView(emptyBox, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(listWrap);

        final BaseAdapter adapter =
                new BaseAdapter() {
                    @Override
                    public int getCount() {
                        return visibleRows.size();
                    }

                    @Override
                    public Object getItem(int pos) {
                        return visibleRows.get(pos);
                    }

                    @Override
                    public long getItemId(int pos) {
                        return pos;
                    }

                    @Override
                    public View getView(int pos, View recycled, ViewGroup parent) {
                        RowHolder h;
                        if (recycled == null) {
                            LinearLayout wrap = Design.column(host);
                            Design.pad(host, wrap, 0, 3, 3);
                            LinearLayout card = Design.row(host);
                            card.setMinimumHeight(Ui.dp(host, 58));
                            Design.pad(host, card, 12, 10, 10);
                            Design.tactile(card);

                            FrameLayout badge =
                                    Design.iconBadge(
                                            host,
                                            R.drawable.ic_folder,
                                            Design.color(host, R.color.c_accent),
                                            Design.color(host, R.color.c_accent_soft),
                                            38,
                                            19);
                            LinearLayout.LayoutParams bp =
                                    (LinearLayout.LayoutParams) badge.getLayoutParams();
                            bp.rightMargin = Ui.dp(host, 12);
                            card.addView(badge, bp);

                            LinearLayout copy = Design.column(host);
                            TextView name = Design.text(host, "", 15, R.color.c_text);
                            name.setTypeface(null, Typeface.BOLD);
                            name.setSingleLine(true);
                            name.setEllipsize(TextUtils.TruncateAt.END);
                            copy.addView(name);
                            Design.gap(host, copy, 2);
                            TextView sub = Design.text(host, "", 12, R.color.c_text2);
                            sub.setSingleLine(true);
                            sub.setEllipsize(TextUtils.TruncateAt.END);
                            copy.addView(sub);
                            card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

                            TextView actionPill = Design.text(host, "选定", 12, R.color.c_accent);
                            actionPill.setTypeface(null, Typeface.BOLD);
                            actionPill.setGravity(Gravity.CENTER);
                            actionPill.setMinHeight(Ui.dp(host, 36));
                            Design.pad(host, actionPill, 12, 6, 6);
                            LinearLayout.LayoutParams app = new LinearLayout.LayoutParams(-2, -2);
                            app.leftMargin = Ui.dp(host, 8);
                            card.addView(actionPill, app);

                            ImageView check = new ImageView(host);
                            check.setImageResource(R.drawable.ic_check);
                            check.setColorFilter(Design.color(host, R.color.c_accent));
                            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                            LinearLayout.LayoutParams chp =
                                    new LinearLayout.LayoutParams(Ui.dp(host, 18), Ui.dp(host, 18));
                            chp.leftMargin = Ui.dp(host, 8);
                            card.addView(check, chp);

                            ImageView chev = new ImageView(host);
                            chev.setImageResource(R.drawable.ic_chevron_right);
                            chev.setColorFilter(Design.color(host, R.color.c_text2));
                            chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                            LinearLayout.LayoutParams cvp =
                                    new LinearLayout.LayoutParams(Ui.dp(host, 18), Ui.dp(host, 18));
                            cvp.leftMargin = Ui.dp(host, 6);
                            card.addView(chev, cvp);

                            wrap.addView(card);
                            wrap.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                            h =
                                    new RowHolder(
                                            card,
                                            badge,
                                            (ImageView) badge.getChildAt(0),
                                            name,
                                            sub,
                                            actionPill,
                                            check,
                                            chev);
                            wrap.setTag(h);
                            recycled = wrap;
                        } else {
                            h = (RowHolder) recycled.getTag();
                        }

                        Row row = visibleRows.get(pos);
                        boolean searching = currentQuery[0].trim().length() > 0;

                        if (row.type == Row.TYPE_ROOT) {
                            boolean isSel = effectiveSelected == null;
                            h.card.setBackgroundResource(
                                    isSel ? R.drawable.bg_card_selected : R.drawable.bg_card_soft);
                            h.icon.setImageResource(R.drawable.ic_cat);
                            h.icon.setColorFilter(
                                    Design.color(host, isSel ? R.color.c_accent : R.color.c_text2));
                            ((GradientDrawable) h.badge.getBackground())
                                    .setColor(
                                            Design.color(
                                                    host,
                                                    isSel ? R.color.c_accent_soft : R.color.c_card));
                            h.name.setText(rootLabel);
                            h.sub.setText(
                                    TextUtils.isEmpty(rootSubtitle)
                                            ? (isSel ? "当前已选" : "根层级选项")
                                            : rootSubtitle);
                            h.sub.setTextColor(
                                    Design.color(host, isSel ? R.color.c_accent : R.color.c_text2));
                            h.sub.setVisibility(View.VISIBLE);
                            h.actionPill.setOnClickListener(null);
                            h.actionPill.setClickable(false);
                            h.actionPill.setVisibility(View.GONE);
                            h.chev.setVisibility(View.GONE);
                            h.check.setVisibility(isSel ? View.VISIBLE : View.GONE);
                            h.card.setOnClickListener(
                                    v -> {
                                        dialog.dismiss();
                                        deliver(pathMode, dbListener, pathListener, null);
                                    });
                            return recycled;
                        }

                        if (row.type == Row.TYPE_CURRENT_SELF) {
                            Node cur = row.node;
                            boolean isSel = cur.key.equals(effectiveSelected);
                            h.card.setBackgroundResource(
                                    isSel ? R.drawable.bg_card_selected : R.drawable.bg_card_soft);
                            h.icon.setImageResource(R.drawable.ic_folder);
                            h.icon.setColorFilter(cur.color);
                            ((GradientDrawable) h.badge.getBackground())
                                    .setColor(Design.softTint(cur.color));
                            h.name.setText("选择「" + cur.name + "」");
                            h.sub.setText(
                                    cur.entryCount > 0
                                            ? "直接放入此文件夹（现有 " + cur.entryCount + " 条收藏）"
                                            : "直接放入此文件夹");
                            h.sub.setTextColor(
                                    Design.color(host, isSel ? R.color.c_accent : R.color.c_text2));
                            h.sub.setVisibility(View.VISIBLE);
                            h.actionPill.setVisibility(View.VISIBLE);
                            h.actionPill.setText(isSel ? "已选" : "选定");
                            h.actionPill.setBackgroundResource(R.drawable.bg_pill_on);
                            h.actionPill.setTextColor(Design.color(host, R.color.c_accent));
                            h.actionPill.setOnClickListener(
                                    v -> {
                                        dialog.dismiss();
                                        deliver(pathMode, dbListener, pathListener, cur.key);
                                    });
                            h.check.setVisibility(View.GONE);
                            h.chev.setVisibility(View.GONE);
                            h.card.setOnClickListener(
                                    v -> {
                                        dialog.dismiss();
                                        deliver(pathMode, dbListener, pathListener, cur.key);
                                    });
                            return recycled;
                        }

                        // TYPE_NODE
                        final Node node = row.node;
                        final boolean isSel = node.key.equals(effectiveSelected);
                        final boolean hasKids = node.visibleChildCount > 0;
                        final boolean containsSel = selectedAncestors.contains(node.key);

                        h.card.setBackgroundResource(
                                isSel ? R.drawable.bg_card_selected : R.drawable.bg_card_soft);
                        h.icon.setImageResource(R.drawable.ic_folder);
                        h.icon.setColorFilter(node.color);
                        ((GradientDrawable) h.badge.getBackground())
                                .setColor(Design.softTint(node.color));
                        h.name.setText(node.name);

                        String subText;
                        if (searching) {
                            subText =
                                    "路径："
                                            + node.fullPath
                                            + (hasKids ? " · " + node.visibleChildCount + " 个子分类" : "");
                        } else {
                            List<String> parts = new ArrayList<>();
                            if (hasKids) parts.add(node.visibleChildCount + " 个子分类");
                            if (node.entryCount > 0) parts.add(node.entryCount + " 条收藏");
                            else if (!hasKids) parts.add(node.draftOnly ? "待导入新建" : "空文件夹");
                            if (isSel) parts.add("当前已选");
                            else if (containsSel) parts.add("含当前已选");
                            subText = TextUtils.join(" · ", parts);
                        }
                        h.sub.setText(subText);
                        h.sub.setTextColor(
                                Design.color(
                                        host,
                                        isSel || (!searching && containsSel)
                                                ? R.color.c_accent
                                                : R.color.c_text2));
                        h.sub.setVisibility(View.VISIBLE);

                        if (searching) {
                            h.check.setVisibility(!hasKids && isSel ? View.VISIBLE : View.GONE);
                            h.chev.setVisibility(View.GONE);
                            if (hasKids) {
                                h.actionPill.setVisibility(View.VISIBLE);
                                h.actionPill.setText("进入 ›");
                                h.actionPill.setBackgroundResource(R.drawable.bg_pill);
                                h.actionPill.setTextColor(Design.color(host, R.color.c_text2));
                                h.actionPill.setOnClickListener(
                                        v -> {
                                            currentParentKey[0] = node.key;
                                            searchInput.setText("");
                                        });
                            } else {
                                h.actionPill.setOnClickListener(null);
                                h.actionPill.setClickable(false);
                                h.actionPill.setVisibility(View.GONE);
                            }
                            h.card.setOnClickListener(
                                    v -> {
                                        dialog.dismiss();
                                        deliver(pathMode, dbListener, pathListener, node.key);
                                    });
                        } else if (hasKids) {
                            // Browsing mode with children: tap card drills in, tap pill picks folder itself
                            h.check.setVisibility(View.GONE);
                            h.chev.setVisibility(View.VISIBLE);
                            h.actionPill.setVisibility(View.VISIBLE);
                            h.actionPill.setText(isSel ? "已选" : "选定");
                            h.actionPill.setBackgroundResource(
                                    isSel ? R.drawable.bg_pill_on : R.drawable.bg_pill);
                            h.actionPill.setTextColor(
                                    Design.color(
                                            host, isSel ? R.color.c_accent : R.color.c_text2));
                            h.actionPill.setOnClickListener(
                                    v -> {
                                        dialog.dismiss();
                                        deliver(pathMode, dbListener, pathListener, node.key);
                                    });
                            h.card.setOnClickListener(
                                    v -> {
                                        currentParentKey[0] = node.key;
                                        refreshRef[0].run();
                                    });
                        } else {
                            // Browsing mode leaf folder: 1-tap picks directly
                            h.actionPill.setOnClickListener(null);
                            h.actionPill.setClickable(false);
                            h.actionPill.setVisibility(View.GONE);
                            h.chev.setVisibility(View.GONE);
                            h.check.setVisibility(isSel ? View.VISIBLE : View.GONE);
                            h.card.setOnClickListener(
                                    v -> {
                                        dialog.dismiss();
                                        deliver(pathMode, dbListener, pathListener, node.key);
                                    });
                        }
                        return recycled;
                    }
                };
        listView.setAdapter(adapter);

        refreshRef[0] =
                () -> {
                    visibleRows.clear();
                    String q = currentQuery[0].trim();
                    boolean searching = !q.isEmpty();

                    clearSearch.setVisibility(currentQuery[0].length() > 0 ? View.VISIBLE : View.GONE);
                    backBtn.setVisibility(
                            !searching && currentParentKey[0] != null ? View.VISIBLE : View.GONE);
                    jumpBanner.setVisibility(
                            !searching
                                            && currentParentKey[0] == null
                                            && initialSelectedNode != null
                                            && initialSelectedNode.parentKey != null
                                    ? View.VISIBLE
                                    : View.GONE);

                    // Update breadcrumb bar
                    if (!searching && currentParentKey[0] != null) {
                        crumbScroll.setVisibility(View.VISIBLE);
                        crumbRow.removeAllViews();
                        List<Node> trail = new ArrayList<>();
                        Node cur = nodesByKey.get(currentParentKey[0]);
                        int guard = 0;
                        while (cur != null && guard++ < 32) {
                            trail.add(0, cur);
                            cur = cur.parentKey == null ? null : nodesByKey.get(cur.parentKey);
                        }
                        LinearLayout rootCrumb =
                                Design.pill(
                                        host,
                                        "全部",
                                        0,
                                        false,
                                        () -> {
                                            currentParentKey[0] = null;
                                            refreshRef[0].run();
                                        });
                        crumbRow.addView(rootCrumb);
                        for (int i = 0; i < trail.size(); i++) {
                            final Node item = trail.get(i);
                            final boolean isLast = i == trail.size() - 1;
                            TextView sep = Design.text(host, "›", 14, R.color.c_text2);
                            Design.pad(host, sep, 6, 0, 0);
                            crumbRow.addView(sep);
                            crumbRow.addView(
                                    Design.pill(
                                            host,
                                            item.name,
                                            0,
                                            isLast,
                                            isLast
                                                    ? () -> {}
                                                    : () -> {
                                                        currentParentKey[0] = item.key;
                                                        refreshRef[0].run();
                                                    }));
                        }
                        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
                    } else {
                        crumbScroll.setVisibility(View.GONE);
                    }

                    if (searching) {
                        String lower = q.toLowerCase(Locale.ROOT);
                        String compactQ = lower.replaceAll("\\s*/\\s*", "/");
                        List<Node> starts = new ArrayList<>();
                        List<Node> nameMatch = new ArrayList<>();
                        List<Node> pathMatch = new ArrayList<>();
                        for (Node n : allNodes) {
                            String nl = n.name.toLowerCase(Locale.ROOT);
                            String pl = n.fullPath.toLowerCase(Locale.ROOT);
                            String plCompact = pl.replace(" / ", "/");
                            if (nl.startsWith(lower)) starts.add(n);
                            else if (nl.contains(lower)) nameMatch.add(n);
                            else if (pl.contains(lower) || plCompact.contains(compactQ))
                                pathMatch.add(n);
                        }
                        for (Node n : starts) visibleRows.add(new Row(Row.TYPE_NODE, n));
                        for (Node n : nameMatch) visibleRows.add(new Row(Row.TYPE_NODE, n));
                        for (Node n : pathMatch) visibleRows.add(new Row(Row.TYPE_NODE, n));
                    } else if (currentParentKey[0] == null) {
                        visibleRows.add(new Row(Row.TYPE_ROOT, null));
                        for (Node n : allNodes) {
                            if (n.parentKey == null) visibleRows.add(new Row(Row.TYPE_NODE, n));
                        }
                    } else {
                        Node parentNode = nodesByKey.get(currentParentKey[0]);
                        if (parentNode != null) {
                            visibleRows.add(new Row(Row.TYPE_CURRENT_SELF, parentNode));
                        }
                        for (Node n : allNodes) {
                            if (currentParentKey[0].equals(n.parentKey)) {
                                visibleRows.add(new Row(Row.TYPE_NODE, n));
                            }
                        }
                    }

                    // Empty state handling (compact so it fits comfortably even with IME open)
                    if (visibleRows.isEmpty()) {
                        listView.setVisibility(View.GONE);
                        emptyBox.setVisibility(View.VISIBLE);
                        emptyBox.removeAllViews();
                        Design.pad(host, emptyBox, 20, 14, 14);
                        emptyBox.addView(
                                Design.iconBadge(
                                        host,
                                        R.drawable.ic_search,
                                        Design.color(host, R.color.c_text2),
                                        Design.color(host, R.color.c_card2),
                                        44,
                                        20));
                        Design.gap(host, emptyBox, 10);
                        String msg =
                                searching
                                        ? "没有找到包含「" + q + "」的分类"
                                        : "当前层级还没有子分类";
                        TextView emptyTitle = Design.text(host, msg, 14, R.color.c_text);
                        emptyTitle.setTypeface(null, Typeface.BOLD);
                        emptyTitle.setGravity(Gravity.CENTER);
                        emptyBox.addView(emptyTitle);
                        if (allowCreate) {
                            Design.gap(host, emptyBox, 6);
                            TextView emptySub =
                                    Design.text(host, "可以直接新建并选中", 12, R.color.c_text2);
                            emptySub.setGravity(Gravity.CENTER);
                            emptyBox.addView(emptySub);
                            Design.gap(host, emptyBox, 12);
                            emptyBox.addView(
                                    Design.pill(
                                            host,
                                            searching ? "新建「" + q + "」" : "新建分类",
                                            R.drawable.ic_add,
                                            true,
                                            () ->
                                                    showCreateDialog(
                                                            host,
                                                            pathMode,
                                                            nodesByKey.get(currentParentKey[0]),
                                                            searching ? q : "",
                                                            pickedKey -> {
                                                                dialog.dismiss();
                                                                deliver(
                                                                        pathMode,
                                                                        dbListener,
                                                                        pathListener,
                                                                        pickedKey);
                                                            })),
                                    new LinearLayout.LayoutParams(-2, -2));
                        }
                    } else {
                        emptyBox.setVisibility(View.GONE);
                        listView.setVisibility(View.VISIBLE);
                    }
                    adapter.notifyDataSetChanged();
                    listView.setSelection(0);
                };

        searchInput.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        currentQuery[0] = s == null ? "" : s.toString();
                        refreshRef[0].run();
                    }
                });

        refreshRef[0].run();
        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(
                    Math.min(
                            host.getResources().getDisplayMetrics().widthPixels,
                            Ui.dp(host, 640)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            Design.allowImeResize(w, false);
            if (!android.animation.ValueAnimator.areAnimatorsEnabled())
                w.setWindowAnimations(0);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (w != null) {
            w.setLayout(
                    Math.min(
                            host.getResources().getDisplayMetrics().widthPixels,
                            Ui.dp(host, 640)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static void deliver(
            boolean pathMode, Listener dbListener, PathListener pathListener, String key) {
        if (pathMode) {
            if (pathListener != null) pathListener.onPickedPath(key == null ? "" : key);
        } else {
            if (dbListener != null) dbListener.onPicked(key);
        }
    }

    // ==================== Inline Category Creation ====================

    private static void showCreateDialog(
            final Activity host,
            final boolean pathMode,
            final Node currentParent,
            final String prefillName,
            final Listener onCreatedKey) {
        LinearLayout root = Ui.sheetContainer(host);

        String title;
        if (currentParent == null) {
            title = pathMode ? "新建分类路径" : "新建分类";
        } else {
            title = "在「" + currentParent.name + "」下新建子分类";
        }
        TextView head = Design.text(host, title, 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(head);

        if (pathMode) {
            Design.gap(host, root, 6);
            TextView tip =
                    Design.text(
                            host,
                            currentParent == null
                                    ? "输入分类名，或用 / 输入多级路径（例如：电影 / 科幻）"
                                    : "上级目录：" + currentParent.fullPath + "（支持用 / 再建多级）",
                            13,
                            R.color.c_text2);
            tip.setBackgroundResource(R.drawable.bg_card_soft);
            Design.pad(host, tip, 12, 10, 10);
            root.addView(tip);
        }

        Design.label(host, root, "分类名称");
        EditText input = Design.input(host, pathMode ? "例如：4K 或 科幻 / 4K" : "例如：电影、剧集", false);
        if (!TextUtils.isEmpty(prefillName)) {
            input.setText(prefillName);
            input.setSelection(prefillName.length());
        }
        root.addView(input);

        final TextView preview = pathMode ? Design.text(host, "", 12, R.color.c_accent) : null;
        if (pathMode) {
            Design.gap(host, root, 8);
            root.addView(preview);
            Runnable updatePreview =
                    () -> {
                        String p = buildDraftPath(currentParent, input.getText().toString());
                        preview.setText(p.isEmpty() ? "" : "将归类到：" + p);
                    };
            updatePreview.run();
            input.addTextChangedListener(
                    new TextWatcher() {
                        public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                        public void onTextChanged(CharSequence s, int a, int b, int c) {}

                        public void afterTextChanged(Editable s) {
                            updatePreview.run();
                        }
                    });
        }

        final int[] pickedColor = {
            currentParent != null ? currentParent.color : Ui.COLORS[0]
        };
        if (!pathMode) {
            Design.label(host, root, "文件夹颜色");
            root.addView(Design.colorSwatches(host, pickedColor[0], c -> pickedColor[0] = c));
        }

        TextView errView = Design.text(host, "", 12, R.color.c_danger);
        errView.setVisibility(View.GONE);
        Design.pad(host, errView, 4, 8, 0);
        root.addView(errView);

        Design.gap(host, root, 16);
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

        final Button[] submitRef = {null};
        submitRef[0] =
                Design.button(
                        host,
                        pathMode ? "确定" : "创建并选中",
                        R.drawable.ic_check,
                        true,
                        () -> {
                            errView.setVisibility(View.GONE);
                            if (pathMode) {
                                String fullPath =
                                        buildDraftPath(currentParent, input.getText().toString());
                                if (fullPath.isEmpty()) {
                                    errView.setText("请输入分类名称");
                                    errView.setVisibility(View.VISIBLE);
                                    input.requestFocus();
                                    return;
                                }
                                Entry test = new Entry();
                                test.categoryName = fullPath;
                                String err = ImportDraft.validate(test);
                                if (err != null) {
                                    errView.setText(err);
                                    errView.setVisibility(View.VISIBLE);
                                    input.requestFocus();
                                    return;
                                }
                                if (ref[0] != null) ref[0].dismiss();
                                onCreatedKey.onPicked(fullPath);
                                return;
                            }
                            final String name = input.getText().toString().trim();
                            if (name.isEmpty()) {
                                errView.setText("请输入分类名称");
                                errView.setVisibility(View.VISIBLE);
                                input.requestFocus();
                                return;
                            }
                            final String parentId =
                                    currentParent == null ? null : currentParent.key;
                            submitRef[0].setEnabled(false);
                            AppActivity.DATA.execute(
                                    () -> {
                                        String error =
                                                Repo.addCategory(
                                                        host.getApplicationContext(),
                                                        name,
                                                        pickedColor[0],
                                                        parentId);
                                        String createdId = null;
                                        if (error == null) {
                                            for (Category c :
                                                    Repo.categories(
                                                            host.getApplicationContext(), false)) {
                                                boolean sameParent =
                                                        parentId == null
                                                                ? c.parentId == null
                                                                : parentId.equals(c.parentId);
                                                if (sameParent && name.equalsIgnoreCase(c.name)) {
                                                    createdId = c.id;
                                                    break;
                                                }
                                            }
                                        }
                                        final String errOut = error;
                                        final String idOut = createdId;
                                        AppActivity.MAIN.post(
                                                () -> {
                                                    if (host.isFinishing() || host.isDestroyed())
                                                        return;
                                                    submitRef[0].setEnabled(true);
                                                    if (errOut != null) {
                                                        errView.setText(errOut);
                                                        errView.setVisibility(View.VISIBLE);
                                                        return;
                                                    }
                                                    if (ref[0] != null) ref[0].dismiss();
                                                    Ui.toast(host, "已创建「" + name + "」");
                                                    onCreatedKey.onPicked(idOut);
                                                });
                                    });
                        });
        actions.addView(submitRef[0], new LinearLayout.LayoutParams(0, -2, 1.4f));
        root.addView(actions);

        ref[0] = Ui.showSheet(host, root, true);
        input.requestFocus();
    }

    private static String buildDraftPath(Node currentParent, String rawInput) {
        String seg = ImportTextParser.normalizePath(rawInput);
        if (seg.isEmpty()) return "";
        if (currentParent == null) return seg;
        return ImportTextParser.normalizePath(currentParent.fullPath + " / " + seg);
    }

    // ==================== Tree Construction ====================

    private static void buildTree(
            Activity host,
            boolean pathMode,
            Set<String> excludedIds,
            Collection<String> extraPaths,
            Map<String, Node> nodesByKey,
            List<Node> allNodes) {
        List<Category> cats = Repo.categories(host, true);
        Map<String, Category> catById = new HashMap<>();
        for (Category c : cats) catById.put(c.id, c);

        if (!pathMode) {
            for (Category c : cats) {
                if (excludedIds != null && excludedIds.contains(c.id)) continue;
                // Find nearest non-excluded ancestor so promoted children (e.g. during delete) stay reachable
                String parentKey = c.parentId;
                Set<String> guardSeen = new HashSet<>();
                guardSeen.add(c.id);
                while (parentKey != null
                        && guardSeen.add(parentKey)
                        && ((excludedIds != null && excludedIds.contains(parentKey))
                                || !catById.containsKey(parentKey))) {
                    Category anc = catById.get(parentKey);
                    parentKey = anc == null ? null : anc.parentId;
                }
                if (parentKey != null && excludedIds != null && excludedIds.contains(parentKey)) {
                    parentKey = null;
                }
                String fullPath = fullPathOf(catById, c);
                Node node = new Node(c.id, parentKey, c.name, fullPath, c.color, c.count, false);
                nodesByKey.put(node.key, node);
                allNodes.add(node);
            }
        } else {
            Map<String, Node> lowerIndex = new HashMap<>();
            Map<String, String> idToPath = new HashMap<>();
            for (Category c : cats) {
                String path = fullPathOf(catById, c);
                idToPath.put(c.id, path);
            }
            for (Category c : cats) {
                String path = idToPath.get(c.id);
                String parentPath = c.parentId == null ? null : idToPath.get(c.parentId);
                Node node = new Node(path, parentPath, c.name, path, c.color, c.count, false);
                nodesByKey.put(node.key, node);
                lowerIndex.put(path.toLowerCase(Locale.ROOT), node);
                allNodes.add(node);
            }
            if (extraPaths != null) {
                int defaultColor = Design.color(host, R.color.c_accent);
                for (String raw : extraPaths) {
                    String norm = ImportTextParser.normalizePath(raw);
                    if (norm.isEmpty()) continue;
                    String[] parts = norm.split(" / ");
                    String curParentKey = null;
                    StringBuilder acc = new StringBuilder();
                    for (int i = 0; i < parts.length; i++) {
                        if (i > 0) acc.append(" / ");
                        acc.append(parts[i]);
                        String candidate = acc.toString();
                        Node existing = lowerIndex.get(candidate.toLowerCase(Locale.ROOT));
                        if (existing != null) {
                            curParentKey = existing.key;
                        } else {
                            String full =
                                    curParentKey == null
                                            ? parts[i]
                                            : curParentKey + " / " + parts[i];
                            Node virt =
                                    new Node(
                                            full,
                                            curParentKey,
                                            parts[i],
                                            full,
                                            defaultColor,
                                            0,
                                            true);
                            nodesByKey.put(virt.key, virt);
                            lowerIndex.put(full.toLowerCase(Locale.ROOT), virt);
                            allNodes.add(virt);
                            curParentKey = virt.key;
                        }
                    }
                }
            }
        }

        // Compute visible child counts cleanly after exclusions & merges
        for (Node n : allNodes) n.visibleChildCount = 0;
        for (Node n : allNodes) {
            if (n.parentKey != null) {
                Node p = nodesByKey.get(n.parentKey);
                if (p != null) p.visibleChildCount++;
            }
        }
    }

    private static Category findCategory(List<Category> cats, String id) {
        if (cats == null || id == null) return null;
        for (Category c : cats) if (id.equals(c.id)) return c;
        return null;
    }

    private static String parentPathOf(List<Category> cats, Category c) {
        if (c == null || c.parentId == null) return "";
        Map<String, Category> byId = new HashMap<>();
        for (Category item : cats) byId.put(item.id, item);
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(c.id);
        Category cur = byId.get(c.parentId);
        while (cur != null && seen.add(cur.id)) {
            names.add(0, cur.name);
            cur = cur.parentId == null ? null : byId.get(cur.parentId);
        }
        return TextUtils.join(" / ", names);
    }

    private static String fullPathOf(Map<String, Category> byId, Category c) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Category cur = c;
        while (cur != null && seen.add(cur.id)) {
            names.add(0, cur.name);
            cur = cur.parentId == null ? null : byId.get(cur.parentId);
        }
        return TextUtils.join(" / ", names);
    }

    private static final class RowHolder {
        final LinearLayout card;
        final FrameLayout badge;
        final ImageView icon;
        final TextView name;
        final TextView sub;
        final TextView actionPill;
        final ImageView check;
        final ImageView chev;

        RowHolder(
                LinearLayout card,
                FrameLayout badge,
                ImageView icon,
                TextView name,
                TextView sub,
                TextView actionPill,
                ImageView check,
                ImageView chev) {
            this.card = card;
            this.badge = badge;
            this.icon = icon;
            this.name = name;
            this.sub = sub;
            this.actionPill = actionPill;
            this.check = check;
            this.chev = chev;
        }
    }
}
