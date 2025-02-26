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

import sun.nio.ch.FileChannelImpl;

import java.nio.channels.FileChannel;

/**
 * A channel returned by {@link #getInterruptibleChannel()} must be interruptible,
 * but the channel used inside this class must be uninterruptible.
 * This class holds the channel for external usage, which may differ from {@link #channel}.
 */
class ExternalChannelHolder {
    private final Object synchronizationPoint;
    private final FileChannel channel;
    private final NioChannelCleanable channelCleanable;
    private FileChannel externallySharedChannel;

    ExternalChannelHolder(Object owner, FileChannel channel, NioChannelCleanable channelCleanable) {
        this.synchronizationPoint = owner;
        this.channel = channel;
        this.channelCleanable = channelCleanable;
    }

    FileChannel getInterruptibleChannel() {
        synchronized (synchronizationPoint) {
            if (externallySharedChannel == null) {
                externallySharedChannel = channel;
                if (externallySharedChannel instanceof FileChannelImpl fci) {
                    fci = fci.clone();
                    fci.setInterruptible();
                    externallySharedChannel = fci;
                }
            }
        }
        channelCleanable.setChannel(null);
        return externallySharedChannel;
    }

    void close() throws IOException {
        FileChannel ch = externallySharedChannel;
        if (ch != null) {
            ch.close();
        }
    }
}
