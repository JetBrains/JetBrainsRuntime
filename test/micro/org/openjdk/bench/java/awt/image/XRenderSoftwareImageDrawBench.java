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

package org.openjdk.bench.java.awt.image;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.VolatileImage;
import java.awt.image.WritableRaster;
import java.util.concurrent.TimeUnit;

/**
 * JBR-5818: measures how fast software images reach XRender surface.
 * <p>
 * {@code newIcons} draws new 32x32 images twice: first uses software image directly, the second
 * uses X pixmap.<br>
 * {@code cachedIcons} draws images that are already uploaded
 * <p>
 * Before the measurement a large image is drawn to warm up cache
 * <p>
 * To exacerbate the degradation you might add a fixed delay to every round trip,
 * for example using {@code LD_PRELOAD} library by wrapping {@code xcb_wait_for_reply} and {@code xcb_wait_for_reply64}
 * */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Dawt.toolkit.name=XToolkit", "-Dsun.java2d.xrender=true"})
@State(Scope.Thread)
public class XRenderSoftwareImageDrawBench {
    private static final int ICONS = 100;
    private static final int ICON_SIZE = 32;
    private static final int COLUMNS = 100;

    public enum ImageKind {
        SVG_ICON_CACHE,
        INT_ARGB,
        INT_ARGB_PRE,
        FOUR_BYTE_ABGR,
        INT_RGB,
        INT_BGR,
        TYPE_3BYTE_BGR,
        USHORT_565_RGB,
        USHORT_555_RGB,
        BYTE_INDEXED
    }

    @Param("SVG_ICON_CACHE")
    public ImageKind imageKind;

    private GraphicsConfiguration graphicsConfiguration;
    private VolatileImage target;
    private BufferedImage[] newIcons;
    private BufferedImage[] cachedIcons;


    @Setup(Level.Trial)
    public void setup() {
        graphicsConfiguration = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!graphicsConfiguration.getClass().getName().equals("sun.java2d.xr.XRGraphicsConfig")) {
            throw new IllegalStateException("Needs an X server with XRender, got " + graphicsConfiguration.getClass().getName());
        }
        target = graphicsConfiguration.createCompatibleVolatileImage(COLUMNS * ICON_SIZE, ICONS / COLUMNS * ICON_SIZE, Transparency.OPAQUE);
        drawLargeImage();

        cachedIcons = createIcons();
        drawIcons(cachedIcons);
        drawIcons(cachedIcons);
    }

    private void drawLargeImage() {
        VolatileImage largeImage = graphicsConfiguration.createCompatibleVolatileImage(600, 400, Transparency.OPAQUE);
        Graphics2D g2d = largeImage.createGraphics();
        g2d.drawImage(new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB), 0, 0, null);
        g2d.dispose();
        Toolkit.getDefaultToolkit().sync();
        largeImage.flush();
    }

    @Setup(Level.Invocation)
    public void setupNewIcons() {
        newIcons = createIcons();
    }

    @TearDown(Level.Invocation)
    public void flushNewIcons() {
        for (BufferedImage icon : newIcons) {
            icon.flush();
        }
    }

    @TearDown(Level.Trial)
    public void flushTarget() {
        target.flush();
    }

    @Benchmark
    @OperationsPerInvocation(ICONS)
    public void newIcons() {
        drawIcons(newIcons);
        drawIcons(newIcons);
    }

    @Benchmark
    @OperationsPerInvocation(ICONS)
    public void cachedIcons() {
        drawIcons(cachedIcons);
    }

    private void drawIcons(BufferedImage[] icons) {
        target.validate(graphicsConfiguration);
        Graphics2D g2d = target.createGraphics();
        for (int i = 0; i < icons.length; ++i) {
            g2d.drawImage(icons[i], i % COLUMNS * ICON_SIZE, i / COLUMNS * ICON_SIZE, null);
        }
        g2d.dispose();
        Toolkit.getDefaultToolkit().sync();
    }

    private BufferedImage[] createIcons() {
        BufferedImage[] icons = new BufferedImage[ICONS];
        for (int i = 0; i < icons.length; ++i) {
            icons[i] = createIcon();
        }
        return icons;
    }

    private BufferedImage createIcon() {
        BufferedImage icon = switch (imageKind) {
            case SVG_ICON_CACHE -> svgIconCacheImage();
            case INT_ARGB -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            case INT_ARGB_PRE -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB_PRE);
            case FOUR_BYTE_ABGR -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_4BYTE_ABGR);
            case INT_RGB -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_RGB);
            case INT_BGR -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_BGR);
            case TYPE_3BYTE_BGR -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_3BYTE_BGR);
            case USHORT_565_RGB -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_USHORT_565_RGB);
            case USHORT_555_RGB -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_USHORT_555_RGB);
            case BYTE_INDEXED -> new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_BYTE_INDEXED);
        };
        Graphics2D g2d = icon.createGraphics();
        g2d.setColor(new Color(80, 160, 240, 200));
        g2d.fillRect(1, 1, ICON_SIZE - 2, ICON_SIZE - 2);
        g2d.dispose();
        return icon;
    }

    private static BufferedImage svgIconCacheImage() {
        ComponentColorModel cm = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                true,
                false,
                Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE
        );
        WritableRaster raster = Raster.createInterleavedRaster(
                new DataBufferByte(ICON_SIZE * ICON_SIZE * 4),
                ICON_SIZE,
                ICON_SIZE,
                ICON_SIZE * 4,
                4,
                new int[]{2, 1, 0, 3},
                new Point(0, 0)
        );
        return new BufferedImage(cm, raster, false, null);
    }
}
