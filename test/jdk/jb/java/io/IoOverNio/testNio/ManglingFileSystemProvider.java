/*
 * Copyright 2025 JetBrains s.r.o.
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

package testNio;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ManglingFileSystemProvider extends FileSystemProvider {
    public static final String extraContent = "3x7r4";
    private final static byte[] manglingTable = new byte[256];
    public static boolean mangleFileContent = false;
    public static boolean readExtraFileContent = false;
    public static boolean manglePaths = false;
    public static boolean mangleOnlyFileName = false;
    public static boolean denyAccessToEverything = false;
    public static boolean allFilesAreEmptyDirectories = false;
    public static boolean prohibitFileTreeModifications = false;
    public static boolean addEliteToEveryDirectoryListing = false;

    static {
        for (int i = 0; i < manglingTable.length; i++) {
            manglingTable[i] = (byte) i;
        }
        manglingTable['A'] = '4';
        manglingTable['a'] = '4';
        manglingTable['E'] = '3';
        manglingTable['e'] = '3';
        manglingTable['G'] = '9';
        manglingTable['g'] = '9';
        manglingTable['I'] = '1';
        manglingTable['i'] = '1';
        manglingTable['L'] = '1';
        manglingTable['l'] = '1';
        manglingTable['O'] = '0';
        manglingTable['o'] = '0';
        manglingTable['S'] = '5';
        manglingTable['s'] = '5';
        manglingTable['T'] = '7';
        manglingTable['t'] = '7';
        manglingTable['Z'] = '2';
        manglingTable['z'] = '2';

        assert mangle("eleet haxor").equals("31337 h4x0r");
    }

    final FileSystemProvider defaultProvider;
    final FileSystem defaultFs;
    final ManglingFileSystem manglingFs = new ManglingFileSystem(this);

    public ManglingFileSystemProvider(FileSystemProvider defaultProvider) {
        super();
        this.defaultProvider = defaultProvider;
        this.defaultFs = defaultProvider.getFileSystem(URI.create("file:/"));
    }

    public static void resetTricks() {
        mangleFileContent = false;
        readExtraFileContent = false;
        manglePaths = false;
        mangleOnlyFileName = false;
        denyAccessToEverything = false;
        allFilesAreEmptyDirectories = false;
        prohibitFileTreeModifications = false;
        addEliteToEveryDirectoryListing = false;
    }

    public static String mangle(String source) {
        StringBuilder sb = new StringBuilder();
        for (char c : source.toCharArray()) {
            if ((int) c < manglingTable.length) {
                sb.append((char) manglingTable[c]);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static byte[] mangle(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = manglingTable[source[i]];
        }
        return result;
    }

    public static int mangle(int source) {
        if (source >= 0 && source < manglingTable.length) {
            return manglingTable[source];
        }
        return source;
    }

    static Path unwrap(Path path) {
        if (path instanceof ManglingPath pcp) {
            return pcp.delegate;
        }
        throw new IllegalArgumentException("Wrong path " + path + " (class: " + path.getClass() + ")");
    }

    @Override
    public String getScheme() {
        return defaultProvider.getScheme();
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        URI rootUri = URI.create("file:/");
        if (!Objects.equals(uri, rootUri)) {
            throw new UnsupportedOperationException();
        }
        return manglingFs;
    }

    @Override
    public Path getPath(URI uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return new ManglingFileChannel(defaultProvider.newFileChannel(unwrap(path), options, attrs));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return newFileChannel(path, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return new ManglingDirectoryStream(manglingFs, (ManglingPath) dir, defaultProvider.newDirectoryStream(unwrap(dir), filter));
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        if (prohibitFileTreeModifications) {
            throw new AccessDeniedException(dir.toString(), null, "Test: All file tree modifications are prohibited");
        }
        defaultProvider.createDirectory(unwrap(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        if (prohibitFileTreeModifications) {
            throw new AccessDeniedException(path.toString(), null, "Test: All file tree modifications are prohibited");
        }
        defaultProvider.delete(unwrap(path));
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        if (prohibitFileTreeModifications) {
            throw new AccessDeniedException(source.toString(), null, "Test: All file tree modifications are prohibited");
        }
        Path source2 = unwrap(source);
        Path target2 = unwrap(target);
        defaultProvider.copy(source2, target2, options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        if (prohibitFileTreeModifications) {
            throw new AccessDeniedException(source.toString(), null, "Test: All file tree modifications are prohibited");
        }
        Path source2 = unwrap(source);
        Path target2 = unwrap(target);
        defaultProvider.move(source2, target2, options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        Path source2 = unwrap(path);
        Path target2 = unwrap(path2);
        return defaultProvider.isSameFile(source2, target2);
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return defaultProvider.isHidden(unwrap(path));
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return defaultProvider.getFileStore(unwrap(path));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        defaultProvider.checkAccess(unwrap(path), modes);
        if (denyAccessToEverything) {
            throw new AccessDeniedException(path.toString(), null, "Test: No access rules to anything");
        }
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return wrap(defaultProvider.getFileAttributeView(unwrap(path), type, options));
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        return wrap(defaultProvider.readAttributes(unwrap(path), type, options));
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        if (prohibitFileTreeModifications) {
            throw new AccessDeniedException(path.toString(), null, "Test: All file tree modifications are prohibited");
        }
        defaultProvider.setAttribute(unwrap(path), attribute, value, options);
    }

    @Override
    public boolean exists(Path path, LinkOption... options) {
        return defaultProvider.exists(unwrap(path), options);
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
        if (prohibitFileTreeModifications) {
            throw new AccessDeniedException(link.toString(), null, "Test: All file tree modifications are prohibited");
        }
        defaultProvider.createSymbolicLink(unwrap(link), unwrap(target), attrs);
    }

    private <A extends BasicFileAttributes> A wrap(A attrs) {
        if (attrs instanceof DosFileAttributes dosAttrs) {
            return (A) new ManglingDosFileAttributes(dosAttrs);
        }
        if (attrs instanceof PosixFileAttributes posixAttrs) {
            return (A) new ManglingPosixFileAttributes(posixAttrs);
        }
        if (attrs instanceof BasicFileAttributes basicAttrs) {
            return (A) new ManglingBasicFileAttributes(basicAttrs);
        }
        return attrs;
    }

    private <V extends FileAttributeView> V wrap(V view) {
        if (view instanceof DosFileAttributeView dosView) {
            return (V) new ManglingDosFileAttributeView(dosView);
        }
        if (view instanceof PosixFileAttributeView posixView) {
            return (V) new ManglingPosixFileAttributeView(posixView);
        }
        if (view instanceof BasicFileAttributeView basicView) {
            return (V) new ManglingBasicFileAttributeView(basicView);
        }
        return view;
    }
}
