/*
 * Copyright 2000-2022 JetBrains s.r.o.
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

import java.awt.Window;

/**
 * This manager allows decorate awt Window with rounded corners.
 * Appearance depends from operating system.
 */
public interface RoundedCornersManager {
    /**
     * @param params for macOS is Float object with radius or
     *               Array with {Float for radius, Integer for border width, java.awt.Color for border color}.
     *
     * @param params for Windows 11 is String with values:
     * "default" - let the system decide whether or not to round window corners,
     * "none" - never round window corners,
     * "full" - round the corners if appropriate,
     * "small" - round the corners if appropriate, with a small radius.
     */
    void setRoundedCorners(Window window, Object params);
}
