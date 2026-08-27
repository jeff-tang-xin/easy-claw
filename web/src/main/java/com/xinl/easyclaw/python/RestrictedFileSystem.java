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
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.io.FileSystem;

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
 */
final class RestrictedFileSystem implements FileSystem {

    private final FileSystem delegate = FileSystem.newDefaultFileSystem();

    /** 允许访问的根目录，已解析为真实绝对路径。 */
    private final Path root;

    RestrictedFileSystem(Path allowedDir) throws IOException {
        this.root = allowedDir.toRealPath();
    }

    /**
     * 校验路径是否落在根目录内。
     *
     * @return 规范化后的绝对路径
     * @throws AccessDeniedException 越界时抛出，Python 侧会看到 PermissionError
     */
    private Path guard(Path path) throws IOException {
        Path abs = path.isAbsolute() ? path : root.resolve(path);
        Path normalized = abs.normalize();
        if (!normalized.startsWith(root)) {
            throw new AccessDeniedException("沙箱拒绝访问: " + path + "（超出允许目录 " + root + "）");
        }
        // 符号链接可能指向根目录之外，需按真实路径复查。
        // 路径尚不存在属正常情况（如新建文件），此时上一步的规范化检查已足够。
        try {
            Path real = normalized.toRealPath();
            if (!real.startsWith(root)) {
                throw new AccessDeniedException(
                        "沙箱拒绝访问: " + path + "（符号链接指向目录之外）");
            }
            return real;
        } catch (IOException notExistsYet) {
            return normalized;
        }
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
        delegate.checkAccess(guard(path), modes, options);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(guard(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(guard(path));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        return delegate.newByteChannel(guard(path), options, attrs);
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
        delegate.copy(guard(source), guard(target), options);
    }

    @Override
    public void move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
        delegate.move(guard(source), guard(target), options);
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
        delegate.createLink(guard(link), guard(existing));
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
            throws IOException {
        // 允许创建，但两端都必须在沙箱内；guard 同时保证了 readLink 后仍受限
        delegate.createSymbolicLink(guard(link), guard(target), attrs);
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return guard(delegate.readSymbolicLink(guard(link)));
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
            throws IOException {
        delegate.setAttribute(guard(path), attribute, value, options);
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
