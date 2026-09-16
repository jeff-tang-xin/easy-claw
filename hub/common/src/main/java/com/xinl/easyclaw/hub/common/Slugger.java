package com.xinl.easyclaw.hub.common;

/**
 * slug 派生工具：名称 → slug（小写字母/数字/连字符）。组织与项目创建时服务端自动生成 slug 共用。
 */
public final class Slugger {

    public static final String SLUG_PATTERN = "[a-z0-9-]+";

    private Slugger() {
    }

    /**
     * 从名称派生 slug：转小写、连续非法字符折叠为一个连字符、去首尾连字符、截断到 48 位；
     * 派生结果为空（如纯中文名）时返回 fallback，调用方负责唯一性兜底。
     */
    public static String derive(String name, String fallback) {
        String s = name == null ? "" : name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 48) {
            s = s.substring(0, 48).replaceAll("-+$", "");
        }
        // 派生结果不含任何字母（如「测试001」→「001」）时没有辨识度，视同派生失败走 fallback
        if (!s.isEmpty() && !s.matches(".*[a-z].*")) {
            s = "";
        }
        return s.isEmpty() ? fallback : s;
    }

    public static boolean isValid(String slug) {
        return slug != null && slug.matches(SLUG_PATTERN);
    }
}
