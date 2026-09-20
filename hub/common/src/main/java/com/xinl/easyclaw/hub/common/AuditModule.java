package com.xinl.easyclaw.hub.common;

/**
 * 审计维度常量：module（模块）/ result（结果）。action 为自由动词（register/login/create_org/...），
 * 统一小写蛇形。集中定义避免散落字符串字面量。
 */
public final class AuditModule {

    private AuditModule() {
    }

    public static final String AUTH = "auth";
    public static final String ORG = "org";
    public static final String PROJECT = "project";
    public static final String USER = "user";
    public static final String PROVIDER = "provider";
    public static final String APPKEY = "appkey";
    public static final String KNOWLEDGE = "knowledge";
    public static final String BLACKBOARD = "blackboard";
    public static final String WORKSPACE = "workspace";
    public static final String MENU = "menu";

    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
}
