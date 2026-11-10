import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.List;

/** For each (base, char): does the route draw the char in the platform's fallback font (fallback.tsv's CTLine column)? */
public class FallbackRoutes {
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        Font bundled = Font.createFont(Font.TRUETYPE_FONT, new File(args[1])).deriveFont(13f);
        int same = 0, total = 0;
        StringBuilder misses = new StringBuilder();
        for (String l : lines.subList(2, lines.size())) {
            String[] c = l.split("\t");
            if (c.length < 6 || c[3].isEmpty()) continue;
            String base = c[0], ch = c[1], platform = c[2];
            for (boolean createFont : new boolean[] {false, true}) {
                if (createFont && !base.equals("JetBrainsMono-Regular")) continue;
                Font font = createFont ? bundled : new Font(base, Font.PLAIN, 13);
                Font expected = new Font(platform, Font.PLAIN, 13);
                if (platform.equals(base)) expected = font;
                long a = hash(font, ch), b = hash(expected, ch);
                total++;
                if (a == b) same++; else misses.append(String.format("  %s%s %s: not %s%n", base, createFont ? " (createFont)" : "", ch, platform));
            }
        }
        System.out.println(same + " of " + total + " drawn in the platform's fallback font");
        System.out.print(misses);
    }
    static long hash(Font f, String s) {
        BufferedImage img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 60, 40); g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(f); g.drawString(s, 10, 28); g.dispose();
        long h = 17; for (int y = 0; y < 40; y++) for (int x = 0; x < 60; x++) h = 31 * h + img.getRGB(x, y); return h;
    }
}
