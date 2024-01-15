/**
 * @test 1.0 2016/08/25
 * @bug 8139176
 * @run main DrawTest
 * @summary java.awt.TextLayout does not handle correctly the bolded logical fonts (Serif)
 */

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Robot;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;


// The test against java.awt.font.TextLayout, it draws the text "Gerbera" twise
// via the methods Graphics.drawString and TextLayout.draw and then it checks
// that both output have the same width.
// The test does this checking for two styles of the font Serif - PLAIN and
// BOLD in course one by one.

public class DrawTest {
    static final Font plain = new Font("Serif", Font.PLAIN, 32);
    static final Font bold = new Font("Serif", Font.BOLD, 32);
    static int testCaseNo = 1;
    static final String txt = "Gerbera";
    static boolean isPassed = true;
    static String errMsg = "";
    static String testCaseName;
    static JFrame frame;
    static JPanel panel;
    static Robot robot;

    public static void main(String[] args) throws Exception {
        robot = new Robot();

        SwingUtilities.invokeAndWait(() -> createUI());
        robot.waitForIdle();
        robot.delay(100);

        BufferedImage paintImage;
        for (testCaseNo = 1; testCaseNo <= 2; testCaseNo++) {
            if (testCaseNo == 2) {
                panel.revalidate();
                panel.repaint();
            }
            paintImage = getScreenShot(panel);
            int width1 = charWidth(paintImage, 0, 9, 116, 23);
            int width2 = charWidth(paintImage, 0, 42, 116, 23);

            if (width1 != width2) {
                String tmpMsg = testCaseName + ": test case FAILED Wrong width: " + width1 + " != " + width2;
                System.out.println(tmpMsg);
                if (errMsg.length() > 0) {
                    errMsg += "; ";
                }
                errMsg += tmpMsg;
                isPassed = false;
            } else
                System.out.println(testCaseName + " test case PASSED");
        }

        frame.dispose();
        if (!isPassed) {
            throw new RuntimeException(errMsg + " logical fonts (Serif) was not correctly handled");
        }
    }

    private static void createUI() {
        frame = new JFrame();
        frame.getContentPane().setPreferredSize(new Dimension(116, 75));
        frame.pack();
        frame.setVisible(true);
        Insets insets = frame.getInsets();
        System.out.println(insets);
        panel = new JPanel() {

            private void drawString(Graphics g, Font font) {
                g.setFont(font);
                g.drawString(txt, 0, 32);
            }

            private void drawTextLayout(Graphics g, Font font) {
                TextLayout tl = new TextLayout(txt,
                        font,
                        g.getFontMetrics(font).getFontRenderContext());
                tl.draw((Graphics2D) g, 0, 65);
            }

            /**
             * @see javax.swing.JComponent#paintComponent(java.awt.Graphics)
             */
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.BLACK);

                int width;
                TextLayout tl;
                if (testCaseNo == 1) {
                    // Ok.
                    // For the PLAIN font, the text painted by g.drawString and the text layout are the same.
                    testCaseName = "PLAIN";
                    drawString(g, plain);
                    drawTextLayout(g, plain);
                } else {
                    // Not Ok.
                    // For the BOLD font, the text painted by g.drawString and the text layout are NOT the same.
                    testCaseName = "BOLD";
                    drawString(g, bold);
                    drawTextLayout(g, bold);
                }
            }
        };
        frame.getContentPane().add(panel);
        frame.setVisible(true);
    }

    static private BufferedImage getScreenShot(JPanel panel) {
        BufferedImage bi = new BufferedImage(
                panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        panel.paint(bi.getGraphics());
        return bi;
    }

    private static int charWidth(BufferedImage bufferedImage, int x, int y, int width, int height) {
        int rgb;
        int returnWidth = 0;
        for (int row = y; row < y + height; row++) {
            for (int col = x; col < x + width; col++) {
                // remove transparance
                rgb = bufferedImage.getRGB(col, row) & 0x00FFFFFF;

                int r = rgb >> 16;
                int g = (rgb >> 8) & 0x000000FF;
                int b = rgb & 0x00000FF;
                if (r == g && g == b && b == 255)
                    System.out.print(" .");
                else
                    System.out.print(" X");

                if (rgb != 0xFFFFFF && returnWidth < col)
                    returnWidth = col;
            }
            System.out.println();
        }
        return returnWidth;
    }
}
