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
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

public class ManglingFileChannel extends FileChannel {
    private final FileChannel delegateChannel;
    private byte[] extraBytes;
    private int extraBytesPosition = -1;

    public ManglingFileChannel(FileChannel delegateChannel) {
        this.delegateChannel = delegateChannel;
        if (ManglingFileSystemProvider.readExtraFileContent) {
            extraBytes = ManglingFileSystemProvider.extraContent.getBytes(StandardCharsets.UTF_8);
        } else {
            extraBytes = new byte[0];
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        int read = delegateChannel.read(dst);
        if (ManglingFileSystemProvider.mangleFileContent) {
            for (int i = 0; i < read; i++) {
                dst.put(i, (byte) ManglingFileSystemProvider.mangle(dst.get(i)));
            }
        }
        if (!dst.hasRemaining()) {
            return read;
        }
        if (extraBytesPosition == -1) {
            extraBytesPosition = 0;
        }
        if (extraBytesPosition < extraBytes.length) {
            if (read == -1) {
                read = 0;
            }
            do {
                dst.put(extraBytes[extraBytesPosition++]);
                read++;
            } while (dst.hasRemaining() && extraBytesPosition < extraBytes.length);
        }
        return read;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (ManglingFileSystemProvider.mangleFileContent) {
            byte[] srcArray = new byte[src.remaining()];
            src.get(srcArray);
            src = ByteBuffer.wrap(ManglingFileSystemProvider.mangle(srcArray));
        }
        return delegateChannel.write(src);
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long position() throws IOException {
        long delegatePosition = delegateChannel.position();
        if (extraBytesPosition != -1) {
            return extraBytesPosition + delegatePosition;
        }
        return delegatePosition;
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        long size = delegateChannel.size();
        if (newPosition > size) {
            assert newPosition <= size + extraBytes.length;
            delegateChannel.position(size);
            extraBytesPosition = (int)(newPosition - size);
        } else {
            extraBytesPosition = -1;
            delegateChannel.position(newPosition);
        }
        return this;
    }

    @Override
    public long size() throws IOException {
        return delegateChannel.size() + extraBytes.length;
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        long actualSize = delegateChannel.size();
        if (size < actualSize) {
            delegateChannel.truncate(size);
            extraBytesPosition = -1;
        } else {
            size -= actualSize;
            if (size < extraBytes.length) {
                byte[] newExtraBytes = new byte[(int)size];
                System.arraycopy(extraBytes, 0, newExtraBytes, 0, (int)size);
                extraBytes = newExtraBytes;
                extraBytesPosition = Math.min(extraBytesPosition, extraBytes.length - 1);
            }
        }
        return this;
    }

    @Override
    public void force(boolean metaData) throws IOException {
        delegateChannel.force(metaData);
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        int read = delegateChannel.read(dst, position);
        if (ManglingFileSystemProvider.mangleFileContent) {
            for (int i = 0; i < dst.remaining(); i++) {
                dst.put(i, (byte) ManglingFileSystemProvider.mangle(dst.get(i)));
            }
        }
        if (dst.hasRemaining() && extraBytesPosition == -1) {
            extraBytesPosition = 0;
        }
        while (dst.hasRemaining() && extraBytesPosition < extraBytes.length) {
            dst.put(extraBytes[extraBytesPosition++]);
            read++;
        }
        return read;
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        if (ManglingFileSystemProvider.mangleFileContent) {
            byte[] srcArray = new byte[src.remaining()];
            src.get(srcArray);
            src = ByteBuffer.wrap(ManglingFileSystemProvider.mangle(srcArray));
            return delegateChannel.write(src);
        }
        return delegateChannel.write(src, position);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return delegateChannel.lock(position, size, shared);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return delegateChannel.tryLock(position, size, shared);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        extraBytesPosition = -1;
        delegateChannel.close();
    }
}
