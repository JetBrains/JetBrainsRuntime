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
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;

public class ManglingPath implements Path {
    final Path delegate;
    private final ManglingFileSystem fileSystem;

    ManglingPath(ManglingFileSystem fileSystem, Path delegate) {
        this.fileSystem = fileSystem;
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ManglingPath paths = (ManglingPath) o;
        return Objects.equals(fileSystem, paths.fileSystem) && Objects.equals(delegate, paths.delegate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileSystem, delegate);
    }

    @Override
    public String toString() {
        if (!ManglingFileSystemProvider.manglePaths) {
            return delegate.toString();
        } else if (!ManglingFileSystemProvider.mangleOnlyFileName) {
            return ManglingFileSystemProvider.mangle(delegate.toString());
        } else if (delegate.getParent() == null) {
            return ManglingFileSystemProvider.mangle(delegate.toString());
        } else {
            String mangledFileName = ManglingFileSystemProvider.mangle(delegate.getFileName().toString());
            return delegate.getParent().resolve(mangledFileName).toString();
        }
    }

    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return delegate.isAbsolute();
    }

    @Override
    public Path getRoot() {
        Path delegateRoot = delegate.getRoot();
        if (delegateRoot == null) {
            return null;
        }
        return new ManglingPath(fileSystem, delegateRoot);
    }

    @Override
    public Path getFileName() {
        return new ManglingPath(fileSystem, delegate.getFileName());
    }

    @Override
    public Path getParent() {
        Path parent = delegate.getParent();
        if (parent != null) {
            return new ManglingPath(fileSystem, parent);
        } else {
            return null;
        }
    }

    @Override
    public int getNameCount() {
        return delegate.getNameCount();
    }

    @Override
    public Path getName(int index) {
        return new ManglingPath(fileSystem, delegate.getName(index));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new ManglingPath(fileSystem, delegate.subpath(beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(Path other) {
        if (other instanceof ManglingPath pcp) {
            return delegate.startsWith(pcp.delegate);
        }
        throw new IllegalArgumentException("Wrong path " + other + " (class: " + other.getClass() + ")");
    }

    @Override
    public boolean endsWith(Path other) {
        if (other instanceof ManglingPath pcp) {
            return delegate.endsWith(pcp.delegate);
        }
        throw new IllegalArgumentException("Wrong path " + other + " (class: " + other.getClass() + ")");
    }

    @Override
    public Path normalize() {
        return new ManglingPath(fileSystem, delegate.normalize());
    }

    @Override
    public Path resolve(Path other) {
        if (other instanceof ManglingPath pcp) {
            return new ManglingPath(fileSystem, delegate.resolve(pcp.delegate));
        }
        throw new IllegalArgumentException("Wrong path " + other + " (class: " + other.getClass() + ")");
    }

    @Override
    public Path relativize(Path other) {
        if (other instanceof ManglingPath pcp) {
            return new ManglingPath(fileSystem, delegate.relativize(pcp.delegate));
        }
        throw new IllegalArgumentException("Wrong path " + other + " (class: " + other.getClass() + ")");
    }

    @Override
    public URI toUri() {
        return delegate.toUri();
    }

    @Override
    public Path toAbsolutePath() {
        return new ManglingPath(fileSystem, delegate.toAbsolutePath());
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        return new ManglingPath(fileSystem, delegate.toRealPath(options));
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(Path other) {
        if (other instanceof ManglingPath pcp) {
            return delegate.compareTo(pcp.delegate);
        }
        return -1;
    }
}
