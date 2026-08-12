/*
 * Copyright 2026 JetBrains s.r.o.
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

package sun.awt.wl.protocol;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WLPoller {
    private final Set<WLPollFD> watches = new HashSet<>();

    public void add(WLPollFD fd) {
        synchronized (this) {
            if (!watches.add(fd)) {
                throw new IllegalStateException("already watching file descriptor " + fd.getFd());
            }
        }
    }

    public void remove(WLPollFD fd) {
        synchronized (this) {
            if (!watches.remove(fd)) {
                throw new IllegalStateException("removing file descriptor that's not being watched");
            }
        }
    }

    public void poll() {
        try (Arena arena = Arena.ofConfined()) {
            List<WLPollFD> watches;
            synchronized (this) {
                watches = this.watches.stream().toList();
            }

            int n = watches.size();
            if (n == 0) {
                return;
            }

            MemorySegment nativePoll = arena.allocate(WLNative.pollfd, n);

            for (int i = 0; i < watches.size(); ++i) {
                WLPollFD fd = watches.get(i);
                short nativeEvents = 0;
                if (fd.wantsRead()) {
                    nativeEvents |= WLNative.POLLIN;
                }
                if (fd.wantsWrite()) {
                    nativeEvents |= WLNative.POLLOUT;
                }

                MemorySegment nativeFd = nativePoll.asSlice(i * WLNative.pollfd.byteSize(), WLNative.pollfd);
                WLNative.pollfd$fd.set(nativeFd, 0L, (int) fd.getFd());
                WLNative.pollfd$events.set(nativeFd, 0L, (short) nativeEvents);
                WLNative.pollfd$revents.set(nativeFd, 0L, (short) 0);
            }

            WLNative.poll(nativePoll);
            for (int i = 0; i < watches.size(); ++i) {
                WLPollFD fd = watches.get(i);
                MemorySegment nativeFd = nativePoll.asSlice(i * WLNative.pollfd.byteSize(), WLNative.pollfd);
                short nativeEvents = (short) WLNative.pollfd$revents.get(nativeFd, 0L);
                if (fd.wantsRead() && (nativeEvents & WLNative.POLLIN) != 0) {
                    fd.onReadable();
                }
                if (fd.wantsWrite() && (nativeEvents & WLNative.POLLOUT) != 0) {
                    fd.onWriteable();
                }
            }
        }
    }
}
