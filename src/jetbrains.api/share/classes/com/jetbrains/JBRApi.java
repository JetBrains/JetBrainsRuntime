/*
 * Copyright 2000-2020 JetBrains s.r.o.
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

package com.jetbrains;

public class JBRApi {

    private static final int MAJOR_VERSION = 0;
    private static final int MINOR_VERSION = 0;

    private JBRApi() {}

    /**
     * Returns major API version.
     * It has nothing to do with {@link #isAvailable() actual implementation availability}.
     */
    public static int getMajorVersion() {
        return MAJOR_VERSION;
    }

    /**
     * Returns minor API version.
     * It has nothing to do with {@link #isAvailable() actual implementation availability}.
     */
    public static int getMinorVersion() {
        return MINOR_VERSION;
    }

    /**
     * Checks for JBR API implementation availability at runtime.
     * {@code true} means we're running on JBR with {@code jetbrains.api.impl} module.
     */
    public static boolean isAvailable() {
        return JBRService.isApiAvailable();
    }

}
