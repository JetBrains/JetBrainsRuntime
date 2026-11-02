import java.awt.*; import java.awt.image.BufferedImage;
/** Does the JDK on macOS synthesize italic or bold for a font that lacks the face? Renders a face-less font plain, italic and bold and counts differing pixels. */
public class SynthProbe { public static void main(String[] a) {
  for (String fam : new String[]{"Monaco", "Chalkduster", "Helvetica Neue"}) {
    BufferedImage[] im = new BufferedImage[3]; int[] styles = {Font.PLAIN, Font.ITALIC, Font.BOLD};
    for (int i = 0; i < 3; i++) { im[i] = new BufferedImage(400, 60, BufferedImage.TYPE_INT_RGB); Graphics2D g = im[i].createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,400,60);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g.setColor(Color.BLACK); g.setFont(new Font(fam, styles[i], 36)); g.drawString("Hamburg l", 4, 44); g.dispose(); }
    int dI = 0, dB = 0, inkPlain = 0, inkBold = 0; for (int y = 0; y < 60; y++) for (int x = 0; x < 400; x++) { int p = im[0].getRGB(x,y) & 0xffffff; if (p != (im[1].getRGB(x,y) & 0xffffff)) dI++; if (p != (im[2].getRGB(x,y) & 0xffffff)) dB++; if (p != 0xffffff) inkPlain++; if ((im[2].getRGB(x,y) & 0xffffff) != 0xffffff) inkBold++; }
    Font f = new Font(fam, Font.ITALIC, 36); System.out.printf("%-16s italic differs in %5d px, bold differs in %5d px (ink plain %d, bold %d); family resolved: %s%n", fam, dI, dB, inkPlain, inkBold, f.getFontName());
  } } }
