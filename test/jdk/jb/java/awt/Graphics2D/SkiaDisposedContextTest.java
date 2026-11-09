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
 * @test id=vulkan-window
 * @key headful
 * @summary Regression test for JBR-10654: nothing made with the recorder of a disposed Graphite context
 *          outlives the context: a window presented through Graphite shows what is painted after its
 *          configuration's context was disposed.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia:+open
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.graphite.api=vulkan SkiaDisposedContextTest Vulkan window
 */

/*
 * @test id=vulkan-texturePaint
 * @key headful
 * @summary Regression test for JBR-10654: a texture paint's shader, an image of a Graphite context's
 *          recorder, goes with the context.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia:+open
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.graphite.api=vulkan SkiaDisposedContextTest Vulkan texturePaint
 */

/*
 * @test id=dawn-window
 * @key headful
 * @summary Regression test for JBR-10654: a window shows what is painted after its configuration's
 *          Graphite context was disposed (Dawn: the platform presents).
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia:+open
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaDisposedContextTest Dawn window
 */

/*
 * @test id=dawn-texturePaint
 * @key headful
 * @summary Regression test for JBR-10654: a texture paint's shader goes with its Graphite context (Dawn).
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia:+open
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaDisposedContextTest Dawn texturePaint
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.TexturePaint;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import sun.java2d.skia.Win32SKGraphicsConfig;

/**
 * The Graphite context of a configuration is disposed while the configuration lives (the Disposer
 * disposes it before a window surface's own release, and Java does not reset the paint before):
 * SKRenderQueue.disposeGraphicsConfig, called through reflection (not the configuration's
 * disposeConfigInfo: the configuration stays, and its next paint makes a new context on the same
 * device). Nothing made with the disposed context's recorder may be used or freed after it.
 * <ul>
 * <li>window: on Vulkan the window is presented through Graphite, into wrappers of its swap chain
 * images made with the context's recorder; they must go with the context. The window is painted
 * red, the context disposed, the window painted in five more colors (more frames than the swap
 * chain has images, so that every image is presented again): each must reach the screen.</li>
 * <li>texturePaint: a TexturePaint whose image is cached as a texture is the paint's shader, an
 * image of the context's recorder: it must go with the context, not with the next paint. A volatile
 * image (no present) is filled with the paint and gets a line of text in it, plain and antialiased
 * (a texture paint's text is filled as shapes: spans in the rect batch, Marlin's masks in the mask
 * cache's batch), the context disposed right after such a fill (the paint's shader is the render
 * state's and the rect batch's then), the image filled with the paint again (sent again: the
 * context changed, which releases the previous shader) and with a color, DISPOSE_CYCLES times.</li>
 * </ul>
 * Both on Dawn as well, where the platform presents: the same must hold. The test runs in a child
 * JVM with the release traces of errors on (PipelineComparison.ranInChild): a texture paint image
 * of the disposed context that anything but the context's own caches still references when the
 * context goes is reported there (SKGraphicsConfig's destructor counts the images' references, so
 * whichever holder kept one: the render state, the rect batch, the last glyph list, or another),
 * and a surface wrapped while it is another context's.
 */
public class SkiaDisposedContextTest {

    /** How long a paint may take to reach the screen. */
    private static final int TIMEOUT_MS = 3000;

    /** How often the screen pixel is read while waiting. */
    private static final long POLL_INTERVAL_MS = 50;

    /** How far a channel of the screen pixel may be off the painted color (the display's color depth and rounding). */
    private static final int COLOR_TOLERANCE = 8;

    /** The window's size and position: nothing special, but away from the screen edge. */
    private static final int FRAME_WIDTH = 300;
    private static final int FRAME_HEIGHT = 200;
    private static final int FRAME_X = 100;
    private static final int FRAME_Y = 100;

    /** The texture paint's tile: one color, so that every pixel of the fill is the tile's color. */
    private static final int TILE_SIZE = 8;

    /** The line of text in the fills, in the image's top left corner (the center is checked). */
    private static final String TEXT = "SkiaDisposedContextTest";
    private static final int FONT_SIZE = 14;
    private static final int TEXT_X = 4;
    private static final int TEXT_Y = 20;

    /** Fills with the texture paint before the dispose: the proxy caches the tile's image as a texture after some uses. */
    private static final int CACHING_FILLS = 4;

    /**
     * Texture paint cases in a row, each disposing a context: the release of a shader after its context reads
     * freed memory, which crashes only when the memory was reused, about every other time.
     */
    private static final int DISPOSE_CYCLES = 10;

    /** What the window's next paint fills it with. */
    private static volatile Color panelColor = Color.RED;

    public static void main(String[] args) throws Exception {
        if (PipelineComparison.ranInChild(SkiaDisposedContextTest.class, args, PipelineComparison.IMAGES_OUTLIVE_CONTEXT_TRACE,
                                          PipelineComparison.WRAPPED_BY_ANOTHER_CONTEXT_TRACE)) {
            return;   // the child ran the test, with the assertions' traces on
        }
        PipelineComparison.requireAPI(args[0], null, null);
        if (args[1].equals("texturePaint")) {
            texturePaint();
        } else {
            window();
        }
        System.out.println("PASSED");
    }

    private static void window() throws Exception {
        Robot robot = new Robot();
        AtomicReference<JFrame> frameReference = new AtomicReference<>();
        AtomicReference<JPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("SkiaDisposedContextTest");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setUndecorated(true);
            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    graphics.setColor(panelColor);
                    graphics.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            frame.setContentPane(panel);
            frame.setSize(FRAME_WIDTH, FRAME_HEIGHT);
            frame.setLocation(FRAME_X, FRAME_Y);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frameReference.set(frame);
            panelReference.set(panel);
        });
        JFrame frame = frameReference.get();
        JPanel panel = panelReference.get();
        try {
            robot.waitForIdle();
            Point center = centerOf(panel);
            repaint(panel);
            expect(robot, center, Color.RED, "the first paint");
            disposeGraphiteContext((Win32SKGraphicsConfig) frame.getGraphicsConfiguration());
            // as many frames as the swap chain has images, and more: each image's wrapper is used again
            for (Color color : List.of(Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.WHITE)) {
                panelColor = color;
                repaint(panel);
                expect(robot, center, color, "a paint after the dispose");
            }
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
        Toolkit.getDefaultToolkit().sync();
    }

    private static void texturePaint() throws Exception {
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        VolatileImage image = graphicsConfig.createCompatibleVolatileImage(FRAME_WIDTH, FRAME_HEIGHT);
        TexturePaint paint = new TexturePaint(tile(Color.MAGENTA), new Rectangle(0, 0, TILE_SIZE, TILE_SIZE));
        for (int cycle = 1; cycle <= DISPOSE_CYCLES; cycle++) {
            for (int fill = 0; fill < CACHING_FILLS; fill++) {
                fill(graphicsConfig, image, paint);
            }
            expect(image, Color.MAGENTA, "cycle " + cycle + ": the texture paint");
            // the read-back reset the paint (it validates without one): the texture paint's shader is the
            // render state's paint again when the context goes
            fill(graphicsConfig, image, paint);
            disposeGraphiteContext((Win32SKGraphicsConfig) graphicsConfig);
            // the paint is sent again (the context changed): the previous paint's shader is released now
            fill(graphicsConfig, image, paint);
            expect(image, Color.MAGENTA, "cycle " + cycle + ": the texture paint after the dispose");
            fill(graphicsConfig, image, Color.GREEN);
            expect(image, Color.GREEN, "cycle " + cycle + ": a color after the dispose");
        }
        image.flush();
        Toolkit.getDefaultToolkit().sync();
    }

    /**
     * A line of text with the paint, plain and antialiased, then a fill of the whole image with it (the
     * fill's rect batch and the paint last), synced.
     */
    private static void fill(GraphicsConfiguration graphicsConfig, VolatileImage image, Paint paint) {
        if (image.validate(graphicsConfig) == VolatileImage.IMAGE_INCOMPATIBLE) {
            throw new RuntimeException("the volatile image is not compatible with its configuration");
        }
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setPaint(paint);
            graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, FONT_SIZE));
            graphics.drawString(TEXT, TEXT_X, TEXT_Y);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString(TEXT, TEXT_X, TEXT_Y + FONT_SIZE);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        Toolkit.getDefaultToolkit().sync();
    }

    /** The image's center pixel must be the color. */
    private static void expect(VolatileImage image, Color expected, String what) {
        Color seen = new Color(image.getSnapshot().getRGB(image.getWidth() / 2, image.getHeight() / 2), true);
        if (!withinTolerance(seen, expected)) {
            throw new RuntimeException(what + ": " + seen + " instead of " + expected);
        }
        System.out.println(what + ": " + seen);
    }

    /** DISPOSE_CONFIG for the configuration, as its disposer record sends it, but the configuration stays. */
    private static void disposeGraphiteContext(Win32SKGraphicsConfig graphicsConfig) throws Exception {
        Class<?> renderQueue = Class.forName("sun.java2d.skia.SKRenderQueue");
        Method dispose = renderQueue.getDeclaredMethod("disposeGraphicsConfig", long.class);
        dispose.setAccessible(true);
        dispose.invoke(null, graphicsConfig.getNativeConfigInfo());
        System.out.println("disposed the Graphite context of " + graphicsConfig);
    }

    private static BufferedImage tile(Color color) {
        BufferedImage tile = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = tile.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
        graphics.dispose();
        return tile;
    }

    /** A paint of the panel, done and synced. */
    private static void repaint(JPanel panel) throws Exception {
        SwingUtilities.invokeAndWait(() -> panel.paintImmediately(0, 0, panel.getWidth(), panel.getHeight()));
        Toolkit.getDefaultToolkit().sync();
    }

    /** The screen point at the panel's center. */
    private static Point centerOf(JPanel panel) throws Exception {
        AtomicReference<Point> pointReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Point origin = panel.getLocationOnScreen();
            pointReference.set(new Point(origin.x + panel.getWidth() / 2, origin.y + panel.getHeight() / 2));
        });
        return pointReference.get();
    }

    /** Waits for the screen pixel to take the color, up to the timeout. */
    private static void expect(Robot robot, Point at, Color expected, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        Color seen = null;
        while (System.currentTimeMillis() < deadline) {
            seen = robot.getPixelColor(at.x, at.y);
            if (withinTolerance(seen, expected)) {
                System.out.println(what + ": " + seen);
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException(what + " did not reach the screen within " + TIMEOUT_MS + " ms: " + seen
                + " instead of " + expected);
    }

    private static boolean withinTolerance(Color seen, Color expected) {
        return Math.abs(seen.getRed() - expected.getRed()) <= COLOR_TOLERANCE
                && Math.abs(seen.getGreen() - expected.getGreen()) <= COLOR_TOLERANCE
                && Math.abs(seen.getBlue() - expected.getBlue()) <= COLOR_TOLERANCE;
    }
}
