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
package test.jb.testhelpers.TitleBar;

import com.jetbrains.WindowDecorations;
import test.jb.testhelpers.TitleBar.TaskResult;
import test.jb.testhelpers.TitleBar.TestUtils;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.Window;
import java.util.function.Function;

abstract public class Task {

    protected final String name;

    protected WindowDecorations.CustomTitleBar titleBar;
    protected Window window;
    protected boolean passed = true;
    protected Robot robot;
    protected String error;


    public Task(String name) {
        this.name = name;
    }

    public TaskResult run(Function<WindowDecorations.CustomTitleBar, Window> windowCreator) {
        try {
            robot = new Robot();
        } catch (AWTException e) {
            final String message = "ERROR: unable to initialize robot";
            e.printStackTrace();
            return new TaskResult(false, message);
        }
        init();
        System.out.printf("RUN TEST CASE: %s%n", name);
        passed = true;
        error = "";
        prepareTitleBar();
        window = windowCreator.apply(titleBar);
        System.out.println("Created a window with the custom title bar. Window name: " + window.getName());
        customizeWindow();

        window.setVisible(true);
        try {
            test();
        } catch (Exception e) {
            System.out.println("ERROR: An error occurred during tests execution");
            e.printStackTrace();
            passed = false;
        }

        disposeUI();

        if (!TestUtils.isBasicTestCase()) {
            boolean isMetConditions = TestUtils.checkScreenSizeConditions(window);
            if (!isMetConditions) {
                error += "SKIPPED: environment don't match screen size conditions";
                return new TaskResult(false, true, error);
            }
        }

        return new TaskResult(passed, error);
    }

    protected void err(String message) {
        this.error = error + message + "\n";
        passed = false;
        System.out.println(message);
    }

    protected void init() {

    }

    protected void disposeUI() {
        titleBar = null;
        window.dispose();
        cleanup();
    }

    protected void cleanup() {
    }

    abstract public void prepareTitleBar();

    protected void customizeWindow() {
    }

    abstract public void test() throws Exception;

}
