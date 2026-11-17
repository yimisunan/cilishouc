package com.magnetvault.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 备份载荷的构建 / 解析 / 恢复执行（不含 HTTP，纯本地）。 */
public final class BackupManager {

    public static final int SCHEMA_VERSION = 1;
    public static final int PAYLOAD_VERSION = 1;
    public static final String APP_VERSION = "2.2.1";
    public static final String EXT = ".mjbf";

    private BackupManager() {}

    // ==================== 导出 ====================

    public static String buildPayloadJson(Context ctx) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("deviceId", ThemePrefs.deviceId(ctx));
        root.put("appVersion", APP_VERSION);

        JSONArray cats = new JSONArray();
        for (Category c : Repo.categories(ctx, false)) {
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("parentId", c.parentId == null ? JSONObject.NULL : c.parentId);
            o.put("name", c.name);
            o.put("color", c.color);
            o.put("sortOrder", c.sortOrder);
            cats.put(o);
        }
        root.put("categories", cats);

        // 顺带把坚果云连接配置一起打包：换机/重装后填完备份密码，配置也回来了
        JSONObject dav = new JSONObject();
        dav.put("url", ThemePrefs.davUrl(ctx));
        dav.put("user", ThemePrefs.davUser(ctx));
        dav.put("dir", ThemePrefs.davDir(ctx));
        String davPwd = SecretStore.get(ctx, ThemePrefs.SECRET_DAV_PASSWORD);
        dav.put("password", davPwd == null ? JSONObject.NULL : davPwd);
        root.put("dav", dav);

        JSONArray tags = new JSONArray();
        for (String t : Repo.allTagNames(ctx)) {
            tags.put(t);
        }
        root.put("tags", tags);

        JSONArray entries = new JSONArray();
        for (Entry e : Repo.listAll(ctx)) {
            JSONObject o = new JSONObject();
            o.put("id", e.id);
            o.put("title", e.title);
            o.put("magnet", e.magnet);
            o.put("infoHash", e.infoHash == null ? JSONObject.NULL : e.infoHash);
            o.put("categoryId", e.categoryId == null ? JSONObject.NULL : e.categoryId);
            o.put("note", e.note);
            o.put("source", e.source);
            o.put("starred", e.starred);
            o.put("createdAt", e.createdAt);
            o.put("updatedAt", e.updatedAt);
            o.put("deletedAt", e.deletedAt == null ? JSONObject.NULL : e.deletedAt);
            JSONArray tagArr = new JSONArray();
            for (String t : e.tags) {
                tagArr.put(t);
            }
            o.put("tags", tagArr);
            entries.put(o);
        }
        root.put("entries", entries);
        root.put("tombstones", new JSONArray());
        return root.toString();
    }

    public static byte[] buildEncrypted(Context ctx, char[] password) throws Exception {
        String json = buildPayloadJson(ctx);
        return BackupCrypto.encrypt(
                password, json.getBytes(StandardCharsets.UTF_8), APP_VERSION, PAYLOAD_VERSION);
    }

    // ==================== 摘要 ====================

    public static class Summary {
        public int entries;
        public int trashed;
        public int categories;
        public int tags;
        public long exportedAt;
        public String deviceId = "";
        public String appVersion = "";

        public String describe() {
            return "条目 "
                    + entries
                    + " 条（其中回收站 "
                    + trashed
                    + " 条）\n"
                    + "分类 "
                    + categories
                    + " 个 · 标签 "
                    + tags
                    + " 个\n"
                    + "导出时间 "
                    + Ui.time(exportedAt)
                    + "\n"
                    + "来源设备 "
                    + (deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId)
                    + " · 应用版本 "
                    + appVersion;
        }
    }

    public static Summary summarize(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        Summary s = new Summary();
        s.exportedAt = root.optLong("exportedAt", 0);
        s.deviceId = root.optString("deviceId", "");
        s.appVersion = root.optString("appVersion", "");
        JSONArray entries = root.optJSONArray("entries");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject o = entries.getJSONObject(i);
                s.entries++;
                if (!o.isNull("deletedAt")) {
                    s.trashed++;
                }
            }
        }
        JSONArray cats = root.optJSONArray("categories");
        s.categories = cats == null ? 0 : cats.length();
        JSONArray tags = root.optJSONArray("tags");
        s.tags = tags == null ? 0 : tags.length();
        return s;
    }

    // ==================== 恢复 ====================

    public static class RestoreResult {
        public int added;
        public int updated;
        public int skipped;
        public int unchanged;
        public int conflict;
        public boolean replaceMode;

        /** 备份里带回了坚果云配置并已写入本机 */
        public boolean davRestored;

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(replaceMode ? "完整替换完成\n" : "合并完成\n");
            sb.append("新增 ").append(added).append(" 条 · 覆盖更新 ").append(updated).append(" 条\n");
            sb.append("未变化 ")
                    .append(unchanged)
                    .append(" 条 · 跳过（本地更新）")
                    .append(skipped)
                    .append(" 条");
            if (conflict > 0) {
                sb.append("\n时间戳相同但内容不同：").append(conflict).append(" 条（保留本地版本）");
            }
            if (davRestored) {
                sb.append("\n已一并恢复坚果云连接配置");
            }
            return sb.toString();
        }
    }

    /** 内容是否完全一致（用于判断"未变化"，避免把重复恢复算成冲突）。 */
    private static boolean sameContent(Entry a, Entry b) {
        if (!eq(a.title, b.title) || !eq(a.magnet, b.magnet)) {
            return false;
        }
        if (!eq(a.infoHash, b.infoHash) || !eq(a.categoryId, b.categoryId)) {
            return false;
        }
        if (!eq(a.note, b.note) || !eq(a.source, b.source)) {
            return false;
        }
        if (a.starred != b.starred) {
            return false;
        }
        if (a.deletedAt == null ? b.deletedAt != null : !a.deletedAt.equals(b.deletedAt)) {
            return false;
        }
        java.util.Set<String> ta = new java.util.HashSet<>(a.tags);
        java.util.Set<String> tb = new java.util.HashSet<>(b.tags);
        return ta.equals(tb);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static RestoreResult restore(Context ctx, String json, boolean replace)
            throws JSONException {
        android.database.sqlite.SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            RestoreResult result = restoreInside(ctx, json, replace);
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    private static RestoreResult restoreInside(Context ctx, String json, boolean replace)
            throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schemaVersion", 1) > SCHEMA_VERSION)
            throw new JSONException("备份版本较新，请先升级应用");
        RestoreResult result = new RestoreResult();
        result.replaceMode = replace;
        Map<String, String> catMap = new HashMap<>();
        JSONArray categories = root.optJSONArray("categories");
        List<JSONObject> pending = new ArrayList<>();
        if (categories != null)
            for (int i = 0; i < categories.length(); i++) pending.add(categories.getJSONObject(i));
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (java.util.Iterator<JSONObject> it = pending.iterator(); it.hasNext(); ) {
                JSONObject object = it.next();
                String bid = object.optString("id", "");
                String name = object.optString("name", "").trim();
                if (bid.isEmpty() || name.isEmpty()) {
                    it.remove();
                    progress = true;
                    continue;
                }
                String parentBid =
                        object.isNull("parentId") ? null : object.optString("parentId", null);
                if (parentBid != null && !catMap.containsKey(parentBid)) continue;
                String parent = parentBid == null ? null : catMap.get(parentBid);
                String localId = null;
                List<Category> local = Repo.categories(ctx, false);
                for (Category c : local)
                    if (c.id.equals(bid)) {
                        localId = c.id;
                        break;
                    }
                if (localId == null)
                    for (Category c : local)
                        if ((parent == null ? c.parentId == null : parent.equals(c.parentId))
                                && c.name.equalsIgnoreCase(name)) {
                            localId = c.id;
                            break;
                        }
                int color = object.optInt("color", Ui.COLORS[0]);
                if (localId == null) {
                    Repo.insertCategoryRaw(ctx, bid, name, color, parent);
                    localId = bid;
                } else if (localId.equals(bid)) {
                    // Preserve same-id identity; avoid violating the destination unique-name index.
                    String moveError = Repo.setCategoryParent(ctx, localId, parent);
                    if (moveError == null) Repo.setCategoryMeta(ctx, localId, name, color);
                }
                catMap.put(bid, localId);
                it.remove();
                progress = true;
            }
            if (!progress) throw new JSONException("备份中的分类层级无效或存在循环，未修改本机收藏");
        }
        JSONArray dictionary = root.optJSONArray("tags");
        if (dictionary != null) {
            android.database.sqlite.SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
            for (int i = 0; i < dictionary.length(); i++) {
                String name = dictionary.optString(i, "").trim();
                if (name.isEmpty()) continue;
                android.content.ContentValues values = new android.content.ContentValues();
                values.put("id", java.util.UUID.randomUUID().toString());
                values.put("name", name);
                db.insertWithOnConflict(
                        "tags",
                        null,
                        values,
                        android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
            }
        }

        // 2) 条目
        Map<String, Entry> localById = new HashMap<>();
        for (Entry e : Repo.listAll(ctx)) {
            localById.put(e.id, e);
        }
        if (replace) {
            Repo.clearAllEntries(ctx);
        }

        JSONArray entries = root.optJSONArray("entries");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject o = entries.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.optString("id", "");
                if (e.id.length() == 0) {
                    continue;
                }
                e.title = o.optString("title", "");
                e.magnet = o.optString("magnet", "");
                if (e.magnet.length() == 0) {
                    continue;
                }
                e.normalized = MagnetParser.normalize(e.magnet);
                e.infoHash =
                        o.isNull("infoHash")
                                ? MagnetParser.infoHash(e.magnet)
                                : o.optString("infoHash", null);
                String rawCat = o.isNull("categoryId") ? null : o.optString("categoryId", null);
                e.categoryId = rawCat == null ? null : catMap.get(rawCat);
                e.note = o.optString("note", "");
                e.source = o.optString("source", "");
                e.starred = o.optBoolean("starred", false);
                e.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                e.updatedAt = o.optLong("updatedAt", e.createdAt);
                e.deletedAt = o.isNull("deletedAt") ? null : o.optLong("deletedAt", 0);
                JSONArray tagArr = o.optJSONArray("tags");
                if (tagArr != null) {
                    for (int j = 0; j < tagArr.length(); j++) {
                        String t = tagArr.optString(j, "").trim();
                        if (t.length() > 0) {
                            e.tags.add(t);
                        }
                    }
                }

                Entry local = replace ? null : localById.get(e.id);
                if (local == null) {
                    Repo.restoreEntry(ctx, e);
                    result.added++;
                } else if (e.updatedAt > local.updatedAt) {
                    Repo.restoreEntry(ctx, e);
                    result.updated++;
                } else if (e.updatedAt == local.updatedAt) {
                    if (sameContent(local, e)) {
                        result.unchanged++;
                    } else {
                        result.conflict++;
                    }
                } else {
                    result.skipped++;
                }
            }
        }

        // 3) 坚果云配置：仅当本机还没配置时才写入（不覆盖你现有的账号）
        JSONObject dav = root.optJSONObject("dav");
        if (dav != null && !ThemePrefs.isDavConfigured(ctx)) {
            String url = dav.optString("url", "");
            String user = dav.optString("user", "");
            String dir = dav.optString("dir", "");
            String pwd = dav.isNull("password") ? null : dav.optString("password", null);
            if (user.length() > 0 && pwd != null && pwd.length() > 0) {
                ThemePrefs.setDav(ctx, url, user, dir);
                SecretStore.put(ctx, ThemePrefs.SECRET_DAV_PASSWORD, pwd);
                result.davRestored = true;
            }
        }
        return result;
    }

    // ==================== 本地回滚快照 ====================

    public static File snapshotDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "rollback");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File saveSnapshot(Context ctx, char[] password) throws Exception {
        File dir = snapshotDir(ctx);
        File out = new File(dir, "snapshot-" + System.currentTimeMillis() + EXT);
        byte[] data = buildEncrypted(ctx, password);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
        trimSnapshots(dir, 2);
        return out;
    }

    public static File latestSnapshot(Context ctx) {
        File dir = snapshotDir(ctx);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        File best = null;
        for (File f : files) {
            if (best == null || f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best;
    }

    public static void deleteSnapshot(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }

    private static void trimSnapshots(File dir, int keep) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keep) {
            return;
        }
        List<File> list = new ArrayList<>();
        for (File f : files) {
            list.add(f);
        }
        java.util.Collections.sort(
                list,
                new java.util.Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
        for (int i = 0; i < list.size() - keep; i++) {
            list.get(i).delete();
        }
    }

    // ==================== 文件工具 ====================

    public static byte[] readAll(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static String defaultFileName() {
        return "magnetvault-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA)
                        .format(new java.util.Date())
                + EXT;
    }
}
