package quality.text;

import org.junit.Assert;
import org.junit.Test;

import java.awt.*;
import java.util.Locale;

public class BundledFontTest {
    /* Tests for the following font names:
            "droid sans"
            "droid sans bold"
            "droid sans mono"
            "droid sans mono slashed"
            "droid sans mono dotted"
            "droid serif"
            "droid serif bold"
            "fira code"
            "fira code light"
            "fira code medium"
            "fira code retina"
            "inconsolata"
            "roboto light"
            "roboto thin"
            "source code pro"
            "jetbrains mono"
    */

    @SuppressWarnings("SameParameterValue")
    private void doTestFont(String name, int style) {

        Font f = new Font(name, style, 20);
        String family = f.getFamily(Locale.ENGLISH);

        Assert.assertTrue(family.equalsIgnoreCase(name));
    }

    private void doTestFont(String name)
            throws Exception {
        doTestFont(name, Font.PLAIN);
    }

    @Test
    public void testDroidSans() throws Exception {
        doTestFont("Droid Sans");
    }

    @Test
    public void testDroidSansBold() throws Exception {
        doTestFont("Droid Sans");
        doTestFont("Droid Sans", Font.BOLD);
    }

    @Test
    public void testDroidSansMono() throws Exception {
        doTestFont("Droid Sans Mono");
    }

    @Test
    public void testDroidSansMonoSlashed() throws Exception {
        doTestFont("Droid Sans Mono Slashed");
    }

    @Test
    public void testDroidSansMonoDotted() throws Exception {
        doTestFont("Droid Sans Mono Dotted");
    }

    @Test
    public void testDroidSerif() throws Exception {
        doTestFont("Droid Serif");
    }

    @Test
    public void testDroidSerifBold() throws Exception {
        doTestFont("Droid Serif");
        doTestFont("Droid Serif", Font.BOLD);
    }

    @Test
    public void testDroidSerifItalic() throws Exception {
        doTestFont("Droid Serif");
        doTestFont("Droid Serif", Font.ITALIC);
    }

    @Test
    public void testFiraCode() throws Exception {
        doTestFont("Fira Code");
    }

    @Test
    public void testFiraCodeLight() throws Exception {
        doTestFont("Fira Code Light");
    }

    @Test
    public void testFiraCodeMedium() throws Exception {
        doTestFont("Fira Code Medium");
    }

    @Test
    public void testInconsolata() throws Exception {
        doTestFont("Inconsolata");
    }

    @Test
    public void testRobotoLight() throws Exception {
        doTestFont("Roboto Light");
    }
    @Test
    public void testRobotoThin() throws Exception {
        doTestFont("Roboto Thin");
    }

    @Test
    public void testSourceCodePro() throws Exception {
        doTestFont("Source Code Pro");
    }

    @Test
    public void testJetBrainsMono() throws Exception {
        doTestFont("JetBrains Mono");
    }

    @Test
    public void testJetBrainsMonoBold() throws Exception {
        doTestFont("JetBrains Mono", Font.BOLD);
    }
}
