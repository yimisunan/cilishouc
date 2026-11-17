package com.magnetvault.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Clipboard access and transactional commits for the shared import workflow. */
public final class ClipImporter {
    public static class Result {
        public int added, duplicate, invalid, excluded;
        public String describe() {
            StringBuilder s = new StringBuilder("新增 ").append(added).append(" 条");
            if (duplicate > 0) s.append(" · 重复跳过 ").append(duplicate).append(" 条");
            if (invalid > 0) s.append(" · 无效 ").append(invalid).append(" 条");
            if (excluded > 0) s.append(" · 暂不导入 ").append(excluded).append(" 条");
            return s.toString();
        }
    }
    private ClipImporter() {}

    public static String readClipboard(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(ctx);
            return text == null ? "" : text.toString();
        } catch (Exception e) { return ""; }
    }

    public static List<Entry> parse(String text, Result info) {
        ImportTextParser.Parsed parsed = ImportTextParser.parse(text);
        if (info != null) info.invalid += parsed.invalid;
        return parsed.entries;
    }

    public static ImportDraft preview(Context ctx, String text, String defaultCategoryId) {
        ImportTextParser.Parsed parsed = ImportTextParser.parse(text);
        ImportDraft draft = new ImportDraft();
        draft.invalid = parsed.invalid;
        String fallback = defaultCategoryId == null ? "" : Repo.categoryLabel(ctx, defaultCategoryId);
        Set<String> known = activeKeys(ctx), seen = new HashSet<>();
        for (Entry entry : parsed.entries) {
            if (!parsed.explicitCategories.contains(entry.id)) entry.categoryName = ImportTextParser.normalizePath(fallback);
            ImportDraft.Row row = new ImportDraft.Row(entry);
            if (known.contains(entry.normalized)) row.duplicate = "已收藏 · 自动跳过";
            else if (!seen.add(entry.normalized)) row.duplicate = "本批重复 · 自动跳过";
            draft.rows.add(row);
        }
        return draft;
    }

    public static Result importFromText(Context ctx, String text) {
        return importFromText(ctx, text, null);
    }

    public static Result importFromText(Context ctx, String text, String defaultCategoryId) {
        return importDraft(ctx, preview(ctx, text, defaultCategoryId));
    }

    /** All category creation, entry writes and tag associations commit or roll back together. */
    public static Result importDraft(Context ctx, ImportDraft draft) {
        Result result = new Result();
        result.invalid = draft.invalid;
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            Set<String> known = activeKeys(ctx), seen = new HashSet<>();
            Map<String, String> categories = categoryIndex(ctx);
            for (ImportDraft.Row row : draft.rows) {
                if (!row.editable()) { result.duplicate++; continue; }
                if (!row.included) { result.excluded++; continue; }
                Entry entry = row.entry;
                if (!MagnetParser.isValid(entry.magnet)) throw new IllegalArgumentException("链接格式不正确");
                String validation = ImportDraft.validate(entry);
                if (validation != null) throw new IllegalArgumentException(validation);
                entry.normalized = MagnetParser.normalize(entry.magnet);
                entry.infoHash = MagnetParser.infoHash(entry.magnet);
                if (known.contains(entry.normalized) || !seen.add(entry.normalized)
                        || Repo.findDuplicate(ctx, entry.normalized, entry.magnet, null) != null) {
                    result.duplicate++;
                    continue;
                }
                String categoryId = ensurePath(db, categories, entry.categoryName);
                // Keep drafts unchanged on rollback, including their transient category IDs.
                String oldCategory = entry.categoryId;
                entry.categoryId = categoryId;
                try { Repo.save(ctx, entry); } finally { entry.categoryId = oldCategory; }
                result.added++;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        try {
            Repo.addAudit(ctx, "IMPORT_CLIP", result.added > 0 ? "SUCCESS" : "SKIPPED", result.describe(), result.added);
        } catch (Exception ignored) { /* An audit failure must not turn a committed batch into an error. */ }
        return result;
    }

    private static Set<String> activeKeys(Context ctx) {
        Set<String> keys = new HashSet<>();
        Cursor c = Db.get(ctx).getReadableDatabase().rawQuery(
                "SELECT magnetNormalized, magnet FROM entries WHERE deletedAt IS NULL", null);
        try {
            while (c.moveToNext()) {
                if (c.getString(0) != null) keys.add(c.getString(0));
                keys.add(MagnetParser.normalize(c.getString(1)));
            }
        } finally { c.close(); }
        return keys;
    }

    /** Resolve category labels in memory instead of querying the tree for each imported row. */
    public static Map<String, String> categoryIndex(Context ctx) {
        List<Category> all = Repo.categories(ctx, false);
        Map<String, Category> byId = new HashMap<>();
        for (Category c : all) byId.put(c.id, c);
        Map<String, String> index = new LinkedHashMap<>();
        for (Category c : all) {
            List<String> names = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Category at = c;
            while (at != null && seen.add(at.id) && names.size() < 32) {
                names.add(0, at.name);
                at = byId.get(at.parentId);
            }
            index.put(ImportTextParser.join(names, " / "), c.id);
        }
        return index;
    }

    private static String ensurePath(SQLiteDatabase db, Map<String, String> index, String raw) {
        String path = ImportTextParser.normalizePath(raw);
        if (path.isEmpty()) return null;
        for (Map.Entry<String, String> existing : index.entrySet())
            if (existing.getKey().equalsIgnoreCase(path)) return existing.getValue();
        String parent = null, prefix = "";
        for (String name : path.split(" / ")) {
            prefix = prefix.isEmpty() ? name : prefix + " / " + name;
            String id = null;
            for (Map.Entry<String, String> item : index.entrySet())
                if (item.getKey().equalsIgnoreCase(prefix)) { id = item.getValue(); break; }
            if (id == null) {
                id = UUID.randomUUID().toString();
                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("name", name);
                values.put("parentId", parent);
                values.put("color", 0xff607d8b);
                values.put("sortOrder", index.size());
                values.put("createdAt", System.currentTimeMillis());
                values.put("updatedAt", System.currentTimeMillis());
                db.insertOrThrow("categories", null, values);
                index.put(prefix, id);
            }
            parent = id;
        }
        return parent;
    }
}