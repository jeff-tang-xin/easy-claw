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
 *   <li>规范化后判断是否在根目录下——挡掉 {@code ..} 与绝对路径；
 *   <li>对已存在的路径再解析真实路径（跟随符号链接）后复查——挡掉符号链接逃逸。
 * </ol>
 *
 * <p>符号链接必须单独处理：仅做字符串规范化的话，沙箱内一个指向 {@code C:\} 的链接
 * 就能绕过全部检查。
 *
 * <p><b>只读根</b>：Python 的标准库不在工作目录里（{@code sys.prefix} 指向 GraalPy 的
 * python-home），若只放开工作目录，连 {@code import ast} 都会失败——
 * 实测报 {@code ModuleNotFoundError: No module named 'ast'}。因此额外接受一组
 * <b>只读</b>根用于加载运行时自身文件：可读、可遍历，但写/删/改一律拒绝，
 * 避免脚本篡改标准库去污染后续执行。
 */
final class RestrictedFileSystem implements FileSystem {

    /** 受限文件系统的日志，仅用于记录被忽略的只读根。 */
    private static final Logger log = LoggerFactory.getLogger(RestrictedFileSystem.class);

    private final FileSystem delegate = FileSystem.newDefaultFileSystem();

    /** 允许读写的根目录，已解析为真实绝对路径。 */
    private final Path root;

    /** 只读根（如 Python 标准库目录），已解析为真实绝对路径。 */
    private final List<Path> readOnlyRoots;

    RestrictedFileSystem(Path allowedDir) throws IOException {
        this(allowedDir, List.of());
    }

    RestrictedFileSystem(Path allowedDir, List<Path> readOnlyRoots) throws IOException {
        this.root = allowedDir.toRealPath();
        List<Path> resolved = new ArrayList<>();
        for (Path p : readOnlyRoots) {
            try {
                resolved.add(p.toRealPath());
            } catch (IOException missing) {
                // 运行时目录缺失不该让整个沙箱不可用，跳过即可（后续 import 会报错，信息更直接）
                log.warn("只读根不存在，已忽略: {}", p);
            }
        }
        this.readOnlyRoots = List.copyOf(resolved);
    }

    /** 判断路径是否位于任一只读根内。 */
    private boolean inReadOnlyRoot(Path normalized) {
        for (Path ro : readOnlyRoots) {
            if (normalized.startsWith(ro)) {
                return true;
            }
        }
        return false;
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
        boolean inWorkspace = normalized.startsWith(root);
        if (!inWorkspace && !inReadOnlyRoot(normalized)) {
            throw new AccessDeniedException("沙箱拒绝访问: " + path + "（超出允许目录 " + root + "）");
        }
        if (write && !inWorkspace) {
            throw new AccessDeniedException("沙箱拒绝写入: " + path + "（该目录为只读的运行时目录）");
        }
        // 符号链接可能指向根目录之外，需按真实路径复查。
        try {
            Path real = normalized.toRealPath();
            if (!real.startsWith(root) && !inReadOnlyRoot(real)) {
                throw new AccessDeniedException(
                        "沙箱拒绝访问: " + path + "（符号链接指向目录之外）");
            }
            return real;
        } catch (AccessDeniedException denied) {
            throw denied;
        } catch (IOException notExistsYet) {
            // 路径不存在属正常情况（新建文件），但**不能**就此放过：
            // 若父目录是指向沙箱外的符号链接（root/link -> C:\），则 root/link/new.txt
            // 的字符串形式完全合法，toRealPath 又因末段不存在而失败，
            // 写操作就会穿过链接落到 C:\new.txt。必须改为校验最近的已存在祖先。
            guardNearestExistingAncestor(path, normalized);
            return normalized;
        }
    }

    /**
     * 自下而上找到第一个真实存在的祖先目录，按其真实路径校验边界。
     *
     * <p>这样即便目标文件尚未创建，也无法借助父目录上的符号链接逃出沙箱。
     */
    private void guardNearestExistingAncestor(Path original, Path normalized) throws IOException {
        for (Path parent = normalized.getParent(); parent != null; parent = parent.getParent()) {
            Path realParent;
            try {
                realParent = parent.toRealPath();
            } catch (IOException stillMissing) {
                continue; // 这一层也不存在，继续往上找
            }
            if (!realParent.startsWith(root) && !inReadOnlyRoot(realParent)) {
                throw new AccessDeniedException(
                        "沙箱拒绝访问: " + original + "（父目录经符号链接指向目录之外）");
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
