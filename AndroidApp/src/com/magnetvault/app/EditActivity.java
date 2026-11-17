package com.magnetvault.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 新增 / 编辑收藏（新建模式下可与批量识别无缝切换）。 */
public class EditActivity extends AppActivity {

    public static final String EXTRA_ENTRY_ID = "entryId";

    /** 从某个文件夹里点"新建磁力"时带过来：默认归属该分类。 */
    public static final String EXTRA_DEFAULT_CATEGORY = "defaultCategoryId";

    private String baseline;
    private boolean saving;
    private boolean ready;
    private String editingId;
    private Entry existing;

    private final List<Category> cats = new ArrayList<>();
    private String pickedCategoryId;

    private ViewGroup editContent;
    private EditText inputMagnet;
    private EditText inputTitle;
    private EditText inputNote;
    private EditText inputSource;
    private TextView parseHint;
    private View multiLinkBanner;
    private TextView multiLinkText;
    private TextView headerTitle;
    private CategoryPicker.Card categoryCard;
    private ChipFlow tagFlow;

    /** 已选标签（点选式，不再手打） */
    private final List<String> pickedTags = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        editContent = findViewById(R.id.edit_content);
        inputMagnet = findViewById(R.id.input_magnet);
        inputTitle = findViewById(R.id.input_title);
        tagFlow = findViewById(R.id.tag_flow);
        inputNote = findViewById(R.id.input_note);
        inputSource = findViewById(R.id.input_source);
        parseHint = findViewById(R.id.parse_hint);
        multiLinkBanner = findViewById(R.id.multi_link_banner);
        multiLinkText = findViewById(R.id.multi_link_text);
        headerTitle = findViewById(R.id.header_title);
        FrameLayout categorySlot = findViewById(R.id.category_slot);
        categoryCard = CategoryPicker.selectorCard(this, this::openCategoryPicker);
        categorySlot.addView(
                categoryCard.root,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT));

        View btnBack = findViewById(R.id.btn_back);
        View btnSave = findViewById(R.id.btn_save);
        View btnPaste = findViewById(R.id.btn_paste);
        View tagCard = findViewById(R.id.tag_card);
        Design.tactile(btnBack);
        Design.tactile(btnSave);
        Design.tactile(btnPaste);
        Design.tactile(tagCard);
        Design.tactile(multiLinkBanner);

        btnBack.setOnClickListener(v -> onBackPressed());
        btnSave.setOnClickListener(v -> doSave());
        btnPaste.setOnClickListener(
                v -> {
                    String clip = ClipImporter.readClipboard(EditActivity.this);
                    if (clip.trim().isEmpty()) {
                        Ui.toast(EditActivity.this, "剪贴板为空");
                    } else {
                        inputMagnet.setText(clip.trim());
                        inputMagnet.setSelection(inputMagnet.getText().length());
                    }
                });
        multiLinkBanner.setOnClickListener(v -> openBatchImport(true));

        editingId = getIntent().getStringExtra(EXTRA_ENTRY_ID);
        if (editingId != null) {
            existing = Repo.byId(this, editingId);
            if (existing == null || existing.deletedAt != null) {
                Ui.toast(this, "该收藏已不存在");
                finish();
                return;
            }
            headerTitle.setText("编辑收藏");
        }

        reloadCategories();

        FrameLayout modeSlot = findViewById(R.id.mode_switch_slot);
        if (existing != null) {
            modeSlot.setVisibility(View.GONE);
            inputMagnet.setText(existing.magnet);
            inputTitle.setText(existing.title);
            pickedTags.clear();
            pickedTags.addAll(existing.tags);
            inputNote.setText(existing.note);
            inputSource.setText(existing.source);
            pickedCategoryId = normalizeCategoryId(existing.categoryId);
        } else {
            modeSlot.addView(
                    Design.segmented(
                            this,
                            new String[] {"批量 / 智能识别", "单条精细录入"},
                            new int[] {R.drawable.ic_sparkle, R.drawable.ic_edit},
                            1,
                            idx -> {
                                if (idx == 0) openBatchImport(false);
                            }));
            pickedCategoryId =
                    normalizeCategoryId(getIntent().getStringExtra(EXTRA_DEFAULT_CATEGORY));
            if (savedInstanceState == null && getIntent().hasExtra("text")) {
                String prefill = getIntent().getStringExtra("text");
                if (prefill != null && !prefill.trim().isEmpty()) {
                    inputMagnet.setText(prefill);
                    inputMagnet.setSelection(inputMagnet.getText().length());
                }
            }
        }

        // 标签：点击弹出选择器，而不是手打
        Runnable openTags =
                () ->
                        TagPicker.show(
                                EditActivity.this,
                                "选择标签",
                                pickedTags,
                                tags -> {
                                    pickedTags.clear();
                                    pickedTags.addAll(tags);
                                    refreshTagField();
                                });
        tagCard.setOnClickListener(v -> openTags.run());
        tagFlow.setOnClickListener(v -> openTags.run());

        inputMagnet.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        updateHint(s == null ? "" : s.toString());
                    }
                });
        if (savedInstanceState != null) {
            pickedTags.clear();
            ArrayList<String> restored = savedInstanceState.getStringArrayList("pickedTags");
            if (restored != null) pickedTags.addAll(restored);
            if (savedInstanceState.containsKey("pickedCategoryId")) {
                pickedCategoryId =
                        normalizeCategoryId(savedInstanceState.getString("pickedCategoryId"));
            }
            baseline = savedInstanceState.getString("baseline");
        }
        refreshCategoryCard();
        refreshTagField();
        if (baseline == null) baseline = snapshot();
        ready = true;
        updateHint(inputMagnet.getText().toString());
    }

    private void openBatchImport(boolean autoPreview) {
        if (saving || existing != null) return;
        Intent i = new Intent(this, ImportActivity.class);
        i.putExtra(EXTRA_FADE, true);
        String raw = inputMagnet.getText().toString();
        if (!raw.trim().isEmpty()) i.putExtra("text", raw);
        if (pickedCategoryId != null) i.putExtra("category", pickedCategoryId);
        if (autoPreview) i.putExtra("autoPreview", true);
        startActivity(i);
        finishQuietFade();
    }

    private void reloadCategories() {
        cats.clear();
        cats.addAll(Repo.categories(this, false));
    }

    private String normalizeCategoryId(String id) {
        if (id == null) return null;
        for (Category c : cats) {
            if (id.equals(c.id)) return id;
        }
        return null;
    }

    private void refreshCategoryCard() {
        CategoryPicker.bindSelectorCard(
                this,
                categoryCard,
                cats,
                pickedCategoryId,
                "未分类",
                "按层级浏览或搜索分类");
    }

    private void openCategoryPicker() {
        CategoryPicker.show(
                this,
                "选择分类",
                pickedCategoryId,
                "未分类",
                "不归入任何文件夹",
                null,
                true,
                id -> {
                    pickedCategoryId = id;
                    reloadCategories();
                    refreshCategoryCard();
                });
    }

    private void updateHint(String text) {
        List<String> links = MagnetParser.extractAll(text);
        if (links.isEmpty()) {
            setMultiBanner(false, 0);
            parseHint.setText("粘贴链接后会自动检查格式");
            parseHint.setTextColor(Design.color(this, R.color.c_text2));
            return;
        }
        if (links.size() > 1) {
            if (existing == null) {
                setMultiBanner(true, links.size());
                parseHint.setText("一次精细录入仅保存单条；点击上方可携带文本直达批量识别清单");
                parseHint.setTextColor(Design.color(this, R.color.c_accent));
            } else {
                setMultiBanner(false, 0);
                parseHint.setText("编辑模式下仅支持单条链接，请保留需要的那一条");
                parseHint.setTextColor(Design.color(this, R.color.c_star));
            }
            return;
        }
        setMultiBanner(false, 0);
        boolean valid = MagnetParser.isValid(links.get(0));
        if (!valid) {
            parseHint.setText("链接格式不完整，请检查是否复制完整");
            parseHint.setTextColor(Design.color(this, R.color.c_star));
        } else {
            parseHint.setText(
                    MagnetParser.isEd2k(links.get(0)) ? "已识别 ED2K（电驴）文件链接，可以保存" : "已识别有效磁力链接，可以保存");
            parseHint.setTextColor(Design.color(this, R.color.c_accent));
        }
    }

    private void setMultiBanner(boolean show, int count) {
        boolean visible = multiLinkBanner.getVisibility() == View.VISIBLE;
        if (show) {
            multiLinkText.setText("识别到 " + count + " 条链接，更适合批量处理");
            if (!visible) {
                Design.animate(editContent);
                multiLinkBanner.setVisibility(View.VISIBLE);
            }
        } else if (visible) {
            Design.animate(editContent);
            multiLinkBanner.setVisibility(View.GONE);
        }
    }

    private void doSave() {
        if (saving) return;
        List<String> links = MagnetParser.extractAll(inputMagnet.getText().toString());
        if (links.isEmpty()) {
            inputMagnet.setError("请先粘贴磁力或 ED2K 文件链接");
            inputMagnet.requestFocus();
            return;
        }
        if (links.size() > 1) {
            if (existing == null) {
                openBatchImport(true);
            } else {
                inputMagnet.setError("这里一次保存一条，多条链接请使用「批量识别」");
                inputMagnet.requestFocus();
            }
            return;
        }
        final String link = links.get(0);
        if (!MagnetParser.isValid(link)) {
            inputMagnet.setError("链接格式不正确，请检查是否复制完整");
            inputMagnet.requestFocus();
            return;
        }

        final Entry e = new Entry();
        if (existing != null) {
            e.id = existing.id;
            e.createdAt = existing.createdAt;
            e.starred = existing.starred;
            e.deletedAt = existing.deletedAt;
        } else {
            e.id = UUID.randomUUID().toString();
        }
        e.magnet = link;
        e.normalized = MagnetParser.normalize(link);
        e.infoHash = MagnetParser.infoHash(link);
        e.title = inputTitle.getText().toString().trim();
        if (e.title.isEmpty()) e.title = MagnetParser.titleFromLink(link);
        e.note = inputNote.getText().toString().trim();
        e.source = inputSource.getText().toString().trim();
        e.categoryId = pickedCategoryId;
        e.tags = new ArrayList<>(pickedTags);

        Entry dup = Repo.findDuplicate(this, e.normalized, e.magnet, e.id);
        if (dup != null) {
            Ui.confirmSheet(
                    this,
                    "疑似重复链接",
                    "已存在相同链接的收藏：\n\n" + dup.displayTitle() + "\n\n仍要保存吗？",
                    "仍然保存",
                    false,
                    () -> persist(e));
            return;
        }
        persist(e);
    }

    private void refreshTagField() {
        tagFlow.removeAllViews();
        Runnable openTags =
                () ->
                        TagPicker.show(
                                EditActivity.this,
                                "选择标签",
                                pickedTags,
                                tags -> {
                                    pickedTags.clear();
                                    pickedTags.addAll(tags);
                                    refreshTagField();
                                });
        for (String tag : pickedTags) {
            tagFlow.addView(Design.tagChip(this, "# " + tag, false, openTags));
        }
        tagFlow.addView(
                Design.tagChip(
                        this, pickedTags.isEmpty() ? "+ 选择或新建标签" : "+ 调整标签", true, openTags));
    }

    private String snapshot() {
        return inputMagnet.getText()
                + "\u0002"
                + inputTitle.getText()
                + "\u0002"
                + inputSource.getText()
                + "\u0002"
                + inputNote.getText()
                + "\u0002"
                + (pickedCategoryId == null ? "" : pickedCategoryId)
                + "\u0002"
                + pickedTags.toString();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("pickedCategoryId", pickedCategoryId);
        state.putStringArrayList("pickedTags", new ArrayList<>(pickedTags));
        state.putString("baseline", baseline);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (saving) {
            Ui.toast(this, "正在保存，请稍候");
            return;
        }
        if (!ready || snapshot().equals(baseline)) {
            finish();
            return;
        }
        Ui.confirmSheet(
                this,
                "保留这次修改？",
                "还有未保存的内容。",
                "保存并退出",
                "放弃修改",
                false,
                this::doSave,
                this::finish);
    }

    private void persist(Entry e) {
        saving = true;
        View save = findViewById(R.id.btn_save);
        save.setEnabled(false);
        DATA.execute(
                () -> {
                    String error = null;
                    try {
                        Repo.save(getApplicationContext(), e);
                    } catch (Exception ex) {
                        error = "保存失败，请检查内容后重试";
                    }
                    final String message = error;
                    MAIN.post(
                            () -> {
                                if (isFinishing() || isDestroyed()) return;
                                saving = false;
                                save.setEnabled(true);
                                if (message != null) {
                                    Ui.toast(this, message);
                                    return;
                                }
                                Ui.toast(this, existing == null ? "已添加收藏" : "修改已保存");
                                setResult(RESULT_OK);
                                finish();
                            });
                });
    }
}
