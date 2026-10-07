/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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
 */

/*
 * @test
 * @key headful
 * @summary JBR-10541 Verifies that SwingUtilities.convertPoint gives the same
 *          results as the screen-based conversion, including the same-window
 *          fast path, the ancestor shortcut and conversions across different
 *          windows.
 * @run main ConvertPointTest
 */

import java.awt.Component;
import java.awt.Dialog;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class ConvertPointTest {

    private static JFrame frame;
    private static JDialog dialog;
    private static JPanel left;
    private static JPanel leftInner;
    private static JButton button;
    private static JPanel right;
    private static JLabel label;
    private static JButton dialogButton;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(ConvertPointTest::createUI);
            SwingUtilities.invokeAndWait(() -> {
                testSameWindowAllPairs();
                testAncestorAndDescendant();
                testSameComponent();
                testNullSourceOrDestination();
                testAcrossWindows();
                testDetachedHierarchy();
            });
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog != null) dialog.dispose();
                if (frame != null) frame.dispose();
            });
        }
    }

    private static void createUI() {
        frame = new JFrame("ConvertPointTest");
        frame.setLocation(100, 100);
        frame.setSize(400, 300);

        JPanel content = new JPanel(null);
        frame.setContentPane(content);

        left = new JPanel(null);
        left.setBounds(10, 20, 150, 200);
        content.add(left);

        leftInner = new JPanel(null);
        leftInner.setBounds(30, 40, 100, 100);
        left.add(leftInner);

        button = new JButton("b");
        button.setBounds(5, 6, 40, 20);
        leftInner.add(button);

        right = new JPanel(null);
        right.setBounds(200, 50, 150, 150);
        content.add(right);

        label = new JLabel("l");
        label.setBounds(7, 8, 30, 15);
        right.add(label);

        frame.setVisible(true);

        dialog = new JDialog(frame, "dialog", Dialog.ModalityType.MODELESS);
        dialog.setLocation(650, 230);
        dialog.setSize(200, 150);
        JPanel dContent = new JPanel(null);
        dialog.setContentPane(dContent);
        dialogButton = new JButton("d");
        dialogButton.setBounds(11, 13, 50, 20);
        dContent.add(dialogButton);
        dialog.setVisible(true);
    }

    /** Reference implementation: the old, screen-based algorithm. */
    private static Point reference(Component src, Point p, Component dst) {
        Point r = new Point(p);
        SwingUtilities.convertPointToScreen(r, src);
        SwingUtilities.convertPointFromScreen(r, dst);
        return r;
    }

    private static void check(Component src, Point p, Component dst, String what) {
        Point expected = reference(src, p, dst);
        Point actual = SwingUtilities.convertPoint(src, p, dst);
        if (!expected.equals(actual)) {
            throw new RuntimeException(what + ": expected " + expected
                    + " but got " + actual + " for point " + p);
        }
    }

    private static void testSameWindowAllPairs() {
        List<Component> comps = new ArrayList<>();
        comps.add(frame.getRootPane());
        comps.add(frame.getContentPane());
        comps.add(left);
        comps.add(leftInner);
        comps.add(button);
        comps.add(right);
        comps.add(label);

        Point[] points = {
            new Point(0, 0), new Point(3, 4), new Point(-5, 17), new Point(123, -45)
        };
        for (Component s : comps) {
            for (Component d : comps) {
                for (Point p : points) {
                    check(s, p, d, "same window " + s.getClass().getSimpleName()
                            + " -> " + d.getClass().getSimpleName());
                }
            }
        }
    }

    private static void testAncestorAndDescendant() {
        Point p = new Point(2, 3);
        // destination is an ancestor of the source
        check(button, p, leftInner, "to parent");
        check(button, p, left, "to grandparent");
        check(button, p, frame.getContentPane(), "to content pane");
        // source is an ancestor of the destination
        check(left, p, button, "from grandparent");
        // explicit values, independent of screen conversion
        Point toLeftInner = SwingUtilities.convertPoint(button, p, leftInner);
        assertEquals(new Point(2 + 5, 3 + 6), toLeftInner, "button->leftInner");
        Point toLeft = SwingUtilities.convertPoint(button, p, left);
        assertEquals(new Point(2 + 5 + 30, 3 + 6 + 40), toLeft, "button->left");
        Point back = SwingUtilities.convertPoint(left, toLeft, button);
        assertEquals(p, back, "left->button round trip");
    }

    private static void testSameComponent() {
        Point p = new Point(9, 11);
        Point r = SwingUtilities.convertPoint(button, p, button);
        assertEquals(p, r, "same component");
        r = SwingUtilities.convertPoint(frame, p, frame);
        assertEquals(p, r, "same window");
    }

    private static void testNullSourceOrDestination() {
        Point p = new Point(15, 25);
        // null destination -> window ancestor of source
        Point toWindow = SwingUtilities.convertPoint(button, p, null);
        assertEquals(SwingUtilities.convertPoint(button, p, frame), toWindow,
                "null destination");
        // null source -> window ancestor of destination
        Point fromWindow = SwingUtilities.convertPoint(null, p, button);
        assertEquals(SwingUtilities.convertPoint(frame, p, button), fromWindow,
                "null source");
        // both null -> unchanged
        assertEquals(p, SwingUtilities.convertPoint(null, p, null), "both null");
    }

    private static void testAcrossWindows() {
        Point[] points = { new Point(0, 0), new Point(5, 5), new Point(-20, 40) };
        Component[] a = { frame, button, label, frame.getContentPane() };
        Component[] b = { dialog, dialogButton, dialog.getContentPane() };
        for (Component s : a) {
            for (Component d : b) {
                for (Point p : points) {
                    check(s, p, d, "frame -> dialog");
                    check(d, p, s, "dialog -> frame");
                }
            }
        }
    }

    /** Components that are not displayable must still convert by plain offsets. */
    private static void testDetachedHierarchy() {
        JPanel root = new JPanel(null);
        root.setBounds(50, 60, 300, 300);
        JPanel mid = new JPanel(null);
        mid.setBounds(10, 20, 200, 200);
        root.add(mid);
        JButton a = new JButton();
        a.setBounds(3, 4, 20, 20);
        mid.add(a);
        JButton b = new JButton();
        b.setBounds(100, 120, 20, 20);
        root.add(b);

        Point p = new Point(1, 2);
        // a -> root: (1+3+10, 2+4+20)
        assertEquals(new Point(14, 26), SwingUtilities.convertPoint(a, p, root),
                "detached a->root");
        // root -> a: inverse
        assertEquals(new Point(-12, -22), SwingUtilities.convertPoint(root, p, a),
                "detached root->a");
        // a -> b through the common ancestor: (1+3+10-100, 2+4+20-120)
        assertEquals(new Point(-86, -94), SwingUtilities.convertPoint(a, p, b),
                "detached a->b");
    }

    private static void assertEquals(Point expected, Point actual, String what) {
        if (!expected.equals(actual)) {
            throw new RuntimeException(what + ": expected " + expected
                    + " but got " + actual);
        }
    }
}
