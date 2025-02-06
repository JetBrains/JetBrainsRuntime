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
import java.nio.file.AccessDeniedException;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;

class ManglingDosFileAttributeView extends ManglingBasicFileAttributeView implements DosFileAttributeView {
    private final DosFileAttributeView view;

    ManglingDosFileAttributeView(DosFileAttributeView view) {
        super(view);
        this.view = view;
    }

    @Override
    public DosFileAttributes readAttributes() throws IOException {
        return new ManglingDosFileAttributes(view.readAttributes());
    }

    @Override
    public void setReadOnly(boolean value) throws IOException {
        if (ManglingFileSystemProvider.prohibitFileTreeModifications) {
            throw new AccessDeniedException(null, null, "Test: Prohibiting file tree modifications");
        }
        view.setReadOnly(value);
    }

    @Override
    public void setHidden(boolean value) throws IOException {
        if (ManglingFileSystemProvider.prohibitFileTreeModifications) {
            throw new AccessDeniedException(null, null, "Test: Prohibiting file tree modifications");
        }
        view.setHidden(value);
    }

    @Override
    public void setSystem(boolean value) throws IOException {
        if (ManglingFileSystemProvider.prohibitFileTreeModifications) {
            throw new AccessDeniedException(null, null, "Test: Prohibiting file tree modifications");
        }
        view.setSystem(value);
    }

    @Override
    public void setArchive(boolean value) throws IOException {
        if (ManglingFileSystemProvider.prohibitFileTreeModifications) {
            throw new AccessDeniedException(null, null, "Test: Prohibiting file tree modifications");
        }
        view.setArchive(value);
    }
}
