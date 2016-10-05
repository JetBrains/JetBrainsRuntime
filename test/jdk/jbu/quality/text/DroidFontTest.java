package quality.text;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;

import static org.junit.Assert.*;

public class DroidFontTest {
    /* Tests for the following font names and styles:

            "droid sans" : PLAIN, BOLD
            "droid sans bold" : alias for "droid sans"

            "droid sans mono" : PLAIN
            "droid sans mono slashed" : PLAIN
            "droid sans mono dotted" : PLAIN

            "droid serif" : PLAIN, BOLD, ITALIC, BOLD | ITALIC

            "droid serif bold" : alias for "droid serif" BOLD, BOLD | ITALIC
    */

    private static final String abc =
            "the quick brown fox jumps over the lazy dog";

    private static final String digits = "0123456789";

    @SuppressWarnings("SameParameterValue")
    private void doTestFont(String aliasName, String name, int style, int size)
            throws Exception {

        String[] testDataVariant = {
                "osx_hardware_rendering", "osx_software_rendering", "osx_sierra_rendering",
                "linux_rendering"};

        String testDataStr = System.getProperty("testdata");
        assertNotNull("testdata property is not set", testDataStr);

        File testData = new File(testDataStr, "quality" + File.separator +
                "text");
        assertTrue("Test data dir does not exist", testData.exists());

        String testStr = abc.toUpperCase() + abc + digits;

        BufferedImage image = new BufferedImage((size + 3)*testStr.length(),
                size*3, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        Font f = new Font(aliasName, style, size);

        g2d.setFont(f);
        g2d.setColor(Color.WHITE);
        Rectangle2D bnd = f.getStringBounds(testStr,
                g2d.getFontRenderContext());

        g2d.drawString(testStr, 0, size + 3);


        BufferedImage resultImage = image.getSubimage((int) bnd.getX(),
                (int) (size + 3 + bnd.getY()),
                (int) bnd.getWidth(), (int) bnd.getHeight());

        String gfName = name.toLowerCase().replace(" ", "") +
                Integer.toString(style) + "_" + Integer.toString(size) + ".png";

        if (System.getProperty("gentestdata") == null) {
            boolean failed = true;
            StringBuilder failureReason = new StringBuilder();
            for (String variant : testDataVariant) {
                File goldenFile = new File(testData, variant + File.separator +
                        gfName);

                BufferedImage goldenImage = ImageIO.read(goldenFile);
                failed = true;
                if (resultImage.getWidth() != goldenImage.getWidth() ||
                    resultImage.getHeight() != resultImage.getHeight())
                {
                    failureReason.append(variant).append(" : Golden image and result have different sizes\n");
                    continue;
                }

                Raster gRaster = goldenImage.getData();
                Raster rRaster = resultImage.getData();
                int[] gArr = new int[3];
                int[] rArr = new int[3];
                failed = false;
                scan:
                for (int i = 0; i < gRaster.getWidth(); i++) {
                    for (int j = 0; j < gRaster.getHeight(); j++) {
                        gRaster.getPixel(i, j, gArr);
                        rRaster.getPixel(i, j, rArr);
                        assertTrue(gArr.length == rArr.length);
                        for (int k = 0; k < gArr.length; k++) {
                            if (gArr[k] != rArr[k]) {
                                failureReason.append(variant).append(" : Different pixels found ").append("at (").append(i).append(",").append(j).append(")");
                                failed = true;
                                break scan;
                            }
                        }
                    }
                }

                if (!failed) break;
            }

            if (failed) throw new RuntimeException(failureReason.toString());
        }
        else {
            ImageIO.write(resultImage, "png", new File(testData, gfName));
        }
    }

    private void doTestFont(String name, int style)
            throws Exception {
        doTestFont(name, name, style, 20);
    }

        @Test
    public void testDroidSans() throws Exception {
        doTestFont("Droid Sans", Font.PLAIN);
    }

    @Test
    public void testDroidSansBold() throws Exception {
        doTestFont("Droid Sans", Font.BOLD);
        doTestFont("Droid Sans Bold", "Droid Sans", Font.BOLD, 20);
    }

    @Test
    public void testDroidSansMono() throws Exception {
        doTestFont("Droid Sans Mono", Font.PLAIN);
    }

    @Test
    public void testDroidSansMonoSlashed() throws Exception {
        doTestFont("Droid Sans Mono Slashed", Font.PLAIN);
    }

    @Test
    public void testDroidSansMonoDotted() throws Exception {
        doTestFont("Droid Sans Mono Dotted", Font.PLAIN);
    }

    @Test
    public void testDroidSerif() throws Exception {
        doTestFont("Droid Serif", Font.PLAIN);
    }

    @Test
    public void testDroidSerifBold() throws Exception {
        doTestFont("Droid Serif", Font.BOLD);
        doTestFont("Droid Serif Bold", "Droid Serif",
                Font.BOLD, 20);
        doTestFont("Droid Serif Bold", "Droid Serif",
                Font.BOLD | Font.ITALIC, 20);
    }

    @Test
    public void testDroidSerifItalic() throws Exception {
        doTestFont("Droid Serif", Font.ITALIC);
        doTestFont("Droid Serif Italic", "Droid Serif",
                Font.ITALIC, 20);
    }
}
