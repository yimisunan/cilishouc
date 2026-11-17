package com.magnetvault.app;

import java.io.Serializable;
import java.util.*;

/** Serializable staging area. Selection edits drafts only; inclusion controls the commit. */
public final class ImportDraft implements Serializable {
    private static final long serialVersionUID = 1L;
    public final List<Row> rows = new ArrayList<>();
    public int invalid;

    public static final class Row implements Serializable {
        private static final long serialVersionUID = 1L;
        public Entry entry;
        public boolean selected;
        public boolean included = true;
        public String duplicate = "";
        public Row(Entry e) { entry = e; }
        public boolean editable() { return duplicate.isEmpty(); }
        public boolean matches(String query) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            return q.isEmpty() || (entry.displayTitle() + "\n" + entry.magnet + "\n"
                    + entry.categoryName + "\n" + entry.tagText() + "\n" + entry.note)
                    .toLowerCase(Locale.ROOT).contains(q);
        }
    }

    public List<Row> selected() {
        List<Row> out = new ArrayList<>();
        for (Row row : rows) if (row.selected && row.editable()) out.add(row);
        return out;
    }

    public int includedCount() {
        int n = 0;
        for (Row row : rows) if (row.included && row.editable()) n++;
        return n;
    }

    public int duplicateCount() {
        int n = 0;
        for (Row row : rows) if (!row.editable()) n++;
        return n;
    }

    public static void setCategory(Collection<Row> rows, String path) {
        for (Row row : rows) {
            row.entry.categoryName = ImportTextParser.normalizePath(path);
            row.entry.categoryId = null; // Resolved by full path inside the final transaction.
        }
    }

    public static void setTags(Collection<Row> rows, List<String> tags, boolean append) {
        for (Row row : rows) {
            List<String> values = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (append) for (String name : row.entry.tags)
                if (seen.add(name.toLowerCase(Locale.ROOT))) values.add(name);
            for (String name : tags) if (seen.add(name.toLowerCase(Locale.ROOT))) values.add(name);
            row.entry.tags = values;
        }
    }

    public static String validate(Entry entry) {
        String path = ImportTextParser.normalizePath(entry.categoryName);
        String[] parts = path.isEmpty() ? new String[0] : path.split(" / ");
        if (parts.length > 32) return "分类最多支持 32 层";
        for (String part : parts) if (part.length() > 80) return "每级分类名称请控制在 80 字以内";
        for (String tag : entry.tags) if (tag.length() > 40) return "标签名称请控制在 40 字以内";
        return null;
    }
}