/*
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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
 * @bug 8249806
 * @summary Tests that JTree's root is not set as accessible parent of top-level tree nodes (direct children of root)
 *          when the JTree has setRootVisible(false).
 * @run main AccessibleJTreeNodeAccessibleParentTest
 */

import java.awt.Robot;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

public class AccessibleJTreeNodeAccessibleParentTest {
    private static JTree jTree;
    private static JFrame jFrame;

    private static void createGUI() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        DefaultMutableTreeNode node = new DefaultMutableTreeNode("node");
        root.add(node);

        jTree = new JTree(root);
        jTree.setRootVisible(false);

        jFrame = new JFrame();
        jFrame.setBounds(100, 100, 300, 300);
        jFrame.getContentPane().add(jTree);
        jFrame.setVisible(true);
    }

    private static void doTest() throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> createGUI());
            Robot robot = new Robot();
            robot.waitForIdle();

            AtomicBoolean accessibleNodeInitialized = new AtomicBoolean(false);

            SwingUtilities.invokeAndWait(() -> {
                jTree.getAccessibleContext().addPropertyChangeListener(evt -> {
                    // When an AccessibleJTreeNode is created for the active descendant change event,
                    // its parent is not set on initialization but calculated on the first access.
                    // This imitates the way assistive tools obtain AccessibleJTreeNode objects.
                    if (AccessibleContext.ACCESSIBLE_ACTIVE_DESCENDANT_PROPERTY.equals(evt.getPropertyName()) &&
                            evt.getNewValue() instanceof Accessible accessibleNode) {
                        // Check that the parent of the top-level node is the tree itself instead of the invisible root.
                        if (!jTree.equals(accessibleNode.getAccessibleContext().getAccessibleParent())) {
                            throw new RuntimeException("Accessible parent of the top-level node is not the tree.");
                        }
                        accessibleNodeInitialized.set(true);
                    }
                });

                jTree.setSelectionRow(0);
            });
            robot.waitForIdle();

            if (!accessibleNodeInitialized.get()) {
                throw new RuntimeException("The active descendant property change event wasn't fired, " +
                        "or the accessible node wasn't initialized properly.");
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> jFrame.dispose());
        }
    }

    public static void main(String[] args) throws Exception {
        doTest();
        System.out.println("Test Passed");
    }
}

