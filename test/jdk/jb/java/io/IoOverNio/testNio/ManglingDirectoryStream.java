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
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

class ManglingDirectoryStream implements DirectoryStream<Path> {
    private final ManglingFileSystem manglingFileSystem;
    private final ManglingPath dir;
    private final DirectoryStream<Path> delegateResult;
    private boolean addEliteElement = ManglingFileSystemProvider.addEliteToEveryDirectoryListing;

    public ManglingDirectoryStream(ManglingFileSystem manglingFileSystem, ManglingPath dir, DirectoryStream<Path> delegateResult) {
        this.manglingFileSystem = manglingFileSystem;
        this.dir = dir;
        this.delegateResult = delegateResult;
    }

    @Override
    public void close() throws IOException {
        delegateResult.close();
    }

    @Override
    public Iterator<Path> iterator() {
        return new ManglingPathIterator();
    }

    class ManglingPathIterator implements Iterator<Path> {
        private final Iterator<Path> delegateIterator = delegateResult.iterator();

        @Override
        public boolean hasNext() {
            if (delegateIterator.hasNext()) return true;
            if (addEliteElement) {
                addEliteElement = false;
                return true;
            }
            return false;
        }

        @Override
        public Path next() {
            try {
                return new ManglingPath(manglingFileSystem, delegateIterator.next());
            } catch (NoSuchElementException err) {
                if (ManglingFileSystemProvider.addEliteToEveryDirectoryListing) {
                    return dir.resolve("37337");
                }
                throw err;
            }
        }
    }
}
