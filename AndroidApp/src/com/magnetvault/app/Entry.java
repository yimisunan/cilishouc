package com.magnetvault.app;

import java.util.ArrayList;
import java.util.List;

/** 收藏条目（领域模型，不做持久化映射）。 */
public class Entry implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public String id;
    public String title = "";
    public String magnet = "";
    public String infoHash; // 可能为 null
    public String normalized = "";
    public String categoryId; // null = 未分类
    public String categoryName = "";
    public int categoryColor = 0;
    public String note = "";
    public String source = "";
    public boolean starred;
    public long createdAt;
    public long updatedAt;
    public Long deletedAt; // null = 未删除
    public List<String> tags = new ArrayList<>();

    /** 列表中显示的标题：无标题时用 hash 前 12 位兜底。 */
    public String displayTitle() {
        if (title != null && title.trim().length() > 0) {
            return title;
        }
        String filename = MagnetParser.titleFromLink(magnet);
        if (!filename.isEmpty()) return filename;
        if (infoHash != null && infoHash.length() >= 12) {
            return "未命名 · " + infoHash.substring(0, 12);
        }
        if (magnet.length() >= 12) {
            return "未命名 · " + magnet.substring(magnet.length() - 12);
        }
        return "未命名";
    }

    public String tagText() {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append("，");
            }
            sb.append(tags.get(i));
        }
        return sb.toString();
    }
}
