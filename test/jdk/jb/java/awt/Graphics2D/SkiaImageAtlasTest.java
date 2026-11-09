/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: unscaled blits of small accelerated images on the Skia
 *          pipeline are drawn from the image atlas in one batch and match the software loops.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaImageAtlasTest on
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan SkiaImageAtlasTest on
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.imageAtlas=false SkiaImageAtlasTest off
 */

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

import sun.java2d.skia.SKEnvironment;

/**
 * By default (sun.java2d.skia.imageAtlas), unscaled blits of small accelerated images are drawn
 * from an atlas of their copies as sprites, consecutive blits in one mesh rather than one
 * Graphite draw each, also when the source image changes from one blit to the next (a
 * SET_SURFACES that keeps the destination keeps the batch). A frame of 200 blits of 16 images, in
 * cell clips that cut some of them, under rect fills drawn over some and over rect fills drawn
 * before others, some at half extra alpha, and one image changed in the middle of the frame (the
 * sprites drawn before show its old pixels, those after its new ones), matches the software
 * loops; 150 blits of the 16 images in a row under one clip take one atlas batch draw (150 when
 * each new source drew the batch). With the property false (the last run line) the frame matches
 * as well and nothing is drawn from the atlas.
 */
public class SkiaImageAtlasTest {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 200;
    /** How many source images, their side, and how many blits the frame makes of them. */
    private static final int SOURCES = 16;
    private static final int SOURCE_SIZE = 16;
    private static final int BLITS_PER_FRAME = 200;
    /** The image redrawn in the middle of the frame. */
    private static final int CHANGED = 3;
    /** Sampling a translucent sprite from the atlas against the loops' blit: filtering and premultiplication rounding. */
    private static final int TOLERANCE = 4;
    /** Nearly every blit must come from the atlas: the cut ones and the half-alpha ones may not. */
    private static final int MIN_ATLAS_BLITS = BLITS_PER_FRAME * 9 / 10;
    /** The changed image is copied into the atlas twice per frame: its first pixels, then its new ones. */
    private static final int MIN_COPIES = 2;
    /**
     * The row of blits under one clip that shares one atlas batch (its draw at the snapshot; one more if the
     * atlas is refilled meanwhile); without the atlas every blit is a draw of its own.
     */
    private static final int ROW_BLITS = 150;
    private static final int MAX_ROW_DRAWS = 2;
    /** The row's grid: 50 sprites a line, 19 pixels apart. */
    private static final int ROW_COLUMNS = 50;
    private static final int ROW_PITCH = 19;

    /** The frame's grid: 40 cells a line, 23 by 38 pixels. */
    private static final int FRAME_COLUMNS = 40;
    private static final int CELL_WIDTH = 23;
    private static final int CELL_HEIGHT = 38;
    /** The sprite's alpha at its edge, so that it is translucent there and opaque inside. */
    private static final int EDGE_ALPHA = 0x80;
    private static final int OPAQUE = 0xff;

    private static final Color BACKGROUND = new Color(0xf4f4f0);
    private static final Color UNDER_IMAGE = new Color(0x30a050);
    private static final Color OVER_IMAGE = new Color(0xc03030);

    private record Result(BufferedImage actual, long[] countersBefore, long[] countersAfter) {
    }

    public static void main(String[] args) throws Exception {
        boolean atlasOn = args[0].equals("on");
        PipelineComparison.requireRequestedAPI();
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        BufferedImage[] pixels = new BufferedImage[SOURCES];
        VolatileImage[] sources = new VolatileImage[SOURCES];
        for (int index = 0; index < SOURCES; index++) {
            pixels[index] = source(index, false);
            sources[index] = graphicsConfig.createCompatibleVolatileImage(SOURCE_SIZE, SOURCE_SIZE, Transparency.TRANSLUCENT);
        }
        BufferedImage changed = source(CHANGED, true);
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        VolatileImage[] allImages = new VolatileImage[SOURCES + 1];
        System.arraycopy(sources, 0, allImages, 0, SOURCES);
        allImages[SOURCES] = volatileImage;
        Result result = PipelineComparison.keepingContents(graphicsConfig, () -> {
            for (int index = 0; index < SOURCES; index++) {
                copy(pixels[index], sources[index]);
            }
            paint(volatileImage.createGraphics(), sources, changed);   // the atlas takes its copies
            volatileImage.getSnapshot();
            for (int index = 0; index < SOURCES; index++) {
                copy(pixels[index], sources[index]);   // the changed image back to its first pixels
            }
            long[] countersBefore = SKEnvironment.imageAtlasCounters();
            paint(volatileImage.createGraphics(), sources, changed);
            BufferedImage actual = volatileImage.getSnapshot();
            return new Result(actual, countersBefore, SKEnvironment.imageAtlasCounters());
        }, allImages);
        BufferedImage[] referenceSources = new BufferedImage[SOURCES];
        for (int index = 0; index < SOURCES; index++) {
            referenceSources[index] = new BufferedImage(SOURCE_SIZE, SOURCE_SIZE, BufferedImage.TYPE_INT_ARGB);
            copy(pixels[index], referenceSources[index]);
        }
        BufferedImage expected = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        paint(expected.createGraphics(), referenceSources, changed);

        PipelineComparison.Difference difference = PipelineComparison.compare(expected, result.actual(), TOLERANCE);
        long[] delta = PipelineComparison.countersDelta(result.countersBefore(), result.countersAfter());
        long atlasBlits = delta[PipelineComparison.ImageAtlasCounter.BLITS];
        long copies = delta[PipelineComparison.ImageAtlasCounter.COPIES];
        long batchDraws = delta[PipelineComparison.ImageAtlasCounter.BATCH_DRAWS];
        System.out.println(BLITS_PER_FRAME + " image blits: " + atlasBlits + " from the atlas, " + copies + " copies into it, "
                + batchDraws + " atlas batch draws; against the software loops max " + difference.max() + " levels, "
                + difference.beyond() + " pixels beyond " + TOLERANCE);
        if (difference.beyond() > 0) {
            throw new RuntimeException("the sources differ from the software loops: " + difference.beyond() + " pixels beyond "
                    + TOLERANCE + " levels (max " + difference.max() + ")");
        }
        if (!atlasOn) {
            if (atlasBlits != 0) {
                throw new RuntimeException(atlasBlits + " blits drawn from the atlas with sun.java2d.skia.imageAtlas=false");
            }
            System.out.println("PASSED");
            return;
        }
        if (atlasBlits < MIN_ATLAS_BLITS) {
            throw new RuntimeException("only " + atlasBlits + " of " + BLITS_PER_FRAME + " image blits drawn from the atlas");
        }
        if (copies < MIN_COPIES) {
            throw new RuntimeException(copies + " copies into the atlas: the changed image was not copied again (twice per frame)");
        }
        // consecutive blits under one clip share the atlas batch, of any of the images in it: a row of them, one
        // draw (each new source image is a SET_SURFACES of the same destination, which keeps the batch)
        Graphics2D graphics = volatileImage.createGraphics();
        long rowDrawsBefore = SKEnvironment.imageAtlasCounters()[PipelineComparison.ImageAtlasCounter.BATCH_DRAWS];
        for (int blit = 0; blit < ROW_BLITS; blit++) {
            graphics.drawImage(sources[blit % SOURCES], 4 + (blit % ROW_COLUMNS) * ROW_PITCH, 4 + (blit / ROW_COLUMNS) * ROW_PITCH, null);
        }
        graphics.dispose();
        volatileImage.getSnapshot();
        long rowDraws = SKEnvironment.imageAtlasCounters()[PipelineComparison.ImageAtlasCounter.BATCH_DRAWS] - rowDrawsBefore;
        System.out.println(ROW_BLITS + " blits of " + SOURCES + " images in a row under one clip: " + rowDraws + " atlas batch draws");
        if (rowDraws > MAX_ROW_DRAWS) {
            throw new RuntimeException(rowDraws + " atlas batch draws for " + ROW_BLITS + " blits in a row (at most " + MAX_ROW_DRAWS + ")");
        }
        System.out.println("PASSED");
    }

    /** A translucent image: a checker pattern in its own colors with a soft edge; {@code changed} gives it other colors. */
    private static BufferedImage source(int index, boolean changed) {
        BufferedImage image = new BufferedImage(SOURCE_SIZE, SOURCE_SIZE, BufferedImage.TYPE_INT_ARGB);
        // two colors per image, derived from its index so that no two images look alike
        int firstColor = changed ? 0x2060e0 : (0x101010 * (index + 1)) ^ (index * 0x3a1f05);
        int secondColor = changed ? 0xe0e020 : 0xf0f0f0 - (firstColor & 0x7f7f7f);
        for (int y = 0; y < SOURCE_SIZE; y++) {
            for (int x = 0; x < SOURCE_SIZE; x++) {
                int rgb = (x / 4 + y / 4 + index) % 2 == 0 ? firstColor : secondColor;
                boolean edge = x == 0 || y == 0 || x == SOURCE_SIZE - 1 || y == SOURCE_SIZE - 1;
                int alpha = edge ? EDGE_ALPHA : OPAQUE;
                image.setRGB(x, y, (alpha << 24) | (rgb & 0xffffff));
            }
        }
        return image;
    }

    private static void copy(BufferedImage from, Image to) {
        Graphics2D graphics = (Graphics2D) to.getGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(from, 0, 0, null);
        graphics.dispose();
    }

    private static void paint(Graphics2D graphics, Image[] sources, BufferedImage changed) {
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        for (int blit = 0; blit < BLITS_PER_FRAME; blit++) {
            int x = 6 + (blit % FRAME_COLUMNS) * CELL_WIDTH;
            int y = 8 + (blit / FRAME_COLUMNS) * CELL_HEIGHT;
            // a cell's clip; every seventh cuts its image
            graphics.setClip(x - 2, y - 2, blit % 7 == 0 ? 12 : 22, blit % 7 == 3 ? 10 : 34);
            if (blit % 5 == 1) {
                graphics.setColor(UNDER_IMAGE);
                graphics.fillRect(x + 4, y + 4, 12, 20);   // beneath the image: drawn before it
            }
            if (blit % 25 == 2) {
                graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            }
            graphics.drawImage(sources[blit % SOURCES], x, y, null);
            graphics.setComposite(AlphaComposite.SrcOver);
            if (blit % 20 == 3) {
                graphics.setColor(OVER_IMAGE);
                graphics.fillRect(x + 6, y + 6, 6, 14);    // over the image: drawn after it
            }
            if (blit == BLITS_PER_FRAME / 2) {
                copy(changed, sources[CHANGED]);   // the changed image from here on
            }
        }
        graphics.setClip(null);
        graphics.dispose();
    }
}
