package com.magnetvault.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 数据访问层：所有 SQL 集中在这里。 */
public final class Repo {

    public static final int SORT_ADDED = 0;
    public static final int SORT_UPDATED = 1;
    public static final int SORT_TITLE = 2;

    private Repo() {}

    // ==================== 查询 ====================

    public static List<Entry> listActive(
            Context ctx,
            String categoryId,
            boolean starredOnly,
            boolean uncategorizedOnly,
            String q,
            int sort) {
        return query(ctx, 0, categoryId, starredOnly, uncategorizedOnly, q, sort);
    }

    public static List<Entry> listTrash(Context ctx) {
        return query(ctx, 1, null, false, false, null, SORT_UPDATED);
    }

    /** 全部条目（含回收站），用于备份导出。 */
    public static List<Entry> listAll(Context ctx) {
        return query(ctx, 2, null, false, false, null, SORT_ADDED);
    }

    /** mode: 0=仅未删除 1=仅回收站 2=全部 */
    private static List<Entry> query(
            Context ctx,
            int mode,
            String categoryId,
            boolean starredOnly,
            boolean uncategorizedOnly,
            String q,
            int sort) {
        SQLiteDatabase db = Db.get(ctx).getReadableDatabase();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT e.*, IFNULL(c.name,'') AS catName, IFNULL(c.color,0) AS catColor,")
                .append(" (SELECT GROUP_CONCAT(t.name, char(1)) FROM entry_tags et")
                .append("   JOIN tags t ON t.id = et.tagId WHERE et.entryId = e.id) AS tagCsv")
                .append(" FROM entries e LEFT JOIN categories c ON c.id = e.categoryId")
                .append(
                        mode == 0
                                ? " WHERE e.deletedAt IS NULL"
                                : mode == 1 ? " WHERE e.deletedAt IS NOT NULL" : " WHERE 1=1");

        List<String> args = new ArrayList<>();
        if (categoryId != null && categoryId.length() > 0) {
            sql.append(" AND e.categoryId = ?");
            args.add(categoryId);
        }
        if (uncategorizedOnly) {
            sql.append(" AND e.categoryId IS NULL");
        }
        if (starredOnly) {
            sql.append(" AND e.starred = 1");
        }
        if (q != null && q.trim().length() > 0) {
            String like = "%" + q.trim() + "%";
            sql.append(" AND (e.title LIKE ? OR e.magnet LIKE ? OR IFNULL(e.infoHash,'') LIKE ?")
                    .append(" OR e.note LIKE ? OR IFNULL(c.name,'') LIKE ?")
                    .append(
                            " OR EXISTS(SELECT 1 FROM entry_tags et2 JOIN tags t2 ON t2.id ="
                                    + " et2.tagId")
                    .append(" WHERE et2.entryId = e.id AND t2.name LIKE ?))");
            for (int i = 0; i < 6; i++) {
                args.add(like);
            }
        }

        if (mode == 1) {
            sql.append(" ORDER BY e.deletedAt DESC");
        } else if (sort == SORT_UPDATED) {
            sql.append(" ORDER BY e.updatedAt DESC, e.id");
        } else if (sort == SORT_TITLE) {
            sql.append(" ORDER BY (CASE WHEN e.title = '' THEN 1 ELSE 0 END),")
                    .append(" e.title COLLATE NOCASE ASC, e.id");
        } else {
            sql.append(" ORDER BY e.createdAt DESC, e.id");
        }

        List<Entry> out = new ArrayList<>();
        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                out.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public static Entry byId(Context ctx, String id) {
        SQLiteDatabase db = Db.get(ctx).getReadableDatabase();
        Cursor c =
                db.rawQuery(
                        "SELECT e.*, IFNULL(c.name,'') AS catName, IFNULL(c.color,0) AS catColor,"
                            + " (SELECT GROUP_CONCAT(t.name, char(1)) FROM entry_tags et   JOIN"
                            + " tags t ON t.id = et.tagId WHERE et.entryId = e.id) AS tagCsv FROM"
                            + " entries e LEFT JOIN categories c ON c.id = e.categoryId WHERE e.id"
                            + " = ?",
                        new String[] {id});
        try {
            return c.moveToFirst() ? fromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    /** 重复检测：优先规范化链接，其次原始链接。 */
    public static Entry findDuplicate(
            Context ctx, String normalized, String rawMagnet, String excludeId) {
        SQLiteDatabase db = Db.get(ctx).getReadableDatabase();
        String sql =
                "SELECT e.*, '' AS catName, 0 AS catColor, '' AS tagCsv FROM entries e"
                        + " WHERE e.deletedAt IS NULL AND (e.magnetNormalized = ? OR e.magnet = ?)"
                        + (excludeId != null ? " AND e.id <> ?" : "")
                        + " LIMIT 1";
        String[] args =
                excludeId != null
                        ? new String[] {normalized, rawMagnet, excludeId}
                        : new String[] {normalized, rawMagnet};
        Cursor c = db.rawQuery(sql, args);
        try {
            return c.moveToFirst() ? fromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    private static Entry fromCursor(Cursor c) {
        Entry e = new Entry();
        e.id = c.getString(c.getColumnIndexOrThrow("id"));
        e.title = c.getString(c.getColumnIndexOrThrow("title"));
        e.magnet = c.getString(c.getColumnIndexOrThrow("magnet"));
        e.normalized = c.getString(c.getColumnIndexOrThrow("magnetNormalized"));
        int hi = c.getColumnIndex("infoHash");
        e.infoHash = c.isNull(hi) ? null : c.getString(hi);
        int ci = c.getColumnIndex("categoryId");
        e.categoryId = c.isNull(ci) ? null : c.getString(ci);
        e.note = c.getString(c.getColumnIndexOrThrow("note"));
        e.source = c.getString(c.getColumnIndexOrThrow("source"));
        e.starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1;
        e.createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt"));
        e.updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt"));
        int di = c.getColumnIndex("deletedAt");
        e.deletedAt = c.isNull(di) ? null : c.getLong(di);
        int cni = c.getColumnIndex("catName");
        if (cni >= 0 && !c.isNull(cni)) {
            e.categoryName = c.getString(cni);
        }
        int cci = c.getColumnIndex("catColor");
        if (cci >= 0) {
            e.categoryColor = c.getInt(cci);
        }
        int tci = c.getColumnIndex("tagCsv");
        if (tci >= 0 && !c.isNull(tci)) {
            String csv = c.getString(tci);
            if (csv != null && csv.length() > 0) {
                for (String t : csv.split("\u0001")) {
                    if (t != null && t.trim().length() > 0) {
                        e.tags.add(t.trim());
                    }
                }
            }
        }
        return e;
    }

    // ==================== 写入 ====================

    public static void save(Context ctx, Entry e) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        long now = System.currentTimeMillis();
        boolean isNew = e.createdAt == 0;
        if (isNew) {
            e.createdAt = now;
        }
        e.updatedAt = now;

        ContentValues cv = new ContentValues();
        cv.put("id", e.id);
        cv.put("title", e.title == null ? "" : e.title);
        cv.put("magnet", e.magnet);
        cv.put("magnetNormalized", e.normalized);
        cv.put("infoHash", e.infoHash);
        cv.put("categoryId", e.categoryId);
        cv.put("note", e.note == null ? "" : e.note);
        cv.put("source", e.source == null ? "" : e.source);
        cv.put("starred", e.starred ? 1 : 0);
        cv.put("createdAt", e.createdAt);
        cv.put("updatedAt", e.updatedAt);
        if (e.deletedAt != null) {
            cv.put("deletedAt", e.deletedAt);
        }

        db.beginTransaction();
        try {
            if (db.insertWithOnConflict("entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new android.database.SQLException("收藏写入失败");
            syncTags(db, e);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        note(ctx, isNew ? "ADD" : "EDIT", "SUCCESS", (isNew ? "新增：" : "编辑：") + shortTitle(e.title));
    }

    /** 审计（任何异常都不影响主流程）。 */
    private static void note(Context ctx, String type, String result, String msg) {
        try {
            addAudit(ctx, type, result, msg, countActive(ctx) + countTrash(ctx));
        } catch (Throwable ignored) {
            // 审计失败绝不能影响用户操作
        }
    }

    private static String shortTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.length() == 0) {
            return "(未命名)";
        }
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }

    /** 按 id 取标题，用于删除类审计。 */
    private static String titleById(Context ctx, String id) {
        try {
            Entry e = byId(ctx, id);
            return e == null ? id : shortTitle(e.title);
        } catch (Throwable t) {
            return id;
        }
    }

    private static void syncTags(SQLiteDatabase db, Entry e) {
        db.delete("entry_tags", "entryId = ?", new String[] {e.id});
        if (e.tags == null) {
            return;
        }
        for (String rawName : e.tags) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.length() == 0) {
                continue;
            }
            String tagId = findTagId(db, name);
            if (tagId == null) {
                tagId = UUID.randomUUID().toString();
                ContentValues tv = new ContentValues();
                tv.put("id", tagId);
                tv.put("name", name);
                db.insertWithOnConflict("tags", null, tv, SQLiteDatabase.CONFLICT_IGNORE);
                tagId = findTagId(db, name);
                if (tagId == null) throw new android.database.SQLException("标签写入失败");
            }
            if (tagId != null) {
                ContentValues cv = new ContentValues();
                cv.put("entryId", e.id);
                cv.put("tagId", tagId);
                if (db.insertWithOnConflict("entry_tags", null, cv, SQLiteDatabase.CONFLICT_IGNORE) < 0) {
                    Cursor present = db.rawQuery("SELECT 1 FROM entry_tags WHERE entryId = ? AND tagId = ?", new String[]{e.id, tagId});
                    try { if (!present.moveToFirst()) throw new android.database.SQLException("标签关联写入失败"); }
                    finally { present.close(); }
                }
            }
        }
    }

    private static String findTagId(SQLiteDatabase db, String name) {
        Cursor c =
                db.rawQuery(
                        "SELECT id FROM tags WHERE name = ? COLLATE NOCASE", new String[] {name});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    public static void setStar(Context ctx, String id, boolean star) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("starred", star ? 1 : 0);
        cv.put("updatedAt", System.currentTimeMillis());
        db.update("entries", cv, "id = ?", new String[] {id});
        note(ctx, star ? "STAR" : "UNSTAR", "SUCCESS", shortTitle(titleById(ctx, id)));
    }

    public static void moveCategory(Context ctx, List<String> ids, String categoryId) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            for (String id : ids) {
                ContentValues cv = new ContentValues();
                cv.put("categoryId", categoryId);
                cv.put("updatedAt", now);
                db.update("entries", cv, "id = ?", new String[] {id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        note(ctx, "MOVE_CATEGORY", "SUCCESS", "移动 " + ids.size() + " 条");
    }

    public static void softDelete(Context ctx, String id) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("deletedAt", now);
        cv.put("updatedAt", now);
        db.update("entries", cv, "id = ?", new String[] {id});
        note(ctx, "DELETE", "SUCCESS", shortTitle(titleById(ctx, id)));
    }

    public static void restore(Context ctx, String id) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.putNull("deletedAt");
        cv.put("updatedAt", System.currentTimeMillis());
        db.update("entries", cv, "id = ?", new String[] {id});
        note(ctx, "UNDELETE", "SUCCESS", shortTitle(titleById(ctx, id)));
    }

    public static void deleteForever(Context ctx, String id) {
        String title = shortTitle(titleById(ctx, id));
        Db.get(ctx).getWritableDatabase().delete("entries", "id = ?", new String[] {id});
        note(ctx, "DELETE_FOREVER", "SUCCESS", title);
    }

    public static void emptyTrash(Context ctx) {
        int n = countTrash(ctx);
        Db.get(ctx).getWritableDatabase().delete("entries", "deletedAt IS NOT NULL", null);
        if (n > 0) {
            note(ctx, "EMPTY_TRASH", "SUCCESS", "清空 " + n + " 条");
        }
    }

    /** 清理回收站中超过 ageMs 的条目，返回删除条数。 */
    public static int purgeOlderThan(Context ctx, long ageMs) {
        long limit = System.currentTimeMillis() - ageMs;
        int n =
                Db.get(ctx)
                        .getWritableDatabase()
                        .delete(
                                "entries",
                                "deletedAt IS NOT NULL AND deletedAt < ?",
                                new String[] {String.valueOf(limit)});
        if (n > 0) {
            note(
                    ctx,
                    "PURGE",
                    "SUCCESS",
                    "自动清理 " + n + " 条（超过 " + (ageMs / (24L * 60 * 60 * 1000)) + " 天）");
        }
        return n;
    }

    public static int countActive(Context ctx) {
        return count(Db.get(ctx).getReadableDatabase(), "entries", "deletedAt IS NULL");
    }

    public static int countStarred(Context ctx) {
        return count(
                Db.get(ctx).getReadableDatabase(), "entries", "deletedAt IS NULL AND starred = 1");
    }

    public static int countTrash(Context ctx) {
        return count(Db.get(ctx).getReadableDatabase(), "entries", "deletedAt IS NOT NULL");
    }

    private static int count(SQLiteDatabase db, String table, String where) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    // ==================== 分类 ====================

    public static List<Category> categories(Context ctx, boolean withCount) {
        SQLiteDatabase db = Db.get(ctx).getReadableDatabase();
        String sql =
                "SELECT c.id, c.parentId, c.name, c.color, c.sortOrder, (SELECT COUNT(*) FROM"
                    + " entries e WHERE e.categoryId = c.id AND e.deletedAt IS NULL) AS cnt,"
                    + " (SELECT COUNT(*) FROM categories k WHERE k.parentId = c.id) AS kids FROM"
                    + " categories c ORDER BY c.sortOrder, c.name COLLATE NOCASE";
        List<Category> out = new ArrayList<>();
        Cursor c = db.rawQuery(sql, null);
        try {
            while (c.moveToNext()) {
                Category cat = new Category(c.getString(0), c.getString(2), c.getInt(3));
                cat.parentId = c.isNull(1) ? null : c.getString(1);
                cat.sortOrder = c.getInt(4);
                cat.count = withCount ? c.getInt(5) : 0;
                cat.childCount = c.getInt(6);
                out.add(cat);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 某一层级的直接子分类。 */
    public static List<Category> children(Context ctx, String parentId) {
        List<Category> out = new ArrayList<>();
        for (Category c : categories(ctx, true)) {
            if (parentId == null ? c.isRoot() : parentId.equals(c.parentId)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 从根到该分类的路径（含自身），用于面包屑。 */
    public static List<Category> categoryPath(Context ctx, String id) {
        List<Category> all = categories(ctx, true);
        List<Category> path = new ArrayList<>();
        String cur = id;
        int guard = 0;
        while (cur != null && guard++ < 32) {
            Category found = null;
            for (Category c : all) {
                if (cur.equals(c.id)) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                break;
            }
            path.add(0, found);
            cur = found.parentId;
        }
        return path;
    }

    /** 返回 null 表示成功，否则返回错误提示。 */
    public static String addCategory(Context ctx, String name, int color) {
        return addCategory(ctx, name, color, null);
    }

    /** 新建分类；parentId 为 null 表示根层级。 */
    public static String addCategory(Context ctx, String name, int color, String parentId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() > 80) return "分类名称请控制在 80 字以内";
        if (trimmed.length() == 0) {
            return "分类名不能为空";
        }
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        if (nameExists(db, trimmed, null, parentId)) {
            return "同一层级下已存在同名分类";
        }
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("id", UUID.randomUUID().toString());
        cv.put("parentId", parentId);
        cv.put("name", trimmed);
        cv.put("color", color);
        cv.put("sortOrder", count(db, "categories", "1=1"));
        cv.put("createdAt", now);
        cv.put("updatedAt", now);
        db.insertOrThrow("categories", null, cv);
        note(ctx, "CATEGORY_ADD", "SUCCESS", (parentId == null ? "根层级新增：" : "子分类新增：") + trimmed);
        return null;
    }

    public static String updateCategory(Context ctx, String id, String name, int color) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() > 80) return "分类名称请控制在 80 字以内";
        if (trimmed.length() == 0) {
            return "分类名不能为空";
        }
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        if (nameExists(db, trimmed, id, parentOf(db, id))) {
            return "同一层级下已存在同名分类";
        }
        ContentValues cv = new ContentValues();
        cv.put("name", trimmed);
        cv.put("color", color);
        cv.put("updatedAt", System.currentTimeMillis());
        db.update("categories", cv, "id = ?", new String[] {id});
        note(ctx, "CATEGORY_EDIT", "SUCCESS", "改名/改色：" + trimmed);
        return null;
    }

    private static String parentOf(SQLiteDatabase db, String id) {
        Cursor c = db.rawQuery("SELECT parentId FROM categories WHERE id = ?", new String[] {id});
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /** 移动分类到新父级；返回 null 表示成功。 */
    public static String setCategoryParent(Context ctx, String id, String newParentId) {
        if (id == null) {
            return "分类不存在";
        }
        if (id.equals(newParentId)) {
            return "不能把分类移动到它自己下面";
        }
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        Cursor moving = db.rawQuery("SELECT name FROM categories WHERE id = ?", new String[] {id});
        String movingName;
        try {
            if (!moving.moveToFirst()) return "分类已不存在";
            movingName = moving.getString(0);
        } finally {
            moving.close();
        }
        if (newParentId != null) {
            Cursor target =
                    db.rawQuery(
                            "SELECT id FROM categories WHERE id = ?", new String[] {newParentId});
            try {
                if (!target.moveToFirst()) return "目标分类已不存在";
            } finally {
                target.close();
            }
        }
        if (nameExists(db, movingName, id, newParentId)) return "目标位置已有同名分类，请先修改名称";
        // 防环：新父级不能是本分类的子孙
        String cur = newParentId;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            if (cur.equals(id)) {
                return "不能移动到自己的子分类下面";
            }
            cur = parentOf(db, cur);
        }
        ContentValues cv = new ContentValues();
        if (newParentId == null) {
            cv.putNull("parentId");
        } else {
            cv.put("parentId", newParentId);
        }
        cv.put("updatedAt", System.currentTimeMillis());
        db.update("categories", cv, "id = ?", new String[] {id});
        note(ctx, "CATEGORY_MOVE", "SUCCESS", newParentId == null ? "移到根层级" : "移到子级");
        return null;
    }

    private static boolean nameExists(
            SQLiteDatabase db, String name, String excludeId, String parentId) {
        // 注意：selectionArgs 不能传 null，用空串配合 COALESCE 判等
        String p = parentId == null ? "" : parentId;
        Cursor c =
                db.rawQuery(
                        "SELECT id FROM categories"
                                + " WHERE name = ? COLLATE NOCASE"
                                + " AND COALESCE(parentId,'') = COALESCE(?, '')"
                                + (excludeId != null ? " AND id <> ?" : ""),
                        excludeId != null
                                ? new String[] {name, p, excludeId}
                                : new String[] {name, p});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** 删除分类。migrateToId 非空则把该分类下的条目迁移过去，否则置为未分类。 子分类不会被删掉，而是上提到当前分类的父级（避免误删整棵子树）。 */
    public static void deleteCategory(Context ctx, String id, String migrateToId) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        String parent = parentOf(db, id);
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("categoryId", migrateToId);
            cv.put("updatedAt", System.currentTimeMillis());
            db.update("entries", cv, "categoryId = ?", new String[] {id});
            List<String[]> children = new ArrayList<>();
            Cursor cc =
                    db.rawQuery(
                            "SELECT id,name FROM categories WHERE parentId = ?", new String[] {id});
            try {
                while (cc.moveToNext())
                    children.add(new String[] {cc.getString(0), cc.getString(1)});
            } finally {
                cc.close();
            }
            for (String[] child : children) {
                String name = child[1];
                int suffix = 2;
                while (nameExists(db, name, child[0], parent))
                    name = child[1] + " (" + suffix++ + ")";
                ContentValues kid = new ContentValues();
                kid.put("parentId", parent);
                kid.put("name", name);
                kid.put("updatedAt", System.currentTimeMillis());
                db.update("categories", kid, "id = ?", new String[] {child[0]});
            }
            db.delete("categories", "id = ?", new String[] {id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        note(ctx, "CATEGORY_DELETE", "SUCCESS", "删除分类（子分类上提）");
    }

    public static int countInCategory(Context ctx, String id) {
        return count(
                Db.get(ctx).getReadableDatabase(),
                "entries",
                "categoryId = '" + id.replace("'", "''") + "' AND deletedAt IS NULL");
    }

    /** 未分类（categoryId 为空）的条目数。 */
    public static int countUncategorized(Context ctx) {
        return count(
                Db.get(ctx).getReadableDatabase(),
                "entries",
                "categoryId IS NULL AND deletedAt IS NULL");
    }

    /** 插入分类并保留指定 id（恢复备份用）。 */
    public static void insertCategoryRaw(Context ctx, String id, String name, int color) {
        insertCategoryRaw(ctx, id, name, color, null);
    }

    /** 插入分类并保留指定 id + 父级（恢复备份用，兼容旧备份的 parentId=null）。 */
    public static void insertCategoryRaw(
            Context ctx, String id, String name, int color, String parentId) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("parentId", parentId);
        cv.put("name", name);
        cv.put("color", color);
        cv.put("sortOrder", count(db, "categories", "1=1"));
        cv.put("createdAt", now);
        cv.put("updatedAt", now);
        db.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** 直接改写分类的名称/颜色（恢复备份用；不改变 id、不动父子关系）。 */
    public static boolean setCategoryMeta(Context ctx, String id, String name, int color) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("color", color);
        cv.put("updatedAt", System.currentTimeMillis());
        try {
            return db.update("categories", cv, "id = ?", new String[] {id}) > 0;
        } catch (Exception e) {
            // 同层级已有同名分类时会撞唯一索引，这种情况就保留本地名字
            return false;
        }
    }

    // ==================== 备份/恢复用 ====================

    /** 恢复时写入条目：保留原始 createdAt/updatedAt/deletedAt，不做时间改写。 */
    public static void restoreEntry(Context ctx, Entry e) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", e.id);
        cv.put("title", e.title == null ? "" : e.title);
        cv.put("magnet", e.magnet);
        cv.put("magnetNormalized", e.normalized);
        cv.put("infoHash", e.infoHash);
        cv.put("categoryId", e.categoryId);
        cv.put("note", e.note == null ? "" : e.note);
        cv.put("source", e.source == null ? "" : e.source);
        cv.put("starred", e.starred ? 1 : 0);
        cv.put("createdAt", e.createdAt);
        cv.put("updatedAt", e.updatedAt);
        if (e.deletedAt == null) {
            cv.putNull("deletedAt");
        } else {
            cv.put("deletedAt", e.deletedAt);
        }
        db.beginTransaction();
        try {
            db.insertWithOnConflict("entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            syncTags(db, e);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 完整替换模式：清空全部条目（保留分类与标签字典）。 */
    public static void clearAllEntries(Context ctx) {
        Db.get(ctx).getWritableDatabase().delete("entries", "1=1", null);
    }

    public static List<String> allTagNames(Context ctx) {
        List<String> out = new ArrayList<>();
        Cursor c =
                Db.get(ctx)
                        .getReadableDatabase()
                        .rawQuery("SELECT name FROM tags ORDER BY name COLLATE NOCASE", null);
        try {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ==================== 标签统计 / 批量操作 ====================

    /** 标签使用次数（只统计未删除条目）。 */
    public static Map<String, Integer> tagUsage(Context ctx) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Cursor c =
                Db.get(ctx)
                        .getReadableDatabase()
                        .rawQuery(
                                "SELECT t.name, (SELECT COUNT(*) FROM entry_tags et"
                                        + " JOIN entries e ON e.id = et.entryId"
                                        + " WHERE et.tagId = t.id AND e.deletedAt IS NULL) AS n"
                                        + " FROM tags t",
                                null);
        try {
            while (c.moveToNext()) {
                out.put(c.getString(0), c.getInt(1));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 按使用次数降序、同次数按名称排序。 */
    public static List<Map.Entry<String, Integer>> sortByUsage(Map<String, Integer> usage) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(usage.entrySet());
        Collections.sort(
                list,
                new Comparator<Map.Entry<String, Integer>>() {
                    @Override
                    public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                        int d = b.getValue() - a.getValue();
                        return d != 0 ? d : a.getKey().compareToIgnoreCase(b.getKey());
                    }
                });
        return list;
    }

    /** 批量移入回收站。 */
    public static int batchSoftDelete(Context ctx, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        long now = System.currentTimeMillis();
        int n = 0;
        db.beginTransaction();
        try {
            for (String id : ids) {
                ContentValues cv = new ContentValues();
                cv.put("deletedAt", now);
                cv.put("updatedAt", now);
                n += db.update("entries", cv, "id = ? AND deletedAt IS NULL", new String[] {id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        note(ctx, "BATCH_DELETE", "SUCCESS", "移入回收站 " + n + " 条");
        return n;
    }

    /** 批量加标签（已存在同名标签的条目自动跳过，不动原有标签）。 */
    public static int addTagsToEntries(Context ctx, List<String> ids, List<String> tagNames) {
        if (ids == null || ids.isEmpty() || tagNames == null || tagNames.isEmpty()) {
            return 0;
        }
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        int n = 0;
        db.beginTransaction();
        try {
            for (String name0 : tagNames) {
                String name = name0 == null ? "" : name0.trim();
                if (name.length() == 0) {
                    continue;
                }
                String tagId = findTagId(db, name);
                if (tagId == null) {
                    tagId = UUID.randomUUID().toString();
                    ContentValues tv = new ContentValues();
                    tv.put("id", tagId);
                    tv.put("name", name);
                    db.insertWithOnConflict("tags", null, tv, SQLiteDatabase.CONFLICT_IGNORE);
                    tagId = findTagId(db, name);
                }
                if (tagId == null) {
                    continue;
                }
                for (String id : ids) {
                    ContentValues cv = new ContentValues();
                    cv.put("entryId", id);
                    cv.put("tagId", tagId);
                    long r =
                            db.insertWithOnConflict(
                                    "entry_tags", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                    if (r != -1) {
                        n++;
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        note(
                ctx,
                "BATCH_TAG",
                "SUCCESS",
                "为 " + ids.size() + " 条加标签 " + TextUtils.join("、", tagNames));
        return n;
    }

    /** Manage dictionary items, including tags not attached to an entry. */
    public static String addTag(Context ctx, String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) return "请输入标签名称";
        if (name.length() > 40 || name.indexOf((char) 1) >= 0) return "标签名称请控制在 40 字以内";
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        if (findTagId(db, name) != null) return "已存在同名标签";
        ContentValues cv = new ContentValues();
        cv.put("id", UUID.randomUUID().toString());
        cv.put("name", name);
        db.insertOrThrow("tags", null, cv);
        note(ctx, "TAG_ADD", "SUCCESS", name);
        return null;
    }

    public static int tagTotalUsage(Context ctx, String name) {
        Cursor c =
                Db.get(ctx)
                        .getReadableDatabase()
                        .rawQuery(
                                "SELECT COUNT(*) FROM entry_tags WHERE tagId IN (SELECT id FROM"
                                        + " tags WHERE name = ? COLLATE NOCASE)",
                                new String[] {name});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Rename or merge atomically, including associations in the recycle bin. */
    public static String renameTag(Context ctx, String oldName, String raw, boolean merge) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) return "请输入标签名称";
        if (name.length() > 40 || name.indexOf((char) 1) >= 0) return "标签名称请控制在 40 字以内";
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            String source = findTagId(db, oldName);
            if (source == null) return "标签已不存在，请刷新后重试";
            String target = findTagId(db, name);
            if (target != null && !source.equals(target) && !merge) return "已存在同名标签，可选择合并";
            db.execSQL(
                    "UPDATE entries SET updatedAt = ? WHERE id IN (SELECT entryId FROM entry_tags"
                            + " WHERE tagId = ?)",
                    new Object[] {System.currentTimeMillis(), source});
            if (target != null && !source.equals(target)) {
                db.execSQL(
                        "INSERT OR IGNORE INTO entry_tags(entryId,tagId) SELECT entryId,? FROM"
                                + " entry_tags WHERE tagId = ?",
                        new Object[] {target, source});
                db.delete("tags", "id = ?", new String[] {source});
            } else {
                ContentValues cv = new ContentValues();
                cv.put("name", name);
                db.update("tags", cv, "id = ?", new String[] {source});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        note(ctx, "TAG_RENAME", "SUCCESS", oldName + " → " + name);
        return null;
    }

    public static void deleteTag(Context ctx, String name) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            String id = findTagId(db, name);
            if (id != null) {
                db.execSQL(
                        "UPDATE entries SET updatedAt = ? WHERE id IN (SELECT entryId FROM"
                                + " entry_tags WHERE tagId = ?)",
                        new Object[] {System.currentTimeMillis(), id});
                db.delete("tags", "id = ?", new String[] {id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        note(ctx, "TAG_DELETE", "SUCCESS", name);
    }

    public static int deleteUnusedTags(Context ctx) {
        int n =
                Db.get(ctx)
                        .getWritableDatabase()
                        .delete(
                                "tags",
                                "NOT EXISTS(SELECT 1 FROM entry_tags et WHERE et.tagId = tags.id)",
                                null);
        note(ctx, "TAG_CLEAN", "SUCCESS", "清理 " + n + " 个未使用标签");
        return n;
    }

    public static String categoryLabel(Context ctx, String id) {
        if (id == null) return "未分类";
        List<String> path = new ArrayList<>();
        for (Category c : categoryPath(ctx, id)) path.add(c.name);
        return TextUtils.join(" / ", path);
    }

    // ==================== 搜索 ====================

    public static final int SCOPE_ALL = 0;
    public static final int SCOPE_TITLE = 1;
    public static final int SCOPE_TAG = 2;
    public static final int SCOPE_NOTE = 3;

    /**
     * 搜索。
     *
     * @param q 关键字（可空：空且带标签时 = 按标签筛）
     * @param scope SCOPE_ALL / TITLE / TAG / NOTE
     * @param tagNames 标签过滤（任一命中即可），null 或空 = 不筛
     * @param categoryId 限定分类，null = 全库
     */
    public static List<Entry> search(
            Context ctx,
            String q,
            int scope,
            List<String> tagNames,
            String categoryId,
            boolean starOnly,
            int sort) {
        SQLiteDatabase db = Db.get(ctx).getReadableDatabase();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT e.*, IFNULL(c.name,'') AS catName, IFNULL(c.color,0) AS catColor,")
                .append(" (SELECT GROUP_CONCAT(t.name, char(1)) FROM entry_tags et")
                .append("   JOIN tags t ON t.id = et.tagId WHERE et.entryId = e.id) AS tagCsv")
                .append(" FROM entries e LEFT JOIN categories c ON c.id = e.categoryId")
                .append(" WHERE e.deletedAt IS NULL");
        List<String> args = new ArrayList<>();

        if (categoryId != null) {
            sql.append(" AND e.categoryId = ?");
            args.add(categoryId);
        }
        if (starOnly) {
            sql.append(" AND e.starred = 1");
        }

        String kw = q == null ? "" : q.trim();
        if (kw.length() > 0) {
            String like = "%" + kw + "%";
            if (scope == SCOPE_TITLE) {
                sql.append(" AND e.title LIKE ?");
                args.add(like);
            } else if (scope == SCOPE_NOTE) {
                sql.append(" AND e.note LIKE ?");
                args.add(like);
            } else if (scope == SCOPE_TAG) {
                sql.append(" AND EXISTS(SELECT 1 FROM entry_tags et2 JOIN tags t2")
                        .append(
                                " ON t2.id = et2.tagId WHERE et2.entryId = e.id AND t2.name LIKE"
                                        + " ?)");
                args.add(like);
            } else {
                sql.append(
                                " AND (e.title LIKE ? OR e.magnet LIKE ? OR IFNULL(e.infoHash,'')"
                                        + " LIKE ?")
                        .append(" OR e.note LIKE ? OR IFNULL(c.name,'') LIKE ?")
                        .append(" OR EXISTS(SELECT 1 FROM entry_tags et2 JOIN tags t2")
                        .append(
                                " ON t2.id = et2.tagId WHERE et2.entryId = e.id AND t2.name LIKE"
                                        + " ?))");
                for (int i = 0; i < 6; i++) {
                    args.add(like);
                }
            }
        }

        if (tagNames != null && !tagNames.isEmpty()) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < tagNames.size(); i++) {
                in.append(i == 0 ? "?" : ",?");
                args.add(tagNames.get(i));
            }
            sql.append(" AND EXISTS(SELECT 1 FROM entry_tags et3 JOIN tags t3")
                    .append(" ON t3.id = et3.tagId WHERE et3.entryId = e.id")
                    .append(" AND t3.name IN (")
                    .append(in)
                    .append("))");
        }

        if (sort == SORT_UPDATED) {
            sql.append(" ORDER BY e.updatedAt DESC, e.id");
        } else if (sort == SORT_TITLE) {
            sql.append(" ORDER BY (CASE WHEN e.title = '' THEN 1 ELSE 0 END),")
                    .append(" e.title COLLATE NOCASE ASC, e.id");
        } else {
            sql.append(" ORDER BY e.createdAt DESC, e.id");
        }

        List<Entry> out = new ArrayList<>();
        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                out.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public static void addAudit(
            Context ctx, String type, String result, String message, int count) {
        SQLiteDatabase db = Db.get(ctx).getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("type", type);
        cv.put("result", result);
        cv.put("message", message == null ? "" : message);
        cv.put("entryCount", count);
        cv.put("createdAt", System.currentTimeMillis());
        db.insert("audit_logs", null, cv);
        // 只保留最近 100 条
        db.execSQL(
                "DELETE FROM audit_logs WHERE id NOT IN "
                        + "(SELECT id FROM audit_logs ORDER BY id DESC LIMIT 100)");
    }

    public static String lastAuditText(Context ctx) {
        Cursor c =
                Db.get(ctx)
                        .getReadableDatabase()
                        .rawQuery(
                                "SELECT type, result, message, entryCount, createdAt FROM"
                                        + " audit_logs ORDER BY id DESC LIMIT 1",
                                null);
        try {
            if (!c.moveToFirst()) {
                return "暂无备份记录";
            }
            return c.getString(0)
                    + " · "
                    + c.getString(1)
                    + " · "
                    + Ui.time(c.getLong(4))
                    + (c.getInt(3) > 0 ? " · " + c.getInt(3) + " 条" : "")
                    + (c.getString(2).isEmpty() ? "" : "\n" + c.getString(2));
        } finally {
            c.close();
        }
    }

    public static String dbSizeText(Context ctx) {
        long bytes = 0;
        java.io.File db = ctx.getDatabasePath("magnet_vault.db");
        if (db != null && db.exists()) {
            bytes = db.length();
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        return String.format(java.util.Locale.CHINA, "%.1f KB", bytes / 1024.0);
    }
}
