import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.awt.Robot;

/*
 * @test
 * @summary The test renders the same string twice. At the top the string is rendered directly. At the bottom,
 *     it's first rendered to a BufferedImage, and then the image is blitted.
 *     Then the test compares these two strings and fails if there are the difference in their renderings is above 0.1%
 * @run main/othervm -Dverbose=true BufferedFontRenderingTest
 */
public class BufferedFontRenderingTest {

    private static final int X = 40;
    private static final int Y = 20;
    private static final float DIFF_PERCENTAGE = 0.1f;
    private static JFrame f;

    public static void main(String[] args) throws Exception {

        boolean verbose = Boolean.parseBoolean(System.getProperty("verbose", "false"));

        Robot robot = new Robot();
        SwingUtilities.invokeAndWait(() -> {
            f = new JFrame();
            f.add(new MyComponent());
            f.setSize(200, 100);
            f.setLocationRelativeTo(null);
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setUndecorated(true);
            f.setVisible(true);
        });
        robot.delay(100);
        robot.waitForIdle();

        BufferedImage image = robot.createScreenCapture(new Rectangle(f.getX(), f.getY(), f.getWidth(), f.getHeight()));

        if (verbose) {
            String format = "bmp";
            ImageIO.write(image, format, new File("image" + "." + format));
        }

        int countDiffs = 0;
        int totalCmps = 0;

        for (int row = 1; row < f.getHeight() / 2; row++) {
            for (int col = 1; col < f.getWidth(); col++) {
                totalCmps++;

                int expectedRGB = image.getRGB(col, row) & 0x00FFFFFF;
                int rgb = image.getRGB(col, row + f.getHeight()/2) & 0x00FFFFFF;

                if (verbose)
                    System.out.print((rgb == expectedRGB) ? "." : "x");

                if (expectedRGB != rgb)
                    countDiffs++;
            }
            if (verbose)
                System.out.println();
        }

        double percentage = (double)countDiffs*100/totalCmps;

        System.out.printf("total comparisons: %6d\n", totalCmps);
        System.out.printf("     diffs number: %6d\n", countDiffs);
        System.out.printf("  diff percentage: %6.4f%%\n", percentage);

        boolean passed = (percentage < DIFF_PERCENTAGE);

        SwingUtilities.invokeAndWait(() -> {
            f.dispose();
        });
        robot.delay(100);
        robot.waitForIdle();

        if (!passed) {
            String msg = String.format("images differ to each other by %6.4f%% that is more than allowed %6.4f%%",
                    percentage, DIFF_PERCENTAGE);
            throw new RuntimeException(msg);
        }
    }

    private static class MyComponent extends JComponent {
        private static final String TEXT = "A quick brown fox";
        private static final Font FONT = new Font("Inter", Font.PLAIN, 13);
        private static final Map<?, ?> HINTS = Map.of(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        @Override
        protected void paintComponent(Graphics _g) {
            Graphics2D g = (Graphics2D) _g;
            g.setRenderingHints(HINTS);
            g.setFont(FONT);
            g.drawString(TEXT, X, Y);

            double scale = g.getTransform().getScaleX();
            int imgWidth = getWidth();
            int imgHeight = getHeight() / 2;

            BufferedImage img = new BufferedImage((int) Math.ceil(imgWidth * scale), (int) Math.ceil(imgHeight * scale), BufferedImage.TYPE_INT_RGB);
            Graphics2D ig = img.createGraphics();
            ig.scale(scale, scale);
            ig.setColor(getForeground());
            ig.setBackground(getBackground());
            ig.clearRect(0, 0, imgWidth, imgHeight);
            ig.setRenderingHints(HINTS);
            ig.setFont(FONT);
            ig.drawString(TEXT , X, Y);
            ig.dispose();

            g.translate(0, imgHeight);
            g.scale(1 / scale, 1 / scale);
            g.drawImage(img, 0, 0, null);
        }
    }
}