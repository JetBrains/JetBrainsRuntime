package quality.text;

import org.junit.Assert;
import org.junit.Test;
import quality.util.RenderUtil;
import util.Renderer;

import java.awt.*;
import java.awt.image.BufferedImage;

public class FiraCodeScrTest {

    @Test
    public void testRenderText() throws Exception {
        String [] testResult = new String[] {null};

        (new util.Renderer(2, 300, 30, true)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                g2d.setColor(Color.WHITE);
                Font f = new Font("Fira Code", Font.PLAIN, 12);
                g2d.setFont(f);
                g2d.drawString("The quick brown fox jumps over the lazy dog", 0, 15);
            }
            @Override
            public void screenShot(BufferedImage result) {
                try {
                    RenderUtil.checkImage(result, "text", "firacode.png");
                } catch (Exception e) {
                    testResult[0] = e.getMessage();
                }
            }
        });

        if (testResult[0] != null) Assert.fail(testResult[0]);
    }
}
