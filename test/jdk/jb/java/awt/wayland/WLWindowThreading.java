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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.awt.Frame;
import java.awt.Label;
import java.util.Random;

/*
 * @test
 * @summary Verifies that various AWT Window API calls can be made
 *          from different threads without any issues
 * @run main/othervm WLWindowThreading
 */
public class WLWindowThreading implements Runnable {
    private static final int COUNT = 456;
    private static final int PAUSE_MS = 2;

    private final Frame frame;

    public WLWindowThreading() {
        frame = new Frame();
        frame.setTitle("Window Threading Test");
        frame.add(new Label("Hello World"));
        frame.setSize(300, 200);
        frame.pack();

    }

    public static void main(String[] args) throws Exception {
        WLWindowThreading frame = new WLWindowThreading();

        Thread thread1 = new Thread(frame);
        Thread thread2 = new Thread(frame);
        thread1.start();
        thread2.start();


        thread1.join();
        thread2.join();
    }

    @Override
    public void run() {
        Random r = new Random();
        for (int i = 0; i < COUNT; i++) {
            System.gc(); // to encourage peer/surface-data disposal
            try {
                Thread.sleep(PAUSE_MS);
            } catch (InterruptedException ignored) {}

            int n = r.nextInt();
            if (n % 4 == 0) {
                frame.setVisible(false);
                System.out.println("hid");
            } if (n % 4 == 1) {
                frame.setVisible(true);
                System.out.println("made visible");
            } if (n % 4 == 2) {
                var g = frame.getGraphics();
                if (g != null) {
                    try {
                        g.drawRect(0, 0, 5, 8 + n);
                    } finally {
                        g.dispose();
                    }
                }
                System.out.println("drawn");
            } else {
                int d = r.nextInt() % 200 + 300;
                frame.setBounds(0, 0, d, d + n % 10);
                System.out.println("changed bounds to " + frame.getBounds());
            }
        }
    }
}
