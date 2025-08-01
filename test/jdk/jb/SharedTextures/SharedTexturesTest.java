import com.jetbrains.desktop.SharedTextures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
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
 * @run main/othervm/native -Dsun.java2d.uiScale=1 -Dsun.java2d.metal=True --enable-native-access=ALL-UNNAMED --add-exports java.desktop/com.jetbrains.desktop=ALL-UNNAMED SharedTexturesTest
 * @run main/othervm/native -Dsun.java2d.uiScale=1 -Dsun.java2d.opengl=True --enable-native-access=ALL-UNNAMED --add-exports java.desktop/com.jetbrains.desktop=ALL-UNNAMED SharedTexturesTest
 * @requires (os.family=="mac")
 */

/**
 * @test
 * @key headful
 * @summary The test creates a BufferedImage and makes a texture from its content.
 *          The texture gets wrapped into a TextureWrapperImage image by SharedTextures JBR API service.
 *          The TextureWrapperImage is copied into a BufferedImage and VolatileImage and expects that all images have
 *          the same content.
 * @library /test/lib
 * @compile --add-exports java.desktop/com.jetbrains.desktop=ALL-UNNAMED SharedTexturesTest.java
 * @run main/othervm/native -Dsun.java2d.uiScale=1 -Dsun.java2d.opengl=True --enable-native-access=ALL-UNNAMED --add-exports java.desktop/com.jetbrains.desktop=ALL-UNNAMED SharedTexturesTest
 * @requires (os.family=="windows")
 */

/**
 * @test
 * @key headful
 * @summary The test creates a BufferedImage and makes a texture from its content.
 *          The texture gets wrapped into a TextureWrapperImage image by SharedTextures JBR API service.
 *          The TextureWrapperImage is copied into a BufferedImage and VolatileImage and expects that all images have
 *          the same content.
 * @library /test/lib
 * @compile --add-exports java.desktop/com.jetbrains.desktop=ALL-UNNAMED SharedTexturesTest.java
 * @run main/othervm/native -Dsun.java2d.uiScale=1 -Dsun.java2d.opengl=True --enable-native-access=ALL-UNNAMED --add-exports java.desktop/com.jetbrains.desktop=ALL-UNNAMED SharedTexturesTest
 * @requires (os.family=="linux")
 */

public class SharedTexturesTest {
    static {
        System.loadLibrary("SharedTexturesTest");
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        GraphicsConfiguration gc = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();

        SharedTextures sharedTextures = SharedTextures.create();
        int textureType = sharedTextures.getTextureType(gc);
        if ("True".equals(System.getProperty("sun.java2d.opengl"))) {
            Asserts.assertEquals(textureType, SharedTextures.OPENGL_TEXTURE_TYPE);
        } else if ("True".equals(System.getProperty("sun.java2d.metal"))) {
            Asserts.assertEquals(textureType, SharedTextures.METAL_TEXTURE_TYPE);
        } else {
            throw new RuntimeException("The rendering pipeline has to be specified explicitly");
        }

        BufferedImage originalImage = createImage();
        boolean flipY = textureType == SharedTextures.OPENGL_TEXTURE_TYPE;
        byte[] bytes = getPixelData(originalImage, TexturePixelLayout.get(textureType), flipY);

        BufferedImage bufferedImageContent;
        BufferedImage volatileImageContent;

        if (textureType == SharedTextures.OPENGL_TEXTURE_TYPE) {
            setSharedContextInfo(sharedTextures.getOpenGLContextInfo(gc));
        }
        initNative(textureType);
        long textureId = createTexture(bytes, originalImage.getWidth(), originalImage.getHeight());

        try {
            Image textureImage = sharedTextures.wrapTexture(gc, textureId);

            // Render the shared texture image onto a BufferedImage
            bufferedImageContent = new BufferedImage(
                    textureImage.getWidth(null), textureImage.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            copyImage(textureImage, bufferedImageContent);

            // Render the shared texture image onto a VolatileImage and copy it to another BufferedImage
            // to check the content later
            volatileImageContent = new BufferedImage(
                    textureImage.getWidth(null), textureImage.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            VolatileImage volatileImageTarget = gc.createCompatibleVolatileImage(
                    textureImage.getWidth(null), textureImage.getHeight(null), Transparency.TRANSLUCENT);
            int attempts = 10;
            do {
                copyImage(textureImage, volatileImageTarget);
                copyImage(volatileImageTarget, volatileImageContent);
                attempts--;
            } while (volatileImageTarget.contentsLost() && attempts > 0);
            Asserts.assertNotEquals(attempts, 0, "Failed to draw the VolatileImage");
        } finally {
            disposeTexture(textureId);
            releaseContext();
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
            saveImage(imagesDiff(originalImage, bufferedImageContent), "buffered_image_diff.png");
            saveImage(imagesDiff(originalImage, volatileImageContent), "volatile_image_diff.png");
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

        g.setColor(Color.BLUE);
        g.drawLine(0, 0, width, height - 50);

        g.dispose();

        return bufferedImage;
    }

    static void saveImage(BufferedImage image, String name) throws IOException {
        File outputFile = new File(name);
        String formatName = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        ImageIO.write(image, formatName, outputFile);
    }

    private static class TexturePixelLayout {
        int r, g, b, a; // color component indexes
        private TexturePixelLayout(int r, int g, int b, int a) {this.r = r; this.g = g; this.b = b; this.a = a;}
        final static TexturePixelLayout RGBA = new TexturePixelLayout(0, 1, 2, 3);
        final static TexturePixelLayout BGRA = new TexturePixelLayout(2, 1, 0, 3);
        static TexturePixelLayout get(int textureType) {
            return switch (textureType) {
                case SharedTextures.OPENGL_TEXTURE_TYPE -> TexturePixelLayout.RGBA;
                case SharedTextures.METAL_TEXTURE_TYPE -> TexturePixelLayout.BGRA;
                default -> throw new RuntimeException("Unexpected texture type: " + textureType);
            };
        }
    }

    private static byte[] getPixelData(BufferedImage image, TexturePixelLayout pixelLayout, boolean flipY) {
        byte[] rawPixels = new byte[image.getWidth() * image.getHeight() * 4];
        int[] argbPixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        ColorModel cm = image.getColorModel();
        for (int i = 0; i < argbPixels.length; i++) {
            int x = i % image.getWidth();
            int y = flipY ? image.getHeight() - (i / image.getWidth()) - 1 : i / image.getWidth();
            int targetIndex = (x + y * image.getWidth()) * 4;
            int argb = argbPixels[i];
            rawPixels[targetIndex + pixelLayout.r] = (byte) cm.getRed(argb);
            rawPixels[targetIndex + pixelLayout.g] = (byte) cm.getGreen(argb);
            rawPixels[targetIndex + pixelLayout.b] = (byte) cm.getBlue(argb);
            rawPixels[targetIndex + pixelLayout.a] = (byte) cm.getAlpha(argb);
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

    private static BufferedImage imagesDiff(BufferedImage lhs, BufferedImage rhs) {
        int width = Math.min(lhs.getWidth(), rhs.getWidth());
        int height = Math.min(lhs.getHeight(), rhs.getHeight());
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ColorModel lhsCM = lhs.getColorModel();
        ColorModel rhsCM = rhs.getColorModel();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result.setRGB(x, y, lhsCM.getRGB(lhs.getRGB(x, y)) ^ rhsCM.getRGB(rhs.getRGB(x, y)));
            }
        }
        return result;
    }

    private native static void initNative(int textureType);

    private native static void setSharedContextInfo(long[] sharedContextInfo);

    private native static long createTexture(byte[] data, int width, int height);

    private native static void disposeTexture(long textureId);

    private native static void releaseContext();
}
