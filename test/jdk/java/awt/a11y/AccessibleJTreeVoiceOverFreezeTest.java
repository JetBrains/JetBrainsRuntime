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
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Dimension;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/*
 * @test
 * @summary JBR-10147 Test for freezes when VoiceOver interacts with large trees
 * @author dmitry.drobotov@jetbrains.com
 * @requires (os.family == "mac")
 * @run main/manual AccessibleJTreeVoiceOverFreezeTest
 */

public class AccessibleJTreeVoiceOverFreezeTest extends AccessibleComponentTest {
    private static final int NODE_COUNT = 100_000;
    private static final int LEVELS = 10;
    private static final int FREEZE_TIMEOUT_SECONDS = 10;

    @Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    private void createTree() {
        INSTRUCTIONS = """
                INSTRUCTIONS:
                Check that there is no freeze while navigating a large JTree with VoiceOver enabled.

                Turn VoiceOver on (Cmd + F5).
                Tab to the tree.
                Move the selection using Up/Down arrows multiple times.

                The test will automatically fail if the EDT is unresponsive for 10 seconds.
                A short freeze is acceptable on the first focus of the tree.
                If the UI doesn't freeze, press PASS.
                If you notice a freeze that was not detected automatically, press FAIL.""";

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        DefaultMutableTreeNode current = root;
        for (int level = 1; level <= LEVELS; level++) {
            for (int i = 1; i <= NODE_COUNT / LEVELS; i++) {
                current.add(new DefaultMutableTreeNode("Level" + level + " Node" + i));
            }
            current = (DefaultMutableTreeNode) current.getFirstChild();
        }

        JTree tree = new JTree(root);
        tree.setRootVisible(true);
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setPreferredSize(new Dimension(400, 600));

        JPanel panel = new JPanel();
        panel.add(scrollPane);

        exceptionString = "AccessibleJTreeVoiceOverFreezeTest test failed!";
        super.createUI(panel, "AccessibleJTreeVoiceOverFreezeTest");
    }

    public static void main(String[] args) throws Exception {
        AccessibleJTreeVoiceOverFreezeTest test = new AccessibleJTreeVoiceOverFreezeTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeLater(test::createTree);

        Thread freezeMonitor = new Thread(() -> {
            while (countDownLatch.getCount() > 0) {
                CountDownLatch ping = new CountDownLatch(1);
                SwingUtilities.invokeLater(ping::countDown);
                try {
                    if (!ping.await(FREEZE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && countDownLatch.getCount() > 0) {
                        testResult = false;
                        countDownLatch.countDown();
                        return;
                    }
                    //noinspection BusyWait
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "freeze-monitor");
        freezeMonitor.setDaemon(true);
        freezeMonitor.start();

        //noinspection ResultOfMethodCallIgnored
        countDownLatch.await(15, TimeUnit.MINUTES);

        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }
    }
}
