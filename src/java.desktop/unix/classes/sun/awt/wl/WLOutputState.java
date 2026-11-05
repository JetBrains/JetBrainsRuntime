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

package sun.awt.wl;

import sun.java2d.pipe.Region;

import java.awt.Dimension;
import java.awt.Rectangle;

public record WLOutputState(
        String name,
        String description,
        String make,
        String model,
        int physicalWidthMm,
        int physicalHeightMm,
        int compositorX,
        int compositorY,
        int compositorWidth,
        int compositorHeight,
        int pixelWidth,
        int pixelHeight,
        int logicalX,
        int logicalY,
        int logicalWidth,
        int logicalHeight,
        int dpiX,
        int dpiY,
        int scale,
        double effectiveScale,
        int refreshMilliHz
) {
    Dimension physicalSizeMm() {
        return new Dimension(physicalWidthMm, physicalHeightMm);
    }

    Rectangle compositorBounds() {
        return new Rectangle(compositorX, compositorY, compositorWidth, compositorHeight);
    }

    Dimension pixelSize() {
        return new Dimension(pixelWidth, pixelHeight);
    }

    Rectangle logicalBounds() {
        return new Rectangle(logicalX, logicalY, logicalWidth, logicalHeight);
    }

    Dimension dpi() {
        return new Dimension(dpiX, dpiY);
    }

    // Compute device space bounds, the same way as SunGraphicsEnvironment.toDeviceSpaceAbs()
    Rectangle deviceSpaceBounds() {
        return new Rectangle(
                logicalX,
                logicalY,
                Region.clipRound(logicalWidth * effectiveScale),
                Region.clipRound(logicalHeight * effectiveScale)
        );
    }
}
