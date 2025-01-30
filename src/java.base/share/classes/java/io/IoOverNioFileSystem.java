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

import com.sun.IoOverNio;
import jdk.internal.misc.VM;
import sun.security.action.GetPropertyAction;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

class IoOverNioFileSystem extends FileSystem {
    static final boolean DEBUG = GetPropertyAction.privilegedGetBooleanProp("jbr.java.io.use.nio.debug", false, null);
    private final FileSystem defaultFileSystem;

    IoOverNioFileSystem(FileSystem defaultFileSystem) {
        this.defaultFileSystem = defaultFileSystem;
    }

    java.nio.file.FileSystem acquireNioFs() {
        if (!VM.isBooted()) {
            return null;
        }

        if (!IoOverNio.ALLOW_IO_OVER_NIO.get()) {
            return null;
        }

        try {
            return FileSystems.getDefault();
        } catch (NullPointerException ignored) {
            // TODO Explain.
            // TODO Probably, it can be removed now.
        }

        return null;
    }

    private static boolean setPermission0(java.nio.file.FileSystem nioFs, File f, int access, boolean enable, boolean owneronly) {
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
                if (DEBUG) {
                    new Throwable(String.format("File system %s supports neither posix nor dos attributes", nioFs), err)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }

        boolean result = true;
        if (view instanceof DosFileAttributeView dosView) {
            if (((access & ACCESS_READ) != 0)) {
                try {
                    dosView.setReadOnly(enable);
                } catch (IOException e) {
                    if (DEBUG) {
                        new Throwable(String.format("Can't set read only attributes for %s with %s", f, nioFs), e)
                                .printStackTrace(System.err);
                    }
                    result = false;
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
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't set posix attributes for %s with %s", f, nioFs), e)
                            .printStackTrace(System.err);
                }
                result = false;
            }
        }

        return result;
    }

    @Override
    public char getSeparator() {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            return nioFs.getSeparator().charAt(0);
        }
        return defaultFileSystem.getSeparator();
    }

    @Override
    public char getPathSeparator() {
        return defaultFileSystem.getPathSeparator();
    }

    @Override
    public String normalize(String path) {
        // java.nio.file.Path.normalize works differently compared to this normalizer.
        // Especially, Path.normalize converts "." into an empty string, which breaks
        // even tests in OpenJDK.
        return defaultFileSystem.normalize(path);
    }

    @Override
    public int prefixLength(String path) {
        // Although it's possible to represent this function via `Path.getRoot`,
        // the default file system and java.nio handle corner cases with incorrect paths differently.
        return defaultFileSystem.prefixLength(path);
    }

    @Override
    public String resolve(String parent, String child) {
        // java.nio is stricter to various invalid symbols than java.io.
        return defaultFileSystem.resolve(parent, child);
    }

    @Override
    public String getDefaultParent() {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            return nioFs.getSeparator();
        }
        return defaultFileSystem.getDefaultParent();
    }

    @Override
    public String fromURIPath(String path) {
        return defaultFileSystem.fromURIPath(path);
    }

    @Override
    public boolean isAbsolute(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                return nioFs.getPath(f.getPath()).isAbsolute();
            } catch (InvalidPathException err) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
                if (DEBUG) {
                    new Throwable(String.format("Can't check if a path is absolute with %s", nioFs), err)
                            .printStackTrace(System.err);
                }
            }
        }
        return defaultFileSystem.isAbsolute(f);
    }

    @Override
    public boolean isInvalid(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path ignored = nioFs.getPath(f.getPath());
                return false;
            } catch (InvalidPathException ignored) {
                return true;
            }
        }
        return defaultFileSystem.isInvalid(f);
    }

    @Override
    public String resolve(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                return nioFs.getPath(f.getPath()).toAbsolutePath().toString();
            } catch (InvalidPathException err) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
                if (DEBUG) {
                    new Throwable(String.format("Can't resolve a path with %s", nioFs), err)
                            .printStackTrace(System.err);
                }
            }
        }
        return defaultFileSystem.resolve(f);
    }

    @Override
    public String canonicalize(String path) throws IOException {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path nioPath = nioFs.getPath(path);
                if (!nioPath.isAbsolute()) {
                    nioPath = Path.of(System.getProperty("user.dir")).resolve(nioPath);
                }
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
                        } catch (IOException err) {
                            // Nothing.
                        }
                        suffix = nioPath.getFileName().resolve(suffix);
                    }
                    nioPath = parent;
                }

                return nioPath.resolve(suffix).normalize().toString();
            } catch (InvalidPathException err) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
                if (DEBUG) {
                    new Throwable(String.format("Can't canonicalize a path %s with %s", path, nioFs), err)
                            .printStackTrace(System.err);
                }
            }
        }
        return defaultFileSystem.canonicalize(path);
    }

    @Override
    public int getBooleanAttributes(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());

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
                // Checking for Posix attributes allows filtering Windows out,
                // even though Posix permissions aren't used in this method.
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(path, PosixFileAttributes.class);
                } catch (UnsupportedOperationException ignored) {
                    attrs = Files.readAttributes(path, DosFileAttributes.class);
                }

                return BA_EXISTS
                        | (attrs.isDirectory() ? BA_DIRECTORY : 0)
                        | (attrs.isRegularFile() ? BA_REGULAR : 0)
                        | (attrs instanceof DosFileAttributes dosAttrs && dosAttrs.isHidden() ? BA_HIDDEN : 0);
            } catch (InvalidPathException err) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
                if (DEBUG) {
                    new Throwable(String.format("Can't get attributes for a path with %s", nioFs), err)
                            .printStackTrace(System.err);
                }
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't get attributes a path %s with %s", f, nioFs), e)
                            .printStackTrace(System.err);
                }
                return 0;
            }
        }
        return defaultFileSystem.getBooleanAttributes(f);
    }

    @Override
    public boolean checkAccess(File f, int access) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
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
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't check access for a path %s with %s", f, nioFs), e)
                            .printStackTrace(System.err);
                }
                return false;
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            }
        }
        return defaultFileSystem.checkAccess(f, access);
    }

    @Override
    public boolean setPermission(File f, int access, boolean enable, boolean owneronly) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            return setPermission0(nioFs, f, access, enable, owneronly);
        }
        return defaultFileSystem.setPermission(f, access, enable, owneronly);
    }

    @Override
    public long getLastModifiedTime(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());
                nioFs.provider().readAttributes(path, BasicFileAttributes.class).lastModifiedTime();
            } catch (IOException err) {
                if (DEBUG) {
                    new Throwable(String.format("Can't get last modified time for a path %s with %s", f, nioFs), err)
                            .printStackTrace(System.err);
                }
                return 0;
            } catch (InvalidPathException err) {
                throw new InternalError(err.getMessage(), err);
            }
        }
        return defaultFileSystem.getLastModifiedTime(f);
    }

    @Override
    public long getLength(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        try {
            if (nioFs != null) {
                Path path = nioFs.getPath(f.getPath());
                return nioFs.provider().readAttributes(path, BasicFileAttributes.class).size();
            }
        } catch (IOException e) {
            if (DEBUG) {
                new Throwable(String.format("Can't get file length for a path %s with %s", f, nioFs), e)
                        .printStackTrace(System.err);
            }
            return 0;
        } catch (InvalidPathException e) {
            throw new InternalError(e.getMessage(), e);
        }
        return defaultFileSystem.getLength(f);
    }

    @Override
    public boolean createFileExclusively(String pathname) throws IOException {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        try {
            if (nioFs != null) {
                Path path = nioFs.getPath(pathname);
                nioFs.provider().newByteChannel(
                        path,
                        EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                ).close();
                return true;
            }
        } catch (FileAlreadyExistsException e) {
            if (DEBUG) {
                new Throwable(String.format("Can't exclusively create a file %s with %s", pathname, nioFs), e)
                        .printStackTrace(System.err);
            }
            return false;
        } catch (InvalidPathException e) {
            throw new IOException(e.getMessage(), e); // The default file system would throw IOException too.
        }
        return defaultFileSystem.createFileExclusively(pathname);
    }

    @Override
    public boolean delete(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());
                nioFs.provider().delete(path);
                return true;
            } catch (InvalidPathException e) {
                throw new InternalError(e.getMessage(), e);
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't delete a path %s with %s", f, nioFs), e)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }
        return defaultFileSystem.delete(f);
    }

    @Override
    public String[] list(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());
                try (DirectoryStream<Path> children = nioFs.provider().newDirectoryStream(path, AcceptAllFilter.FILTER)) {
                    List<String> result = new ArrayList<>();
                    for (Path child : children) {
                        result.add(child.getFileName().toString());
                    }
                    return result.toArray(String[]::new);
                }
            } catch (InvalidPathException e) {
                throw new InternalError(e.getMessage(), e);
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't list a path %s with %s", f, nioFs), e)
                            .printStackTrace(System.err);
                }
                return null;
            }
        }
        return defaultFileSystem.list(f);
    }

    @Override
    public boolean createDirectory(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());
                nioFs.provider().createDirectory(path);
                return true;
            } catch (InvalidPathException e) {
                throw new InternalError(e.getMessage(), e);
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't create a directory %s with %s", f, nioFs), e)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }
        return defaultFileSystem.createDirectory(f);
    }

    @Override
    public boolean rename(File f1, File f2) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path path1 = nioFs.getPath(f1.getPath());
                Path path2 = nioFs.getPath(f2.getPath());
                nioFs.provider().move(path1, path2, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (InvalidPathException e) {
                throw new InternalError(e.getMessage(), e);
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't rename %s to %s with %s", f1, f2, nioFs), e)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }
        return defaultFileSystem.rename(f1, f2);
    }

    @Override
    public boolean setLastModifiedTime(File f, long time) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path path = nioFs.getPath(f.getPath());
                nioFs.provider()
                        .getFileAttributeView(path, BasicFileAttributeView.class)
                        .setTimes(FileTime.fromMillis(time), null, null);
                return true;
            } catch (InvalidPathException e) {
                throw new InternalError(e.getMessage(), e);
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't set last modified time of %s with %s", f, nioFs), e)
                            .printStackTrace(System.err);
                }
                return false;
            }
        }
        return defaultFileSystem.setLastModifiedTime(f, time);
    }

    @Override
    public boolean setReadOnly(File f) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            return setPermission0(nioFs, f, ACCESS_EXECUTE | ACCESS_WRITE, false, false);
        }
        return defaultFileSystem.setReadOnly(f);
    }

    @Override
    public File[] listRoots() {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            List<File> roots = new ArrayList<>();
            for (Path rootDirectory : nioFs.getRootDirectories()) {
                roots.add(rootDirectory.toFile());
            }
            return roots.toArray(File[]::new);
        }
        return defaultFileSystem.listRoots();
    }

    @Override
    public long getSpace(File f, int t) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
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
            } catch (InvalidPathException e) {
                throw new InternalError(e.getMessage(), e);
            } catch (IOException e) {
                if (DEBUG) {
                    new Throwable(String.format("Can't get space %s for a path %s with %s", t, f, nioFs), e)
                            .printStackTrace(System.err);
                }
                return 0;
            }
        }
        return defaultFileSystem.getSpace(f, t);
    }

    @Override
    public int getNameMax(String path) {
        // TODO Seems to be impossible with java.nio.
        return defaultFileSystem.getNameMax(path);
    }

    @Override
    public int compare(File f1, File f2) {
        java.nio.file.FileSystem nioFs = acquireNioFs();
        if (nioFs != null) {
            try {
                Path p1 = nioFs.getPath(f1.getPath());
                Path p2 = nioFs.getPath(f2.getPath());
                return p1.compareTo(p2);
            } catch (InvalidPathException e) {
                // Path parsing in java.nio is stricter than in java.io.
                // Giving a chance to the original implementation.
            }
        }
        return defaultFileSystem.compare(f1, f2);
    }

    @Override
    public int hashCode(File f) {
        return defaultFileSystem.hashCode(f);
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
