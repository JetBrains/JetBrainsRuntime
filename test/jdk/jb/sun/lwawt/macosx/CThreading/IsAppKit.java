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

/*
 * @test
 * @summary regression test on JRE-624 CThreading.isAppKit() fails to detect main app thread if it was renamed
 * @compile -XDignore.symbol.file IsAppKit.java
 * @run main/othervm IsAppKit 
 */

import javax.swing.*;
import java.util.concurrent.*;
import sun.lwawt.macosx.*;

/*
 * Description: The test checks detection of the main application thread
 *
 */

public class IsAppKit {
    public static void main(String[] args) throws Exception {

        final CountDownLatch counter = new CountDownLatch(1);

        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.pack();
            frame.setVisible(true);
            counter.countDown();
        });

        counter.await();

        CThreading.privilegedExecuteOnAppKit(() -> {
            String name = Thread.currentThread().getName();
            if (!"AWT-AppKit".equals(name)) {
                throw new RuntimeException("Unexpected thread name: " + name);
            }

            Thread.currentThread().setName("Some other thread name");
            return null;
        });


        String name = CThreading.privilegedExecuteOnAppKit(() -> {
            return Thread.currentThread().getName();
        });

        if (!"AWT-AppKit".equals(name)) {
            throw new RuntimeException("Unexpected thread name: " + name);
        }
    }
}
