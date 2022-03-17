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
