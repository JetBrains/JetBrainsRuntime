/*
 * Copyright 2000-2023 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package util;

import com.jetbrains.WindowDecorations;

import java.awt.Window;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class CommonAPISuite {

    public static boolean runTestSuite(List<Function<WindowDecorations.CustomTitleBar, Window>> functions, Task task) {
        AtomicBoolean testPassed = new AtomicBoolean(true);
        functions.forEach(function -> testPassed.set(testPassed.get() && task.run(function)));

        return testPassed.get();
    }

}
