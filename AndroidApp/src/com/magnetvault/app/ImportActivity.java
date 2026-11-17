package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Unified one/many paste entry point with an editable, recycled staging list. */
public class ImportActivity extends AppActivity {
    private Task task;
    private EditText input;
    private CategoryPicker.Card categoryCard;
    private TextView hint, summary, selection;
    private Button preview, commit, bulk;
    private ListView list;
    private DraftAdapter adapter;
    private final List<Category> dbCategories = new ArrayList<>();
    private final Set<String> categoryPathSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final List<ImportDraft.Row> visible = new ArrayList<>();
    private final Set<ImportDraft.Row> expandedRows =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private String stateKey;
    private boolean recognizing;
    private int previewRequest;
    private int position, positionTop;
    private boolean reading;
    private Runnable filterUpdate;
    private boolean discarded;

    private static final class CategorySnapshot {
        final List<Category> cats;
        final Set<String> paths;

        CategorySnapshot(List<Category> cats, Set<String> paths) {
            this.cats = cats;
            this.paths = paths;
        }
    }

    private static final class Session implements Serializable {
        private static final long serialVersionUID = 1L;
        String text = "", categoryId, query = "";
        ImportDraft draft;
    }

    private static final class Task {
        Session session = new Session();
        volatile boolean running;
        ClipImporter.Result result;
        String error;
        boolean delivered;
        Runnable observer;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        task = (Task) getLastNonConfigurationInstance();
        boolean retained = task != null;
        if (task == null) task = new Task();
        stateKey = state == null ? UUID.randomUUID().toString() : state.getString("draftKey", UUID.randomUUID().toString());
        if (state != null) { position = state.getInt("position"); positionTop = state.getInt("positionTop"); }
        task.observer = this::taskChanged;
        if (!retained && state != null) {
            reading = true;
            load(() -> readSession(), restored -> {
                reading = false;
                if (restored != null) task.session = restored;
                else Ui.toast(this, "未能恢复草稿，请重新粘贴内容");
                render(); loadCategories(); taskChanged();
            }, error -> { reading = false; render(); loadCategories(); });
        } else if (!retained) {
            String text = getIntent().getStringExtra("text");
            task.session.text = text == null ? "" : text;
            task.session.categoryId = getIntent().getStringExtra("category");
        }
        render();
        if (!reading) loadCategories();
        if (retained && task.result != null) task.delivered = false;
        taskChanged();
    }

    private void loadCategories() {
        load(() -> new CategorySnapshot(Repo.categories(this, false), ClipImporter.categoryIndex(this).keySet()), snap -> {
            dbCategories.clear();
            dbCategories.addAll(snap.cats);
            categoryPathSet.clear();
            categoryPathSet.addAll(snap.paths);
            refreshCategoryCard();
        });
    }

    private void refreshCategoryCard() {
        CategoryPicker.bindSelectorCard(
                this,
                categoryCard,
                dbCategories,
                task.session.categoryId,
                "未分类",
                "按层级浏览或搜索分类，未自带分类的链接将归入此处");
    }

    private void render() {
        input = null; categoryCard = null; hint = null; preview = null;
        summary = null; selection = null; commit = null; bulk = null; list = null; adapter = null;
        LinearLayout root = Design.column(this);
        root.setBackgroundColor(Design.color(this, R.color.c_bg));
        root.addView(Design.toolbar(this, task.session.draft == null ? "添加收藏" : "待导入清单", this::onBackPressed));
        if (task.session.draft == null) renderInput(root); else renderDraft(root);
        setContentView(root);
        if (reading) setEnabled(root, false);
    }

    private void renderInput(LinearLayout root) {
        LinearLayout box = Design.column(this);
        Design.pad(this, box, 18, 8, 24);
        box.addView(Design.heading(this, "粘贴链接，先整理再保存"));
        Design.gap(this, box, 8);
        box.addView(Design.text(this, "一条或多条都可以，自动识别磁力和 ED2K。预览后可分别设置分类和标签。", 14, R.color.c_text2));
        Design.label(this, box, "默认分类（已有分类信息的条目优先使用自己的分类）");
        categoryCard =
                CategoryPicker.selectorCard(
                        this,
                        () -> {
                            captureInput();
                            CategoryPicker.show(
                                    this,
                                    "选择默认分类",
                                    task.session.categoryId,
                                    "未分类",
                                    "若文本未自带分类，默认归入未分类",
                                    null,
                                    true,
                                    pickedId -> {
                                        task.session.categoryId = pickedId;
                                        loadCategories();
                                        saveSession();
                                    });
                        });
        refreshCategoryCard();
        box.addView(categoryCard.root);

        Design.gap(this, box, 18);
        LinearLayout inputHeader = Design.row(this);
        TextView inputLbl = Design.text(this, "待导入内容", 13, R.color.c_text2);
        inputLbl.setTypeface(null, android.graphics.Typeface.BOLD);
        inputHeader.addView(inputLbl, new LinearLayout.LayoutParams(0, -2, 1));
        inputHeader.addView(
                Design.pill(
                        this,
                        "从剪贴板粘贴",
                        R.drawable.ic_paste,
                        true,
                        () -> {
                            String clip = ClipImporter.readClipboard(this);
                            if (clip.trim().isEmpty()) {
                                Ui.toast(this, "未读到内容，请在输入框内长按粘贴");
                            } else {
                                input.setText(clip);
                                input.setSelection(input.getText().length());
                            }
                        }));
        box.addView(inputHeader);
        Design.gap(this, box, 8);

        input = Design.input(this, "粘贴一条或多条链接，也可粘贴表格", true);
        input.setId(0x7f0a7001);
        input.setSaveEnabled(false); // Large pastes stay in private draft storage, outside Binder bundles.
        input.setMinLines(4); input.setMaxLines(10);
        input.setText(task.session.text);
        box.addView(input);
        Design.gap(this, box, 10);
        box.addView(Design.textAction(this, "带分类、标签的导入格式说明 ›", this::showFormat));
        hint = Design.text(this, "先预览，不会立即保存。重复链接会自动跳过。", 13, R.color.c_text2);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Design.pad(this, hint, 2, 2, 0);
        box.addView(hint);
        root.addView(Design.scroll(this, box), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = Design.column(this);
        Design.pad(this, footer, 18, 8, 14);
        preview = Design.button(this, "预览导入", R.drawable.ic_import, true, this::preview);
        footer.addView(preview); root.addView(footer);
    }

    private void preview() {
        if (recognizing || task.running || reading) return;
        captureInput();
        if (task.session.text.trim().isEmpty()) {
            input.setError("请先粘贴待导入的内容"); input.requestFocus(); return;
        }
        recognizing = true;
        preview.setEnabled(false); preview.setText("正在识别…");
        final String text = task.session.text;
        final String destination = task.session.categoryId;
        final int request = ++previewRequest;
        load(() -> ClipImporter.preview(this, text, destination), draft -> {
            if (request != previewRequest) return;
            recognizing = false;
            if (input == null) return;
            preview.setEnabled(true); preview.setText("预览导入");
            if (!input.getText().toString().equals(text)) { hint.setText("内容已修改，请重新预览"); return; }
            if (draft.rows.isEmpty()) {
                input.setError("没有找到有效链接，请检查是否复制完整");
                hint.setText("支持 magnet:、ed2k://|file|…，以及名字 / 链接 / 出处格式。"); return;
            }
            task.session.draft = draft;
            task.session.query = ""; position = positionTop = 0;
            expandedRows.clear();
            render(); saveSession();
        }, error -> {
            recognizing = false;
            if (preview != null) { preview.setEnabled(true); preview.setText("预览导入"); }
            if (hint != null) hint.setText("识别失败，请重试。输入内容已保留。");
        });
    }

    private void renderDraft(LinearLayout root) {
        list = new ListView(this);
        list.setId(0x7f0a7002); list.setSaveEnabled(false);
        list.setDividerHeight(Ui.dp(this, 8));
        list.setClipToPadding(false);
        Design.pad(this, list, 16, 0, 12);
        LinearLayout header = Design.column(this);
        Design.pad(this, header, 0, 4, 12);
        summary = Design.text(this, "", 15, R.color.c_text);
        summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(summary);
        Design.gap(this, header, 8);
        header.addView(Design.text(this, "点标题可展开完整名称；点分类、标签可直接修改，勾选后可批量设置。", 14, R.color.c_text2));
        Design.gap(this, header, 8);
        EditText search = Design.input(this, "搜索标题、标签或分类", false);
        search.setContentDescription("搜索待导入清单");
        search.setSaveEnabled(false);
        search.setText(task.session.query);
        header.addView(search);
        LinearLayout actions = Design.row(this);
        actions.addView(Design.textAction(this, "全选筛选结果", this::selectVisible));
        actions.addView(Design.textAction(this, "清空选择", () -> {
            for (ImportDraft.Row row : task.session.draft.rows) row.selected = false;
            refresh();
        }));
        header.addView(actions);
        selection = Design.text(this, "", 14, R.color.c_text2);
        header.addView(selection);
        header.addView(Design.textAction(this, "返回修改粘贴内容", this::returnToInput));
        list.addHeaderView(header, null, false);
        adapter = new DraftAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = Design.column(this);
        Design.pad(this, footer, 20, 8, 12);
        bulk = Design.button(this, "设置所选…", R.drawable.ic_edit, false, this::batchMenu);
        footer.addView(bulk); Design.gap(this, footer, 8);
        commit = Design.button(this, "", R.drawable.ic_import, true, this::confirmImport);
        footer.addView(commit); root.addView(footer);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                task.session.query = s.toString();
                if (filterUpdate != null) MAIN.removeCallbacks(filterUpdate);
                filterUpdate = () -> { if (!isDestroyed()) refresh(); };
                MAIN.postDelayed(filterUpdate, 120);
            }
        });
        refresh();
        list.setSelectionFromTop(position, positionTop);
    }

    private void refresh() {
        if (adapter == null || task.session.draft == null) return;
        visible.clear();
        for (ImportDraft.Row row : task.session.draft.rows)
            if (row.matches(task.session.query)) visible.add(row);
        ImportDraft draft = task.session.draft;
        summary.setText("识别 " + draft.rows.size() + " 条 · 待新增 " + draft.includedCount()
                + " 条 · 重复 " + draft.duplicateCount() + " 条" + (draft.invalid > 0 ? " · 无效 " + draft.invalid + " 条" : ""));
        selection.setText("当前显示 " + visible.size() + " 条 · 已选 " + draft.selected().size() + " 条（含筛选外）");
        commit.setText(task.running ? "正在保存收藏…" : "导入 " + draft.includedCount() + " 条收藏");
        commit.setEnabled(!task.running && draft.includedCount() > 0);
        bulk.setEnabled(!task.running);
        adapter.notifyDataSetChanged();
    }

    private void selectVisible() {
        if (task.running) return;
        for (ImportDraft.Row row : visible) if (row.editable()) row.selected = true;
        refresh();
    }

    private void batchMenu() {
        if (task.running) return;
        List<ImportDraft.Row> rows = task.session.draft.selected();
        if (rows.isEmpty()) { Ui.toast(this, "请先勾选条目，或全选筛选结果"); return; }
        Ui.menuSheet(
                this,
                "设置所选 " + rows.size() + " 条",
                new String[]{"设置分类", "追加标签", "替换标签", "暂不导入", "恢复导入"},
                new int[]{
                    R.drawable.ic_folder,
                    R.drawable.ic_tag,
                    R.drawable.ic_edit,
                    R.drawable.ic_close,
                    R.drawable.ic_restore
                },
                (d, which) -> {
                    if (which == 0) pickCategory(rows);
                    else if (which == 1 || which == 2) pickTags(rows, which == 1);
                    else {
                        for (ImportDraft.Row row : rows) row.included = which == 4;
                        changed();
                    }
                });
    }

    private void pickCategory(List<ImportDraft.Row> rows) {
        if (task.running || task.session.draft == null) return;
        TreeSet<String> extraPaths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ImportDraft.Row row : task.session.draft.rows) {
            if (!row.entry.categoryName.isEmpty()) extraPaths.add(row.entry.categoryName);
        }
        String initialPath = null;
        if (rows.size() == 1) {
            initialPath = rows.get(0).entry.categoryName;
        } else if (!rows.isEmpty()) {
            String first = ImportTextParser.normalizePath(rows.get(0).entry.categoryName);
            boolean allSame = true;
            for (int i = 1; i < rows.size(); i++) {
                if (!first.equalsIgnoreCase(ImportTextParser.normalizePath(rows.get(i).entry.categoryName))) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) initialPath = first;
        }
        String title =
                rows.size() == 1
                        ? "设置分类 · " + rows.get(0).entry.displayTitle()
                        : "批量设置分类 · " + rows.size() + " 条";
        CategoryPicker.showPath(
                this,
                title,
                initialPath,
                extraPaths,
                pickedPath -> {
                    String norm = ImportTextParser.normalizePath(pickedPath);
                    Entry test = new Entry();
                    test.categoryName = norm;
                    String error = ImportDraft.validate(test);
                    if (error != null) {
                        Ui.toast(this, error);
                        return;
                    }
                    ImportDraft.setCategory(rows, norm);
                    changed();
                });
    }

    private void pickTags(List<ImportDraft.Row> rows, boolean append) {
        if (task.running) return;
        List<String> initial = rows.size() == 1 ? rows.get(0).entry.tags : Collections.emptyList();
        List<String> pool = new ArrayList<>();
        for (ImportDraft.Row row : task.session.draft.rows) pool.addAll(row.entry.tags);
        TagPicker.showDraft(this, (append ? "追加标签" : "替换标签") + " · " + rows.size() + " 条", initial, pool, tags -> {
            ImportDraft.setTags(rows, tags, append); changed();
        });
    }

    private void editDetails(ImportDraft.Row row) {
        if (task.running || !row.editable()) return;
        LinearLayout box = Design.column(this); Design.pad(this, box, 20, 8, 12);
        Design.label(this, box, "标题");
        EditText title = Design.input(this, "留空使用链接标题", false); title.setText(row.entry.title); box.addView(title);
        Design.label(this, box, "来源");
        EditText source = Design.input(this, "来源网址或说明", false); source.setText(row.entry.source); box.addView(source);
        Design.label(this, box, "备注");
        EditText note = Design.input(this, "备注", true); note.setText(row.entry.note); box.addView(note);
        CheckBox include = new CheckBox(this); include.setText("导入这条收藏"); include.setMinHeight(Ui.dp(this, 48));
        include.setChecked(row.included); include.setTextColor(Design.color(this, R.color.c_text)); box.addView(include);
        new AlertDialog.Builder(this).setTitle("编辑待导入条目").setView(Design.scroll(this, box))
                .setNegativeButton("取消", null).setPositiveButton("应用", (d, w) -> {
                    row.entry.title = title.getText().toString().trim();
                    row.entry.source = source.getText().toString().trim();
                    row.entry.note = note.getText().toString().trim();
                    row.included = include.isChecked(); changed();
                }).show();
    }

    private void changed() { refresh(); saveSession(); }

    private void confirmImport() {
        if (task.running || task.session.draft.includedCount() == 0) return;
        ImportDraft draft = task.session.draft;
        Set<String> newPaths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ImportDraft.Row row : draft.rows) if (row.editable() && row.included) {
            String validation = ImportDraft.validate(row.entry);
            if (validation != null) { Ui.toast(this, row.entry.displayTitle() + "：" + validation); return; }
            String path = row.entry.categoryName;
            if (!path.isEmpty() && !categoryPathSet.contains(path)) newPaths.add(path);
        }
        String message = "保存 " + draft.includedCount() + " 条收藏，各自使用清单中的分类和标签。\n重复链接会再次检查并跳过。";
        if (!newPaths.isEmpty()) message += "\n\n将按路径创建缺少的分类（" + newPaths.size() + " 个目标路径）：\n"
                + ImportTextParser.join(new ArrayList<>(newPaths).subList(0, Math.min(5, newPaths.size())), "\n")
                + (newPaths.size() > 5 ? "\n…" : "");
        new AlertDialog.Builder(this).setTitle("确认导入").setMessage(message)
                .setNegativeButton("返回修改", null).setPositiveButton("开始导入", (d, w) -> start()).show();
    }

    private void start() {
        if (task.running) return;
        saveSession();
        task.running = true; task.result = null; task.error = null; task.delivered = false;
        taskChanged();
        Context app = getApplicationContext(); Task pending = task;
        DATA.execute(() -> {
            try { pending.result = ClipImporter.importDraft(app, pending.session.draft); }
            catch (Exception e) {
                pending.error = "导入未完成，整批已撤销。草稿已保留，可修改后重试。";
                android.util.Log.e("MagnetVault", "Import failed", e);
            } finally {
                pending.running = false;
                MAIN.post(() -> { if (pending.observer != null) pending.observer.run(); });
            }
        });
    }

    private void taskChanged() {
        if (isFinishing() || isDestroyed()) return;
        if (task.session.draft != null && list != null) {
            setEnabled(list, !task.running); refresh();
        }
        if (task.result != null && !task.delivered) {
            task.delivered = true; deleteSession();
            new AlertDialog.Builder(this).setTitle("导入完成").setMessage(task.result.describe())
                    .setPositiveButton("返回收藏", (d, w) -> finish())
                    .setNegativeButton("继续添加", (d, w) -> {
                        Task next = new Task(); next.observer = this::taskChanged;
                        task = next; discarded = false; stateKey = UUID.randomUUID().toString();
                        expandedRows.clear();
                        position = positionTop = 0; render(); loadCategories();
                    }).setCancelable(false).show();
        } else if (task.error != null && !task.delivered) {
            task.delivered = true;
            new AlertDialog.Builder(this).setTitle("导入未完成").setMessage(task.error)
                    .setPositiveButton("返回清单", null).show();
        }
    }

    private void returnToInput() {
        if (task.running) return;
        new AlertDialog.Builder(this).setTitle("重新识别粘贴内容？")
                .setMessage("清单中的分类、标签和标题修改将清除，原粘贴内容会保留。")
                .setNegativeButton("继续整理", null).setPositiveButton("返回修改", (d, w) -> {
                    task.session.draft = null; task.session.query = "";
                    expandedRows.clear();
                    render(); saveSession();
                }).show();
    }

    private void showFormat() {
        new AlertDialog.Builder(this).setTitle("带分类、标签一起导入")
                .setMessage("方式一：每条一个文本块，条目之间空一行。\n\n标题：示例视频\n链接：magnet:?xt=urn:btih:…\n分类：电影 / 科幻\n标签：高清，待看\n来源：https://example.com\n备注：可选\n\n方式二：从 Excel/WPS 复制表格，首行包含列名：标题、链接、分类、标签。还可带来源、备注，列顺序不限。单元格内不要换行。\n\n分类用 / 分层，标签用逗号或分号分隔。缺少的分类和标签在最终导入时创建。只有链接时，可在预览中搜索、勾选后分组设置。")
                .setPositiveButton("知道了", null).show();
    }

    private void captureInput() {
        if (input != null) task.session.text = input.getText().toString();
    }

    private File sessionFile() {
        File folder = new File(getFilesDir(), "import-drafts"); folder.mkdirs();
        return new File(folder, stateKey.replaceAll("[^a-zA-Z0-9-]", "") + ".draft");
    }

    private Session readSession() throws Exception {
        File file = sessionFile();
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        try (ObjectInputStream in = new ObjectInputStream(atomic.openRead())) { return (Session) in.readObject(); }
        catch (FileNotFoundException missing) { return null; }
    }

    private void saveSession() {
        if (reading || discarded || task.running || task.result != null) return;
        captureInput();
        android.util.AtomicFile file = new android.util.AtomicFile(sessionFile());
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            ObjectOutputStream out = new ObjectOutputStream(stream);
            out.writeObject(task.session); out.flush(); file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            android.util.Log.w("MagnetVault", "Draft persistence failed", e);
        }
    }

    private void deleteSession() { discarded = true; new android.util.AtomicFile(sessionFile()).delete(); }

    @Override public Object onRetainNonConfigurationInstance() { captureInput(); return task; }
    @Override protected void onSaveInstanceState(Bundle state) {
        captureInput(); saveSession();
        if (list != null) { position = list.getFirstVisiblePosition(); View child = list.getChildAt(0); positionTop = child == null ? 0 : child.getTop(); }
        state.putString("draftKey", stateKey); state.putInt("position", position); state.putInt("positionTop", positionTop);
        super.onSaveInstanceState(state);
    }
    @Override protected void onStop() { saveSession(); super.onStop(); }
    @Override protected void onDestroy() { task.observer = null; if (filterUpdate != null) MAIN.removeCallbacks(filterUpdate); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (task.running) { Ui.toast(this, "正在保存，请等待导入完成"); return; }
        if (reading) return;
        captureInput();
        if (task.result != null || task.session.text.trim().isEmpty()) { deleteSession(); finish(); return; }
        if (task.session.draft != null) { returnToInput(); return; }
        new AlertDialog.Builder(this).setTitle("离开添加页面？").setMessage("本次粘贴内容尚未保存。")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃并离开", (d, w) -> { deleteSession(); finish(); }).show();
    }

    private void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            setEnabled(((ViewGroup) view).getChildAt(i), enabled);
    }

    private TextView draftSpecPill() {
        TextView tv = Design.text(this, "", 11, R.color.c_chip_text);
        tv.setBackgroundResource(R.drawable.bg_tag_pill);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setMaxWidth(Ui.dp(this, 120));
        Design.pad(this, tv, 6, 2, 2);
        tv.setVisibility(View.GONE);
        return tv;
    }

    private void bindDraftSpecs(Holder h, List<String> badges) {
        if (badges == null || badges.isEmpty()) {
            h.spec1.setVisibility(View.GONE);
            h.spec2.setVisibility(View.GONE);
            return;
        }
        h.spec1.setVisibility(View.VISIBLE);
        h.spec1.setText(badges.get(0));
        h.spec1.setMaxWidth(Ui.dp(this, 120));
        if (badges.size() == 1) {
            h.spec2.setVisibility(View.GONE);
        } else if (badges.size() == 2) {
            h.spec2.setVisibility(View.VISIBLE);
            h.spec2.setText(badges.get(1));
            h.spec2.setMaxWidth(Ui.dp(this, 120));
        } else {
            h.spec2.setVisibility(View.VISIBLE);
            h.spec2.setText(badges.get(1) + " · " + badges.get(2));
            h.spec2.setMaxWidth(Ui.dp(this, 148));
        }
    }

    private final class DraftAdapter extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Object getItem(int p) { return visible.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View recycled, ViewGroup parent) {
            Holder h;
            if (recycled == null) {
                h = new Holder();
                LinearLayout card = Design.card(ImportActivity.this);
                LinearLayout row = Design.row(ImportActivity.this);
                row.setGravity(android.view.Gravity.TOP);

                h.check = new CheckBox(ImportActivity.this);
                LinearLayout.LayoutParams ckp =
                        new LinearLayout.LayoutParams(
                                Ui.dp(ImportActivity.this, 40), Ui.dp(ImportActivity.this, 40));
                ckp.rightMargin = Ui.dp(ImportActivity.this, 4);
                row.addView(h.check, ckp);

                h.titleCol = Design.column(ImportActivity.this);
                h.titleCol.setClickable(true);

                h.focusRow = Design.row(ImportActivity.this);
                h.focusRow.setVisibility(View.GONE);
                h.code = Design.text(ImportActivity.this, "", 12, R.color.c_accent);
                h.code.setTypeface(null, android.graphics.Typeface.BOLD);
                h.code.setSingleLine(true);
                h.code.setEllipsize(android.text.TextUtils.TruncateAt.END);
                Design.pad(ImportActivity.this, h.code, 7, 2, 2);
                h.code.setVisibility(View.GONE);
                LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-2, -2);
                codeLp.rightMargin = Ui.dp(ImportActivity.this, 6);
                h.focusRow.addView(h.code, codeLp);

                h.spec1 = draftSpecPill();
                LinearLayout.LayoutParams s1Lp = new LinearLayout.LayoutParams(-2, -2);
                s1Lp.rightMargin = Ui.dp(ImportActivity.this, 5);
                h.focusRow.addView(h.spec1, s1Lp);

                h.spec2 = draftSpecPill();
                h.focusRow.addView(h.spec2, new LinearLayout.LayoutParams(-2, -2));

                LinearLayout.LayoutParams frp = new LinearLayout.LayoutParams(-1, -2);
                frp.bottomMargin = Ui.dp(ImportActivity.this, 4);
                h.titleCol.addView(h.focusRow, frp);

                h.title = Design.text(ImportActivity.this, "", 15, R.color.c_text);
                h.title.setTypeface(null, android.graphics.Typeface.BOLD);
                h.titleCol.addView(h.title);

                LinearLayout statusRow = Design.row(ImportActivity.this);
                h.status = Design.text(ImportActivity.this, "", 12, R.color.c_text2);
                statusRow.addView(h.status, new LinearLayout.LayoutParams(0, -2, 1));
                h.chev = new ImageView(ImportActivity.this);
                h.chev.setImageResource(R.drawable.ic_chevron_down);
                h.chev.setColorFilter(Design.color(ImportActivity.this, R.color.c_text2));
                h.chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams chp =
                        new LinearLayout.LayoutParams(
                                Ui.dp(ImportActivity.this, 14), Ui.dp(ImportActivity.this, 14));
                chp.leftMargin = Ui.dp(ImportActivity.this, 6);
                statusRow.addView(h.chev, chp);
                LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
                srp.topMargin = Ui.dp(ImportActivity.this, 4);
                h.titleCol.addView(statusRow, srp);

                h.note = Design.text(ImportActivity.this, "", 12, R.color.c_text2);
                h.note.setBackgroundResource(R.drawable.bg_card_soft);
                Design.pad(ImportActivity.this, h.note, 8, 6, 6);
                h.note.setVisibility(View.GONE);
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
                np.topMargin = Ui.dp(ImportActivity.this, 6);
                h.titleCol.addView(h.note, np);

                row.addView(h.titleCol, new LinearLayout.LayoutParams(0, -2, 1));

                h.more = Design.quietIcon(ImportActivity.this, R.drawable.ic_more, "编辑标题、来源与备注", () -> {});
                h.more.setLayoutParams(
                        new LinearLayout.LayoutParams(
                                Ui.dp(ImportActivity.this, 40), Ui.dp(ImportActivity.this, 40)));
                Design.pad(ImportActivity.this, h.more, 8, 8, 8);
                row.addView(h.more);
                card.addView(row);

                LinearLayout actions = Design.row(ImportActivity.this);
                h.folder = Design.button(ImportActivity.this, "", R.drawable.ic_folder, false, () -> {});
                h.folder.setTextSize(13); h.folder.setSingleLine(true); h.folder.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                h.tags = Design.button(ImportActivity.this, "", R.drawable.ic_tag, false, () -> {});
                h.tags.setTextSize(13); h.tags.setSingleLine(true); h.tags.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1); fp.rightMargin = Ui.dp(ImportActivity.this, 8);
                actions.addView(h.folder, fp);
                actions.addView(h.tags, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
                ap.topMargin = Ui.dp(ImportActivity.this, 8);
                card.addView(actions, ap);

                card.setClickable(true);
                card.setTag(h);
                recycled = card;
            } else h = (Holder) recycled.getTag();
            ImportDraft.Row item = visible.get(p); Entry e = item.entry;
            final View cardView = recycled;
            boolean ed2k = MagnetParser.isEd2k(e.magnet);
            boolean compact = ThemePrefs.isCompactList(ImportActivity.this);
            boolean rowExpanded = expandedRows.contains(item);
            String rawTitle = e.displayTitle();
            TitleFormat.Parsed parsed = compact ? TitleFormat.parse(rawTitle) : null;

            cardView.setBackgroundResource(item.selected ? R.drawable.bg_card_selected : R.drawable.bg_card);
            if (compact && parsed != null && parsed.hasFocus()) {
                boolean showCode = !parsed.code.isEmpty();
                boolean showSpecs = !rowExpanded && !parsed.badges.isEmpty();
                h.focusRow.setVisibility((showCode || showSpecs) ? View.VISIBLE : View.GONE);
                if (showCode) {
                    h.code.setVisibility(View.VISIBLE);
                    h.code.setText(parsed.code);
                    h.code.setTextColor(
                            Design.color(ImportActivity.this, ed2k ? R.color.c_accent2 : R.color.c_accent));
                    h.code.setBackgroundResource(
                            ed2k ? R.drawable.bg_badge_ed2k : R.drawable.bg_badge_magnet);
                } else {
                    h.code.setVisibility(View.GONE);
                }
                if (showSpecs) {
                    bindDraftSpecs(h, parsed.badges);
                } else {
                    h.spec1.setVisibility(View.GONE);
                    h.spec2.setVisibility(View.GONE);
                }
                if (rowExpanded) {
                    h.title.setVisibility(View.VISIBLE);
                    h.title.setText(rawTitle);
                } else if (parsed.summary.isEmpty()) {
                    h.title.setVisibility(View.GONE);
                } else {
                    h.title.setVisibility(View.VISIBLE);
                    h.title.setText(parsed.summary);
                }
            } else {
                h.focusRow.setVisibility(View.GONE);
                h.code.setVisibility(View.GONE);
                h.spec1.setVisibility(View.GONE);
                h.spec2.setVisibility(View.GONE);
                h.title.setVisibility(View.VISIBLE);
                h.title.setText(rawTitle);
            }

            if (!compact || rowExpanded) {
                h.title.setMaxLines(Integer.MAX_VALUE);
                h.title.setEllipsize(null);
            } else {
                h.title.setMaxLines(2);
                h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }

            if (rowExpanded && !android.text.TextUtils.isEmpty(e.note)) {
                h.note.setVisibility(View.VISIBLE);
                h.note.setText("备注：" + e.note.trim());
            } else {
                h.note.setVisibility(View.GONE);
            }
            h.chev.setRotation(rowExpanded ? 180f : 0f);

            View.OnClickListener toggleRow =
                    v -> {
                        if (!expandedRows.remove(item)) expandedRows.add(item);
                        notifyDataSetChanged();
                    };
            cardView.setOnClickListener(toggleRow);
            h.titleCol.setOnClickListener(toggleRow);

            h.check.setOnCheckedChangeListener(null); h.check.setChecked(item.selected);
            h.check.setContentDescription("选择 " + rawTitle);
            h.check.setEnabled(!task.running && item.editable());
            h.check.setOnCheckedChangeListener((v, checked) -> {
                item.selected = checked;
                cardView.setBackgroundResource(checked ? R.drawable.bg_card_selected : R.drawable.bg_card);
                selection.setText("当前显示 " + visible.size() + " 条 · 已选 " + task.session.draft.selected().size() + " 条（含筛选外）");
            });
            h.status.setText((ed2k ? "ED2K" : "BT") + " · " + (item.editable() ? (item.included ? "待导入" : "暂不导入") : item.duplicate));
            h.status.setTextColor(Design.color(ImportActivity.this, item.editable() ? (item.included ? R.color.c_accent : R.color.c_text2) : R.color.c_star));
            h.folder.setText(e.categoryName.isEmpty() ? "未分类" : e.categoryName);
            h.tags.setText(e.tags.isEmpty() ? "设置标签" : e.tagText());
            h.folder.setEnabled(!task.running && item.editable()); h.tags.setEnabled(!task.running && item.editable()); h.more.setEnabled(!task.running && item.editable());
            h.folder.setOnClickListener(v -> pickCategory(Collections.singletonList(item)));
            h.tags.setOnClickListener(v -> pickTags(Collections.singletonList(item), false));
            h.more.setOnClickListener(v -> editDetails(item));
            return recycled;
        }
    }
    private static class Holder {
        CheckBox check;
        LinearLayout titleCol, focusRow;
        TextView code, spec1, spec2, title, status, note;
        ImageView chev;
        Button folder, tags;
        ImageButton more;
    }
}
