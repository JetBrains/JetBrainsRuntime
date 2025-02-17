/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

package sun.java2d.vulkan;

import sun.java2d.pipe.RenderQueue;

import static sun.java2d.pipe.BufferedOpCodes.SYNC;

/**
 * VK-specific implementation of RenderQueue.
 */
public class VKRenderQueue extends RenderQueue {

    private static final VKRenderQueue theInstance = new VKRenderQueue();

    /**
     * Returns the single VKRenderQueue instance.
     */
    public static VKRenderQueue getInstance() {
        return theInstance;
    }

    /**
     * Flushes the single VKRenderQueue instance synchronously.
     */
    public static void sync() {
        theInstance.lock();
        try {
            theInstance.ensureCapacity(4);
            theInstance.getBuffer().putInt(SYNC);
            theInstance.flushNow();
        } finally {
            theInstance.unlock();
        }
    }

    @Override
    public void flushNow() {
        // assert lock.isHeldByCurrentThread();
        int limit = buf.position();
        if (limit > 0) {
            // process the queue
            flushBuffer(buf.getAddress(), limit);
        }
        // reset the buffer position
        buf.clear();
        // clear the set of references, since we no longer need them
        refSet.clear();
    }

    public void flushAndInvokeNow(Runnable r) {
        flushNow();
        r.run();
    }

    private native void flushBuffer(long buf, int limit);
}
