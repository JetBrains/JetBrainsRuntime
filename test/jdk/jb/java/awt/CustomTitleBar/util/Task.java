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

package util;

import com.jetbrains.WindowDecorations;

import java.awt.*;
import java.util.function.Function;

abstract public class Task {

    private final String name;

    protected WindowDecorations.CustomTitleBar titleBar;
    protected Window window;
    protected boolean passed = true;
    protected Robot robot;


    public Task(String name) {
        this.name = name;
    }

    public final boolean run(Function<WindowDecorations.CustomTitleBar, Window> windowCreator) {
        try {
            robot = new Robot();
        } catch (AWTException e) {
            System.out.println("ERROR: unable to initialize robot");
            e.printStackTrace();
            return false;
        }
        init();
        System.out.printf("RUN TEST CASE: %s%n", name);
        passed = true;
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

        cleanup();
        titleBar = null;
        window.dispose();

        if (passed) {
            System.out.println("State: PASSED");
        } else {
            System.out.println("State: FAILED");
        }
        return passed;
    }

    protected void init() {

    }

    protected void cleanup() {
    }

    abstract public void prepareTitleBar();

    protected void customizeWindow() {
    }

    abstract public void test() throws Exception;

}
