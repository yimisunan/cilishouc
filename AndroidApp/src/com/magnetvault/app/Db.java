package com.magnetvault.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** SQLite 库定义。v3：分类支持父子嵌套（parentId）。 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "magnet_vault.db";
    /** v1 初版 · v2 加审计表 · v3 分类树（parentId） */
    private static final int VERSION = 3;
    private static Db sInstance;

    public static synchronized Db get(Context ctx) {
        if (sInstance == null) {
            sInstance = new Db(ctx.getApplicationContext());
        }
        return sInstance;
    }

    private Db(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "parentId TEXT,"
                + "name TEXT NOT NULL,"
                + "color INTEGER NOT NULL DEFAULT -15373759,"
                + "sortOrder INTEGER NOT NULL DEFAULT 0,"
                + "createdAt INTEGER NOT NULL DEFAULT 0,"
                + "updatedAt INTEGER NOT NULL DEFAULT 0)");
        // 同名只允许出现在同一层级下（根层级 parentId 为 NULL）
        db.execSQL("CREATE UNIQUE INDEX idx_cat_name ON categories"
                + "(COALESCE(parentId,''), name COLLATE NOCASE)");

        db.execSQL("CREATE TABLE entries ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "magnet TEXT NOT NULL,"
                + "magnetNormalized TEXT NOT NULL,"
                + "infoHash TEXT,"
                + "categoryId TEXT,"
                + "note TEXT NOT NULL DEFAULT '',"
                + "source TEXT NOT NULL DEFAULT '',"
                + "starred INTEGER NOT NULL DEFAULT 0,"
                + "createdAt INTEGER NOT NULL,"
                + "updatedAt INTEGER NOT NULL,"
                + "deletedAt INTEGER,"
                + "FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL)");
        db.execSQL("CREATE INDEX idx_entries_del ON entries(deletedAt, createdAt DESC)");
        db.execSQL("CREATE INDEX idx_entries_cat ON entries(categoryId, deletedAt)");
        db.execSQL("CREATE INDEX idx_entries_hash ON entries(infoHash)");

        db.execSQL("CREATE TABLE tags ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "name TEXT NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX idx_tag_name ON tags(name COLLATE NOCASE)");

        db.execSQL("CREATE TABLE entry_tags ("
                + "entryId TEXT NOT NULL,"
                + "tagId TEXT NOT NULL,"
                + "PRIMARY KEY(entryId, tagId),"
                + "FOREIGN KEY(entryId) REFERENCES entries(id) ON DELETE CASCADE,"
                + "FOREIGN KEY(tagId) REFERENCES tags(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_cross_tag ON entry_tags(tagId)");

        createAuditTable(db);
    }

    private static void createAuditTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS audit_logs ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "type TEXT NOT NULL,"
                + "result TEXT NOT NULL,"
                + "message TEXT NOT NULL DEFAULT '',"
                + "entryCount INTEGER NOT NULL DEFAULT 0,"
                + "createdAt INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createAuditTable(db);
        }
        if (oldVersion < 3) {
            // 分类树：加 parentId，并把"全局唯一名"改成"同一层级内唯一"
            db.execSQL("ALTER TABLE categories ADD COLUMN parentId TEXT");
            db.execSQL("DROP INDEX IF EXISTS idx_cat_name");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_cat_name ON categories"
                    + "(COALESCE(parentId,''), name COLLATE NOCASE)");
        }
    }
}