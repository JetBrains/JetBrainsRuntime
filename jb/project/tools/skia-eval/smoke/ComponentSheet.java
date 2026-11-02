import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Swing components painted headless with the current font scaler: preferred sizes
 * (re-flow check) and pixels. java [-Dsun.java2d.font.scaler=skia] ComponentSheet <label>
 * writes sheet-<label>.png and sheet-<label>.txt (component sizes).
 */
public class ComponentSheet {
    public static void main(String[] args) throws Exception {
        String label = args[0];
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        JPanel root = new JPanel(new GridLayout(0, 1, 4, 4));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        List<JComponent> items = List.of(
            new JLabel("JLabel: The quick brown fox jumps over the lazy dog 0123456789"),
            new JButton("JButton with a longer caption (Ctrl+Shift+A)"),
            new JCheckBox("JCheckBox: enable experimental rendering"),
            new JRadioButton("JRadioButton: platform engine"),
            new JTextField("JTextField: /Users/example/Projects/JetBrainsRuntime/src/java.desktop", 40),
            new JComboBox<>(new String[] {"JComboBox: first entry", "second", "third"}),
            new JSlider(),
            new JProgressBar(0, 100) {{ setValue(42); setStringPainted(true); }},
            new JScrollPane(new JTextArea("JTextArea line one\n    indented line two with code: for (int i = 0; i < n; i++) {}\nline three", 3, 40)),
            new JScrollPane(new JTree()),
            new JScrollPane(new JTable(new Object[][] {{"Alpha", 1, true}, {"Beta", 2, false}}, new Object[] {"Name", "Count", "Flag"})),
            new JTabbedPane() {{ addTab("Editor", new JLabel("tab 1")); addTab("Project", new JLabel("tab 2")); addTab("Settings", new JLabel("tab 3")); }},
            new JPanel() {{ setBorder(new TitledBorder("TitledBorder: Appearance & Behavior")); add(new JLabel("inside")); }},
            new JLabel("<html><b>HTML</b> label with <i>mixed</i> <code>styles</code> and a <a href='#'>link</a></html>")
        );
        for (JComponent c : items) root.add(c);
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setContentPane(root);
        frame.pack();
        StringBuilder sizes = new StringBuilder();
        for (JComponent c : items) sizes.append(c.getClass().getSimpleName()).append(' ').append(c.getPreferredSize().width).append('x').append(c.getPreferredSize().height).append('\n');
        Dimension d = root.getPreferredSize();
        sizes.append("root ").append(d.width).append('x').append(d.height).append('\n');
        for (int scale : new int[] {1, 2}) {
            BufferedImage img = new BufferedImage(d.width * scale, d.height * scale, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(scale, scale);
            root.paint(g);
            g.dispose();
            ImageIO.write(img, "png", new File("sheet-" + label + "-" + scale + "x.png"));
        }
        java.nio.file.Files.writeString(new File("sheet-" + label + ".txt").toPath(), sizes.toString());
        System.out.println("wrote sheet-" + label + " (" + d.width + "x" + d.height + ")");
        frame.dispose();
        System.exit(0);
    }
}
