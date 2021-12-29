/*
 * Copyright 2021 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * Copyright (c) 1998, 2021, Oracle and/or its affiliates. All rights reserved.
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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import jdk.internal.misc.VM;

/**
 * Forwards all requests to j.n.f.FileSystem obtained by name from -Djava.io.nio.fs.provider
 */
class ProxyFileSystem extends FileSystem {
    private final char slash;
    private final char colon;

    private final java.nio.file.FileSystem nioFS;

    private static class InstanceHolder {
        static {
            final String nioFsProviderName = System.getProperty("java.io.nio.fs.provider");
            ProxyFileSystem.logLine("Creating ProxyFileSystem() based on the value of -Djava.io.nio.fs.provider=" + nioFsProviderName);

            assert nioFsProviderName != null;

            final FileSystemProvider nioFsProvider = FileSystemProvider.installedProviders().stream()
                    .filter(p -> p.getClass().getName().equals(nioFsProviderName))
                    .findFirst()
                    .orElseThrow(() -> new Error("Couldn't find " + nioFsProviderName + " specified via -Djava.io.nio.fs.provider among FileSystemProvider.installedProviders()"));

            ProxyFileSystem.logLine("Found NIO FileSystemProvider: " + nioFsProvider);
            try {
                INSTANCE = new ProxyFileSystem(nioFsProvider.getFileSystem(new URI(nioFsProvider.getScheme(), null, "/", null)));
                ProxyFileSystem.logLine("Successfully initialized the single instance of ProxyFileSystem");
            } catch (URISyntaxException e) {
                throw new Error(e);
            }
        }
        final static ProxyFileSystem INSTANCE;
    }

    public static FileSystem instance(final String scheme) {
        return InstanceHolder.INSTANCE;
    }

    private ProxyFileSystem(java.nio.file.FileSystem nioFS) {
        assert VM.isModuleSystemInited();

        this.nioFS = nioFS;

        slash = File.getSeparatorChar(); //nioFS.getSeparator().charAt(0);
        colon = File.getPathSeparatorChar(); // nioFS.supportedFileAttributeViews().contains("dos") ? ';' : ':';
        logLine("using '" + slash + "' as slash and '" + colon + "' as colon (path separator)");
    }

    Path getPath(String pathName) {
        return nioFS.getPath(pathName);
    }

    FileChannel newFileChannel(Path path,
                               Set<? extends OpenOption> options,
                               FileAttribute<?>... attrs) throws IOException {
        return nioFS.provider().newFileChannel(path, options, attrs);
    }
    /* -- Normalization and construction -- */

    @Override
    public char getSeparator() {
        logEntry("getSeparator");
        return slash;
    }

    @Override
    public char getPathSeparator() {
        logEntry("getPathSeparator");
        return colon;
    }

    private Path toPath(final String pathname) {
        return nioFS.getPath(pathname);
    }

    private Path toPath(final File f) {
        return nioFS.getPath(f.getPath());
    }

    @Override
    public String normalize(String pathname) {
        logEntry("normalize");
        final String normalized = toPath(pathname).normalize().toString();
        return normalized;
    }

    @Override
    public int prefixLength(String pathname) {
        logEntry("prefixLength");
        // TODO: this seems quite sub-optimal
        final Path path = toPath(pathname);
        final Path rootPath = path.getRoot();

        return rootPath == null ? 0 : rootPath.toString().length();
    }

    @Override
    public String resolve(String parent, String child) {
        logEntry("resolve");
        if (child.isEmpty()) return parent;

        return toPath(parent).resolve(child).toString();
    }

    @Override
    public String getDefaultParent() {
        logEntry("getDefaultParent");
        return String.valueOf(slash);
    }

    @Override
    public String fromURIPath(String path) {
        logEntry("fromURIPath");
        // Using code from WinNTFileSystem.java that seems to be universal and
        // serves Unix-style paths as well.
        String p = path;
        if ((p.length() > 2) && (p.charAt(2) == ':')) {
            // "/c:/foo" --> "c:/foo"
            p = p.substring(1);
            // "c:/foo/" --> "c:/foo", but "c:/" --> "c:/"
            if ((p.length() > 3) && p.endsWith("/"))
                p = p.substring(0, p.length() - 1);
        } else if ((p.length() > 1) && p.endsWith("/")) {
            // "/foo/" --> "/foo"
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /* -- Path operations -- */

    @Override
    public boolean isAbsolute(File f) {
        logEntry("isAbsolute");
        return toPath(f).isAbsolute();
    }

    @Override
    public String resolve(File f) {
        logEntry("resolve");
        return toPath(f).toAbsolutePath().toString();
    }

    @Override
    public String canonicalize(String path) throws IOException {
        logEntry("canonicalize");
        return toPath(path).toAbsolutePath().normalize().toString();
    }

    /* -- Attribute accessors -- */

    public int toBooleanAttributes(final BasicFileAttributes attrs) {
        logEntry("toBooleanAttributes");
        int rv = BA_EXISTS;
        if (attrs.isDirectory()) {
            rv |= BA_DIRECTORY;
        } else if (attrs.isRegularFile()) {
            rv |= BA_REGULAR;
        }
        return rv;
    }

    @Override
    public int getBooleanAttributes(File f) {
        logEntry("getBooleanAttributes");
        final Path path = toPath(f);
        try {
            final BasicFileAttributes attrs =  nioFS.provider().readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            final int rv = toBooleanAttributes(attrs);
            return rv | isHidden(f);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public boolean hasBooleanAttributes(File f, int attributes) {
        logEntry("hasBooleanAttributes");
        final int rv = getBooleanAttributes(f);
        return (rv & attributes) == attributes;
    }

    private int isHidden(File f) {
        logEntry("isHidden");
        try {
            return nioFS.provider().isHidden(toPath(f)) ? BA_HIDDEN : 0;
        } catch (IOException ignored) {
        }

        return 0;
    }

    @Override
    public boolean checkAccess(File f, int access) {
        logEntry("checkAccess");
        final Path path = toPath(f);
        try {
            final ArrayList<AccessMode> modesList = new ArrayList<>(3);
            if ((access & ACCESS_EXECUTE) == ACCESS_EXECUTE) modesList.add(AccessMode.EXECUTE);
            if ((access & ACCESS_WRITE) == ACCESS_WRITE) modesList.add(AccessMode.WRITE);
            if ((access & ACCESS_READ) == ACCESS_READ) modesList.add(AccessMode.READ);
            nioFS.provider().checkAccess(path, modesList.toArray(new AccessMode[0]));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public long getLastModifiedTime(File f) {
        logEntry("getLastModifiedTime");
        try {
            return nioFS.provider().readAttributes(
                    toPath(f), BasicFileAttributes.class).lastModifiedTime().toMillis();
        } catch (IOException ignored) {
        }

        return 0;
    }

    @Override
    public long getLength(File f) {
        logEntry("getLength");
        try {
            return nioFS.provider().readAttributes(toPath(f), BasicFileAttributes.class).size();
        } catch (IOException ignored) {
        }

        return 0;
    }

    @Override
    public boolean setPermission(File f, int access, boolean enable, boolean owneronly) {
        logEntry("setPermission");
        final Path path = toPath(f);
        try {
            final FileStore fs = nioFS.provider().getFileStore(path);
            if (fs.supportsFileAttributeView(DosFileAttributeView.class)) {
                // Follows logic of Java_java_io_WinNTFileSystem_setPermission()
                if (ACCESS_READ == access || ACCESS_EXECUTE == access) {
                    return enable;
                }

                final DosFileAttributeView view = nioFS.provider().getFileAttributeView(path, DosFileAttributeView.class);
                final boolean readOnlyAccess = !enable; // enable means "write" at this point
                if (!view.readAttributes().isDirectory()) {
                    view.setReadOnly(readOnlyAccess);
                    return true;
                }
            } else if (fs.supportsFileAttributeView(PosixFileAttributeView.class)) {
                // Follows logic of Java_java_io_UnixFileSystem_setPermission()
                final PosixFileAttributeView attributeView = nioFS.provider().getFileAttributeView(path, PosixFileAttributeView.class);
                final Set<PosixFilePermission> permissions = attributeView.readAttributes().permissions();
                Set<PosixFilePermission> newPermissions = null;
                switch(access) {
                    case ACCESS_EXECUTE:
                        newPermissions = owneronly
                            ? Set.of(PosixFilePermission.OWNER_EXECUTE)
                            : Set.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);
                        break;
                    case ACCESS_WRITE:
                        newPermissions = owneronly
                                ? Set.of(PosixFilePermission.OWNER_WRITE)
                                : Set.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
                        break;
                    case ACCESS_READ:
                        newPermissions = owneronly
                                ? Set.of(PosixFilePermission.OWNER_READ)
                                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
                        break;
                    default:
                        assert false;
                }

                if (enable)
                    permissions.addAll(newPermissions);
                else
                    permissions.removeAll(newPermissions);

                attributeView.setPermissions(permissions);
                return true;
            }
        } catch (IOException ignored) {
        }

        return false;
    }

    /* -- File operations -- */

    @Override
    public boolean createFileExclusively(String pathName) throws IOException {
        logEntry("createFileExclusively");
        // Roughly based on Java_java_io_WinNTFileSystem_createFileExclusively() and
        // Java_java_io_UnixFileSystem_createFileExclusively()
        final Path path = toPath(pathName);

        try {
            if (nioFS.supportedFileAttributeViews().contains("posix")) {
                FileAttribute<?> attributes = PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-rw-rw-"));
                nioFS.provider().newByteChannel(path,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE),
                        attributes).close();
            } else {
                // TODO: check if the CreateFileW flags end up being really the same as in
                // Java_java_io_WinNTFileSystem_createFileExclusively()
                nioFS.provider().newByteChannel(path,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)).close();
            }

            return true;
        } catch (IOException ignored) {
        }

        return false;
    }

    @Override
    public boolean delete(File f) {
        logEntry("delete");
        final Path path = toPath(f);
        try {
            nioFS.provider().delete(path);
            return true;
        } catch (IOException ignored) {
        }
        return false;
    }

    @Override
    public String[] list(File f) {
        logEntry("list");
        // Based on Java_java_io_WinNTFileSystem_list() and
        // Java_java_io_UnixFileSystem_list()
        final Path path = toPath(f);
        try {
            final Stream<Path> dirStream = Files.list(path);
            return dirStream.map(Path::toString).toArray(String[]::new);
        } catch (IOException ignored) {
        }

        return null;
    }

    @Override
    public boolean createDirectory(File f) {
        logEntry("createDirectory");
        final Path path = toPath(f);
        try {
            nioFS.provider().createDirectory(path);
            return true;
        } catch (IOException ignored) {
        }

        return false;
    }

    @Override
    public boolean rename(File f1, File f2) {
        logEntry("rename");
        final Path path1 = toPath(f1);
        final Path path2 = toPath(f2);

        try {
            nioFS.provider().move(path1, path2, StandardCopyOption.ATOMIC_MOVE, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (IOException ignored) {
        }

        return false;
    }

    @Override
    public boolean setLastModifiedTime(File f, long time) {
        logEntry("setLastModifiedTime");
        // Based on Java_java_io_UnixFileSystem_setLastModifiedTime() and
        // Java_java_io_WinNTFileSystem_setLastModifiedTime()
        final Path path = toPath(f);
        try {
            nioFS.provider().
                    getFileAttributeView(path, BasicFileAttributeView.class).
                    setTimes(FileTime.from(time, TimeUnit.MILLISECONDS), null, null);
            return true;
        } catch (IOException ignored) {
        }

        return false;
    }

    @Override
    public boolean setReadOnly(File f) {
        logEntry("setReadOnly");
        return setPermission(f, ACCESS_WRITE, false, false);
    }

    /* -- Filesystem interface -- */

    @Override
    public File[] listRoots() {
        logEntry("listRoots");
        final ArrayList<File> roots = new ArrayList<>();
        nioFS.getRootDirectories().forEach(path -> { roots.add(new File(path.toString())); } );
        return roots.toArray(new File[0]);
    }

    /* -- Disk usage -- */

    @Override
    public long getSpace(File f, int t) {
        logEntry("getSpace");
        // Based on Java_java_io_UnixFileSystem_getSpace() and
        // Java_java_io_WinNTFileSystem_getSpace0()
        try {
            final FileStore fileStore = nioFS.provider().getFileStore(toPath(f));
            switch (t) {
                case FileSystem.SPACE_TOTAL:
                    return fileStore.getTotalSpace();
                case FileSystem.SPACE_FREE:
                    return fileStore.getUnallocatedSpace();
                case FileSystem.SPACE_USABLE:
                    return fileStore.getUsableSpace();
                default:
                    assert false;
            }
        } catch (IOException ignore) {
        }
        return 0;
    }

    /* -- Basic infrastructure -- */

    @Override
    public int getNameMax(String path) {
        logEntry("getNameMax");
        // This one problably doesn't have to be accurate, but there's no way
        // to get this information without a native call.
        // Also, max path component length isn't really a thing anymore in general.
        return Integer.MAX_VALUE;
    }

    @Override
    public int compare(File f1, File f2) {
        logEntry("compare");
        return f1.getPath().compareTo(f2.getPath());
    }

    @Override
    public int hashCode(File f) {
        logEntry("hashCode");
        return f.getPath().hashCode() ^ 1234321;
    }

    private final static boolean loggingEnabled = System.getProperty("java.io.log.fs") != null;
    static void logLine(final String line) {
        if (loggingEnabled) {
            System.err.println("[ProxyFileSystem] " + line);
        }
    }

    static void logEntry(final String methodName) {
        if (loggingEnabled) {
            System.err.println("[ProxyFileSystem] " + methodName + "() entered");
        }
    }
}

