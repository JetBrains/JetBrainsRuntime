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

import jdk.internal.ref.CleanerFactory;
import jdk.internal.ref.PhantomCleanable;

import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;

/**
 * Cleanable for {@link FileInputStream}, {@link FileOutputStream} and {@link RandomAccessFile}
 * which is in use if {@link com.jetbrains.internal.IoOverNio#IS_ENABLED_IN_GENERAL} is true.
 * <p>
 * Implicitly, there MAY be used both this cleaner and {@link FileCleanable} for the same file descriptor,
 * when the channel is {@link sun.nio.ch.FileChannelImpl}.
 * However, this class is also able to close other channels.
 *
 * @see FileCleanable
 */
final class NioChannelCleanable extends PhantomCleanable<Object> {
    private WeakReference<FileChannel> channel = new WeakReference<>(null);

    /**
     * @param channelOwner The owner of a channel.
     *                     It is supposed to be {@link FileInputStream}, {@link FileOutputStream} or {@link RandomAccessFile}.
     */
    NioChannelCleanable(Object channelOwner) {
        super(channelOwner, CleanerFactory.cleaner());

        assert channelOwner instanceof FileInputStream ||
                channelOwner instanceof FileOutputStream ||
                channelOwner instanceof RandomAccessFile
                : channelOwner.getClass() + " is not an expected class";
    }

    /**
     * The same as {@link FileCleanable#register} but for channels.
     */
    void setChannel(FileChannel channel) {
        this.channel = new WeakReference<>(channel);
    }

    @Override
    protected void performCleanup() {
        var channel = this.channel.get();
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException | RuntimeException _) {
                // Ignored.
            }
        }
    }
}
