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
package test.jb.testhelpers.TitleBar;

import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Function;

public class TestUtils {

    public static final float TITLE_BAR_HEIGHT = 100;
    public static final Color TITLE_BAR_COLOR = Color.BLUE;

    static final int DEFAULT_WIDTH = 800;
    static final int DEFAULT_HEIGHT = 600;


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
        frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        frame.setName("Frame");

        frame.setTitle("Frame");
        frame.setBounds(calculateWindowBounds(frame));

        JBR.getWindowDecorations().setCustomTitleBar(frame, titleBar);

        return frame;
    }

    public static JFrame createJFrameWithCustomTitleBar(WindowDecorations.CustomTitleBar titleBar) {
        JFrame frame = new JFrame();
        frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
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
        Frame frame = new Frame();
        frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        Dialog dialog = new Dialog(frame) {
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
        dialog.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
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

    public static boolean isBasicTestCase() {
        return getUIScale() == 1.0;
    }

    public static boolean checkScreenSizeConditions(Window window) {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Insets scnMax = Toolkit.getDefaultToolkit().
                getScreenInsets(window.getGraphicsConfiguration());
        System.out.println("Screen size: " + screenSize);
        System.out.println("Screen insets: " + scnMax);
        final int width = screenSize.width - scnMax.left - scnMax.right;
        final int height = screenSize.height - scnMax.top - scnMax.bottom;
        System.out.println("Max width = " + width + " max height = " + height);

        double uiScale = getUIScale();

        final int effectiveWidth = (int) (width / uiScale);
        final int effectiveHeight = (int) (height / uiScale);

        if (effectiveWidth < DEFAULT_WIDTH || effectiveHeight < DEFAULT_HEIGHT) {
            return false;
        }
        return true;
    }

    private static Rectangle calculateWindowBounds(Window window) {
        double uiScale = getUIScale();
        System.out.println("UI Scale: " + uiScale);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        System.out.println("Screen size: " + screenSize);

        Insets scnMax = Toolkit.getDefaultToolkit().
                getScreenInsets(window.getGraphicsConfiguration());
        int maxHeight = (int) ((screenSize.height - scnMax.top - scnMax.bottom) / uiScale);
        int maxWidth = (int) ((screenSize.width - scnMax.left - scnMax.right) / uiScale);

        Rectangle bounds = new Rectangle(scnMax.left + 2, scnMax.top + 2, (int) (maxWidth * 0.95), (int) (maxHeight * 0.95));
        System.out.println("Window bounds: " + bounds);

        return bounds;
    }

    public static double getUIScale() {
        boolean scaleEnabled = "true".equals(System.getProperty("sun.java2d.uiScale.enabled"));
        double uiScale = 1.0;
        if (scaleEnabled) {
            uiScale = Float.parseFloat(System.getProperty("sun.java2d.uiScale"));
        }
        return uiScale;
    }

}
