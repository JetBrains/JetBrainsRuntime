import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Contact sheet (software | metal | skia, 3x) for the RenderMatrix cells whose names contain any of the given substrings. */
public class MatrixCells {
    static final int CELL = 64, COLS = 16, S = 3;
    public static void main(String[] a) throws Exception {
        BufferedImage sw = ImageIO.read(new File("matrix-software.png")), mt = ImageIO.read(new File("matrix-metal.png")), sk = ImageIO.read(new File("matrix-skia.png"));
        List<String> names = Files.readAllLines(Path.of("matrix-cells.txt"));
        List<Integer> sel = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) for (int k = 1; k < a.length; k++) if (names.get(i).equals(a[k]) || names.get(i).contains(a[k])) { sel.add(i); break; }
        int rowH = CELL * S + 10;
        BufferedImage out = new BufferedImage(3 * CELL * S + 8 + 360, Math.max(1, sel.size()) * rowH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setFont(new Font("Dialog", Font.PLAIN, 11));
        for (int k = 0; k < sel.size(); k++) {
            int i = sel.get(k), x0 = (i % COLS) * CELL, y0 = (i / COLS) * CELL, yy = k * rowH;
            g.drawImage(sw.getSubimage(x0, y0, CELL, CELL), 0, yy, CELL * S, CELL * S, null);
            g.drawImage(mt.getSubimage(x0, y0, CELL, CELL), CELL * S + 4, yy, CELL * S, CELL * S, null);
            g.drawImage(sk.getSubimage(x0, y0, CELL, CELL), 2 * CELL * S + 8, yy, CELL * S, CELL * S, null);
            g.setColor(Color.BLACK); g.drawString(names.get(i), 3 * CELL * S + 16, yy + 14);
        }
        g.dispose();
        ImageIO.write(out, "png", new File(a[0]));
        System.out.println("wrote " + a[0] + " (" + sel.size() + " cells)");
    }
}
