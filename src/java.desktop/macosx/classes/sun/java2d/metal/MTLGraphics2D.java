/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.metal;

import sun.java2d.SunGraphics2D;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.RenderQueue;

import java.awt.Graphics2D;
import java.awt.RenderingTask;

/**
 * Graphics2D helper for Metal pipeline.
 *
 * Provides a way to execute user-provided Metal code on the rendering thread
 * associated with a particular Graphics2D, ensuring the current destination
 * surface and state are made current on the Metal render queue.
 */
public final class MTLGraphics2D {
    private MTLGraphics2D() {}

    /**
     * Runs a user-provided {@link RenderingTask} on the Metal rendering thread
     * for the destination surface of the given {@link Graphics2D}.
     *
     * This method performs the following steps:
     * - Checks that the destination surface of {@code g2d} is backed by Metal
     * - Validates the Metal context with the current clip, composite, transform, and paint
     * - Enqueues the external task and flushes the queue to execute it immediately
     *
     * @param g2d   a {@link Graphics2D}, typically a {@link SunGraphics2D}
     * @param task  a user callback to run on the Metal rendering thread
     * @return true if executed on Metal, false if {@code g2d} is not a Metal surface
     * @throws NullPointerException if {@code task} is null
     */
    public static boolean runExternal(Graphics2D g2d, RenderingTask task) {
        if (task == null) throw new NullPointerException("task");
        if (!(g2d instanceof SunGraphics2D)) {
            return false;
        }
        SunGraphics2D sg2d = (SunGraphics2D) g2d;
        if (!(sg2d.surfaceData instanceof MTLSurfaceData dst)) {
            return false;
        }

        MTLRenderQueue rq = MTLRenderQueue.getInstance();
        rq.lock();
        try {
            MTLContext ctx = dst.getContext();
            BufferedContext.validateContext(dst);

            rq.enqueueExternal(task);
            rq.flushNow();
            return true;
        } finally {
            rq.unlock();
        }
    }
}
