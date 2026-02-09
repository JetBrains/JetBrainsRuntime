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

package java.awt;

import java.util.List;

/**
 * A marker interface for tasks that can be executed on the rendering thread.
 * Pipeline-specific sub-interfaces should provide the actual execution method
 * with appropriate parameters.
 */
public interface RenderingTask {
    /**
     * Surface type: Metal texture.
     */
    public static final String MTL_TEXTURE = "MTL_TEXTURE";

    /**
     * Surface type: Metal window.
     */
    public static final String MTL_WINDOW = "MTL_WINDOW";

    /**
     * A constant representing the name of the Metal device argument.
     */

    public static final String MTL_DEVICE_ARG = "mtlDevice";
    /**
     * The index of the Metal device argument in the list of pointers.
     */
    public static final int MTL_DEVICE_ARG_INDEX = 0;

    /**
     * A constant representing the name of the Metal command queue argument.
     */
    public static final String MTL_COMMAND_QUEUE_ARG = "mtlCommandQueue";
    /**
     * The index of the Metal command queue argument in the list of pointers.
     */
    public static final int MTL_COMMAND_QUEUE_ARG_INDEX = 1;

    /**
     * A constant representing the name of the Metal render command encoder argument.
     */
    public static final String MTL_RENDER_COMMAND_ENCODER_ARG = "mtlRenderCommandEncoder";
    /**
     * The index of the Metal render command encoder argument in the list of pointers.
     */
    public static final int MTL_RENDER_COMMAND_ENCODER_ARG_INDEX = 2;

    /**
     * A constant representing the name of the Metal texture argument.
     */
    public static final String MTL_TEXTURE_ARG = "mtlTexture";
    /**
     * The index of the Metal texture argument in the list of pointers.
     */
    public static final int MTL_TEXTURE_ARG_INDEX = 3;

    /**
     * Invoked on the rendering thread.
     *
     * @param surfaceType the type of the surface (e.g. MTL_TEXTURE or MTL_WINDOW)
     * @param pointers    a list of needed pointers
     * @param names       a list of the pointers names (e.g. "mtlDevice", etc)
     */
    public void run(String surfaceType, List<Long> pointers, List<String> names);
}
