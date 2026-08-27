package com.xinl.easyclaw.python;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 脚本执行的文件访问权限。
 *
 * <p>把「基准目录 / 额外可读目录 / 可写目录」三者聚成一个值对象，而不是继续往
 * {@code executeScript} 上加参数：三者是一组内聚的权限声明，总是一起出现、一起被校验，
 * 拆成平铺参数会让调用点退化成一串难以辨认的位置实参（{@code (dir, null, List.of(x))}），
 * 也会让参数个数超过本项目自己的 {@code smell_scan.py} 所要求的上限。
 *
 * <p><b>默认最严</b>：{@link #readOnly} 建出的实例完全不可写。放开权限只能通过
 * {@link #plusReadable} / {@link #withWritable} 显式追加，使每一次放权都是代码里
 * 看得见、可被审查的一行——而不是某个参数忘记传导致的静默后果。
 *
 * @param baseDir 相对路径解析基准，始终可读；脚本自身必须位于其中
 * @param readableDirs 额外的只读目录（如被扫描的项目工作区）
 * @param writableDir 唯一可写目录；{@code null} 表示脚本完全不能写文件
 */
public record ScriptAccess(Path baseDir, List<Path> readableDirs, Path writableDir,
                           List<Path> deniedDirs) {

    public ScriptAccess {
        if (baseDir == null) {
            // 脚本必然要读自身文件，没有基准目录是调用方的逻辑错误，早失败比在沙箱里报怪错好
            throw new IllegalArgumentException("baseDir 不能为空");
        }
        readableDirs = readableDirs == null ? List.of() : List.copyOf(readableDirs);
        deniedDirs = deniedDirs == null ? List.of() : List.copyOf(deniedDirs);
    }

    /**
     * 只读权限：仅能读 {@code baseDir}，不能写任何位置。
     *
     * <p>这是执行随包发布的 skill 脚本时的正确默认值——脚本能写自身目录，
     * 就能改写同目录的 {@code SKILL.md}，而该文件会作为 system prompt 注入后续会话。
     */
    public static ScriptAccess readOnly(Path baseDir) {
        return new ScriptAccess(baseDir, List.of(), null, List.of());
    }

    /** 追加一个只读目录；传 {@code null} 或重复目录时原样返回，便于直接串联可选路径。 */
    public ScriptAccess plusReadable(Path dir) {
        if (dir == null || readableDirs.contains(dir)) {
            return this;
        }
        List<Path> merged = new ArrayList<>(readableDirs);
        merged.add(dir);
        return new ScriptAccess(baseDir, merged, writableDir, deniedDirs);
    }

    /**
     * 追加一个禁止访问的子树，优先于所有可读/可写授权。
     *
     * <p>用于「整体放开一个大目录、但挖掉其中敏感部分」的场景，例如放开工作区读权限
     * 时排除 {@code .easyClaw/}（含会话记录与运行时数据）。
     */
    public ScriptAccess minus(Path dir) {
        if (dir == null || deniedDirs.contains(dir)) {
            return this;
        }
        List<Path> merged = new ArrayList<>(deniedDirs);
        merged.add(dir);
        return new ScriptAccess(baseDir, readableDirs, writableDir, merged);
    }

    /** 指定唯一可写目录。 */
    public ScriptAccess withWritable(Path dir) {
        return new ScriptAccess(baseDir, readableDirs, dir, deniedDirs);
    }

    /** 可写目录列表形式；空列表在 {@link RestrictedFileSystem} 中表示全沙箱只读。 */
    List<Path> writableDirs() {
        return writableDir == null ? List.of() : List.of(writableDir);
    }
}
