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
import jdk.internal.misc.VM;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.AccessControlException;
import java.util.*;

import static com.jetbrains.internal.IoOverNio.DEBUG;

class IoOverNioFileSystem extends FileSystem {
    private final FileSystem defaultFileSystem;

    IoOverNioFileSystem(FileSystem defaultFileSystem) {
        this.defaultFileSystem = defaultFileSystem;
    }

    @Override
    public char getSeparator() {
        return defaultFileSystem.getSeparator();
    }

    @Override
    public char getPathSeparator() {
        return defaultFileSystem.getPathSeparator();
    }

    @Override
    public String normalize(String path) {
        return defaultFileSystem.normalize(path);
    }

    @Override
    public int prefixLength(String path) {
        return defaultFileSystem.prefixLength(path);
    }

    @Override
    public String resolve(String parent, String child) {
        return defaultFileSystem.resolve(parent, child);
    }

    @Override
    public String getDefaultParent() {
        return defaultFileSystem.getDefaultParent();
    }

    @Override
    public String fromURIPath(String path) {
        return defaultFileSystem.fromURIPath(path);
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
        return defaultFileSystem.isAbsolute(f);
    }

    @Override
    public boolean isInvalid(File f) {
        return defaultFileSystem.isInvalid(f);
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
        return defaultFileSystem.resolve(f);
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
        return defaultFileSystem.canonicalize(path);
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
        return defaultFileSystem.hasBooleanAttributes(f, attributes);
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
        return defaultFileSystem.getBooleanAttributes(f);
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
        return defaultFileSystem.checkAccess(f, access);
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
        return defaultFileSystem.setPermission(f, access, enable, owneronly);
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
        return defaultFileSystem.getLastModifiedTime(f);
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
        return defaultFileSystem.getLength(f);
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
        return defaultFileSystem.createFileExclusively(pathname);
    }

    @Override
    public boolean delete(File f) {
        try {
            boolean result = delete0(f);
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

    private boolean delete0(File f) {
        return defaultFileSystem.delete(f);
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
        return defaultFileSystem.list(f);
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
        return defaultFileSystem.createDirectory(f);
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
        return defaultFileSystem.rename(f1, f2);
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
        return defaultFileSystem.setLastModifiedTime(f, time);
    }

    @Override
    public boolean setReadOnly(File f) {
        try {
            boolean result = defaultFileSystem.setReadOnly(f);
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
        return defaultFileSystem.listRoots();
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
        return defaultFileSystem.getSpace(f, t);
    }

    @Override
    public int getNameMax(String path) {
        // Seems impossible with java.nio.
        return defaultFileSystem.getNameMax(path);
    }

    @Override
    public int compare(File f1, File f2) {
        return defaultFileSystem.compare(f1, f2);
    }

    @Override
    public int hashCode(File f) {
        return defaultFileSystem.hashCode(f);
    }
}
