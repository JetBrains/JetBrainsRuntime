/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
            screenShoot = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_ARGB);
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
}
