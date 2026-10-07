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
package org.openjdk.bench.javax.swing;

import java.awt.Dialog;
import java.awt.Point;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JBR-10541: measures SwingUtilities.convertPoint.
 * <p>
 * Same-window conversions should take the fast path (plain offset arithmetic),
 * while cross-window conversions still go through screen coordinates and serve
 * as a baseline that shows what the same-window case used to cost.
 * <p>
 * The expected gain is platform-dependent: on Windows getLocationOnScreen is a
 * native call, so the fast path should win big; on macOS the peer answers from
 * cached bounds, so the same-window case is expected to be roughly on par with
 * the old path.
 * <p>
 * Requires a display (headful).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class SwingUtilitiesConvertPoint {

    /** Nesting depth of each of the two branches below the content pane. */
    @Param({"3", "10", "30"})
    public int depth;

    private JFrame frame;
    private JDialog dialog;

    private JComponent leafA;
    private JComponent leafB;
    private JComponent topA;
    private JComponent dialogLeaf;

    private Point point;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("convertPoint benchmark");
            frame.setLocation(100, 100);
            frame.setSize(600, 400);
            JPanel content = new JPanel(null);
            frame.setContentPane(content);

            topA = new JPanel(null);
            topA.setBounds(10, 10, 500, 300);
            content.add(topA);
            leafA = nest(topA, depth);

            JComponent topB = new JPanel(null);
            topB.setBounds(20, 30, 500, 300);
            content.add(topB);
            leafB = nest(topB, depth);

            frame.setVisible(true);

            dialog = new JDialog(frame, "dialog", Dialog.ModalityType.MODELESS);
            dialog.setLocation(750, 150);
            dialog.setSize(200, 150);
            JPanel dialogContent = new JPanel(null);
            dialog.setContentPane(dialogContent);
            // Same depth as the frame's branches, so acrossWindows measures the
            // slow path on an equally deep hierarchy rather than a shallow one.
            dialogLeaf = nest(dialogContent, depth);
            dialog.setVisible(true);
        });
        point = new Point(3, 4);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (dialog != null) dialog.dispose();
            if (frame != null) frame.dispose();
        });
    }

    private static JComponent nest(JComponent parent, int levels) {
        JComponent current = parent;
        for (int i = 0; i < levels; i++) {
            JPanel child = new JPanel(null);
            child.setBounds(2, 3, 400, 250);
            current.add(child);
            current = child;
        }
        return current;
    }

    /** Same window, two deep sibling branches (common ancestor is the content pane). */
    @Benchmark
    public Point sameWindowBetweenBranches() {
        return SwingUtilities.convertPoint(leafA, point, leafB);
    }

    /** Same window, destination is an ancestor of the source. */
    @Benchmark
    public Point toAncestor() {
        return SwingUtilities.convertPoint(leafA, point, topA);
    }

    /** Same window, source is an ancestor of the destination. */
    @Benchmark
    public Point fromAncestor() {
        return SwingUtilities.convertPoint(topA, point, leafA);
    }

    /** Null destination: converts to the window ancestor's coordinates. */
    @Benchmark
    public Point toWindow() {
        return SwingUtilities.convertPoint(leafA, point, null);
    }

    /** Different windows: still uses screen coordinates (baseline). */
    @Benchmark
    public Point acrossWindows() {
        return SwingUtilities.convertPoint(leafA, point, dialogLeaf);
    }
}
