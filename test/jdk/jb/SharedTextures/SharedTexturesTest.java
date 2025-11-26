import com.jetbrains.desktop.SharedTextures;
import com.jetbrains.desktop.SharedTexturesService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.io.IOException;

import jdk.test.lib.Asserts;

import java.awt.*;

/**
 * @test
 * @key headful
 * @summary The test creates a BufferedImage and makes a texture from its content.
 *          The texture gets wrapped into a TextureWrapperImage image by SharedTextures JBR API service.
 *          The TextureWrapperImage is copied into a BufferedImage and VolatileImage and expects that all images have
 *          the same content.
 * @library /test/lib
 * @compile --add-exports java.desktop/com.jetbrains.desktop=ALL-UNNAMED SharedTexturesTest.java
 * @run main/othervm/native -Dsun.java2d.uiScale=1 -Dsun.java2d.metal=True --add-exports java.desktop/com.jetbrains.desktop=ALL-UNNAMED SharedTexturesTest
 * @requires (os.family=="mac")
 */
public class SharedTexturesTest {
    static {
        System.loadLibrary("SharedTexturesTest");
    }

    public static void main(String[] args) throws IOException {
        BufferedImage originalImage = createImage();
        byte[] bytes = getPixelData(originalImage);

        SharedTextures sharedTexturesService = new SharedTexturesService();
        Asserts.assertEquals(sharedTexturesService.getTextureType(), SharedTexturesService.METAL_TEXTURE_TYPE);

        BufferedImage bufferedImageContent;
        BufferedImage volatileImageContent;

        long textureId = createTexture(bytes, originalImage.getWidth(), originalImage.getHeight());
        try {
            GraphicsConfiguration gc = GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            Image textureImage = sharedTexturesService.wrapTexture(gc, textureId);

            // Create a BufferedImage containing a copy of textureImage
            bufferedImageContent = new BufferedImage(
                    textureImage.getWidth(null), textureImage.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            copyImage(textureImage, bufferedImageContent);

            // Create a VolatileImage containing a copy of textureImage and dump it to a BufferedImage
            volatileImageContent = new BufferedImage(
                    textureImage.getWidth(null), textureImage.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            VolatileImage volatileImage = gc.createCompatibleVolatileImage(
                    textureImage.getWidth(null), textureImage.getHeight(null), Transparency.TRANSLUCENT);
            int attempts = 10;
            do {
                copyImage(textureImage, volatileImage);
                copyImage(volatileImage, volatileImageContent);
                attempts--;
            } while (volatileImage.contentsLost() && attempts > 0);
            Asserts.assertNotEquals(attempts, 0, "Failed to draw the VolatileImage");
        } finally {
            disposeTexture(textureId);
        }

        try {
            Asserts.assertEquals(originalImage.getWidth(), bufferedImageContent.getWidth());
            Asserts.assertEquals(originalImage.getHeight(), bufferedImageContent.getHeight());
            Asserts.assertEquals(countImageDiff(originalImage, bufferedImageContent), 0);

            Asserts.assertEquals(originalImage.getWidth(), volatileImageContent.getWidth());
            Asserts.assertEquals(originalImage.getHeight(), volatileImageContent.getHeight());
            Asserts.assertEquals(countImageDiff(originalImage, volatileImageContent), 0);
        } catch (Exception e) {
            saveImage(originalImage, "original_image.png");
            saveImage(bufferedImageContent, "buffered_image.png");
            saveImage(volatileImageContent, "volatile_image.png");
            throw e;
        }
    }

    private static void copyImage(Image src, Image dst) {
        Graphics2D g = (Graphics2D) dst.getGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, dst.getWidth(null), dst.getHeight(null));
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(src, 0, 0, null);
        g.dispose();
    }

    private static BufferedImage createImage() {
        int width = 300;
        int height = 200;

        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bufferedImage.createGraphics();

        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, width, height);
        g.setComposite(AlphaComposite.SrcOver);

        g.setColor(Color.GREEN);
        g.fillRect(0, 0, width / 2, height);

        g.setColor(new Color(255, 0, 0, 128));
        g.fillOval(0, 0, width, height);
        g.dispose();

        return bufferedImage;
    }

    static void saveImage(BufferedImage image, String name) throws IOException {
        File outputFile = new File(name);
        String formatName = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        ImageIO.write(image, formatName, outputFile);
    }

    private native static long createTexture(byte[] data, int width, int height);

    private native static void disposeTexture(long textureId);

    private static byte[] getPixelData(BufferedImage image) {
        byte[] rawPixels = new byte[image.getWidth() * image.getHeight() * 4];
        int[] argbPixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        for (int i = 0; i < argbPixels.length; i++) {
            int argb = argbPixels[i];
            rawPixels[i * 4] = (byte) (argb & 0xFF);
            rawPixels[i * 4 + 1] = (byte) ((argb >> 8) & 0xFF);
            rawPixels[i * 4 + 2] = (byte) ((argb >> 16) & 0xFF);
            rawPixels[i * 4 + 3] = (byte) ((argb >> 24) & 0xFF);
        }

        return rawPixels;
    }

    private static int countImageDiff(BufferedImage lhs, BufferedImage rhs) {
        int count = 0;
        for (int y = 0; y < lhs.getHeight(); y++) {
            for (int x = 0; x < lhs.getWidth(); x++) {
                if (lhs.getRGB(x, y) != rhs.getRGB(x, y)) {
                    count++;
                }
            }
        }

        return count;
    }
}
