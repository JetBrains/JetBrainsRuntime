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

package sun.awt.wl;

import sun.awt.image.SunWritableRaster;
import sun.java2d.SunGraphics2D;
import sun.swing.ImageCache;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.beans.PropertyChangeEvent;

public class WLGtkFrameDecoration extends WLFrameDecoration {
    private final ImageCache cache = new ImageCache(16);

    private static final ColorModel[] COLOR_MODELS = new ColorModel[3];

    private static final int[][] BAND_OFFSETS = {
            { 0x00ff0000, 0x0000ff00, 0x000000ff },             // OPAQUE
            { 0x00ff0000, 0x0000ff00, 0x000000ff, 0x01000000 }, // BITMASK
            { 0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000 }  // TRANSLUCENT
    };

    private int width;
    private int height;
    private double scale;

    public WLGtkFrameDecoration(WLDecoratedPeer peer, boolean isUndecorated, boolean showMinimize, boolean showMaximize) {
        super(peer, isUndecorated, showMinimize, showMaximize);
    }

    @Override
    public void paint(Graphics g) {
        width = peer.getWidth();
        height = 37;
        scale = ((WLGraphicsConfig) peer.getGraphicsConfiguration()).getEffectiveScale();
        ((Graphics2D)g).setBackground(new Color(0, true));
        g.clearRect(0, 0, width, height);
        finishPainting(g);
    }

    BufferedImage finishPainting(Graphics graphics) {
        int nativeW = (int) Math.ceil(width * scale);
        int nativeH = (int) Math.ceil(height * scale);
        DataBufferInt dataBuffer = new DataBufferInt(nativeW * nativeH);

        System.err.printf("width=%d, height=%d, scale=%f, nativeW=%d, nativeH=%d\n", width, height, scale, nativeW, nativeH);
        nativeFinishPainting(SunWritableRaster.stealData(dataBuffer, 0), nativeW, nativeH, (int)scale);
        SunWritableRaster.markDirty(dataBuffer);

        int[] bands = BAND_OFFSETS[2];
        WritableRaster raster = Raster.createPackedRaster(dataBuffer, nativeW, nativeH, nativeW, bands, null);

        ColorModel cm = colorModelFor(Transparency.TRANSLUCENT);
        BufferedImage img = new BufferedImage(cm, raster, true, null);
        cache.setImage(getClass(), null, nativeW, nativeH, null, img);

        prepareGraphics(graphics);
        graphics.drawImage(img, 0, 0, width, height, 0, 0, nativeW, nativeH, null);

        return img;
    }

    private void prepareGraphics(Graphics graphics) {
        if (scale > 1 && graphics instanceof SunGraphics2D sg2d) {
            sg2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            sg2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        }
    }

    private ColorModel colorModelFor(int transparency) {
        synchronized (COLOR_MODELS) {
            int index = transparency - 1;
            if (COLOR_MODELS[index] == null) {
                COLOR_MODELS[index] = peer.getGraphicsConfiguration().getColorModel(transparency);
            }
            return COLOR_MODELS[index];
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        // TODO:
        super.propertyChange(evt);
        if ("awt.os.theme.isDark".equals(evt.getPropertyName())) {
            Object newValue = evt.getNewValue();
            if (newValue != null) {
                nativeSwitchTheme();
                cache.flush();
            }
        }
    }

    private native void nativeSwitchTheme();
    private native void nativeFinishPainting(int[] buffer, int width, int height, int scale);
}
