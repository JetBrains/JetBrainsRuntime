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
import test.jb.testhelpers.TitleBar.Task;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CommonAPISuite {

    public static TaskResult runTestSuite(List<Function<WindowDecorations.CustomTitleBar, Window>> functions, Task task) {
        AtomicBoolean testPassed = new AtomicBoolean(true);
        List<String> errors = new ArrayList<>();
        functions.forEach(function -> {
            TaskResult result = task.run(function);
            testPassed.set(testPassed.get() && result.isPassed());
            errors.add(result.getError());
        });

        return new TaskResult(testPassed.get(), errors.stream().collect(Collectors.joining("\n")));
    }

}
