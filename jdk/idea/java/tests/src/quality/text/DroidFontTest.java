package quality.text;

import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;

public class DroidFontTest {

    private void doTestFont(String name) throws IOException, FontFormatException {
        String testDataStr = System.getProperty("testdata");
        Assert.assertNotNull("testdata property is not set", testDataStr);

        File testData = new File(testDataStr, "quality" + File.separator + "text");

        BufferedImage bimg = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = bimg.createGraphics();

        Font f = new Font(name, Font.PLAIN, 20);

        g2d.setFont(f);
        g2d.setColor(Color.WHITE);
        Rectangle2D bnd = f.getStringBounds(name, g2d.getFontRenderContext());

        g2d.drawString(name, 0, 25);


        BufferedImage resultImage = bimg.getSubimage((int) bnd.getX(), (int) (25 + bnd.getY()),
                (int) bnd.getWidth(), (int) bnd.getHeight());

        String gfName = name.toLowerCase().replace(" ", "").concat(".png");

        BufferedImage goldenImage = ImageIO.read(new File(testData, gfName));
        Assert.assertTrue("Golden image and result have different sizes",
                resultImage.getWidth() == goldenImage.getWidth() && resultImage.getHeight() == resultImage.getHeight());

        Raster gRaster = goldenImage.getData();
        Raster rRaster = resultImage.getData();
        int [] gArr = new int[3];
        int [] rArr = new int[3];
        for (int i = 0; i < gRaster.getWidth(); i++) {
            for (int j = 0; j < gRaster.getHeight(); j++) {
                gRaster.getPixel(i, j, gArr);
                rRaster.getPixel(i, j, rArr);
                Assert.assertArrayEquals("Different pixels found at (" + i + "," + j + ")", gArr, rArr);
            }
        }
    }


    @Test
    public void testDroidSans() throws Exception {
        doTestFont("Droid Sans");
    }

    @Test
    public void testDroidSansBold() throws Exception {
        doTestFont("Droid Sans Bold");
    }

    @Test
    public void testDroidSansMono() throws Exception {
        doTestFont("Droid Sans Mono");
    }

    @Test
    public void testDroidSerifRegular() throws Exception {
        doTestFont("Droid Serif Regular");
    }

    @Test
    public void testDroidSerifBold() throws Exception {
        doTestFont("Droid Serif Bold");
    }

    @Test
    public void testDroidSerifItalic() throws Exception {
        doTestFont("Droid Serif Italic");
    }
}
