package util;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class Renderer {

    public final static int WIDTH = 800;
    public final static int HEIGHT = 800;
    public final static int BW = 50;
    public final static int BH = 50;
    private final static int RESOLUTION = 5;
    private final static int COLOR_TOLERANCE = 10;
    private final static int DELAY = 10;
    private int frame = 0;
    private final int count;
    private final int width;
    private final int height;
    private final boolean capture;
    private JPanel panel;

    private long time;
    private double execTime = 0;
    private Color expColor = Color.RED;
    AtomicBoolean waiting = new AtomicBoolean(false);


    public Renderer(int count) {
        this(count, WIDTH, HEIGHT, false);
    }


    public Renderer(int count, int width, int height, boolean capture) {
        this.count = count;
        this.width = width;
        this.height = height;
        this.capture = capture;
    }

    public double exec(final Renderable renderable) throws Exception {
        final CountDownLatch latch = new CountDownLatch(count);
        final CountDownLatch latchFrame = new CountDownLatch(1);

        final JFrame f = new JFrame();
        f.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                latchFrame.countDown();
            }
        });

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {

                panel = new JPanel()
                {
                    @Override
                    protected void paintComponent(Graphics g) {

                        super.paintComponent(g);
                        time = System.nanoTime();
                        Graphics2D g2d = (Graphics2D) g;
                        g2d.translate(BW, BH);
                        renderable.render(g2d);
                        g2d.translate(-BW, -BH);
                        g2d.setColor(expColor);
                        g.fillRect(0, 0, BW, BH);
                    }
                };

                panel.setPreferredSize(new Dimension(width + BW, height + BH));
                panel.setBackground(Color.BLACK);
                f.add(panel);
                f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                f.pack();
                f.setVisible(true);
            }
        });
        Robot robot = new Robot();

        Timer timer = new Timer(DELAY, e -> {

            if (waiting.compareAndSet(false, true)) {
                Color c = robot.getPixelColor(
                        panel.getTopLevelAncestor().getX() + panel.getTopLevelAncestor().getInsets().left + BW / 2,
                        panel.getTopLevelAncestor().getY() + panel.getTopLevelAncestor().getInsets().top + BH / 2);
                if (isAlmostEqual(c, Color.BLUE)) {
                    expColor = Color.RED;
                } else {
                    expColor = Color.BLUE;
                }
                if (capture) {
                    renderable.screenShot(robot.createScreenCapture(
                            new Rectangle(
                                    panel.getTopLevelAncestor().getX() + panel.getTopLevelAncestor().getInsets().left + BW,
                                    panel.getTopLevelAncestor().getY() + panel.getTopLevelAncestor().getInsets().top + BH,
                                    width, height)));
                }
                renderable.update();
                panel.getParent().repaint();

            } else {
                while (!isAlmostEqual(
                        robot.getPixelColor(
                                panel.getTopLevelAncestor().getX() + panel.getTopLevelAncestor().getInsets().left + BW/2,
                                panel.getTopLevelAncestor().getY() + panel.getTopLevelAncestor().getInsets().top + BH/2),
                        expColor))
                {
                    try {
                        Thread.sleep(RESOLUTION);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
                time = System.nanoTime() - time;
                execTime += time;
                frame++;
                waiting.set(false);
            }

            latch.countDown();
        });
        timer.start();
        latch.await();
        SwingUtilities.invokeAndWait(() -> {
            timer.stop();
            f.setVisible(false);
            f.dispose();
        });

        latchFrame.await();
        return 1e9/(execTime / frame);
    }

    private boolean isAlmostEqual(Color c1, Color c2) {
        return Math.abs(c1.getRed() - c2.getRed()) < COLOR_TOLERANCE ||
                Math.abs(c1.getGreen() - c2.getGreen()) < COLOR_TOLERANCE ||
                Math.abs(c1.getBlue() - c2.getBlue()) < COLOR_TOLERANCE;
    }

    public interface Renderable {
        void render(Graphics2D g2d);
        void update();
        void screenShot(BufferedImage result);
    }

    public static class DefaultRenderable implements Renderable {

        @Override
        public void render(Graphics2D g2d) {

        }

        @Override
        public void update() {

        }

        @Override
        public void screenShot(BufferedImage result) {

        }
    }
}
