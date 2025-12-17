/*
 * Copyright 2025 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.io;

import com.jetbrains.internal.IoOverNio;
import com.jetbrains.internal.IoToNioErrorMessageHolder;
import jdk.internal.misc.VM;
import sun.nio.ch.FileChannelImpl;

import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static com.jetbrains.internal.IoOverNio.DEBUG;

class IoOverNioFileSystem extends FileSystem {
    /**
     * The filesystem created by default in the original OpenJDK.
     */
    private final FileSystem parent;

    IoOverNioFileSystem(FileSystem parent) {
        this.parent = parent;
    }

    /**
     * If this method returns a non-null {@link java.nio.file.FileSystem},
     * then {@code java.io} primitives must use this filesystem for all operations inside wherever it is possible.
     * Otherwise, {@link java.io} must use legacy functions for accessing the filesystem.
     * It is allowed to not use NIO instead of the original java.io code
     * if some function is not implemented in NIO.
     */
    static java.nio.file.FileSystem acquireNioFs(String path) {
        if (!VM.isBooted()) {
            return null;
        }

        if (!IoOverNio.IS_ENABLED_IN_GENERAL) {
            return null;
        }

        if (!IoOverNio.isAllowedInThisThread()) {
            return null;
        }

        java.nio.file.FileSystem nioFs = FileSystems.getDefault();

        if (nioFs.getSeparator().equals("\\") && Objects.equals(path, "NUL:")) {
            return null;
        }

        return nioFs;
    }

    /**
     * To be used in {@link java.io.FileInputStream} and so on.
     * Constructors of these classes may throw only {@link FileNotFoundException} whatever error actually happens.
     */
    static FileNotFoundException convertNioToIoExceptionInStreams(IOException source) {
        if (source instanceof FileNotFoundException nsfe) {
            return nsfe;
        }
        String message = IoToNioErrorMessageHolder.removeMessage(source);
        if (source instanceof FileSystemException s && s.getFile() != null) {
            if (message == null) {
                message = s.getFile();
            } else {
                message = s.getFile() + " (" + message + ")";
            }
        }
        FileNotFoundException result = new FileNotFoundException(message);
        result.initCause(source);
        return result;
    }

    static Path getNioPath(File file, boolean mustBeRegularFile) {
        String path = file.getPath();
        java.nio.file.FileSystem nioFs = IoOverNioFileSystem.acquireNioFs(path);
        if (nioFs == null) {
            return null;
        }

        Path nioPath;
        try {
            nioPath = nioFs.getPath(path);
        } catch (InvalidPathException ignored) {
            return null;
        }

        if (!mustBeRegularFile) {
            return nioPath;
        }

        if (isWindowsPipe(nioPath)) {
            // https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-createnamedpipea see nMaxInstances:
            //
            // Getting file attributes in this case is dangerous.
            // GetFileAttributesW acquires a connection to the pipe internally,
            // occupying a place on the server side.
            // The server and the client are very likely two different processes, and it takes time to deliver
            // the connection closing message to the server.
            // If the caller invokes CreateFileW fast enough after GetFileAttributesW and nMaxInstances = 1,
            // CreateFileW is called before the server closes the previous connection created by GetFileAttributesW
            // and ERROR_PIPE_BUSY is returned.
            //
            // Anyway, `readAttributes(nioPath).isRegularFile()` returns true for pipes, so it's safe to return here.
            return nioPath;
        }

        // Two significant differences between the legacy java.io and java.nio.files:
        // * java.nio.file allows to open directories as streams, java.io.FileInputStream doesn't.
        // * java.nio.file doesn't work well with pseudo devices, i.e., `seek()` fails, while java.io works well.
        try {
            if (Files.readAttributes(nioPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                return nioPath;
            }
        } catch (NoSuchFileException ignored) {
            return nioPath;
        } catch (IOException ignored) {
            // Ignored.
        }

        return null;
    }

    /**
     * <a href="https://learn.microsoft.com/en-us/windows/win32/ipc/pipe-names">
     * The pipe path format: {@code ^\\(\w+|\.)\pipe\.*}
     * </a>
     */
    private static boolean isWindowsPipe(Path path) {
        // A small JMH benchmark shows that this code takes less than a microsecond,
        // and the JIT compiler does its job very well here.
        return path.isAbsolute() &&
                path.getRoot().toString().startsWith("\\\\") &&
                path.getRoot().toString().toLowerCase(Locale.getDefault()).endsWith("\\pipe\\");
    }

    private static boolean setPermission0(java.nio.file.FileSystem nioFs, File f, int access, boolean enable, boolean owneronly) {
        if (f.getPath().isEmpty()) {
            if (nioFs.getSeparator().equals("\\")) {
                // This behavior is hardcoded in Java_java_io_WinNTFileSystem_setPermission0,
                // and this check happens there before calling GetFileAttributesW.
                return (ACCESS_READ == access || ACCESS_EXECUTE == access) && enable;
            } else {
                // Unlike on Windows,
                // Java_java_io_UnixFileSystem_checkAccess0 has no return statements before chmod(),
                // and chmod() returns an error for an empty path.
                return false;
            }
        }

        Path path;
        try {
            path = nioFs.getPath(f.getPath());
        } catch (InvalidPathException err) {
            throw new InternalError(err.getMessage(), err);
        }

        // See the comment inside getBooleanAttributes that explains the order of file attribute view classes.
        BasicFileAttributeView view;
        try {
            view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        } catch (UnsupportedOperationException ignored) {
            view = null;
        }
        if (view == null) {
            try {
                view = Files.getFileAttributeView(path, DosFileAttributeView.class);
            } catch (UnsupportedOperationException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("File system %s supports neither posix nor dos attributes", nioFs), err)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }

        boolean result = true;
        if (view instanceof DosFileAttributeView dosView) {
            result = false;
            try {
                // Both the original java.io.File.setReadOnly
                // and sun.nio.fs.WindowsFileAttributeViews.Dos.setReadOnly
                // use SetFileAttributesW.
                //
                // https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfileattributesw
                // The documentation states:
                // > This attribute is not honored on directories.
                // However, SetFileAttributesW always returns 0, not changing anything.
                // In java.io.File (see Java_java_io_WinNTFileSystem_setReadOnly0), there's an explicit check
                // if it is a directory.
                // On the contrary, there's no such check in WindowsFileAttributeViews.
                if ((access & ACCESS_WRITE) != 0 && !dosView.readAttributes().isDirectory()) {
                    dosView.setReadOnly(!enable);
                    result = true;
                } else if (access == ACCESS_READ || access == ACCESS_EXECUTE) {
                    result = enable; // Like in Java_java_io_WinNTFileSystem_setPermission0
                }
            } catch (IOException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't set read only attributes for %s", f), err)
                            .printStackTrace(System.err);
                }
            }
        } else if (view instanceof PosixFileAttributeView posixView) {
            try {
                Set<PosixFilePermission> perms = posixView.readAttributes().permissions();

                if ((access & ACCESS_READ) != 0) {
                    if (enable) {
                        perms.add(PosixFilePermission.OWNER_READ);
                    } else {
                        perms.remove(PosixFilePermission.OWNER_READ);
                    }
                    if (!owneronly) {
                        if (enable) {
                            perms.add(PosixFilePermission.GROUP_READ);
                            perms.add(PosixFilePermission.OTHERS_READ);
                        } else {
                            perms.remove(PosixFilePermission.GROUP_READ);
                            perms.remove(PosixFilePermission.OTHERS_READ);
                        }
                    }
                }

                if ((access & ACCESS_WRITE) != 0) {
                    if (enable) {
                        perms.add(PosixFilePermission.OWNER_WRITE);
                    } else {
                        perms.remove(PosixFilePermission.OWNER_WRITE);
                    }
                    if (!owneronly) {
                        if (enable) {
                            perms.add(PosixFilePermission.GROUP_WRITE);
                            perms.add(PosixFilePermission.OTHERS_WRITE);
                        } else {
                            perms.remove(PosixFilePermission.GROUP_WRITE);
                            perms.remove(PosixFilePermission.OTHERS_WRITE);
                        }
                    }
                }

                if ((access & ACCESS_EXECUTE) != 0) {
                    if (enable) {
                        perms.add(PosixFilePermission.OWNER_EXECUTE);
                    } else {
                        perms.remove(PosixFilePermission.OWNER_EXECUTE);
                    }
                    if (!owneronly) {
                        if (enable) {
                            perms.add(PosixFilePermission.GROUP_EXECUTE);
                            perms.add(PosixFilePermission.OTHERS_EXECUTE);
                        } else {
                            perms.remove(PosixFilePermission.GROUP_EXECUTE);
                            perms.remove(PosixFilePermission.OTHERS_EXECUTE);
                        }
                    }
                }

                posixView.setPermissions(perms);
            } catch (IOException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't set posix attributes for %s", f), err)
                            .printStackTrace(System.err);
                }
                result = false;
            }
        }

        return result;
    }

    record StreamInitializationBundle(
            FileDescriptor fd,
            FileChannel channel,
            ExternalChannelHolder externalChannelHolder
    ) {
    }

    static StreamInitializationBundle initializeStreamUsingNio(
            Closeable owner,
            java.nio.file.FileSystem nioFs,
            File file,
            Path nioPath,
            Set<OpenOption> optionsForChannel,
            NioChannelCleanable channelCleanable) throws FileNotFoundException {
        try {
            // This tricky thread local variable allows specifying an argument for sun.nio.ch.FileChannelImpl.<init>
            // which is not present in the NIO public API and which is not easy to specify another way.
            IoOverNio.ParentForFileChannelImplHolder.set(owner);
            return initializeStreamsUsingNio0(owner, nioFs, file, nioPath, optionsForChannel, channelCleanable);
        } finally {
            IoOverNio.ParentForFileChannelImplHolder.remove();
        }
    }

    private static StreamInitializationBundle initializeStreamsUsingNio0(
            Closeable owner,
            java.nio.file.FileSystem nioFs,
            File file,
            Path nioPath,
            Set<OpenOption> optionsForChannel,
            NioChannelCleanable channelCleanable) throws FileNotFoundException {
        FileChannel channel = null;
        try {
            channel = nioFs.provider().newFileChannel(nioPath, optionsForChannel);
            return new StreamInitializationBundle(
                    initializeFdUsingNio(owner, channel, channelCleanable),
                    channel,
                    new ExternalChannelHolder(owner, channel, channelCleanable)
            );
        } catch (IOException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("Can't create a %s for %s with %s", owner.getClass().getSimpleName(), file, nioFs), err)
                        .printStackTrace(System.err);
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Nothing.
                }
            }
            throw IoOverNioFileSystem.convertNioToIoExceptionInStreams(err);
        }
    }

    private static FileDescriptor initializeFdUsingNio(
            Closeable owner,
            FileChannel channel,
            NioChannelCleanable channelCleanable) {
        final FileDescriptor fd;
        if (channel instanceof FileChannelImpl fci) {
            fd = fci.getFD();
            fd.attach(owner);
            FileCleanable.register(fd);
            fci.setUninterruptible();
        } else {
            channelCleanable.setChannel(channel);
            fd = new FileDescriptor();
            fd.registerCleanup(new NoOpCleanable(fd));
        }
        return fd;
    }

    @Override
    public char getSeparator() {
        return parent.getSeparator();
    }

    @Override
    public char getPathSeparator() {
        return parent.getPathSeparator();
    }

    @Override
    public String normalize(String path) {
        // java.nio.file.Path.normalize works differently compared to this normalizer.
        // Especially, Path.normalize converts "." into an empty string, which breaks
        // even tests in OpenJDK.
        return parent.normalize(path);
    }

    @Override
    public int prefixLength(String path) {
        // Although it's possible to represent this function via `Path.getRoot`,
        // the default file system and java.nio handle corner cases with incorrect paths differently.
        return parent.prefixLength(path);
    }

    @Override
    public String resolve(String parent, String child) {
        // java.nio is stricter to various invalid symbols than java.io.
        return this.parent.resolve(parent, child);
    }

    @Override
    public String getDefaultParent() {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(null);
        if (nioFs != null) {
            return nioFs.getSeparator();
        }
        return parent.getDefaultParent();
    }

    @Override
    public String fromURIPath(String path) {
        return parent.fromURIPath(path);
    }

    @Override
    public boolean isAbsolute(File f) {
        boolean result = isAbsolute0(f);
        if (DEBUG.writeTraces()) {
            System.err.printf("IoOverNioFileSystem.isAbsolute(%s) = %b%n", f, result);
        }
        return result;
    }

    private boolean isAbsolute0(File f) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            try {
                return nioFs.getPath(f.getPath()).isAbsolute();
            } catch (InvalidPathException err) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't check if a path %s is absolute", f), err)
                            .printStackTrace(System.err);
                }
            }
        }
        return parent.isAbsolute(f);
    }

    @Override
    public boolean isInvalid(File f) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            if (nioFs.getSeparator().equals("\\") && f.getPath().equals("NUL:")) {
                return false;
            }
            try {
                Path ignored = nioFs.getPath(f.getPath());
                return false;
            } catch (InvalidPathException ignored) {
                return true;
            }
        }
        return parent.isInvalid(f);
    }

    @Override
    public String resolve(File f) {
        try {
            String result = resolve0(f);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.resolve(%s) = %s%n", f, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.resolve(%s) threw an error%n", f), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private String resolve0(File f) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            String initialPath = f.toString();
            if (getSeparator() == '\\' && prefixLength(initialPath) == 2 && initialPath.charAt(0) == '\\') {
                // This behavior is covered by `test/jdk/java/io/File/GetAbsolutePath.java`.
                // If this condition passed through Path.toAbsolutePath, an additional excessive slash
                // might be added at the end, and the behavior would differ from the original java.io.File.getAbsolutePath.
                return initialPath;
            }

            try {
                String resolvedPath = nioFs.getPath(f.getPath()).toAbsolutePath().toString();
                if (
                        initialPath.length() > 2
                                && getSeparator() == '\\'
                                && initialPath.charAt(1) == ':'
                                && initialPath.charAt(2) != '\\'
                                && initialPath.charAt(0) != System.getProperty("user.dir", "?").charAt(0)
                ) {
                    // The source is a tricky drive-relative path,
                    // and the current working directory is on a different drive.
                    // For unclear reasons, the resolved path must have two backslashes after the colon.
                    // This behavior is covered by `test/jdk/java/io/File/GetAbsolutePath.java`.
                    resolvedPath = resolvedPath.substring(0, 2) + '\\' + resolvedPath.substring(2);
                }
                return resolvedPath;
            } catch (InvalidPathException err) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't resolve a path %s", f), err)
                            .printStackTrace(System.err);
                }
            }
        }
        return parent.resolve(f);
    }

    @Override
    public String canonicalize(String path) throws IOException {
        try {
            String result = canonicalize0(path);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.canonicalize(%s) = %s%n", path, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.canonicalize(%s) threw an error%n", path), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private String canonicalize0(String path) throws IOException {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(path);
        if (nioFs != null) {
            try {
                // Unlike java.nio.file.Path.toRealPath, File.getCanonicalFile works with non-existent files
                // and resolves symlinks for the first existing parent.
                Path nioPath = nioFs.getPath(path);
                if (!nioPath.isAbsolute()) {
                    nioPath = Path.of(System.getProperty("user.dir")).resolve(nioPath);
                }

                boolean isTrickyDriveRelativePath = !nioPath.toString().startsWith(nioPath.getRoot().toString());

                Path suffix = nioFs.getPath("");

                while (!nioPath.equals(nioPath.getRoot())) {
                    Path parent = nioPath.getParent();
                    if (parent == null) {
                        parent = nioPath.getRoot();
                    }

                    String fileNameStr = nioPath.getFileName().toString();

                    if (!fileNameStr.isEmpty() && !fileNameStr.equals(".")) {
                        try {
                            nioPath = nioPath.toRealPath();
                            break;
                        } catch (IOException ignored) {
                            // Nothing.
                        }
                        suffix = nioPath.getFileName().resolve(suffix);
                    }
                    nioPath = parent;
                }

                Path result = nioPath.resolve(suffix).normalize();

                // It's not clear why it should work like that,
                // but this behaviour is checked by the test `GetAbsolutePath.windowsDriveRelative`.
                if (isTrickyDriveRelativePath) {
                    StringBuilder sb = new StringBuilder(result.getRoot().toString());
                    for (int i = 0; i < result.getNameCount(); i++) {
                        sb.append(getSeparator());
                        sb.append(result.getName(i).toString());
                    }
                    return sb.toString();
                } else {
                    return result.toString();
                }
            } catch (InvalidPathException err) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't canonicalize a path %s", path), err)
                            .printStackTrace(System.err);
                }
            }
        }
        return parent.canonicalize(path);
    }

    @Override
    public boolean hasBooleanAttributes(File f, int attributes) {
        try {
            boolean result = hasBooleanAttributes0(f, attributes);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.hasBooleanAttributes(%s, %s) = %b%n", f, attributes, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.hasBooleanAttributes(%s, %s) threw an error", f, attributes), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    @SuppressWarnings("resource")
    private boolean hasBooleanAttributes0(File f, int attributes) {
        boolean result;
        if (acquireNioFs(f.getPath()) != null) {
            result = (getBooleanAttributes0(f) & attributes) == attributes;
        } else {
            result = parent.hasBooleanAttributes(f, attributes);
        }
        if (DEBUG.writeTraces()) {
            System.err.printf("IoOverNioFileSystem.hasBooleanAttributes(%s, %s) = %b%n", f, attributes, result);
        }
        return result;
    }

    @Override
    public int getBooleanAttributes(File f) {
        int result = getBooleanAttributes0(f);
        if (DEBUG.writeTraces()) {
            System.err.printf("IoOverNioFileSystem.getBooleanAttributes(%s) = %d%n", f, result);
        }
        return result;
    }

    private int getBooleanAttributes0(File f) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());

                {
                    boolean isAbsoluteOrDriveRelative;
                    if (path.isAbsolute()) {
                        isAbsoluteOrDriveRelative = true;
                    } else if (getSeparator() == '\\') {
                        String pathString = path.toString();
                        isAbsoluteOrDriveRelative = switch (pathString.length()) {
                            case 0 -> false;
                            case 1 -> pathString.charAt(0) == '\\';
                            case 2 -> Character.isLetter(pathString.charAt(0)) && pathString.charAt(1) == ':';
                            default -> Character.isLetter(pathString.charAt(0)) && pathString.charAt(1) == ':' && pathString.charAt(2) != '\\';
                        };
                    } else {
                        isAbsoluteOrDriveRelative = false;
                    }
                    if (!isAbsoluteOrDriveRelative && (path.getFileName() == null || path.getFileName().toString().isEmpty())) {
                        // The LibC function `stat` returns an error for such calls.
                        return 0;
                    }
                }

                // The order of checking Posix attributes first and DOS attributes next is deliberate.
                // WindowsFileSystem supports these attributes: basic, dos, acl, owner, user.
                // LinuxFileSystem supports: basic, posix, unix, owner, dos, user.
                // MacOSFileSystem and BsdFileSystem support: basic, posix, unix, owner, user.
                //
                // Notice that Linux FS supports DOS attributes, which is unexpected.
                // The DOS adapter implementation from LinuxFileSystem uses `open` for getting attributes.
                // It's not only more expensive than `stat`, but also doesn't work with Unix sockets.
                //
                // Also, notice that Windows FS does not support Posix attributes, which is expected.
                // Checking for Posix attributes first prevents from checking DOS attributes on Linux,
                // even though Posix permissions aren't used in this method.
                BasicFileAttributes attrs = null;
                Set<String> supportedFileAttributeViews = path.getFileSystem().supportedFileAttributeViews();
                if (supportedFileAttributeViews.contains("posix")) {
                    try {
                        attrs = Files.readAttributes(path, PosixFileAttributes.class);
                    } catch (UnsupportedOperationException | SecurityException ignored) {
                        // Nothing.
                    }
                }
                if (attrs == null && supportedFileAttributeViews.contains("dos")) {
                    try {
                        attrs = Files.readAttributes(path, DosFileAttributes.class);
                    } catch (UnsupportedOperationException | SecurityException ignored) {
                        // Nothing.
                    }
                }
                if (attrs == null) {
                    attrs = Files.readAttributes(path, BasicFileAttributes.class);
                }

                return BA_EXISTS
                        | (attrs.isDirectory() ? BA_DIRECTORY : 0)
                        | (attrs.isRegularFile() ? BA_REGULAR : 0)
                        | (attrs instanceof DosFileAttributes dosAttrs && dosAttrs.isHidden() ? BA_HIDDEN : 0)
                        // Just like in java.io.UnixFileSystem.isHidden
                        | (attrs instanceof PosixFileAttributes && f.getName().startsWith(".") ? BA_HIDDEN : 0);
            } catch (InvalidPathException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't get attributes for a path %s", f), err)
                            .printStackTrace(System.err);
                }
                // Path parsing in java.nio is stricter than in java.io.
                // Not returning here to give a chance to the original implementation.
            } catch (@SuppressWarnings("removal") IOException | AccessControlException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't get attributes for a path %s", f), err)
                            .printStackTrace(System.err);
                }
                return getSeparator() == '/' && f.getName().startsWith(".") ? BA_HIDDEN : 0;
            }
        }
        return parent.getBooleanAttributes(f);
    }

    @Override
    public boolean checkAccess(File f, int access) {
        boolean result = checkAccess0(f, access);
        if (DEBUG.writeTraces()) {
            System.err.printf("IoOverNioFileSystem.checkAccess(%s, %d) = %b%n", f, access, result);
        }
        return result;
    }

    private boolean checkAccess0(File f, int access) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            if (f.getPath().isEmpty()) {
                // Both access() in Posix and GetFileAttributesW in Windows treat an empty path
                // as an invalid argument and return an error.
                return false;
            }

            try {
                Path path = nioFs.getPath(f.getPath());

                int i = 0;
                AccessMode[] accessMode = new AccessMode[3];
                if ((access & ACCESS_READ) != 0) {
                    accessMode[i++] = AccessMode.READ;
                }
                if ((access & ACCESS_WRITE) != 0) {
                    accessMode[i++] = AccessMode.WRITE;
                }
                if ((access & ACCESS_EXECUTE) != 0) {
                    accessMode[i++] = AccessMode.EXECUTE;
                }
                accessMode = Arrays.copyOf(accessMode, i);

                nioFs.provider().checkAccess(path, accessMode);
                return true;
            } catch (@SuppressWarnings("removal") IOException | AccessControlException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't check access for a path %s", f), err)
                            .printStackTrace(System.err);
                }
                return false;
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            }
        }
        return parent.checkAccess(f, access);
    }

    @Override
    public boolean setPermission(File f, int access, boolean enable, boolean owneronly) {
        boolean result = setPermission0(f, access, enable, owneronly);
        if (DEBUG.writeTraces()) {
            System.err.printf("IoOverNioFileSystem.setPermission(%s, %d, %b, %b) = %b%n", f, access, enable, owneronly, result);
        }
        return result;
    }

    private boolean setPermission0(File f, int access, boolean enable, boolean owneronly) {
        java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            return setPermission0(nioFs, f, access, enable, owneronly);
        }
        return parent.setPermission(f, access, enable, owneronly);
    }

    @Override
    public long getLastModifiedTime(File f) {
        try {
            long result = getLastModifiedTime0(f);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.getLastModifiedTime(%s) = %d%n", f, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.getLastModifiedTime(%s) threw an error", f), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private long getLastModifiedTime0(File f) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());
                nioFs.provider().readAttributes(path, BasicFileAttributes.class).lastModifiedTime();
            } catch (IOException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't get last modified time for a path %s", f), err)
                            .printStackTrace(System.err);
                }
                return 0;
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            }
        }
        return parent.getLastModifiedTime(f);
    }

    @Override
    public long getLength(File f) {
        try {
            long result = getLength0(f);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.getLength(%s) = %d%n", f, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.getLength(%s) threw an error%n", f), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private long getLength0(File f) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        try {
            if (nioFs != null) {
                Path path = nioFs.getPath(f.getPath());
                return nioFs.provider().readAttributes(path, BasicFileAttributes.class).size();
            }
        } catch (IOException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("Can't get file length for a path %s", f), err)
                        .printStackTrace(System.err);
            }
            return 0;
        } catch (InvalidPathException err) {
            throw new InternalError(err.getMessage(), err);
        }
        return parent.getLength(f);
    }

    @Override
    public boolean createFileExclusively(String pathname) throws IOException {
        try {
            boolean result = createFileExclusively0(pathname);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.createFileExclusively(%s) = %b%n", pathname, result);
            }
            return result;
        } catch (IOException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.createFileExclusively(%s) threw an error", pathname), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private boolean createFileExclusively0(String pathname) throws IOException {
        java.nio.file.FileSystem nioFs = acquireNioFs(pathname);
        try {
            // In case of an empty pathname, the default file system always throws an exception.
            if (pathname != null && !pathname.isEmpty() && nioFs != null) {
                Path path = nioFs.getPath(pathname);
                nioFs.provider().newByteChannel(
                        path,
                        EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                ).close();
                return true;
            }
        } catch (AccessDeniedException err) {
            // A special case for Windows. It can happen if it is an already existing directory.
            if (Files.exists(nioFs.getPath(pathname))) {
                return false;
            }
        } catch (FileAlreadyExistsException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("Can't exclusively create a file %s", pathname), err)
                        .printStackTrace(System.err);
            }
            return false;
        } catch (InvalidPathException err) {
            throw new IOException(err.getMessage(), err); // The default file system would throw IOException too.
        } catch (IOException err) {
            String originalMessage = IoToNioErrorMessageHolder.removeMessage(err);
            if (originalMessage != null) {
                throw new IOException(originalMessage, err);
            } else {
                throw err;
            }
        }
        return parent.createFileExclusively(pathname);
    }

    @Override
    public boolean delete(File f) {
        try (var guard = IoOverNio.RecursionGuard.create("IoOverNioFileSystem.delete")) {
            IoOverNio.blackhole(guard);
            boolean result = delete0(f, true);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.delete(%s) = %b%n", f, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.delete(%s) threw an error", f), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private boolean delete0(File f, boolean mayRepeat) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            Path path = null;
            try {
                path = nioFs.getPath(f.getPath());
                nioFs.provider().delete(path);
                return true;
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            } catch (IOException err) {
                if (err instanceof AccessDeniedException && mayRepeat && path != null && nioFs.supportedFileAttributeViews().contains("dos")) {
                    try {
                        DosFileAttributeView attrs = nioFs.provider()
                                .getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (attrs != null) {
                            attrs.setReadOnly(false);
                            return delete0(f, false);
                        }
                    } catch (IOException ignored) {
                        // Nothing.
                    }
                }
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't delete a path %s", f), err)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }
        return parent.delete(f);
    }

    @Override
    public String[] list(File f) {
        try {
            String[] result = list0(f);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.list(%s) = %s%n", f, Arrays.toString(result));
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.list(%s) threw an error", f), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private String[] list0(File f) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            String pathStr = f.getPath();
            if (pathStr.isEmpty()) {
                // Java_java_io_UnixFileSystem_list0 calls opendir(), and it returns an error for an empty path.
                // Java_java_io_WinNTFileSystem_list0 returns null
                // after calling fileToNTPath and before any WinAPI call.
                return null;
            }

            try {
                if (getSeparator() == '\\') {
                    // Java_java_io_WinNTFileSystem_list0 deliberately and explicitly removes trailing spaces from the path.
                    // It doesn't happen in Java_java_io_UnixFileSystem_list0
                    int i = pathStr.length();
                    while (i > 0 && pathStr.charAt(i - 1) == ' ') {
                        i--;
                    }
                    pathStr = pathStr.substring(0, i);
                }

                Path path = nioFs.getPath(pathStr);
                try (DirectoryStream<Path> children = nioFs.provider().newDirectoryStream(path, AcceptAllFilter.FILTER)) {
                    List<String> result = new ArrayList<>();
                    for (Path child : children) {
                        result.add(child.getFileName().toString());
                    }
                    return result.toArray(String[]::new);
                }
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            } catch (IOException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't list a path %s", f), err)
                            .printStackTrace(System.err);
                }
                return null;
            }
        }
        return parent.list(f);
    }

    @Override
    public boolean createDirectory(File f) {
        try {
            boolean result = createDirectory0(f);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.createDirectory(%s) = %b%n", f, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.createDirectory(%s) threw an error", f), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private boolean createDirectory0(File f) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());
                nioFs.provider().createDirectory(path);
                return true;
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            } catch (IOException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't create a directory %s", f), err)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }
        return parent.createDirectory(f);
    }

    @Override
    public boolean rename(File f1, File f2) {
        try {
            boolean result = rename0(f1, f2);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.rename(%s, %s) = %b%n", f1, f2, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.rename(%s, %s) threw an error", f1, f2), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private boolean rename0(File f1, File f2) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(null);
        if (nioFs != null) {
            try {
                Path path1 = nioFs.getPath(f1.getPath());
                Path path2 = nioFs.getPath(f2.getPath());
                nioFs.provider().move(path1, path2, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            } catch (IOException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't rename %s to %s", f1, f2), err)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }
        return parent.rename(f1, f2);
    }

    @Override
    public boolean setLastModifiedTime(File f, long time) {
        try {
            boolean result = setLastModifiedTime0(f, time);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.setLastModifiedTime(%s, %d) = %b%n", f, time, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.setLastModifiedTime(%s, %d) threw an error", f, time), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private boolean setLastModifiedTime0(File f, long time) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            if (f.getPath().isEmpty()) {
                // Here happens the same as in checkAccess0.
                return false;
            }

            try {
                Path path = nioFs.getPath(f.getPath());
                var view = nioFs.provider().getFileAttributeView(path, BasicFileAttributeView.class);
                if (view != null) {
                    view.setTimes(FileTime.fromMillis(time), null, null);
                    return true;
                } else {
                    return false;
                }
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            } catch (IOException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't set last modified time of %s", f), err)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }
        return parent.setLastModifiedTime(f, time);
    }

    @Override
    public boolean setReadOnly(File f) {
        try {
            java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
            boolean result;
            if (nioFs != null) {
                result = setPermission0(nioFs, f, ACCESS_WRITE, false, false);
            } else {
                result = parent.setReadOnly(f);
            }
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.setReadOnly(%s) = %b%n", f, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.setReadOnly(%s) threw an error", f), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    @Override
    public File[] listRoots() {
        try {
            File[] result = listRoots0();
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.listRoots() = %s%n", Arrays.toString(result));
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable("IoOverNioFileSystem.listRoots() threw an error", err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private File[] listRoots0() {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(null);
        if (nioFs != null) {
            List<File> roots = new ArrayList<>();
            for (Path rootDirectory : nioFs.getRootDirectories()) {
                roots.add(rootDirectory.toFile());
            }
            return roots.toArray(File[]::new);
        }
        return parent.listRoots();
    }

    @Override
    public long getSpace(File f, int t) {
        try {
            long result = getSpace0(f, t);
            if (DEBUG.writeTraces()) {
                System.err.printf("IoOverNioFileSystem.getSpace(%s, %d) = %d%n", f, t, result);
            }
            return result;
        } catch (RuntimeException err) {
            if (DEBUG.writeErrors()) {
                new Throwable(String.format("IoOverNioFileSystem.getSpace(%s, %d) threw an error", f, t), err)
                        .printStackTrace(System.err);
            }
            throw err;
        }
    }

    private long getSpace0(File f, int t) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(f.getPath());
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());
                FileStore store = nioFs.provider().getFileStore(path);
                return switch (t) {
                    case SPACE_TOTAL -> store.getTotalSpace();
                    case SPACE_USABLE -> store.getUsableSpace();
                    case SPACE_FREE -> store.getUnallocatedSpace();
                    default -> throw new IllegalArgumentException("Invalid space type: " + t);
                };
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            } catch (IOException err) {
                if (DEBUG.writeErrors()) {
                    new Throwable(String.format("Can't get space %s for a path %s", t, f), err)
                            .printStackTrace(System.err);
                }
                return 0;
            }
        }
        return parent.getSpace(f, t);
    }

    @Override
    public int getNameMax(String path) {
        // Seems impossible with java.nio.
        return parent.getNameMax(path);
    }

    @Override
    public int compare(File f1, File f2) {
        @SuppressWarnings("resource") java.nio.file.FileSystem nioFs = acquireNioFs(null);
        if (nioFs != null) {
            try {
                Path p1 = nioFs.getPath(f1.getPath());
                Path p2 = nioFs.getPath(f2.getPath());
                return p1.compareTo(p2);
            } catch (InvalidPathException ignored) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
            }
        }
        return parent.compare(f1, f2);
    }

    @Override
    public int hashCode(File f) {
        return parent.hashCode(f);
    }

    private static class AcceptAllFilter implements DirectoryStream.Filter<Path> {
        static final AcceptAllFilter FILTER = new AcceptAllFilter();

        private AcceptAllFilter() {
        }

        @Override
        public boolean accept(Path entry) {
            return true;
        }
    }
}
