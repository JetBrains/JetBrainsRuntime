/*
 * Copyright 2026 JetBrains s.r.o.
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
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Where a draw's time goes, layer by layer (x64hw 2026-11-03): GraphiteFloor graphite|java2d|compare [draws] [frames] [runs]
 * <ul>
 * <li>graphite: SKEnvironment.benchGraphite — Graphite alone on the backend's context, no Java2D and no
 *     backend in front: ns per draw for record, snap, insert and submit, per kind (rect, rects alternating
 *     colours, one-quad mesh, 32x32 image);</li>
 * <li>java2d: the same kinds through Java2D on a VolatileImage of the running pipeline (Skia on its API, JBR
 *     Vulkan, Direct3D, the loops), plus a token of LCD text: ns per operation for the whole path (the Java
 *     side, the queue, the pipeline's native work, the GPU), each frame ended by a one-pixel read-back into a BufferedImage that
 *     waits for the GPU.</li>
 * </ul>
 * One line per kind: "kind<TAB>ns..." (graphite: four phases; java2d: one total).
 */
public class GraphiteFloor {
    static final String[] KINDS = {"rect", "rect-2colours", "mesh-quad", "image-32", "image-16-textures", "rect-mesh-image", "two-surfaces"};

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int draws = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(1024, 1024, Transparency.OPAQUE);
        vi.validate(gc);
        Graphics2D g0 = vi.createGraphics();   // creates the pipeline's context
        g0.fillRect(0, 0, 10, 10);
        g0.dispose();
        vi.getSnapshot();
        System.out.println("#config " + gc.getClass().getSimpleName());
        if (mode.equals("compare")) {
            compare(draws, frames, args.length > 3 ? Integer.parseInt(args[3]) : 5);
            return;
        }
        if (mode.equals("graphite")) {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("benchGraphite", int.class, int.class, int.class);
            for (int k = 0; k < KINDS.length; k++) {
                double[] r = (double[]) m.invoke(null, k, draws, frames);
                if (r == null) {
                    throw new RuntimeException("no Graphite context");
                }
                System.out.printf("%s\t%.0f\t%.0f\t%.0f\t%.0f%n", KINDS[k], r[0], r[1], r[2], r[3]);
            }
            return;
        }
        java2d(vi, draws, frames);
    }

    // compare: Graphite against Ganesh on the same Vulkan device (SKEnvironment.benchGanesh, run with
    // sun.java2d.skia.graphiteApi=vulkan), `runs` runs, each timing every kind once on each of graphite, ganesh
    // and ganesh with reduceOpsTaskSplitting, in a random order. Per kind and backend: the phases' medians over
    // the runs and the total's median [min-max], ns per draw. Graphite's phases: record, snap, insert, submit;
    // Ganesh's: record, flush (snap and insert together), submit.
    static void compare(int draws, int frames, int runs) throws Exception {
        Class<?> env = Class.forName("sun.java2d.skia.SKEnvironment");
        Method graphite = env.getMethod("benchGraphite", int.class, int.class, int.class);
        Method ganesh = env.getMethod("benchGanesh", int.class, int.class, int.class, boolean.class);
        String[] backends = {"graphite", "ganesh", "ganesh-reduce"};
        java.util.Map<String, java.util.List<double[]>> results = new java.util.TreeMap<>();
        java.util.Random random = new java.util.Random(4711);
        for (int run = 0; run < runs; run++) {
            java.util.List<String> jobs = new java.util.ArrayList<>();
            for (int k = 0; k < KINDS.length; k++) {
                for (String b : backends) {
                    jobs.add(k + ":" + b);
                }
            }
            java.util.Collections.shuffle(jobs, random);
            for (String job : jobs) {
                int k = Integer.parseInt(job.substring(0, job.indexOf(':')));
                String b = job.substring(job.indexOf(':') + 1);
                double[] r = b.equals("graphite") ? (double[]) graphite.invoke(null, k, draws, frames)
                        : (double[]) ganesh.invoke(null, k, draws, frames, b.equals("ganesh-reduce"));
                if (r == null) {
                    throw new RuntimeException("no " + b + " context (Ganesh needs sun.java2d.skia.graphiteApi=vulkan)");
                }
                results.computeIfAbsent(String.format("%d %s\t%s", k, KINDS[k], b), x -> new java.util.ArrayList<>()).add(r);
            }
        }
        System.out.println("#draws " + draws + ", frames " + frames + ", runs " + runs
                + "; ns per draw: phases (graphite record/snap/insert/submit, ganesh record/flush/submit), total median [min-max]");
        for (var e : results.entrySet()) {
            java.util.List<double[]> rs = e.getValue();
            int phases = rs.get(0).length;
            StringBuilder sb = new StringBuilder(e.getKey().substring(e.getKey().indexOf(' ') + 1));
            for (int p = 0; p < phases; p++) {
                final int pp = p;
                sb.append(String.format("\t%.0f", median(rs.stream().mapToDouble(r -> r[pp]).toArray())));
            }
            if (phases == 3) {
                sb.append("\t");
            }
            double[] totals = rs.stream().mapToDouble(r -> Arrays.stream(r).sum()).toArray();
            Arrays.sort(totals);
            sb.append(String.format("\t%.0f [%.0f-%.0f]", median(totals), totals[0], totals[totals.length - 1]));
            System.out.println(sb);
        }
    }

    static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    static void java2d(VolatileImage vi, int draws, int frames) {
        BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = icon.createGraphics();
        ig.setColor(Color.BLUE);
        ig.fillRect(0, 0, 32, 32);
        ig.dispose();
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        BufferedImage one = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        String[] kinds = {"rect", "rect-2colours", "image-32", "text-token-lcd"};
        for (String kind : kinds) {
            double[] ns = new double[frames];
            for (int f = 0; f < 2 * frames; f++) {
                Graphics2D g = vi.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                g.setFont(font);
                long t0 = System.nanoTime();
                for (int i = 0; i < draws; i++) {
                    int x = (i * 37) % 1000, y = (i * 53) % 1000;
                    switch (kind) {
                        case "rect" -> { g.setColor(Color.RED); g.fillRect(x, y, 16, 12); }
                        case "rect-2colours" -> { g.setColor(i % 2 == 0 ? Color.RED : Color.GREEN); g.fillRect(x, y, 16, 12); }
                        case "image-32" -> g.drawImage(icon, x, y, null);
                        default -> { g.setColor(i % 2 == 0 ? Color.BLUE : Color.BLACK); g.drawString("token", x, y + 13); }
                    }
                }
                g.dispose();
                one.createGraphics().drawImage(vi, 0, 0, 1, 1, 0, 0, 1, 1, null);   // a one-pixel read-back: waits for the frame on the GPU
                long t1 = System.nanoTime();
                if (f >= frames) {
                    ns[f - frames] = (t1 - t0) / (double) draws;
                }
            }
            Arrays.sort(ns);
            System.out.printf("%s\t%.0f%n", kind, ns[frames / 2]);
        }
    }
}
