package com.magnetvault.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);

        emptyView = findViewById(R.id.empty);
        subtitle = findViewById(R.id.subtitle);
        ListView list = findViewById(R.id.list);
        adapter = new TrashAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_clear)
                .setOnClickListener(
                        v -> {
                            if (data.isEmpty()) {
                                Ui.toast(TrashActivity.this, "回收站已经是空的");
                                return;
                            }
                            new AlertDialog.Builder(TrashActivity.this)
                                    .setTitle("清空回收站")
                                    .setMessage("将永久删除 " + data.size() + " 条收藏，无法恢复。")
                                    .setPositiveButton(
                                            "永久删除",
                                            (d, which) -> {
                                                Repo.emptyTrash(TrashActivity.this);
                                                reload();
                                            })
                                    .setNegativeButton("取消", null)
                                    .show();
                        });

        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    final Entry e = data.get(position);
                    Ui.menuSheet(
                            TrashActivity.this,
                            e.displayTitle(),
                            new String[] {"恢复到列表", "永久删除"},
                            new int[] {R.drawable.ic_restore, R.drawable.ic_trash},
                            (d, which) -> {
                                if (which == 0) {
                                    Repo.restore(TrashActivity.this, e.id);
                                    Ui.toast(TrashActivity.this, "已恢复");
                                    reload();
                                } else {
                                    new AlertDialog.Builder(TrashActivity.this)
                                            .setTitle("永久删除这条收藏？")
                                            .setMessage(e.displayTitle() + "\n此操作无法恢复。")
                                            .setNegativeButton("取消", null)
                                            .setPositiveButton(
                                                    "永久删除",
                                                    (confirm, index) ->
                                                            load(
                                                                    () -> {
                                                                        Repo.deleteForever(
                                                                                TrashActivity.this,
                                                                                e.id);
                                                                        return true;
                                                                    },
                                                                    ok -> {
                                                                        reload();
                                                                        Ui.toast(
                                                                                TrashActivity.this,
                                                                                "已永久删除");
                                                                    }))
                                            .show();
                                }
                            });
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
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
            if (convertView == null) {
                convertView =
                        LayoutInflater.from(TrashActivity.this)
                                .inflate(R.layout.item_trash, parent, false);
            }
            final Entry e = data.get(position);
            TextView title = convertView.findViewById(R.id.trash_title);
            TextView sub = convertView.findViewById(R.id.trash_sub);
            View restore = convertView.findViewById(R.id.btn_restore);

            title.setText(e.displayTitle());
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
            sub.setText(
                    "删除于 "
                            + Ui.time(deletedAt)
                            + (keepDays > 0 ? " · 还剩 " + daysLeft + " 天自动清理" : " · 不会自动清理"));
            restore.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Repo.restore(TrashActivity.this, e.id);
                            Ui.toast(TrashActivity.this, "已恢复");
                            reload();
                        }
                    });
            return convertView;
        }
    }
}
