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

package util;

import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.List;
import java.util.function.Function;

public class TestUtils {

    public static final float TITLE_BAR_HEIGHT = 100;
    public static final Color TITLE_BAR_COLOR = Color.BLUE;

    public static final int DEFAULT_LOCATION_X = 100;
    public static final int DEFAULT_LOCATION_Y = 100;
    private static final int DEFAULT_WIDTH = 1200;
    private static final int DEFAULT_HEIGHT = 600;


    private static final List<Function<WindowDecorations.CustomTitleBar, Window>> windowCreationFunctions = List.of(
            TestUtils::createJFrameWithCustomTitleBar,
            TestUtils::createFrameWithCustomTitleBar,
            TestUtils::createJDialogWithCustomTitleBar,
            TestUtils::createDialogWithCustomTitleBar
    );

    public static boolean checkTitleBarHeight(WindowDecorations.CustomTitleBar titleBar, float expected) {
        if (titleBar.getHeight() != expected) {
            System.out.printf(String.format("Wrong title bar height. Actual = %f, expected = %d\n", titleBar.getHeight(), expected));
            return false;
        }
        return true;
    }

    public static boolean checkFrameInsets(Window window) {
        Insets insets = window.getInsets();
        if (insets.top != 0) {
            System.out.println("Frame top inset must be zero, but got " + insets.top);
            return false;
        }
        return true;
    }

    public static List<Function<WindowDecorations.CustomTitleBar, Window>> getWindowCreationFunctions() {
        return windowCreationFunctions;
    }

    public static Frame createFrameWithCustomTitleBar(WindowDecorations.CustomTitleBar titleBar) {
        Frame frame = new Frame(){
            @Override
            public void paint(Graphics g) {
                Rectangle r = g.getClipBounds();
                g.setColor(TITLE_BAR_COLOR);
                g.fillRect(r.x, r.y, r.width, (int) TITLE_BAR_HEIGHT);
            }
        };
        frame.setName("Frame");

        frame.setTitle("Frame");
        frame.setBounds(calculateWindowBounds(frame));

        JBR.getWindowDecorations().setCustomTitleBar(frame, titleBar);

        return frame;
    }

    public static JFrame createJFrameWithCustomTitleBar(WindowDecorations.CustomTitleBar titleBar) {
        JFrame frame = new JFrame();
        frame.setContentPane(new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Rectangle r = g.getClipBounds();
                g.setColor(Color.BLUE);
                g.fillRect(r.x, r.y, r.width, 100);
            }
        });
        frame.setName("JFrame");

        frame.setTitle("JFrame");
        frame.setBounds(calculateWindowBounds(frame));

        JBR.getWindowDecorations().setCustomTitleBar(frame, titleBar);

        return frame;
    }

    public static Dialog createDialogWithCustomTitleBar(WindowDecorations.CustomTitleBar titleBar) {
        Dialog dialog = new Dialog((Frame) null){
            @Override
            public void paint(Graphics g) {
                Rectangle r = g.getClipBounds();
                g.setColor(TITLE_BAR_COLOR);
                g.fillRect(r.x, r.y, r.width, (int) TITLE_BAR_HEIGHT);
            }
        };
        dialog.setName("Dialog");

        dialog.setTitle("Dialog");
        dialog.setBounds(calculateWindowBounds(dialog));

        JBR.getWindowDecorations().setCustomTitleBar(dialog, titleBar);

        return dialog;
    }

    public static JDialog createJDialogWithCustomTitleBar(WindowDecorations.CustomTitleBar titleBar) {
        JDialog dialog = new JDialog();
        dialog.setContentPane(new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Rectangle r = g.getClipBounds();
                g.setColor(TITLE_BAR_COLOR);
                g.fillRect(r.x, r.y, r.width, (int) TITLE_BAR_HEIGHT);
            }
        });
        dialog.setName("JDialog");

        dialog.setTitle("JDialog");
        dialog.setBounds(calculateWindowBounds(dialog));

        JBR.getWindowDecorations().setCustomTitleBar(dialog, titleBar);

        return dialog;
    }

    private static Rectangle calculateWindowBounds(Window window) {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Insets scnMax = Toolkit.getDefaultToolkit().
                getScreenInsets(window.getGraphicsConfiguration());
        int maxHeight = screenSize.height - scnMax.top - scnMax.bottom;
        int maxWidth = screenSize.width - scnMax.left - scnMax.right;

        return new Rectangle(scnMax.left + 2, scnMax.top + 2, (int) (maxWidth * 0.8), (int) (maxHeight * 0.8));
    }

}
