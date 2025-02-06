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
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ManglingFileSystem extends FileSystem {
    private final ManglingFileSystemProvider provider;

    public ManglingFileSystem(ManglingFileSystemProvider fileSystemProvider) {
        this.provider = fileSystemProvider;
    }

    @Override
    public ManglingFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        provider.defaultFs.close();
    }

    @Override
    public boolean isOpen() {
        return provider.defaultFs.isOpen();
    }

    @Override
    public boolean isReadOnly() {
        return provider.defaultFs.isReadOnly();
    }

    @Override
    public String getSeparator() {
        return provider.defaultFs.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        Iterable<Path> delegateRoots = provider.defaultFs.getRootDirectories();
        List<Path> result = new ArrayList<>();
        for (Path delegateRoot : delegateRoots) {
            result.add(new ManglingPath(this, delegateRoot));
        }
        if (ManglingFileSystemProvider.addEliteToEveryDirectoryListing) {
            if (getSeparator().equals("/")) {
                result.add(new ManglingPath(this, Paths.get("/31337")));
            } else {
                result.add(new ManglingPath(this, Paths.get("\\\\r00t\\31337\\")));
            }
        }
        return result;
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return provider.defaultFs.getFileStores();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return provider.defaultFs.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(final String first, final String... more) {
        return new ManglingPath(this, provider.defaultFs.getPath(first, more));
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException();
    }
}
