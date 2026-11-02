import javax.imageio.ImageIO; import java.awt.*; import java.awt.image.BufferedImage; import java.io.File; import java.nio.file.*; import java.util.*; import java.util.List;
public class CellCompare {
  public static void main(String[] a) throws Exception {
    String[] files = {"matrix-software.png", "matrix-skia-nomsaa.png", "matrix-skia-msaa4.png"}; String[] labels = {"software (Marlin)", "Skia, no MSAA", "Skia, MSAA 4x"};
    List<String> names = Files.readAllLines(Path.of("matrix-cells.txt")); int S = 6, CELL = 64, COLS = 16;
    String[] cells = Arrays.copyOfRange(a, 1, a.length);
    BufferedImage out = new BufferedImage(3 * CELL * S + 2 * 8, cells.length * (CELL * S + 24), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, out.getWidth(), out.getHeight());
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR); g.setFont(new Font("Dialog", Font.PLAIN, 12));
    for (int k = 0; k < cells.length; k++) {
      int i = names.indexOf(cells[k]); int x0 = (i % COLS) * CELL, y0 = (i / COLS) * CELL, yy = k * (CELL * S + 24);
      for (int f = 0; f < 3; f++) {
        BufferedImage img = ImageIO.read(new File(files[f])); BufferedImage sub = img.getSubimage(x0, y0, CELL, CELL);
        TreeSet<Integer> levels = new TreeSet<>(); for (int y = 0; y < CELL; y++) for (int x = 0; x < CELL; x++) levels.add(sub.getRGB(x, y) & 0xff);
        g.drawImage(sub, f * (CELL * S + 8), yy + 20, CELL * S, CELL * S, null);
        g.setColor(Color.BLACK); g.drawString(labels[f] + " - distinct blue-channel values: " + levels.size(), f * (CELL * S + 8) + 2, yy + 14);
      }
    }
    g.dispose(); ImageIO.write(out, "png", new File(a[0]));
  }
}
