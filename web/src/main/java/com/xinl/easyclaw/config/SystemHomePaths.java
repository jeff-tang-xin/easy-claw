package com.xinl.easyclaw.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 系统全局目录路径（~/.easyClaw）
 * <p>
 * 存放系统级运行配置与全局共享能力（Skills / 子 Agent），
 * 所有 Workspace 共享；Workspace 内的同名能力覆盖全局。
 */
public final class SystemHomePaths {

    private SystemHomePaths() {
    }

    public static Path systemHome() {
        return Paths.get(System.getProperty("user.home"), ".easyClaw").toAbsolutePath().normalize();
    }

    /** 全局 Skills 目录（所有 Workspace 共享，SKILL.md 自动加载） */
    public static Path globalSkillsDir() {
        return systemHome().resolve("skills");
    }

    /** 全局子 Agent 目录（所有 Workspace 共享，被 workspace 同名声明覆盖） */
    public static Path globalSubagentsDir() {
        return systemHome().resolve("subagents");
    }

    /** 系统元数据库 */
    public static Path databaseFile() {
        return systemHome().resolve("ai-assistant.db");
    }

    /** 旧系统目录（迁移用） */
    public static Path legacySystemHome() {
        return Paths.get(System.getProperty("user.home"), ".easy-ai").toAbsolutePath().normalize();
    }
}
