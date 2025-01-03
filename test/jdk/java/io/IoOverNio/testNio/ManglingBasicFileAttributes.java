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

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

class ManglingBasicFileAttributes implements BasicFileAttributes {
    private final BasicFileAttributes attrs;

    ManglingBasicFileAttributes(BasicFileAttributes attrs) {
        this.attrs = attrs;
    }

    @Override
    public FileTime lastModifiedTime() {
        return attrs.lastModifiedTime();
    }

    @Override
    public FileTime lastAccessTime() {
        return attrs.lastAccessTime();
    }

    @Override
    public FileTime creationTime() {
        return attrs.creationTime();
    }

    @Override
    public boolean isRegularFile() {
        return attrs.isRegularFile() && !ManglingFileSystemProvider.allFilesAreEmptyDirectories;
    }

    @Override
    public boolean isDirectory() {
        return attrs.isDirectory() || ManglingFileSystemProvider.allFilesAreEmptyDirectories;
    }

    @Override
    public boolean isSymbolicLink() {
        return attrs.isSymbolicLink() && !ManglingFileSystemProvider.denyAccessToEverything;
    }

    @Override
    public boolean isOther() {
        return attrs.isOther() && !ManglingFileSystemProvider.denyAccessToEverything;
    }

    @Override
    public long size() {
        return attrs.size();
    }

    @Override
    public Object fileKey() {
        return attrs.fileKey();
    }
}
