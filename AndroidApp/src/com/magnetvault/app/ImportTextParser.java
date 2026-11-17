package com.magnetvault.app;

import java.util.*;
import java.util.regex.*;

/** Pure text parser shared by single and batch import. Never touches the database. */
public final class ImportTextParser {
    public static final class Parsed {
        public final List<Entry> entries = new ArrayList<>();
        public final Set<String> explicitCategories = new HashSet<>();
        public int invalid;
    }

    private static final Pattern FIELD = Pattern.compile(
            "^(名字|标题|name|title|出处|来源|网址|原帖|source|url|分类|文件夹|category|folder|标签|tags?|备注|note)\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private ImportTextParser() {}

    public static Parsed parse(String text) {
        Parsed result = new Parsed();
        if (text == null || text.trim().isEmpty()) return result;
        String[] lines = text.replace("\r", "").replace("\ufeff", "").split("\n");
        Map<String, String> fields = new HashMap<>();
        String pending = null;
        String[] columns = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (pending != null) add(result, pending, fields);
                pending = null;
                fields.clear();
                continue;
            }
            if (raw.contains("\t")) {
                if (pending != null) add(result, pending, fields);
                pending = null;
                fields.clear();
                String[] cells = raw.split("\t", -1);
                String[] headers = headers(cells);
                if (headers != null) { columns = headers; continue; }
                if (columns != null) {
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < Math.min(cells.length, columns.length); i++)
                        if (!columns[i].isEmpty()) row.put(columns[i], unquote(cells[i]));
                    String link = row.get("link");
                    if (link != null && !link.trim().isEmpty()) addCell(result, link, row);
                    else result.invalid++;
                    continue;
                }
            }
            Matcher field = FIELD.matcher(line);
            if (field.matches()) {
                String key = key(field.group(1));
                if ("title".equals(key) && pending != null) {
                    add(result, pending, fields);
                    pending = null;
                    fields.clear();
                }
                fields.put(key, field.group(2).trim());
                continue;
            }
            List<String> links = MagnetParser.extractAll(line);
            if (!links.isEmpty()) {
                if (pending != null) { add(result, pending, fields); fields.clear(); }
                for (int i = 0; i < links.size() - 1; i++) add(result, links.get(i), fields);
                pending = links.get(links.size() - 1);
            } else if (line.toLowerCase(Locale.ROOT).contains("magnet:")
                    || line.toLowerCase(Locale.ROOT).contains("ed2k://")) result.invalid++;
        }
        if (pending != null) add(result, pending, fields);
        return result;
    }

    private static void addCell(Parsed result, String cell, Map<String, String> fields) {
        List<String> links = MagnetParser.extractAll(cell);
        if (links.isEmpty()) { result.invalid++; return; }
        for (String link : links) add(result, link, fields);
    }

    private static void add(Parsed result, String link, Map<String, String> fields) {
        if (!MagnetParser.isValid(link)) { result.invalid++; return; }
        Entry entry = new Entry();
        entry.id = UUID.randomUUID().toString();
        entry.magnet = link.trim();
        entry.normalized = MagnetParser.normalize(entry.magnet);
        entry.infoHash = MagnetParser.infoHash(entry.magnet);
        entry.title = fields.containsKey("title") ? fields.get("title") : MagnetParser.titleFromLink(link);
        entry.source = fields.containsKey("source") ? fields.get("source") : "";
        entry.note = fields.containsKey("note") ? fields.get("note") : "";
        entry.categoryName = fields.containsKey("category") ? normalizePath(fields.get("category")) : "";
        entry.tags = splitTags(fields.get("tags"));
        if (fields.containsKey("category")) result.explicitCategories.add(entry.id);
        result.entries.add(entry);
    }

    private static String[] headers(String[] cells) {
        for (String cell : cells) if (!MagnetParser.extractAll(cell).isEmpty()) return null;
        String[] keys = new String[cells.length];
        boolean link = false;
        for (int i = 0; i < cells.length; i++) {
            keys[i] = key(unquote(cells[i]));
            if (keys[i].equals("link")) link = true;
        }
        return link ? keys : null;
    }

    private static String key(String value) {
        String s = value.trim().toLowerCase(Locale.ROOT);
        if (Arrays.asList("名字", "标题", "name", "title").contains(s)) return "title";
        if (Arrays.asList("链接", "磁力", "磁力链接", "link", "magnet", "ed2k").contains(s)) return "link";
        if (Arrays.asList("出处", "来源", "网址", "原帖", "source", "url").contains(s)) return "source";
        if (Arrays.asList("分类", "文件夹", "category", "folder").contains(s)) return "category";
        if (Arrays.asList("标签", "tag", "tags").contains(s)) return "tags";
        if (Arrays.asList("备注", "note").contains(s)) return "note";
        return "";
    }

    private static String unquote(String s) {
        s = s.trim();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")
                ? s.substring(1, s.length() - 1).replace("\"\"", "\"") : s;
    }

    public static List<String> splitTags(String text) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (text != null) for (String raw : text.split("[,，;；、]")) {
            String name = raw.trim();
            if (!name.isEmpty() && seen.add(name.toLowerCase(Locale.ROOT))) out.add(name);
        }
        return out;
    }

    public static String normalizePath(String path) {
        if (path == null || path.trim().isEmpty() || path.trim().equals("未分类")) return "";
        List<String> parts = new ArrayList<>();
        for (String part : path.replace('\\', '/').split("/"))
            if (!part.trim().isEmpty()) parts.add(part.trim());
        return join(parts, " / ");
    }

    public static String join(Collection<String> values, String separator) {
        StringBuilder s = new StringBuilder();
        for (String v : values) { if (s.length() > 0) s.append(separator); s.append(v); }
        return s.toString();
    }
}
