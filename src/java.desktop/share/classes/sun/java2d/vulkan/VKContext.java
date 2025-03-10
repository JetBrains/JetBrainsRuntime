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

import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.RenderQueue;
import sun.java2d.pipe.hw.ContextCapabilities;
import sun.util.logging.PlatformLogger;

import java.awt.BufferCapabilities;
import java.awt.ImageCapabilities;
import java.lang.annotation.Native;

/**
 * Note that the RenderQueue lock must be acquired before calling any of
 * the methods in this class.
 */
final class VKContext extends BufferedContext {
    private static final PlatformLogger log =
            PlatformLogger.getLogger("sun.java2d.vulkan.VKContext");

    public static final VKContext INSTANCE = new VKContext(VKRenderQueue.getInstance());

    public VKContext(RenderQueue rq) {
        super(rq);
    }

    public static void setScratchSurface(VKGraphicsConfig gc) {
        log.info("Not implemented: VKContext.setScratchSurface(VKGraphicsConfig)");
    }

    public static class VKContextCaps extends ContextCapabilities {

        /** Indicates that the context is doublebuffered. */
        @Native
        public static final int CAPS_DOUBLEBUFFERED   = (FIRST_PRIVATE_CAP << 0);
        /**
         * This cap will only be set if the lcdshader system property has been
         * enabled and the hardware supports the minimum number of texture units
         */
        @Native
        static final int CAPS_EXT_LCD_SHADER   = (FIRST_PRIVATE_CAP << 1);
        /**
         * This cap will only be set if the biopshader system property has been
         * enabled and the hardware meets our minimum requirements.
         */
        @Native
        public static final int CAPS_EXT_BIOP_SHADER  = (FIRST_PRIVATE_CAP << 2);
        /**
         * This cap will only be set if the gradshader system property has been
         * enabled and the hardware meets our minimum requirements.
         */
        @Native
        static final int CAPS_EXT_GRAD_SHADER  = (FIRST_PRIVATE_CAP << 3);

        public static final VKContextCaps CONTEXT_CAPS = new VKContextCaps(
                CAPS_PS30 | CAPS_PS20 | CAPS_RT_TEXTURE_ALPHA |
                CAPS_RT_TEXTURE_OPAQUE | CAPS_MULTITEXTURE | CAPS_TEXNONPOW2 |
                CAPS_TEXNONSQUARE, null);

        public static final ImageCapabilities IMAGE_CAPS = new ImageCapabilities(true) {
            @Override
            public boolean isTrueVolatile() {
                return true;
            }
        };

        public static final BufferCapabilities BUFFER_CAPS = new BufferCapabilities(IMAGE_CAPS, IMAGE_CAPS,
                BufferCapabilities.FlipContents.COPIED) {
            @Override
            public boolean isMultiBufferAvailable() {
                return true;
            }
        };

        public VKContextCaps(int caps, String adapterId) {
            super(caps, adapterId);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(super.toString());
            if ((caps & CAPS_DOUBLEBUFFERED) != 0) {
                sb.append("CAPS_DOUBLEBUFFERED|");
            }
            if ((caps & CAPS_EXT_LCD_SHADER) != 0) {
                sb.append("CAPS_EXT_LCD_SHADER|");
            }
            if ((caps & CAPS_EXT_BIOP_SHADER) != 0) {
                sb.append("CAPS_BIOP_SHADER|");
            }
            if ((caps & CAPS_EXT_GRAD_SHADER) != 0) {
                sb.append("CAPS_EXT_GRAD_SHADER|");
            }
            return sb.toString();
        }
    }
}
