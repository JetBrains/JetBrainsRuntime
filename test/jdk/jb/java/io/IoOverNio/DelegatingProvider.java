
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

public class DelegatingProvider extends FileSystemProvider {

    private final FileSystemProvider delegate;

    /** Called reflectively by FileSystems.getDefault(). */
    public DelegatingProvider(FileSystemProvider wrapped) {
        this.delegate = wrapped;
        System.err.println("[DelegatingProvider] wrapping " + wrapped.getClass().getName());
    }

    // ---- scheme / file-system lifecycle ----

    @Override public String getScheme() {
        return delegate.getScheme();
    }

    @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return delegate.newFileSystem(uri, env);
    }

    @Override public FileSystem getFileSystem(URI uri) {
        return delegate.getFileSystem(uri);
    }

    @NotNull
    @Override public Path getPath(@NotNull URI uri) {
        return delegate.getPath(uri);
    }

    // ---- channel / stream ----

    @Override public SeekableByteChannel newByteChannel(Path path,
            Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return delegate.newByteChannel(path, options, attrs);
    }

    @Override public DirectoryStream<Path> newDirectoryStream(Path dir,
            DirectoryStream.Filter<? super Path> filter) throws IOException {
        return delegate.newDirectoryStream(dir, filter);
    }

    // ---- file operations ----

    @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(dir, attrs);
    }

    @Override public void delete(Path path) throws IOException {
        delegate.delete(path);
    }

    @Override public void copy(Path source, Path target, CopyOption... options) throws IOException {
        delegate.copy(source, target, options);
    }

    @Override public void move(Path source, Path target, CopyOption... options) throws IOException {
        delegate.move(source, target, options);
    }

    @Override public boolean isSameFile(Path path, Path path2) throws IOException {
        return delegate.isSameFile(path, path2);
    }

    @Override public boolean isHidden(Path path) throws IOException {
        return delegate.isHidden(path);
    }

    @Override public FileStore getFileStore(Path path) throws IOException {
        return delegate.getFileStore(path);
    }

    @Override public void checkAccess(Path path, AccessMode... modes) throws IOException {
        delegate.checkAccess(path, modes);
    }

    @Override public <V extends FileAttributeView> V getFileAttributeView(Path path,
            Class<V> type, LinkOption... options) {
        return delegate.getFileAttributeView(path, type, options);
    }

    @Override public <A extends BasicFileAttributes> A readAttributes(Path path,
            Class<A> type, LinkOption... options) throws IOException {
        return delegate.readAttributes(path, type, options);
    }

    @Override public Map<String, Object> readAttributes(Path path,
            String attributes, LinkOption... options) throws IOException {
        return delegate.readAttributes(path, attributes, options);
    }

    @Override public void setAttribute(Path path, String attribute,
            Object value, LinkOption... options) throws IOException {
        delegate.setAttribute(path, attribute, value, options);
    }
}
