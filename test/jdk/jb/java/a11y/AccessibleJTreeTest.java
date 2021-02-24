// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * @test
 * @summary manual test for JBR-2785
 * @author Artem.Semenov@jetbrains.com
 * @run main/manual AccessibleJTreeTest
 */

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AccessibleJTreeTest extends AccessibleComponentTest {

    @Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    public void createSampleTree() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JTree in a simple Window.\n\n"
                + "Turn screen reader on, and Tab to the tree.\n"
                + "Press the arrow buttons to move through the tree.\n\n"
                + "If you can hear tree components tab further and press PASS, otherwise press FAIL.\n";

        String root = "Root";
        String[] nodes = new String[] {"One node", "Two node"};
        String[][] leafs = new String[][]{{"leaf 1.1", "leaf 1.2", "leaf 1.3", "leaf 1.4"},
                {"leaf 2.1", "leaf 2.2", "leaf 2.3", "leaf 2.4"}};

        Hashtable<String, String[]> data = new Hashtable<String, String[]>();
        for (int i = 0; i < nodes.length; i++) {
            data.put(nodes[i], leafs[i]);
        }

        JTree tree = new JTree(data);
        tree.setRootVisible(true);

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        JScrollPane scrollPane = new JScrollPane(tree);
        panel.add(scrollPane);
        panel.setFocusable(false);
        exceptionString = "AccessibleJTree sample item test failed!";
        super.createUI(panel, "AccessibleJTreeTest");
    }

    public void createSampleTreeUnvisableRoot() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JTree with unvisable root in a simple Window.\n\n"
                + "Turn screen reader on, and Tab to the tree.\n"
                + "Press the arrow buttons to move through the tree.\n\n"
                + "If you can hear tree components tab further and press PASS, otherwise press FAIL.\n";

        String root = "Root";
        String[] nodes = new String[] {"One node", "Two node"};
        String[][] leafs = new String[][]{{"leaf 1.1", "leaf 1.2", "leaf 1.3", "leaf 1.4"},
                {"leaf 2.1", "leaf 2.2", "leaf 2.3", "leaf 2.4"}};

        Hashtable<String, String[]> data = new Hashtable<String, String[]>();
        for (int i = 0; i < nodes.length; i++) {
            data.put(nodes[i], leafs[i]);
        }

        JTree tree = new JTree(data);

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        JScrollPane scrollPane = new JScrollPane(tree);
        panel.add(scrollPane);
        panel.setFocusable(false);
        exceptionString = "AccessibleJTree sample item unvisable root test failed!";
        super.createUI(panel, "AccessibleJTreeTest");
    }

    public void createSampleTreeNamed() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of named JTree in a simple Window.\n\n"
                + "Turn screen reader on, and Tab to the tree.\n"
                + "Press the tab button to move to second tree.\\n\n"
                + "If you can hear second tree name the \"Second tree\" tab further and press PASS, otherwise press FAIL.\n";

        String root = "Root";
        String[] nodes = new String[] {"One node", "Two node"};
        String[][] leafs = new String[][]{{"leaf 1.1", "leaf 1.2", "leaf 1.3", "leaf 1.4"},
                {"leaf 2.1", "leaf 2.2", "leaf 2.3", "leaf 2.4"}};

        Hashtable<String, String[]> data = new Hashtable<String, String[]>();
        for (int i = 0; i < nodes.length; i++) {
            data.put(nodes[i], leafs[i]);
        }

        JTree tree = new JTree(data);
        JTree secondTree = new JTree(data);
        secondTree.getAccessibleContext().setAccessibleName("Second tree");
        tree.setRootVisible(true);

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        JScrollPane scrollPane = new JScrollPane(tree);
        JScrollPane secondScrollPane = new JScrollPane(secondTree);
        panel.add(scrollPane);
        panel.add(secondScrollPane);
        panel.setFocusable(false);
        exceptionString = "AccessibleJTree named test failed!";
        super.createUI(panel, "AccessibleJTreeTest");
    }


    public void createRendererTree() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JTree using renderer in a simple Window.\n\n"
                + "Turn screen reader on, and Tab to the tree.\n"
                + "Press the arrow buttons to move through the tree.\n\n"
                + "If you can hear tree components tab further and press PASS, otherwise press FAIL.\n";

        String root = "Root";
        String[] nodes = new String[] {"One node", "Two node"};
        String[][] leafs = new String[][]{{"leaf 1.1", "leaf 1.2", "leaf 1.3", "leaf 1.4"},
                {"leaf 2.1", "leaf 2.2", "leaf 2.3", "leaf 2.4"}};

        Hashtable<String, String[]> data = new Hashtable<String, String[]>();
        for (int i = 0; i < nodes.length; i++) {
            data.put(nodes[i], leafs[i]);
        }

        JTree tree = new JTree(data);
        tree.setRootVisible(true);
        tree.setCellRenderer(new AccessibleJTreeTestRenderer());

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        JScrollPane scrollPane = new JScrollPane(tree);
        panel.add(scrollPane);
        panel.setFocusable(false);
        exceptionString = "AccessibleJTree renderer item test failed!";
        super.createUI(panel, "AccessibleJTreeTest");
    }

    public static void main(String[] args) throws Exception {
        AccessibleJTreeTest test = new AccessibleJTreeTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createSampleTree);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createSampleTreeNamed);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }


        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createSampleTreeUnvisableRoot);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createRendererTree);
        countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(AccessibleComponentTest.exceptionString);
        }
    }

    public static class AccessibleJTreeTestRenderer extends JPanel implements TreeCellRenderer {
        private JLabel labelAJT = new JLabel("AJT");
        private JLabel itemName = new JLabel();

        AccessibleJTreeTestRenderer() {
            super(new FlowLayout());
            setFocusable(false);
            layoutComponents();
        }

        private void layoutComponents() {
            add(labelAJT);
            add(itemName);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                itemName.setText((String) (((DefaultMutableTreeNode) value).getUserObject()));

            getAccessibleContext().setAccessibleName(labelAJT.getText() + ", " + itemName.getText());
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(Math.min(size.width, 245), size.height);
        }
    }
}
