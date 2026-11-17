package com.magnetvault.app;

/** 分类（支持父子嵌套）。 */
public class Category {
    public String id;
    public String parentId;     // null = 根层级
    public String name = "";
    public int color = 0xFF1565C0;
    public int sortOrder;
    public int count;           // 该分类直属未删除条目数（查询时填充）
    public int childCount;      // 直接子分类个数（查询时填充）

    public Category() {
    }

    public Category(String id, String name, int color) {
        this.id = id;
        this.name = name;
        this.color = color;
    }

    public boolean isRoot() {
        return parentId == null || parentId.length() == 0;
    }

    @Override
    public String toString() {
        return name;
    }
}