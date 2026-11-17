package com.magnetvault.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 回收站：恢复、永久删除、清空。 */
public class TrashActivity extends AppActivity {

    private final List<Entry> data = new ArrayList<>();
    private TrashAdapter adapter;
    private View emptyView;
    private TextView subtitle;

    private static final class TrashHolder {
        final View card;
        final TextView title;
        final TextView sub;
        final View restore;

        TrashHolder(View root) {
            card = root.findViewById(R.id.trash_card);
            title = root.findViewById(R.id.trash_title);
            sub = root.findViewById(R.id.trash_sub);
            restore = root.findViewById(R.id.btn_restore);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);

        emptyView = findViewById(R.id.empty);
        subtitle = findViewById(R.id.subtitle);
        ListView list = findViewById(R.id.list);
        adapter = new TrashAdapter();
        list.setAdapter(adapter);

        View btnClear = findViewById(R.id.btn_clear);
        Design.tactile(btnClear);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnClear.setOnClickListener(
                v -> {
                    if (data.isEmpty()) {
                        Ui.toast(TrashActivity.this, "回收站已经是空的");
                        return;
                    }
                    Ui.confirmSheet(
                            TrashActivity.this,
                            "清空回收站",
                            "将永久删除全部 " + data.size() + " 条收藏，删除后无法恢复。",
                            "永久删除",
                            true,
                            () ->
                                    load(
                                            () -> {
                                                Repo.emptyTrash(TrashActivity.this);
                                                return true;
                                            },
                                            ok -> {
                                                Ui.toast(TrashActivity.this, "回收站已清空");
                                                reload();
                                            }));
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void itemActions(final Entry e) {
        Ui.menuSheet(
                TrashActivity.this,
                e.displayTitle(),
                new String[] {"恢复到列表", "永久删除"},
                new int[] {R.drawable.ic_restore, R.drawable.ic_trash},
                (d, which) -> {
                    if (which == 0) {
                        restoreEntry(e);
                    } else {
                        Ui.confirmSheet(
                                TrashActivity.this,
                                "永久删除这条收藏？",
                                e.displayTitle() + "\n此操作无法恢复。",
                                "永久删除",
                                true,
                                () ->
                                        load(
                                                () -> {
                                                    Repo.deleteForever(TrashActivity.this, e.id);
                                                    return true;
                                                },
                                                ok -> {
                                                    reload();
                                                    Ui.toast(TrashActivity.this, "已永久删除");
                                                }));
                    }
                });
    }

    private void restoreEntry(final Entry e) {
        load(
                () -> {
                    Repo.restore(TrashActivity.this, e.id);
                    return true;
                },
                ok -> {
                    Ui.toast(TrashActivity.this, "已恢复");
                    reload();
                });
    }

    private void reload() {
        load(
                () -> Repo.listTrash(this),
                entries -> {
                    data.clear();
                    data.addAll(entries);
                    adapter.notifyDataSetChanged();
                    emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
                    int keepDays = ThemePrefs.trashKeepDays(this);
                    String keepText = keepDays > 0 ? "超过 " + keepDays + " 天自动清理" : "不会自动清理";
                    subtitle.setText(
                            data.isEmpty()
                                    ? "删除的条目会保留在这里，" + keepText
                                    : "共 " + data.size() + " 条 · " + keepText);
                    View clear = findViewById(R.id.btn_clear);
                    clear.setEnabled(!data.isEmpty());
                    clear.setAlpha(data.isEmpty() ? 0.45f : 1f);
                });
    }

    private class TrashAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TrashHolder h;
            if (convertView == null || !(convertView.getTag() instanceof TrashHolder)) {
                convertView =
                        LayoutInflater.from(TrashActivity.this)
                                .inflate(R.layout.item_trash, parent, false);
                h = new TrashHolder(convertView);
                Design.tactile(h.card);
                Design.tactile(h.restore);
                convertView.setTag(h);
            } else {
                h = (TrashHolder) convertView.getTag();
            }
            final Entry e = data.get(position);

            h.title.setText(e.displayTitle());
            long deletedAt = e.deletedAt == null ? 0 : e.deletedAt;
            int keepDays = ThemePrefs.trashKeepDays(TrashActivity.this);
            long daysLeft =
                    keepDays > 0
                            ? keepDays
                                    - (System.currentTimeMillis() - deletedAt)
                                            / (24L * 60 * 60 * 1000)
                            : -1;
            if (keepDays > 0 && daysLeft < 0) {
                daysLeft = 0;
            }
            h.sub.setText(
                    "删除于 "
                            + Ui.time(deletedAt)
                            + (keepDays > 0 ? " · 还剩 " + daysLeft + " 天自动清理" : " · 不会自动清理"));
            h.card.setOnClickListener(v -> itemActions(e));
            h.restore.setOnClickListener(v -> restoreEntry(e));
            return convertView;
        }
    }
}
