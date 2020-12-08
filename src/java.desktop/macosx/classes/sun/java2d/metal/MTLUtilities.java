/*
 * Copyright (c) 2011, 2019, Oracle and/or its affiliates. All rights reserved.
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
import sun.java2d.SurfaceData;
import sun.java2d.pipe.Region;

import java.awt.*;

/**
 * This class contains a number of static utility methods that may be
 * called (via reflection) by a third-party library in order
 * to interoperate with the metal-based Java 2D pipeline.
 *
 */
class MTLUtilities {

    /**
     * These OGL-specific surface type constants are the same as those
     * defined in the MTLSurfaceData class and are duplicated here so that
     * clients of this API can access them more easily via reflection.
     */
    public static final int UNDEFINED       = MTLSurfaceData.UNDEFINED;
    public static final int WINDOW          = MTLSurfaceData.WINDOW;
    public static final int TEXTURE         = MTLSurfaceData.TEXTURE;
    public static final int FLIP_BACKBUFFER = MTLSurfaceData.FLIP_BACKBUFFER;
    public static final int RT_TEXTURE      = MTLSurfaceData.RT_TEXTURE;

    private MTLUtilities() {
    }

    /**
     * Returns true if the current thread is the OGL QueueFlusher thread.
     */
    public static boolean isQueueFlusherThread() {
        return MTLRenderQueue.isQueueFlusherThread();
    }

    /**
     * Invokes the given Runnable on the MTL QueueFlusher thread with the
     * MTL context corresponding to the given Graphics object made
     * current.  It is legal for MTL code executed in the given
     * Runnable to change the current MTL context; it will be reset
     * once the Runnable completes.  No guarantees are made as to the
     * state of the MTL context of the Graphics object; for
     *
     * In order to avoid deadlock, it is important that the given Runnable
     * does not attempt to acquire the AWT lock, as that will be handled
     * automatically as part of the {@code rq.flushAndInvokeNow()} step.
     *
     * @param g the Graphics object for the corresponding destination surface;
     * if null, the step making a context current to the destination surface
     * will be skipped
     * @param r the action to be performed on the QFT; cannot be null
     * @return true if the operation completed successfully, or false if
     * there was any problem making a context current to the surface
     * associated with the given Graphics object
     */
    public static boolean invokeWithMTLContextCurrent(Graphics g, Runnable r) {
        MTLRenderQueue rq = MTLRenderQueue.getInstance();
        rq.lock();
        try {
            if (g != null) {
                if (!(g instanceof SunGraphics2D)) {
                    return false;
                }
                SurfaceData sData = ((SunGraphics2D)g).surfaceData;
                if (!(sData instanceof MTLSurfaceData)) {
                    return false;
                }

                // make a context current to the destination surface
                MTLContext.validateContext((MTLSurfaceData)sData);
            }

            // invoke the given runnable on the QFT
            rq.flushAndInvokeNow(r);

            // invalidate the current context so that the next time we render
            // with Java 2D, the context state will be completely revalidated
            MTLContext.invalidateCurrentContext();
        } finally {
            rq.unlock();
        }

        return true;
    }

    /**
     * Invokes the given Runnable on the MTL QueueFlusher thread with the
     * "shared" MTL context (corresponding to the given
     * GraphicsConfiguration object) made current.  This method is typically
     * used when the Runnable needs a current context to complete its
     * operation, but does not require that the context be made current to
     * a particular surface.  For example, an application may call this
     * method so that the given Runnable can query the OpenGL capabilities
     * of the given GraphicsConfiguration, without making a context current
     * to a dummy surface (or similar hacky techniques).
     *
     * In order to avoid deadlock, it is important that the given Runnable
     * does not attempt to acquire the AWT lock, as that will be handled
     * automatically as part of the {@code rq.flushAndInvokeNow()} step.
     *
     * @param config the GraphicsConfiguration object whose "shared"
     * context will be made current during this operation; if this value is
     * null or if MTL is not enabled for the GraphicsConfiguration, this
     * method will return false
     * @param r the action to be performed on the QFT; cannot be null
     * @return true if the operation completed successfully, or false if
     * there was any problem making the shared context current
     */
    public static boolean
    invokeWithMTLSharedContextCurrent(GraphicsConfiguration config,
                                      Runnable r)
    {
        if (!(config instanceof MTLGraphicsConfig)) {
            return false;
        }

        MTLRenderQueue rq = MTLRenderQueue.getInstance();
        rq.lock();
        try {
            // make the "shared" context current for the given GraphicsConfig
            MTLContext.setScratchSurface((MTLGraphicsConfig)config);

            // invoke the given runnable on the QFT
            rq.flushAndInvokeNow(r);

            // invalidate the current context so that the next time we render
            // with Java 2D, the context state will be completely revalidated
            MTLContext.invalidateCurrentContext();
        } finally {
            rq.unlock();
        }

        return true;
    }

    /**
     * Returns the Rectangle describing the MTL viewport on the
     * Java 2D surface associated with the given Graphics object and
     * component width and height. When a third-party library is
     * performing MTL rendering directly into the visible region of
     * the associated surface, this viewport helps the application
     * position the MTL output correctly on that surface.
     *
     * Note that the x/y values in the returned Rectangle object represent
     * the lower-left corner of the viewport region, relative to the
     * lower-left corner of the given surface.
     *
     * @param g the Graphics object for the corresponding destination surface;
     * cannot be null
     * @param componentWidth width of the component to be painted
     * @param componentHeight height of the component to be painted
     * @return a Rectangle describing the MTL viewport for the given
     * destination surface and component dimensions, or null if the given
     * Graphics object is invalid
     */
    public static Rectangle getMTLViewport(Graphics g,
                                           int componentWidth,
                                           int componentHeight)
    {
        if (!(g instanceof SunGraphics2D)) {
            return null;
        }

        SunGraphics2D sg2d = (SunGraphics2D)g;
        SurfaceData sData = sg2d.surfaceData;

        // this is the upper-left origin of the region to be painted,
        // relative to the upper-left origin of the surface
        // (in Java2D coordinates)
        int x0 = sg2d.transX;
        int y0 = sg2d.transY;

        // this is the lower-left origin of the region to be painted,
        // relative to the lower-left origin of the surface
        // (in OpenGL coordinates)
        Rectangle surfaceBounds = sData.getBounds();
        int x1 = x0;
        int y1 = surfaceBounds.height - (y0 + componentHeight);

        return new Rectangle(x1, y1, componentWidth, componentHeight);
    }

    /**
     * Returns the Rectangle describing the MTL scissor box on the
     * Java 2D surface associated with the given Graphics object.  When a
     * third-party library is performing MTL rendering directly
     * into the visible region of the associated surface, this scissor box
     * must be set to avoid drawing over existing rendering results.
     *
     * Note that the x/y values in the returned Rectangle object represent
     * the lower-left corner of the scissor region, relative to the
     * lower-left corner of the given surface.
     *
     * @param g the Graphics object for the corresponding destination surface;
     * cannot be null
     * @return a Rectangle describing the MTL scissor box for the given
     * Graphics object and corresponding destination surface, or null if the
     * given Graphics object is invalid or the clip region is non-rectangular
     */
    public static Rectangle getOGLScissorBox(Graphics g) {
        if (!(g instanceof SunGraphics2D)) {
            return null;
        }

        SunGraphics2D sg2d = (SunGraphics2D)g;
        SurfaceData sData = sg2d.surfaceData;
        Region r = sg2d.getCompClip();
        if (!r.isRectangular()) {
            // caller probably doesn't know how to handle shape clip
            // appropriately, so just return null (Swing currently never
            // sets a shape clip, but that could change in the future)
            return null;
        }

        // this is the upper-left origin of the scissor box relative to the
        // upper-left origin of the surface (in Java 2D coordinates)
        int x0 = r.getLoX();
        int y0 = r.getLoY();

        // this is the width and height of the scissor region
        int w = r.getWidth();
        int h = r.getHeight();

        // this is the lower-left origin of the scissor box relative to the
        // lower-left origin of the surface (in OpenGL coordinates)
        Rectangle surfaceBounds = sData.getBounds();
        int x1 = x0;
        int y1 = surfaceBounds.height - (y0 + h);

        return new Rectangle(x1, y1, w, h);
    }

    /**
     * Returns an Object identifier for the Java 2D surface associated with
     * the given Graphics object.  This identifier may be used to determine
     * whether the surface has changed since the last invocation of this
     * operation, and thereby whether the MTL state corresponding to the
     * old surface must be destroyed and recreated.
     *
     * @param g the Graphics object for the corresponding destination surface;
     * cannot be null
     * @return an identifier for the surface associated with the given
     * Graphics object, or null if the given Graphics object is invalid
     */
    public static Object getMTLSurfaceIdentifier(Graphics g) {
        if (!(g instanceof SunGraphics2D)) {
            return null;
        }
        return ((SunGraphics2D)g).surfaceData;
    }
}
