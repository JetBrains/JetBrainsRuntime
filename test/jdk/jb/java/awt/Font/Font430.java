/*
 * Copyright 2000-2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import javax.imageio.ImageIO;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/*
 * Description: The class is used by the test <code>font430.sh</code>.
 */
public class Font430 extends JFrame implements
        WindowListener {

    private static boolean CALL_GET_FONT_METRICS = false;
    private static String FILE_NAME;
    static Font430 frame = new Font430("Font430");
    private static JEditorPane editorPane;

    private Font430(String name) {
        super(name);
    }

    public static void main(String[] args) {

        if (args.length > 0)
            FILE_NAME = args[0];
        if (args.length > 1)
            CALL_GET_FONT_METRICS = Boolean.valueOf(args[1]);

        SwingUtilities.invokeLater(Font430::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        frame.setSize(200, 100);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);

        frame.addComponentsToPane();

        frame.pack();
        frame.setVisible(true);
    }

    private void addComponentsToPane() {
        editorPane = new JEditorPane("text/html",
                "<html><head><style>body {font-family:'Segoe UI'; font-size:12pt;}</style></head><body>\u4e2d</body></html>");
        editorPane.setCaretColor(getBackground());

        if (CALL_GET_FONT_METRICS) {
            editorPane.getFontMetrics(new Font("Segoe UI", Font.PLAIN, 12));
        }

        editorPane.setLocation(0, 0);
        editorPane.setSize(200, 100);

        frame.add(editorPane);

        addWindowListener(this);
    }

    @Override
    public void windowOpened(WindowEvent e) {
        BufferedImage screenShoot;

        Rectangle rect = editorPane.getBounds();
        try {
            threadSleep(1000);
            screenShoot = new BufferedImage(rect.width - 1, rect.height, BufferedImage.TYPE_INT_ARGB);
            editorPane.paint(screenShoot.getGraphics());
            ImageIO.write(screenShoot, "png", new File(FILE_NAME));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        frame.setVisible(false);
        frame.dispose();
    }

    @Override
    public void windowClosing(WindowEvent e) {
    }

    @Override
    public void windowClosed(WindowEvent e) {
    }

    @Override
    public void windowIconified(WindowEvent e) {
    }

    @Override
    public void windowDeiconified(WindowEvent e) {
    }

    @Override
    public void windowActivated(WindowEvent e) {
    }

    @Override
    public void windowDeactivated(WindowEvent e) {
    }

    static private void threadSleep(long ms) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
}
