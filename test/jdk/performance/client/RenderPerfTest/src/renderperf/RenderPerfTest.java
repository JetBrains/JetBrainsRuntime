
package renderperf;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Transparency;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;

import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntBinaryOperator;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;


public final class RenderPerfTest {

    private final static String VERSION = "RenderPerfTest 2024.02";

    private static final HashSet<String> ignoredTests = new HashSet<>();

    static {
        // add ignored tests here
        // ignoredTests.add("testMyIgnoredTest");
        ignoredTests.add("testCalibration"); // not from command line
    }

    private final static String EXEC_MODE_ROBOT = "robot";
    private final static String EXEC_MODE_BUFFER = "buffer";
    private final static String EXEC_MODE_VOLATILE = "volatile";
    private final static String EXEC_MODE_DEFAULT = EXEC_MODE_ROBOT;

    public final static List<String> EXEC_MODES = Arrays.asList(EXEC_MODE_ROBOT, EXEC_MODE_BUFFER, EXEC_MODE_VOLATILE);

    private static String EXEC_MODE = EXEC_MODE_DEFAULT;

    private final static String GC_MODE_DEF = "def";
    private final static String GC_MODE_ALL = "all";

    private static String GC_MODE = GC_MODE_DEF;

    // System properties:
    private final static boolean CALIBRATION = "true".equalsIgnoreCase(System.getProperty("CALIBRATION", "false"));
    private final static boolean REPORT_OVERALL_FPS = "true".equalsIgnoreCase(System.getProperty("REPORT_OVERALL_FPS", "false"));

    private final static boolean DUMP_SAMPLES = "true".equalsIgnoreCase(System.getProperty("DUMP_SAMPLES", "false"));
    private final static boolean TRACE = "true".equalsIgnoreCase(System.getProperty("TRACE", "false"));
    private final static boolean TRACE_CONFIGURE = "true".equalsIgnoreCase(System.getProperty("TRACE_CONFIGURE", "false"));
    private final static boolean TRACE_SYNC = "true".equalsIgnoreCase(System.getProperty("TRACE_SYNC", "false"));

    private final static boolean DELAY_START = "true".equalsIgnoreCase(System.getProperty("DelayStart", "false"));
    private final static boolean DELAY_TEST = "true".equalsIgnoreCase(System.getProperty("DelayTest", "false"));

    private final static boolean ROBOT_TIME_DELAY = "true".equalsIgnoreCase(System.getProperty("ROBOT_TIME_DELAY", "true"));
    private final static boolean ROBOT_TIME_ROUND = "true".equalsIgnoreCase(System.getProperty("ROBOT_TIME_ROUND", "false"));


    // time scale multiplier to get more samples so refined metrics:
    private final static int TIME_SCALE = Integer.getInteger("TIME_SCALE", 1);

    // default settings:
    private static boolean VERBOSE = false;
    private static int REPEATS = 1;

    private static boolean USE_FPS = true;

    private static int NW = 1;

    private final static int N_DEFAULT = 1000;
    private static int N = N_DEFAULT;
    private final static float WIDTH = 800;
    private final static float HEIGHT = 800;
    private final static float R = 25;
    private final static int BW = 50;
    private final static int BH = 50;
    private final static int IMAGE_W = (int) (WIDTH + BW);
    private final static int IMAGE_H = (int) (HEIGHT + BH);

    private final static int COUNT = 600 * TIME_SCALE;
    private final static int MIN_COUNT = 20;
    private final static int MAX_SAMPLE_COUNT = 2 * COUNT;

    private static int WARMUP_COUNT = MIN_COUNT;

    private final static int DELAY = 1;
    private final static int CYCLE_DELAY = DELAY;

    private final static long MIN_MEASURE_TIME_NS = 1000L * 1000 * 1000 * TIME_SCALE; // 1s min
    private final static long MAX_MEASURE_TIME_NS = 6000L * 1000 * 1000 * TIME_SCALE; // 6s max
    private final static int MAX_FRAME_CYCLES = 1000 * TIME_SCALE / CYCLE_DELAY;

    private final static int COLOR_TOLERANCE = 10;

    private final static Color[] MARKER = {Color.RED, Color.BLUE, Color.GREEN};

    private final static Toolkit TOOLKIT = Toolkit.getDefaultToolkit();

    private final static long FRAME_MAX = 60;
    private final static long FRAME_PREC_IN_NANOS = (1000L * 1000 * 1000) / (2L * FRAME_MAX);

    final static class Particles {
        private final float[] bx;
        private final float[] by;
        private final float[] vx;
        private final float[] vy;
        private final float r;
        private final int n;

        private final float x0;
        private final float y0;
        private final float width;
        private final float height;

        Particles(int n, float r, float x0, float y0, float width, float height) {
            bx = new float[n];
            by = new float[n];
            vx = new float[n];
            vy = new float[n];
            this.n = n;
            this.r = r;
            this.x0 = x0;
            this.y0 = y0;
            this.width = width;
            this.height = height;
            for (int i = 0; i < n; i++) {
                bx[i] = (float) (x0 + r + 0.1 + Math.random() * (width - 2 * r - 0.2 - x0));
                by[i] = (float) (y0 + r + 0.1 + Math.random() * (height - 2 * r - 0.2 - y0));
                vx[i] = 0.1f * (float) (Math.random() * 2 * r - r);
                vy[i] = 0.1f * (float) (Math.random() * 2 * r - r);
            }

        }

        void render(Graphics2D g2d, ParticleRenderer renderer) {
            for (int i = 0; i < n; i++) {
               // renderer.render(g2d, i, bx, by, vx, vy);
                if ((i % 10) == 0) {
                    //g2d.setColor(colors[id % colors.length]);
                    g2d.setColor(Color.BLUE);
                    g2d.setClip(new Ellipse2D.Double((int) (bx[i] - r), (int) (by[i] - r), (int) (2 * r), (int) (2 * r)));
                    g2d.fillRect((int) (bx[i] - 2 * r), (int) (by[i] - 2 * r), (int) (4 * r), (int) (4 * r));
                }

            }
        }

        void update() {
            for (int i = 0; i < n; i++) {
                bx[i] += vx[i];
                if (bx[i] + r > width || bx[i] - r < x0) vx[i] = -vx[i];
                by[i] += vy[i];
                if (by[i] + r > height || by[i] - r < y0) vy[i] = -vy[i];
            }
        }
    }

    interface Renderable {
        void setup(Graphics2D g2d, boolean enabled);

        void render(Graphics2D g2d);

        void update();
    }

    final static class ParticleRenderable implements Renderable {
        final Particles balls;
        final ParticleRenderer renderer;

        ParticleRenderable(final Particles balls, final ParticleRenderer renderer) {
            this.balls = balls;
            this.renderer = renderer;
        }

        @Override
        public void setup(final Graphics2D g2d, final boolean enabled) {
        }

        @Override
        public void render(Graphics2D g2d) {
            balls.render(g2d, renderer);
        }

        @Override
        public void update() {
            balls.update();
        }

    }

    interface ParticleRenderer {
        void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy);
    }

    static class FlatParticleRenderer implements ParticleRenderer {
        Color[] colors;
        float r;

        FlatParticleRenderer(int n, float r) {
            colors = new Color[n];
            this.r = r;
            for (int i = 0; i < n; i++) {
                colors[i] = new Color((float) Math.random(),
                        (float) Math.random(), (float) Math.random());
            }
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.setColor(colors[id % colors.length]);
            g2d.fillOval((int) (x[id] - r), (int) (y[id] - r), (int) (2 * r), (int) (2 * r));
        }

    }

    static class ClipFlatParticleRenderer extends FlatParticleRenderer {

        ClipFlatParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            if ((id % 10) == 0) {
                g2d.setColor(colors[id % colors.length]);
                g2d.setClip(new Ellipse2D.Double((int) (x[id] - r), (int) (y[id] - r), (int) (2 * r), (int) (2 * r)));
                g2d.fillRect((int) (x[id] - 2 * r), (int) (y[id] - 2 * r), (int) (4 * r), (int) (4 * r));
            }
        }

    }

    final static class PerfMeter {

        private final FrameHandler fh;
        private final String name;
        private final PerfMeterExecutor executor;

        PerfMeter(final FrameHandler fh, String name) {
            this.fh = fh;
            this.name = name;
            executor = getExecutor();
        }

        void exec(final Renderable renderable) throws Exception {
            executor.exec(name, renderable);
        }

        private PerfMeterExecutor getExecutor() {
            switch (EXEC_MODE) {
                default:
                case EXEC_MODE_ROBOT:
                    return new PerfMeterRobot(fh);
            }
        }
    }

    static void paintTest(final Renderable renderable, final Graphics2D g2d,
                          final Color markerColor, final boolean doSync) {
        // clip to frame:
        g2d.setClip(0, 0, IMAGE_W, IMAGE_H);
        // clear background:
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, IMAGE_W, IMAGE_H);

        // render test:
        renderable.setup(g2d, true);
        renderable.render(g2d);
        renderable.setup(g2d, false);

        // draw marker at end:
        g2d.setClip(0, 0, BW, BH);
        g2d.setColor(markerColor);
        g2d.fillRect(0, 0, BW, BH);
    }

    final static class FrameHandler {

        private boolean calibrate = VERBOSE;

        private int threadId = -1;
        private int frameId = -1;

        private final GraphicsConfiguration gc;

        private JFrame frame = null;

        private final CountDownLatch latchShownFrame = new CountDownLatch(1);
        private final CountDownLatch latchClosedFrame = new CountDownLatch(1);


        FrameHandler(GraphicsConfiguration gc) {
            this.gc = gc;
        }

        void setIds(int threadId, int frameId) {
            this.threadId = threadId;
            this.frameId = frameId;
        }

        void prepareFrameEDT(final String title) {
            if (frame == null) {
                frame = new JFrame(gc);
                frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                frame.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentShown(ComponentEvent e) {
                        latchShownFrame.countDown();
                    }
                });
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        latchClosedFrame.countDown();
                    }
                });
            }
            frame.setTitle(title);
        }

        void showFrameEDT(final JPanel panel) {
            if (frame != null) {
                panel.setPreferredSize(new Dimension(IMAGE_W, IMAGE_H));
                panel.setBackground(Color.BLACK);

                frame.getContentPane().removeAll();
                frame.getContentPane().add(panel);
                frame.getContentPane().revalidate();

                if (!frame.isVisible()) {
                    if (frameId != -1) {
                        final int off = (frameId - 1) * 100;
                        final Rectangle gcBounds = gc.getBounds();
                        final int xoff = gcBounds.x + off;
                        final int yoff = gcBounds.y + off;

                        if ((xoff != 0) || (yoff != 0)) {
                            frame.setLocation(xoff, yoff);
                        }
                    }
                    frame.pack();
                    frame.setVisible(true);
                }
            }
        }

        void waitFrameShown() throws Exception {
            latchShownFrame.await();
        }

        void resetFrame() throws Exception {
            if (frame != null) {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        frame.getContentPane().removeAll();
                        frame.getContentPane().revalidate();
                    }
                });
            }
        }

        void repaintFrame() throws Exception {
            if (frame != null) {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        frame.repaint();
                    }
                });
            }
        }

        private void waitFrameHidden() throws Exception {
            latchClosedFrame.await();
        }

        void hideFrameAndWait() throws Exception {
            if (frame != null) {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        frame.setVisible(false);
                        frame.dispose();
                        frame = null;
                    }
                });
                waitFrameHidden();
            }
        }

    }

    static abstract class PerfMeterExecutor {

        protected final static int SCORE_MAIN = 0;
        protected final static int SCORE_ERROR = 1;
        protected final static int SCORE_OTHER = 2;

        protected final static int PCT_00 = 0;
        protected final static int PCT_10 = 1;
        protected final static int PCT_25 = 2;
        protected final static int PCT_50 = 3;
        protected final static int PCT_75 = 4;
        protected final static int PCT_90 = 5;
        protected final static int PCT_100 = 6;

        private final static IntBinaryOperator INC_MOD_FUNC = new IntBinaryOperator() {
            public int applyAsInt(int x, int y) {
                return (x + 1) % y;
            }
        };

        private static final AtomicInteger headerMark = new AtomicInteger(1);

        /* members */
        protected final FrameHandler fh;
        protected final boolean skipWait;

        protected final AtomicInteger paintIdx = new AtomicInteger(0);
        protected final AtomicInteger markerIdx = new AtomicInteger(0);
        protected final AtomicLong markerStartTime = new AtomicLong(0);
        protected final AtomicLong markerPaintTime = new AtomicLong(0);
        private int renderedMarkerIdx = -1;
        protected String name = null;
        protected int skippedFrames = 0;

        protected int frames = 0;
        // test timestamp data:
        protected long[] testTimestamp = new long[MAX_SAMPLE_COUNT];
        // test duration data (ns):
        protected long[] testTime = new long[MAX_SAMPLE_COUNT];

        protected final double[] scores = new double[SCORE_OTHER + 1];
        protected final double[] results = new double[PCT_100 + 1];
int x = 100;
int y = 100;
        protected PerfMeterExecutor(final boolean skipWait, final FrameHandler fh) {
            this.skipWait = skipWait;
            this.fh = fh;
        }

        protected void beforeExec() {
        }

        protected void afterExec() {
        }

        protected void reset() {
            paintIdx.set(0);
            markerIdx.set(0);
            markerStartTime.set(0);
            markerPaintTime.set(0);
        }

        protected void updateMarkerIdx() {
            markerIdx.accumulateAndGet(MARKER.length, INC_MOD_FUNC);
        }

        protected final void exec(final String testName, final Renderable renderable) throws Exception {
            if (TRACE) System.out.print("\n!");
            this.name = testName + (isMultiThreads() ? ("-" + fh.threadId) : "");

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    fh.prepareFrameEDT(name);
                    // call beforeExec() after frame is created:
                    beforeExec();

                    final JPanel panel = new JPanel() {
                        @Override
                        protected void paintComponent(Graphics g) {
                            final int idx = markerIdx.get();
                            final long start = System.nanoTime();

                            final Graphics2D g2d = (Graphics2D) g.create();
                            try {
                                // clip to frame:
                                g2d.setClip(0, 0, IMAGE_W, IMAGE_H);

                                var bx = ((ParticleRenderable)renderable).balls.bx;
                                var by = ((ParticleRenderable)renderable).balls.by;
                                var r = ((ParticleRenderable)renderable).balls.r;
                                for (int i = 0; i < 10; i++) {
                                    // renderer.render(g2d, i, bx, by, vx, vy);
                                  //  if ((i % 10) == 0) {
                                        //g2d.setColor(colors[id % colors.length]);
                                        g2d.setColor(Color.BLUE);
                                        g2d.setClip(new Ellipse2D.Double((int) (bx[i] - r), (int) (by[i] - r), (int) (2 * r), (int) (2 * r)));
                                        g2d.fillRect((int) (bx[i] - 2 * r), (int) (by[i] - 2 * r), (int) (4 * r), (int) (4 * r));
                                    //}

                                }
                                g2d.setClip(0, 0, BW, BH);
                                g2d.setColor(MARKER[idx]);
                                g2d.fillRect(0, 0, BW, BH);
                            } finally {
                                g2d.dispose();
                            }

                            // Update paintIdx:
                            paintIdx.incrementAndGet();

                            // publish start time:
                            if (idx != renderedMarkerIdx) {
                                renderedMarkerIdx = idx;
                                markerStartTime.set(start);
                            }
                        }
                    };
                    fh.showFrameEDT(panel);
                    if (TRACE) System.out.print(">>");
                }
            });

            // Wait frame to be shown:
            fh.waitFrameShown();

            if (TRACE) System.out.print(":");

            // Reset before warmup:
            reset();

            if (WARMUP_COUNT > 0) {
                // Warmup to prepare frame synchronization:
                for (int i = 0; i < WARMUP_COUNT; i++) {
                    updateMarkerIdx();
                    renderable.update();
                    fh.repaintFrame();
                    sleep(10);
                    while (markerStartTime.get() == 0) {
                        if (TRACE) System.out.print("-");
                        sleep(1);
                    }
                    markerStartTime.set(0);
                }
                // Reset before measurements:
                reset();
            }
            if (TRACE) System.out.print(":>>");

            // signal thread is ready for test
            readyCount.countDown();
            // wait start signal:
            triggerStart.await();
            // Run Benchmark (all threads):

            int cycles = 0;
            frames = 0;
            long paintStartTime = 0L;
            long paintElapsedTime = 0L;
            long lastFrameTime = 0L;

            final long startTime = System.nanoTime();
            final long minTime = startTime + MIN_MEASURE_TIME_NS;
            final long endTime = startTime + MAX_MEASURE_TIME_NS;

            // Start 1st measurement:
            fh.repaintFrame();

            for (; ; ) {
                long t;
                if ((t = markerStartTime.getAndSet(0L)) > 0L) {
                    paintStartTime = t;
                    if (TRACE) System.out.print("|");
                }

                boolean wait = true;

                if (paintStartTime > 0L) {
                    // get optional elapsed time:
                    paintElapsedTime = markerPaintTime.get();

                    if (TRACE) System.out.print(".");
                    wait = !skipWait;
                    final Color c = getMarkerColor();

                    if (isAlmostEqual(c, MARKER[markerIdx.get()])) {
                        final long durationNs = getElapsedTime((paintElapsedTime != 0L) ? paintElapsedTime : paintStartTime);
                        if ((durationNs > 0L) && (frames < MAX_SAMPLE_COUNT)) {
                            testTimestamp[frames] = paintStartTime - startTime;
                            testTime[frames] = durationNs;
                        }
                        if (REPORT_OVERALL_FPS) {
                            lastFrameTime = System.nanoTime();
                        }
                        if (TRACE) System.out.print("R");
                        frames++;
                        paintStartTime = 0L;
                        paintElapsedTime = 0L;
                        cycles = 0;
                        updateMarkerIdx();
                        renderable.update();
                        fh.repaintFrame();
                    } else if (cycles >= MAX_FRAME_CYCLES) {
                        if (TRACE) System.out.print("M");
                        skippedFrames++;
                        paintStartTime = 0L;
                        paintElapsedTime = 0L;
                        cycles = 0;
                        updateMarkerIdx();
                        fh.repaintFrame();
                    } else {
                        if (TRACE) System.out.print("-");
                    }
                }
                final long currentTime = System.nanoTime();
                if ((frames >= MIN_COUNT) && (currentTime >= endTime)) {
                    break;
                }
                if ((frames >= COUNT) && (currentTime >= minTime)) {
                    break;
                }
                if (wait) {
                    sleep(CYCLE_DELAY);
                }
                cycles++;
            } // end measurements
        }

        protected abstract void paintPanel(final Renderable renderable, final Graphics g);

        protected abstract long getElapsedTime(long paintTime);

        protected abstract Color getMarkerColor() throws Exception;

        protected boolean isAlmostEqual(Color c1, Color c2) {
            return (Math.abs(c1.getRed() - c2.getRed()) < COLOR_TOLERANCE) &&
                    (Math.abs(c1.getGreen() - c2.getGreen()) < COLOR_TOLERANCE) &&
                    (Math.abs(c1.getBlue() - c2.getBlue()) < COLOR_TOLERANCE);
        }
    }

    final static class PerfMeterRobot extends PerfMeterExecutor {

        private int nRobotTimes = 0;
        private long[] robotTime = (fh.calibrate) ? new long[COUNT] : null;

        private int nDelayTimes = 0;
        private long[] delayTime = (ROBOT_TIME_DELAY) ? null : new long[COUNT];

        private long lastPaintTime = 0L;
        private int renderedMarkerIdx = -1;

        private Robot robot = null;

        PerfMeterRobot(final FrameHandler fh) {
            super(true, fh);
        }

        protected void beforeExec() {
            try {
                robot = new Robot();
            } catch (AWTException ae) {
                throw new RuntimeException(ae);
            }
        }

        protected void reset() {
            super.reset();
            nRobotTimes = 0;
            nDelayTimes = 0;
            lastPaintTime = 0L;
            renderedMarkerIdx = -1;
        }

        protected void paintPanel(final Renderable renderable, final Graphics g) {
            final int idx = markerIdx.get();
            final long start = System.nanoTime();

            final Graphics2D g2d = (Graphics2D) g.create();
            try {
                // clip to frame:
                g2d.setClip(0, 0, IMAGE_W, IMAGE_H);
                // clear background:
                g2d.setColor(Color.BLACK);
                g2d.fillRect(0, 0, IMAGE_W, IMAGE_H);

                // render test:
                renderable.setup(g2d, true);
                renderable.render(g2d);
                renderable.setup(g2d, false);

                // draw marker at end:
                g2d.setClip(0, 0, BW, BH);
                g2d.setColor(MARKER[idx]);
                g2d.fillRect(0, 0, BW, BH);
            } finally {
                g2d.dispose();
            }

            // Update paintIdx:
            paintIdx.incrementAndGet();

            // publish start time:
            if (idx != renderedMarkerIdx) {
                renderedMarkerIdx = idx;
                markerStartTime.set(start);
            }
        }

        protected long getElapsedTime(final long paintTime) {
            final long now = System.nanoTime();
            long duration = (!ROBOT_TIME_DELAY) ? roundDuration(now - paintTime) : 0L;
            if (lastPaintTime != 0L) {
                final long delay = roundDuration(now - lastPaintTime);
                if (ROBOT_TIME_DELAY) {
                    duration = delay;
                } else if (nDelayTimes < COUNT) {
                    delayTime[nDelayTimes++] = delay;
                }
            }
            lastPaintTime = now;
            return duration;
        }

        private static long roundDuration(final long durationNs) {
            return (durationNs <= 0L) ? 0L : (
                    (ROBOT_TIME_ROUND) ?
                            FRAME_PREC_IN_NANOS * (long) Math.rint(((double) durationNs) / FRAME_PREC_IN_NANOS) : durationNs
            );
        }

        protected Color getMarkerColor() {
            final Point frameOffset = fh.frame.getLocationOnScreen();
            final Insets insets = fh.frame.getInsets();
            final int px = frameOffset.x + insets.left + BW / 2;
            final int py = frameOffset.y + insets.top + BH / 2;

            final long beforeRobot = (fh.calibrate) ? System.nanoTime() : 0L;

            final Color c = robot.getPixelColor(px, py);

            if ((fh.calibrate) && (nRobotTimes < COUNT)) {
                robotTime[nRobotTimes++] = System.nanoTime() - beforeRobot;
            }
            return c;
        }
    }


    private final FrameHandler fh;

    private final Particles balls = new Particles(N, R, BW, BH, WIDTH, HEIGHT);

    private final ParticleRenderer clipFlatRenderer = new ClipFlatParticleRenderer(N, R);

    RenderPerfTest(final GraphicsConfiguration gc) {
        fh = new FrameHandler(gc);
    }

    ParticleRenderable createPR(final ParticleRenderer renderer) {
        return new ParticleRenderable(balls, renderer);
    }

    PerfMeter createPerfMeter(final String name) {
        return new PerfMeter(fh, name);
    }


    public void testClipFlatOval() throws Exception {
        createPerfMeter(testName).exec(createPR(clipFlatRenderer));
    }


    public static void main(String[] args)
            throws NoSuchMethodException, NumberFormatException {
        int retCode = 0;
        try {
            final RenderPerfTest rp =
                    new RenderPerfTest(
                            GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration());
            rp.fh.setIds(1, 1);

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    rp.fh.prepareFrameEDT(VERSION + " [" + rp.fh.threadId + "]");

                    final JLabel label = new JLabel((DELAY_START) ? "Waiting 3s before starting benchmark..." : "Starting benchmark...");
                    label.setForeground(Color.WHITE);

                    final JPanel panel = new JPanel();
                    panel.add(label);

                    rp.fh.showFrameEDT(panel);
                }
            });

            // Wait frame to be shown:
            rp.fh.waitFrameShown();

            // Set test count per thread:
            testCount = 1 * REPEATS;

            Thread thread = new Thread("]") {
                @Override
                public void run() {
                    try {
                        rp.testClipFlatOval();
                    } catch (Throwable th) {
                    }
                }
            };
            thread.start();

            initBarrierStart();

            // reset stop barrier (to be ready):
            initBarrierStop();

            readyCount.await();

            triggerStart.countDown();

            // reset done barrier (to be ready):
            initBarrierDone();

            completedCount.await();
            thread.join();
        } catch (Throwable th) {
            System.err.println("Exception occurred during :");
            th.printStackTrace(System.err);
            retCode = 1;
        } finally {
            System.exit(retCode);
        }
    }

    // thread synchronization

    private static int threadCount = 0;

    private static int testCount = 0;
    private static volatile String testName = null;

    private static volatile CountDownLatch readyCount = null;
    private static volatile CountDownLatch triggerStart = null;

    private static volatile CountDownLatch completedCount = null;
    private static volatile CountDownLatch triggerStop = null;

    private static volatile CountDownLatch doneCount = null;
    private static volatile CountDownLatch triggerExit = null;

    static void traceSync(final String msg) {
        System.out.println("[" + System.nanoTime() + "] " + msg);
    }

    private static void initThreads(int count) {
        threadCount = count;
    }

    static boolean isMultiThreads() {
        return threadCount > 1;
    }

    private static void initBarrierStart() {
        readyCount = new CountDownLatch(threadCount);
        triggerStart = new CountDownLatch(1);
    }

    private static void initBarrierStop() {
        completedCount = new CountDownLatch(threadCount);
        triggerStop = new CountDownLatch(1);
    }

    private static void initBarrierDone() {
        doneCount = new CountDownLatch(threadCount);
        triggerExit = new CountDownLatch(1);
    }

    public Thread createThreadTests(final int threadId, final int frameId,
                                    final ArrayList<Method> testCases) throws Exception {
        fh.setIds(threadId, frameId);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                fh.prepareFrameEDT(VERSION + " [" + fh.threadId + "]");

                final JLabel label = new JLabel((DELAY_START) ? "Waiting 3s before starting benchmark..." : "Starting benchmark...");
                label.setForeground(Color.WHITE);

                final JPanel panel = new JPanel();
                panel.add(label);

                fh.showFrameEDT(panel);
            }
        });

        // Wait frame to be shown:
        fh.waitFrameShown();

        // Set test count per thread:
        testCount = testCases.size() * REPEATS;

        final RenderPerfTest rp = this;
        return new Thread("RenderPerfThread[" + threadId + "]") {
            @Override
            public void run() {
                try {
                    rp.testClipFlatOval();
                } catch (Throwable th) {
                }
            }
        };
    }

    private static void sleep(final long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ie) {
                ie.printStackTrace(System.err);
            }
        }
    }

}
