/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

/* @test
 * @bug 6445283
 * @library /java/awt/regtesthelpers
 * @build PassFailJFrame
 * @summary Tests if ProgressMonitorInputStream reports progress accurately
 * @run main/manual ProgressTest
 */

import java.io.InputStream;
import java.awt.EventQueue;
import javax.swing.ProgressMonitorInputStream;

public class ProgressTest {
    static volatile long total = 0;
    private static final String instructionsText =
            "A ProgressMonitor will be shown.\n" +
            " If it shows blank progressbar after 2048MB bytes read,\n"+
            " press Fail else press Pass";

    public static void main(String[] args) throws Exception {

        PassFailJFrame pfjFrame = new PassFailJFrame("JScrollPane "
                + "Test Instructions", instructionsText, 5);
        PassFailJFrame.positionTestWindow(null, PassFailJFrame.Position.VERTICAL);

        final long SIZE = (long) (Integer.MAX_VALUE * 1.5);

        InputStream fileIn = new InputStream() {
            long read = 0;

            @Override
            public int available() {
                return (int) Math.min(SIZE - read, Integer.MAX_VALUE);
            }

            @Override
            public int read() {
                return (SIZE - read++ > 0) ? 1 : -1;
            }
        };

        ProgressMonitorInputStream pmis =
            new ProgressMonitorInputStream(null, "Reading File", fileIn);

        Thread thread = new Thread() {
            public void run() {
                byte[] buffer = new byte[512];
                int nb = 0;
                while (true) {
                    try {
                        nb = pmis.read(buffer);
                    } catch (Exception e){}
                    if (nb == 0) break;
                    total += nb;
                    System.out.println("total " + total);
                    if ((total % (1024*1024)) == 0) {
                        try {
                            EventQueue.invokeAndWait(() -> {
                                pmis.getProgressMonitor().setNote(total/(1024*1024)+" MB Read");
                            });
                        } catch (Exception e) {}
                    }
                }
            }
        };
        thread.start();

        pfjFrame.awaitAndCheck();
    }
}
