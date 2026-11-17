package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 新增 / 编辑收藏。 */
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
    private final List<String> catIds = new ArrayList<>();

    private EditText inputMagnet;
    private EditText inputTitle;
    private EditText inputTags;
    private EditText inputNote;
    private EditText inputSource;
    private TextView parseHint;
    private TextView headerTitle;
    private Spinner spinner;

    /** 已选标签（点选式，不再手打） */
    private final List<String> pickedTags = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        inputMagnet = findViewById(R.id.input_magnet);
        inputTitle = findViewById(R.id.input_title);
        inputTags = findViewById(R.id.input_tags);
        inputNote = findViewById(R.id.input_note);
        inputSource = findViewById(R.id.input_source);
        parseHint = findViewById(R.id.parse_hint);
        headerTitle = findViewById(R.id.header_title);
        spinner = findViewById(R.id.input_category);

        findViewById(R.id.btn_back)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                onBackPressed();
                            }
                        });
        findViewById(R.id.btn_save)
                .setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                doSave();
                            }
                        });

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

        setupSpinner();

        if (existing != null) {
            inputMagnet.setText(existing.magnet);
            inputTitle.setText(existing.title);
            pickedTags.clear();
            pickedTags.addAll(existing.tags);
            refreshTagField();
            inputNote.setText(existing.note);
            inputSource.setText(existing.source);
            selectCategory(existing.categoryId);
        } else {
            // 新建：默认存进"你当前所在的那个文件夹"
            String def = getIntent().getStringExtra(EXTRA_DEFAULT_CATEGORY);
            if (def != null) {
                selectCategory(def);
            }
        }

        // 标签：点击弹出选择器，而不是让你手打
        inputTags.setFocusable(false);
        inputTags.setCursorVisible(false);
        inputTags.setLongClickable(false);
        inputTags.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        TagPicker.show(
                                EditActivity.this,
                                "选择标签",
                                pickedTags,
                                new TagPicker.Listener() {
                                    @Override
                                    public void onPicked(List<String> tags) {
                                        pickedTags.clear();
                                        pickedTags.addAll(tags);
                                        refreshTagField();
                                    }
                                });
                    }
                });

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
            java.util.ArrayList<String> restored =
                    savedInstanceState.getStringArrayList("pickedTags");
            if (restored != null) pickedTags.addAll(restored);
            refreshTagField();
            baseline = savedInstanceState.getString("baseline");
        }
        if (baseline == null) baseline = snapshot();
        ready = true;
        updateHint(inputMagnet.getText().toString());
    }

    private void setupSpinner() {
        cats.clear();
        cats.addAll(Repo.categories(this, false));
        catIds.clear();
        List<String> names = new ArrayList<>();
        names.add("未分类");
        catIds.add(null);
        for (Category c : cats) {
            // 分类树之后用"父 / 子"全名，避免同名分不清
            names.add(pathName(c));
            catIds.add(c.id);
        }
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void selectCategory(String categoryId) {
        if (categoryId == null) {
            spinner.setSelection(0);
            return;
        }
        for (int i = 0; i < catIds.size(); i++) {
            if (categoryId.equals(catIds.get(i))) {
                spinner.setSelection(i);
                return;
            }
        }
        spinner.setSelection(0);
    }

    /** "父 / 子" 形式全名。 */
    private String pathName(Category c) {
        List<Category> path = Repo.categoryPath(this, c.id);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(path.get(i).name);
        }
        return sb.length() == 0 ? c.name : sb.toString();
    }

    private void updateHint(String text) {
        List<String> links = MagnetParser.extractAll(text);
        if (links.isEmpty()) {
            parseHint.setText("粘贴链接后会自动检查格式");
            parseHint.setTextColor(getResources().getColor(R.color.c_text2));
            return;
        }
        if (links.size() > 1) {
            parseHint.setText("检测到多条链接，请使用收藏页的「批量导入」");
            parseHint.setTextColor(getResources().getColor(R.color.c_star));
            return;
        }
        boolean valid = MagnetParser.isValid(links.get(0));
        if (!valid) {
            parseHint.setText("链接格式不完整，请检查是否复制完整");
            parseHint.setTextColor(getResources().getColor(R.color.c_star));
        } else {
            parseHint.setText(
                    MagnetParser.isEd2k(links.get(0)) ? "已识别 ED2K（电驴）文件链接，可以保存" : "已识别有效磁力链接，可以保存");
            parseHint.setTextColor(getResources().getColor(R.color.c_accent));
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
            inputMagnet.setError("这里一次保存一条，多条链接请使用「批量导入」");
            inputMagnet.requestFocus();
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
        int pos = spinner.getSelectedItemPosition();
        e.categoryId = (pos >= 0 && pos < catIds.size()) ? catIds.get(pos) : null;
        e.tags = new ArrayList<>(pickedTags);

        Entry dup = Repo.findDuplicate(this, e.normalized, e.magnet, e.id);
        if (dup != null) {
            new AlertDialog.Builder(this)
                    .setTitle("疑似重复")
                    .setMessage("已存在相同链接的收藏：\n\n" + dup.displayTitle() + "\n\n仍要保存吗？")
                    .setPositiveButton(
                            "仍然保存",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    persist(e);
                                }
                            })
                    .setNegativeButton("返回修改", null)
                    .show();
            return;
        }
        persist(e);
    }

    private void refreshTagField() {
        if (pickedTags.isEmpty()) {
            inputTags.setText("");
            inputTags.setHint("点击选择标签");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pickedTags.size(); i++) {
                if (i > 0) {
                    sb.append("，");
                }
                sb.append(pickedTags.get(i));
            }
            inputTags.setText(sb.toString());
        }
    }

    private String snapshot() {
        int position = spinner.getSelectedItemPosition();
        return inputMagnet.getText()
                + "\u0002"
                + inputTitle.getText()
                + "\u0002"
                + inputSource.getText()
                + "\u0002"
                + inputNote.getText()
                + "\u0002"
                + position
                + "\u0002"
                + pickedTags.toString();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
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
        new AlertDialog.Builder(this)
                .setTitle("保留这次修改？")
                .setMessage("还有未保存的内容。")
                .setNegativeButton("继续编辑", null)
                .setNeutralButton("放弃修改", (d, w) -> finish())
                .setPositiveButton("保存", (d, w) -> doSave())
                .show();
    }

    private void persist(Entry e) {
        saving = true;
        int previousOrientation = getRequestedOrientation();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
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
                                    setRequestedOrientation(previousOrientation);
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
