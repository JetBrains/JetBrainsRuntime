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
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

class ManglingPosixFileAttributeView extends ManglingBasicFileAttributeView implements PosixFileAttributeView {
    private final PosixFileAttributeView view;

    ManglingPosixFileAttributeView(PosixFileAttributeView view) {
        super(view);
        this.view = view;
    }

    @Override
    public PosixFileAttributes readAttributes() throws IOException {
        return new ManglingPosixFileAttributes(view.readAttributes());
    }

    @Override
    public void setPermissions(Set<PosixFilePermission> perms) throws IOException {
        if (ManglingFileSystemProvider.prohibitFileTreeModifications) {
            throw new AccessDeniedException(null, null, "Test: Prohibiting file tree modifications");
        }
        view.setPermissions(perms);
    }

    @Override
    public void setGroup(GroupPrincipal group) throws IOException {
        view.setGroup(group);
    }

    @Override
    public UserPrincipal getOwner() throws IOException {
        return view.getOwner();
    }

    @Override
    public void setOwner(UserPrincipal owner) throws IOException {
        view.setOwner(owner);
    }
}
