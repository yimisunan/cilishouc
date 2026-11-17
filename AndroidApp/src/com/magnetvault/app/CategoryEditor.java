package com.magnetvault.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared bottom-sheet CRUD workflows for folders and tags across MainActivity and CategoryActivity. */
public final class CategoryEditor {
    private CategoryEditor() {}

    public static void categoryActions(
            Activity host,
            Category c,
            List<Category> allCategories,
            Runnable onViewCollection,
            Runnable onChanged) {
        Ui.menuSheet(
                host,
                pathLabel(allCategories, c),
                new String[] {"查看此分类收藏", "重命名与选色", "新建子分类", "移动到其他位置", "删除分类"},
                new int[] {
                    R.drawable.ic_folder,
                    R.drawable.ic_edit,
                    R.drawable.ic_add,
                    R.drawable.ic_move,
                    R.drawable.ic_trash
                },
                (d, w) -> {
                    if (w == 0 && onViewCollection != null) onViewCollection.run();
                    if (w == 1) editCategory(host, c, null, onChanged);
                    if (w == 2) editCategory(host, null, c.id, onChanged);
                    if (w == 3) moveCategory(host, c, allCategories, onChanged);
                    if (w == 4) deleteCategory(host, c, allCategories, onChanged);
                });
    }

    public static void editCategory(
            Activity host, Category editing, String parentId, Runnable onChanged) {
        LinearLayout root = Ui.sheetContainer(host);

        String title =
                editing != null
                        ? "编辑分类"
                        : (parentId == null ? "新建分类" : "新建子分类");
        TextView head = Design.text(host, title, 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        root.addView(head);
        Design.gap(host, root, 12);

        final int[] pickedColor = {editing != null ? editing.color : Ui.COLORS[0]};

        // Live folder preview card
        LinearLayout preview = Design.row(host);
        preview.setBackgroundResource(R.drawable.bg_card_soft);
        Design.pad(host, preview, 12, 10, 10);
        FrameLayout previewBadge =
                Design.iconBadge(
                        host,
                        R.drawable.ic_folder,
                        pickedColor[0],
                        Design.softTint(pickedColor[0]),
                        40,
                        20);
        LinearLayout.LayoutParams pbp = (LinearLayout.LayoutParams) previewBadge.getLayoutParams();
        pbp.rightMargin = Ui.dp(host, 12);
        preview.addView(previewBadge, pbp);
        ImageView previewIcon = (ImageView) previewBadge.getChildAt(0);
        TextView previewName =
                Design.text(
                        host,
                        editing != null ? editing.name : "新文件夹名称",
                        15,
                        R.color.c_text);
        previewName.setTypeface(null, Typeface.BOLD);
        previewName.setSingleLine(true);
        previewName.setEllipsize(TextUtils.TruncateAt.END);
        preview.addView(previewName, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(preview);

        Design.label(host, root, "分类名称");
        EditText input = Design.input(host, "例如：电影、剧集或工作资料", false);
        if (editing != null) {
            input.setText(editing.name);
            input.setSelection(editing.name.length());
        }
        root.addView(input);

        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    public void afterTextChanged(Editable s) {
                        String name = s == null ? "" : s.toString().trim();
                        previewName.setText(name.isEmpty() ? "新文件夹名称" : name);
                    }
                });

        Design.label(host, root, "文件夹颜色");
        root.addView(
                Design.colorSwatches(
                        host,
                        pickedColor[0],
                        c -> {
                            pickedColor[0] = c;
                            previewIcon.setColorFilter(c);
                            ((GradientDrawable) previewBadge.getBackground())
                                    .setColor(Design.softTint(c));
                        }));

        TextView err = Design.text(host, "", 12, R.color.c_danger);
        err.setVisibility(View.GONE);
        Design.pad(host, err, 4, 8, 0);
        root.addView(err);

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

        final Button[] saveRef = {null};
        saveRef[0] =
                Design.button(
                        host,
                        "保存",
                        R.drawable.ic_check,
                        true,
                        () -> {
                            String name = input.getText().toString().trim();
                            if (name.isEmpty()) {
                                err.setText("请输入分类名称");
                                err.setVisibility(View.VISIBLE);
                                input.requestFocus();
                                return;
                            }
                            saveRef[0].setEnabled(false);
                            AppActivity.DATA.execute(
                                    () -> {
                                        String resultErr;
                                        try {
                                            resultErr =
                                                    editing == null
                                                            ? Repo.addCategory(
                                                                    host.getApplicationContext(),
                                                                    name,
                                                                    pickedColor[0],
                                                                    parentId)
                                                            : Repo.updateCategory(
                                                                    host.getApplicationContext(),
                                                                    editing.id,
                                                                    name,
                                                                    pickedColor[0]);
                                        } catch (Exception ex) {
                                            resultErr = "保存未完成，请重试";
                                        }
                                        final String finalErr = resultErr;
                                        AppActivity.MAIN.post(
                                                () -> {
                                                    if (host.isFinishing() || host.isDestroyed())
                                                        return;
                                                    saveRef[0].setEnabled(true);
                                                    if (finalErr != null) {
                                                        err.setText(finalErr);
                                                        err.setVisibility(View.VISIBLE);
                                                        return;
                                                    }
                                                    if (ref[0] != null) ref[0].dismiss();
                                                    Ui.toast(host, "分类已保存");
                                                    if (onChanged != null) onChanged.run();
                                                });
                                    });
                        });
        actions.addView(saveRef[0], new LinearLayout.LayoutParams(0, -2, 1.3f));
        root.addView(actions);

        ref[0] = Ui.showSheet(host, root, true);
        input.requestFocus();
    }

    public static void moveCategory(
            Activity host,
            Category source,
            List<Category> allCategories,
            Runnable onChanged) {
        Set<String> excluded = new HashSet<>();
        excluded.add(source.id);
        boolean added = true;
        while (added) {
            added = false;
            for (Category c : allCategories) {
                if (!excluded.contains(c.id) && excluded.contains(c.parentId)) {
                    excluded.add(c.id);
                    added = true;
                }
            }
        }
        CategoryPicker.show(
                host,
                "移动「" + source.name + "」到",
                source.parentId,
                "设为顶层分类",
                "放在根目录作为一级文件夹",
                excluded,
                false,
                newParentId ->
                        AppActivity.DATA.execute(
                                () -> {
                                    String err =
                                            Repo.setCategoryParent(
                                                    host.getApplicationContext(),
                                                    source.id,
                                                    newParentId);
                                    AppActivity.MAIN.post(
                                            () -> {
                                                if (host.isFinishing() || host.isDestroyed()) return;
                                                Ui.toast(host, err == null ? "已移动分类" : err);
                                                if (onChanged != null) onChanged.run();
                                            });
                                }));
    }

    public static void deleteCategory(
            Activity host,
            Category c,
            List<Category> allCategories,
            Runnable onChanged) {
        final String[] targetId = {null};
        LinearLayout root = Ui.sheetContainer(host);

        LinearLayout header = Design.row(host);
        FrameLayout badge =
                Design.iconBadge(
                        host,
                        R.drawable.ic_trash,
                        Design.color(host, R.color.c_danger),
                        Design.color(host, R.color.c_danger_soft),
                        38,
                        20);
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        bp.rightMargin = Ui.dp(host, 12);
        header.addView(badge, bp);
        TextView head = Design.text(host, "删除「" + c.name + "」？", 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        Design.gap(host, root, 12);
        String msg =
                c.count
                        + " 条收藏将移动到下方指定位置，不会丢失数据。"
                        + (c.childCount > 0
                                ? "\n" + c.childCount + " 个子分类会自动上移一级。"
                                : "");
        TextView info = Design.text(host, msg, 13, R.color.c_text2);
        info.setBackgroundResource(R.drawable.bg_card_soft);
        Design.pad(host, info, 12, 10, 10);
        root.addView(info);

        Design.label(host, root, "原收藏迁移到");
        final CategoryPicker.Card[] cardRef = {null};
        cardRef[0] =
                CategoryPicker.selectorCard(
                        host,
                        () ->
                                CategoryPicker.show(
                                        host,
                                        "选择收藏保留位置",
                                        targetId[0],
                                        "移到未分类",
                                        "清除这些收藏的分类归属",
                                        Collections.singleton(c.id),
                                        false,
                                        pickedId -> {
                                            targetId[0] = pickedId;
                                            CategoryPicker.bindSelectorCard(
                                                    host,
                                                    cardRef[0],
                                                    allCategories,
                                                    targetId[0],
                                                    "移到未分类",
                                                    "清除这些收藏的分类归属");
                                        }));
        CategoryPicker.bindSelectorCard(
                host,
                cardRef[0],
                allCategories,
                targetId[0],
                "移到未分类",
                "清除这些收藏的分类归属");
        root.addView(cardRef[0].root);

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

        Button del = new Button(host);
        del.setText("删除分类");
        del.setTextSize(15);
        del.setTypeface(null, Typeface.BOLD);
        del.setAllCaps(false);
        del.setMinHeight(Ui.dp(host, 48));
        del.setTextColor(Design.color(host, R.color.c_danger));
        del.setBackgroundResource(R.drawable.bg_danger);
        Design.tactile(del);
        del.setOnClickListener(
                v -> {
                    del.setEnabled(false);
                    AppActivity.DATA.execute(
                            () -> {
                                Repo.deleteCategory(
                                        host.getApplicationContext(), c.id, targetId[0]);
                                AppActivity.MAIN.post(
                                        () -> {
                                            if (host.isFinishing() || host.isDestroyed()) return;
                                            if (ref[0] != null) ref[0].dismiss();
                                            Ui.toast(host, "分类已删除，收藏已保留");
                                            if (onChanged != null) onChanged.run();
                                        });
                            });
                });
        actions.addView(del, new LinearLayout.LayoutParams(0, -2, 1.3f));
        root.addView(actions);
        ref[0] = Ui.showSheet(host, root, false);
    }

    public static void tagActions(
            Activity host,
            String name,
            List<String> allTags,
            Runnable onSearchTag,
            Runnable onChanged) {
        Ui.menuSheet(
                host,
                "# " + name,
                new String[] {"查看相关收藏", "重命名标签", "合并到其他标签", "删除标签"},
                new int[] {
                    R.drawable.ic_search,
                    R.drawable.ic_edit,
                    R.drawable.ic_move,
                    R.drawable.ic_trash
                },
                (d, w) -> {
                    if (w == 0 && onSearchTag != null) onSearchTag.run();
                    if (w == 1) editTag(host, name, onChanged);
                    if (w == 2) mergeTag(host, name, allTags, onChanged);
                    if (w == 3) deleteTag(host, name, onChanged);
                });
    }

    public static void editTag(Activity host, String original, Runnable onChanged) {
        LinearLayout root = Ui.sheetContainer(host);
        TextView head =
                Design.text(host, original == null ? "新建标签" : "重命名标签", 18, R.color.c_text);
        head.setTypeface(null, Typeface.BOLD);
        root.addView(head);
        Design.label(host, root, "标签名称");
        EditText input = Design.input(host, "例如：待看、4K、蓝光", false);
        if (original != null) {
            input.setText(original);
            input.setSelection(original.length());
        }
        root.addView(input);

        TextView err = Design.text(host, "", 12, R.color.c_danger);
        err.setVisibility(View.GONE);
        Design.pad(host, err, 4, 8, 0);
        root.addView(err);

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

        final Button[] saveRef = {null};
        saveRef[0] =
                Design.button(
                        host,
                        "保存",
                        R.drawable.ic_check,
                        true,
                        () -> {
                            String name = input.getText().toString().trim();
                            if (name.isEmpty()) {
                                err.setText("请输入标签名称");
                                err.setVisibility(View.VISIBLE);
                                input.requestFocus();
                                return;
                            }
                            saveRef[0].setEnabled(false);
                            AppActivity.DATA.execute(
                                    () -> {
                                        String resultErr;
                                        try {
                                            resultErr =
                                                    original == null
                                                            ? Repo.addTag(
                                                                    host.getApplicationContext(),
                                                                    name)
                                                            : Repo.renameTag(
                                                                    host.getApplicationContext(),
                                                                    original,
                                                                    name,
                                                                    false);
                                        } catch (Exception ex) {
                                            resultErr = "保存未完成，请重试";
                                        }
                                        final String finalErr = resultErr;
                                        AppActivity.MAIN.post(
                                                () -> {
                                                    if (host.isFinishing() || host.isDestroyed())
                                                        return;
                                                    saveRef[0].setEnabled(true);
                                                    if (finalErr != null) {
                                                        err.setText(finalErr);
                                                        err.setVisibility(View.VISIBLE);
                                                        return;
                                                    }
                                                    if (ref[0] != null) ref[0].dismiss();
                                                    Ui.toast(host, "标签已保存");
                                                    if (onChanged != null) onChanged.run();
                                                });
                                    });
                        });
        actions.addView(saveRef[0], new LinearLayout.LayoutParams(0, -2, 1.3f));
        root.addView(actions);
        ref[0] = Ui.showSheet(host, root, true);
        input.requestFocus();
    }

    public static void mergeTag(
            Activity host, String source, List<String> allTags, Runnable onChanged) {
        List<String> targets = new ArrayList<>();
        for (String t : allTags) {
            if (!t.equalsIgnoreCase(source)) targets.add(t);
        }
        if (targets.isEmpty()) {
            Ui.toast(host, "先创建另一个标签，再选择合并");
            return;
        }
        String[] labels = new String[targets.size()];
        int[] icons = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            labels[i] = "# " + targets.get(i);
            icons[i] = R.drawable.ic_tag;
        }
        Ui.choiceSheet(
                host,
                "将「" + source + "」合并到",
                labels,
                null,
                icons,
                -1,
                idx -> {
                    String target = targets.get(idx);
                    Ui.confirmSheet(
                            host,
                            "确认合并标签？",
                            "所有「"
                                    + source
                                    + "」关联会归入「"
                                    + target
                                    + "」，相同收藏的关联自动去重。",
                            "确认合并",
                            false,
                            () ->
                                    AppActivity.DATA.execute(
                                            () -> {
                                                String err =
                                                        Repo.renameTag(
                                                                host.getApplicationContext(),
                                                                source,
                                                                target,
                                                                true);
                                                AppActivity.MAIN.post(
                                                        () -> {
                                                            if (host.isFinishing()
                                                                    || host.isDestroyed()) return;
                                                            Ui.toast(
                                                                    host,
                                                                    err == null ? "标签已合并" : err);
                                                            if (onChanged != null) onChanged.run();
                                                        });
                                            }));
                });
    }

    public static void deleteTag(Activity host, String name, Runnable onChanged) {
        AppActivity.DATA.execute(
                () -> {
                    int count = Repo.tagTotalUsage(host.getApplicationContext(), name);
                    AppActivity.MAIN.post(
                            () -> {
                                if (host.isFinishing() || host.isDestroyed()) return;
                                Ui.confirmSheet(
                                        host,
                                        "删除标签「" + name + "」？",
                                        "将从 "
                                                + count
                                                + " 条收藏中移除此标签（包含回收站）。收藏本体与其他标签不受影响。",
                                        "删除标签",
                                        true,
                                        () ->
                                                AppActivity.DATA.execute(
                                                        () -> {
                                                            Repo.deleteTag(
                                                                    host.getApplicationContext(),
                                                                    name);
                                                            AppActivity.MAIN.post(
                                                                    () -> {
                                                                        if (host.isFinishing()
                                                                                || host.isDestroyed())
                                                                            return;
                                                                        Ui.toast(host, "标签已删除");
                                                                        if (onChanged != null)
                                                                            onChanged.run();
                                                                    });
                                                        }));
                            });
                });
    }

    public static void cleanUnusedTags(Activity host, Runnable onChanged) {
        Ui.confirmSheet(
                host,
                "清理未使用标签？",
                "只删除尚未关联任何收藏的空标签。回收站内仍在使用的标签会保留。",
                "清理",
                false,
                () ->
                        AppActivity.DATA.execute(
                                () -> {
                                    int n = Repo.deleteUnusedTags(host.getApplicationContext());
                                    AppActivity.MAIN.post(
                                            () -> {
                                                if (host.isFinishing() || host.isDestroyed())
                                                    return;
                                                Ui.toast(
                                                        host,
                                                        n == 0
                                                                ? "没有未使用的空标签"
                                                                : "已清理 " + n + " 个标签");
                                                if (onChanged != null) onChanged.run();
                                            });
                                }));
    }

    private static String pathLabel(List<Category> cats, Category c) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Category cur = c;
        while (cur != null && seen.add(cur.id)) {
            names.add(0, cur.name);
            Category next = null;
            for (Category x : cats) {
                if (x.id.equals(cur.parentId)) {
                    next = x;
                    break;
                }
            }
            cur = next;
        }
        return TextUtils.join(" / ", names);
    }
}
