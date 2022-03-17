// Copyright 2000-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import javax.swing.*;
import java.util.function.Consumer;

import static helper.ToolkitTestHelper.*;
import static helper.ToolkitTestHelper.TestCase.*;

/*
 * @test
 * @summary tests that CMenu/CMenuItem methods wrapped with {AWTThreading.executeWaitToolkit} are normally callable
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx java.desktop/sun.awt
 * @run main AWTThreadingCMenuTest
 * @author Anton Tarasov
 */
public class AWTThreadingCMenuTest {
    static {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
    }

    public static void main(String[] args) {
        initTest(AWTThreadingCMenuTest.class);

        testCase().
            withCaption("populate Menu").
            withRunnable(AWTThreadingCMenuTest::test1, true).
            withExpectedInLog("discarded", false).
            withCompletionTimeout(1).
            run();

        testCase().
            withCaption("de-populate Menu").
            withRunnable(AWTThreadingCMenuTest::test2, true).
            withExpectedInLog("discarded", false).
            withCompletionTimeout(1).
            run();

        System.out.println("Test PASSED");
    }

    @SuppressWarnings("deprecation")
    private static void test1() {
        var bar = new JMenuBar();
        FRAME.setJMenuBar(bar);

        var menu = new JMenu("Menu");

        Consumer<String> addItem = text -> {
            var item = new JMenuItem(text);
            item.setLabel("label " + text);
            menu.add(item);
            menu.addSeparator();
        };

        addItem.accept("one");
        addItem.accept("two");
        addItem.accept("three");

        bar.add(menu);

        TEST_CASE_RESULT.complete(true);
    }

    private static void test2() {
        var bar = FRAME.getJMenuBar();
        var menu = bar.getMenu(0);

        for (int i = 0; i < FRAME.getJMenuBar().getMenuCount(); i++) {
            menu.remove(i);
        }

        TEST_CASE_RESULT.complete(true);
    }
}
