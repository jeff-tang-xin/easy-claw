package com.xinl.easyclaw.python;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.io.FileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 Python 的文件访问限制在单个根目录内的 FileSystem。
 *
 * <p><b>为什么需要它</b>：实测证明（{@code PythonSandboxTest.cannotEscapeAllowedDir}）
 * 仅设置 {@code currentWorkingDirectory} 配合默认 FileSystem <b>不构成任何隔离</b>——
 * Python 用绝对路径可直接读取沙箱外的文件。cwd 只影响相对路径解析，不是安全边界。
 *
 * <p><b>实现要点</b>：所有接受 Path 的方法统一过 {@link #guard(Path)}。校验分两步：
 *
 * <ol>
 *   <li>规范化后判断是否落在允许的根之内——挡掉 {@code ..} 与绝对路径；
 *   <li>对已存在的路径再解析真实路径（跟随符号链接）后复查——挡掉符号链接逃逸。
 * </ol>
 *
 * <p>符号链接必须单独处理：仅做字符串规范化的话，沙箱内一个指向 {@code C:\} 的链接
 * 就能绕过全部检查。
 *
 * <p><b>三类根</b>：
 *
 * <ul>
 *   <li>{@code root}——相对路径解析基准，始终可读；
 *   <li>{@code readOnlyRoots}——可读可遍历、写删改一律拒绝；
 *   <li>{@code writableRoots}——唯一允许写入的集合，可以为空（全沙箱只读）。
 * </ul>
 *
 * <p>把"可读"与"可写"拆开而不是共用一个根，是为了支持执行随包发布的脚本：
 * 脚本需要读自身目录，却不该能改写自身目录——否则脚本可以改写同目录的
 * {@code SKILL.md}，而该文件会作为 system prompt 注入后续会话，
 * 形成可自我持久化的提示词投毒路径。
 *
 * <p>Python 的标准库不在工作目录里（{@code sys.prefix} 指向 GraalPy 的 python-home），
 * 若只放开工作目录，连 {@code import ast} 都会失败——实测报
 * {@code ModuleNotFoundError: No module named 'ast'}，因此标准库目录也通过只读根放行。
 */
final class RestrictedFileSystem implements FileSystem {

    /** 受限文件系统的日志，仅用于记录被忽略的只读根。 */
    private static final Logger log = LoggerFactory.getLogger(RestrictedFileSystem.class);

    private final FileSystem delegate = FileSystem.newDefaultFileSystem();

    /** 相对路径的解析基准，同时是唯一保证可读的根，已解析为真实绝对路径。 */
    private final Path root;

    /** 只读根（如 Python 标准库目录、只读的 skill 目录），已解析为真实绝对路径。 */
    private final List<Path> readOnlyRoots;

    /**
     * 可写根，已解析为真实绝对路径。
     *
     * <p>为空表示<b>整个沙箱只读</b>——这是刻意支持的状态：执行随包发布的 skill 脚本时，
     * 脚本没有任何理由改写自身所在目录（详见 {@code PythonSandbox.executeScript}）。
     */
    private final List<Path> writableRoots;

    /** 即使落在可读根内也一律拒绝的子树（deny 优先于 allow）。 */
    private final List<Path> deniedRoots;

    /**
     * @param allowedDir 相对路径基准目录，始终可读
     * @param readOnlyRoots 额外的只读根
     * @param writableRoots 可写根；传 {@code null} 表示 {@code allowedDir} 可写，
     *     传空列表表示全沙箱只读。用 null 与空列表区分这两种意图，避免调用方
     *     漏传参数时静默获得写权限——安全默认值必须是"更严"的那一侧。
     */
    RestrictedFileSystem(Path allowedDir, List<Path> readOnlyRoots, List<Path> writableRoots)
            throws IOException {
        this(allowedDir, readOnlyRoots, writableRoots, List.of());
    }

    /**
     * @param deniedRoots 黑名单子树，优先于所有可读/可写判断。
     *     用途：把某个大目录整体设为可读时，仍需挖掉其中的敏感子目录
     *     （如工作区下的 {@code .easyClaw/}，含会话记录与运行时数据）。
     */
    RestrictedFileSystem(Path allowedDir, List<Path> readOnlyRoots, List<Path> writableRoots,
                         List<Path> deniedRoots) throws IOException {
        this.root = allowedDir.toRealPath();
        this.readOnlyRoots = resolveAll(readOnlyRoots, "只读根");
        this.writableRoots = writableRoots == null
                ? List.of(this.root)
                : resolveAll(writableRoots, "可写根");
        this.deniedRoots = resolveAll(deniedRoots, "禁止根");
    }

    /**
     * 批量解析真实路径，跳过不存在的项。
     *
     * <p>解析失败只跳过不抛：运行时目录缺失不该让整个沙箱不可用，
     * 后续 import/写入时报的错比这里抛异常更贴近根因。
     */
    private static List<Path> resolveAll(List<Path> raw, String what) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Path> resolved = new ArrayList<>();
        for (Path p : raw) {
            try {
                resolved.add(p.toRealPath());
            } catch (IOException missing) {
                log.warn("{}不存在，已忽略: {}", what, p);
            }
        }
        return List.copyOf(resolved);
    }

    /** 判断路径是否位于给定的任一根之内。 */
    private static boolean under(Path path, List<Path> roots) {
        for (Path r : roots) {
            if (path.startsWith(r)) {
                return true;
            }
        }
        return false;
    }

    /** 是否处于禁止子树内（deny 优先于 allow）。 */
    private boolean denied(Path path) {
        return under(path, deniedRoots);
    }

    /**
     * 是否可读：{@code root} 与可写根始终可读；只读根需再排除禁止子树。
     *
     * <p>deny 只用于「整体放开一个大目录后挖掉其中敏感部分」，不能推翻显式授权：
     * skill 自身可能就位于工作区 {@code .easyClaw/agent/skills/} 下（页面创建的工作区
     * skill 即是），若让 deny 无条件优先，脚本连同目录的规则文件都读不到。
     */
    private boolean readable(Path path) {
        if (path.startsWith(root) || under(path, writableRoots)) {
            return true;
        }
        return under(path, readOnlyRoots) && !denied(path);
    }

    /** 是否可写：仅可写根之内（可写根是显式授权，不受 deny 约束）。 */
    private boolean writable(Path path) {
        return under(path, writableRoots);
    }

    /**
     * 校验路径是否落在根目录内。
     *
     * @return 规范化后的绝对路径
     * @throws AccessDeniedException 越界时抛出，Python 侧会看到 PermissionError
     */
    private Path guard(Path path) throws IOException {
        return guard(path, false);
    }

    /**
     * 校验路径访问权限。
     *
     * @param write true 表示这是写操作；写操作在只读根内一律拒绝
     */
    private Path guard(Path path, boolean write) throws IOException {
        Path abs = path.isAbsolute() ? path : root.resolve(path);
        Path normalized = abs.normalize();
        if (!readable(normalized)) {
            throw new AccessDeniedException("沙箱拒绝访问: " + path + "（超出允许访问的目录范围）");
        }
        if (write && !writable(normalized)) {
            throw new AccessDeniedException("沙箱拒绝写入: " + path + "（该目录为只读）");
        }
        // 符号链接可能指向根目录之外，需按真实路径复查。
        try {
            Path real = normalized.toRealPath();
            if (!readable(real)) {
                throw new AccessDeniedException(
                        "沙箱拒绝访问: " + path + "（符号链接指向目录之外）");
            }
            // 写权限同样按真实路径复查：只读根里一个指向可写目录的链接，
            // 反过来也可能被用来绕过只读限制。
            if (write && !writable(real)) {
                throw new AccessDeniedException(
                        "沙箱拒绝写入: " + path + "（符号链接目标为只读）");
            }
            return real;
        } catch (AccessDeniedException denied) {
            throw denied;
        } catch (IOException notExistsYet) {
            // 路径不存在属正常情况（新建文件），但**不能**就此放过：
            // 若父目录是指向沙箱外的符号链接（root/link -> C:\），则 root/link/new.txt
            // 的字符串形式完全合法，toRealPath 又因末段不存在而失败，
            // 写操作就会穿过链接落到 C:\new.txt。必须改为校验最近的已存在祖先。
            guardNearestExistingAncestor(path, normalized, write);
            return normalized;
        }
    }

    /**
     * 自下而上找到第一个真实存在的祖先目录，按其真实路径校验边界。
     *
     * <p>这样即便目标文件尚未创建，也无法借助父目录上的符号链接逃出沙箱。
     */
    private void guardNearestExistingAncestor(Path original, Path normalized, boolean write)
            throws IOException {
        for (Path parent = normalized.getParent(); parent != null; parent = parent.getParent()) {
            Path realParent;
            try {
                realParent = parent.toRealPath();
            } catch (IOException stillMissing) {
                continue; // 这一层也不存在，继续往上找
            }
            if (!readable(realParent)) {
                throw new AccessDeniedException(
                        "沙箱拒绝访问: " + original + "（父目录经符号链接指向目录之外）");
            }
            if (write && !writable(realParent)) {
                throw new AccessDeniedException(
                        "沙箱拒绝写入: " + original + "（父目录为只读）");
            }
            return;
        }
        // 一路到根都不存在：不可能落在沙箱内
        throw new AccessDeniedException("沙箱拒绝访问: " + original + "（路径不可解析）");
    }

    @Override
    public Path parsePath(URI uri) {
        return delegate.parsePath(uri);
    }

    @Override
    public Path parsePath(String path) {
        return delegate.parsePath(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... options)
            throws IOException {
        boolean write = modes.contains(AccessMode.WRITE);
        delegate.checkAccess(guard(path, write), modes, options);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(guard(dir, true), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(guard(path, true));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        // 只有真正带写意图的 OpenOption 才算写操作，否则只读根里的标准库就读不出来
        boolean write = options.stream().anyMatch(o ->
                o == StandardOpenOption.WRITE
                        || o == StandardOpenOption.APPEND
                        || o == StandardOpenOption.CREATE
                        || o == StandardOpenOption.CREATE_NEW
                        || o == StandardOpenOption.DELETE_ON_CLOSE
                        || o == StandardOpenOption.TRUNCATE_EXISTING);
        return delegate.newByteChannel(guard(path, write), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        return delegate.newDirectoryStream(guard(dir), filter);
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return path.isAbsolute() ? path : root.resolve(path);
    }

    /**
     * 相对路径的解析基准固定为根目录。
     *
     * <p>自定义 FileSystem 必须自行实现此方法，否则 Context 构建时设置
     * currentWorkingDirectory 会抛 UnsupportedOperationException。
     * 这里刻意忽略传入值：cwd 恒为 root，Python 侧无法把工作目录移出沙箱。
     */
    @Override
    public void setCurrentWorkingDirectory(Path currentWorkingDirectory) {
        // 空实现即"锁定在 root"，见 toAbsolutePath
    }

    /**
     * 返回真实路径。
     *
     * <p><b>刻意忽略 {@code options}</b>：即使调用方传 {@code NOFOLLOW_LINKS}，
     * 这里也始终返回跟随链接后的真实路径。偏离 {@code java.nio} 契约是为了安全——
     * 不跟随就无法判断链接目标是否越界。方向上只会更严，不会放宽。
     */
    @Override
    public Path toRealPath(Path path, LinkOption... options) throws IOException {
        return guard(path);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        return delegate.readAttributes(guard(path), attributes, options);
    }

    @Override
    public void copy(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
        delegate.copy(guard(source), guard(target, true), options);
    }

    @Override
    public void move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
        delegate.move(guard(source, true), guard(target, true), options);
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
        delegate.createLink(guard(link, true), guard(existing));
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
            throws IOException {
        // 允许创建，但两端都必须在沙箱内；guard 同时保证了 readLink 后仍受限
        delegate.createSymbolicLink(guard(link, true), guard(target), attrs);
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return guard(delegate.readSymbolicLink(guard(link)));
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
            throws IOException {
        delegate.setAttribute(guard(path, true), attribute, value, options);
    }

    @Override
    public String getSeparator() {
        return delegate.getSeparator();
    }

    @Override
    public String getPathSeparator() {
        return delegate.getPathSeparator();
    }
}
