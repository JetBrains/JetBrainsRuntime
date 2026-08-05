/*
 * Copyright 2026 JetBrains s.r.o.
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
 */

import com.jetbrains.exported.JBRApi;

/**
 * @test
 * @summary FramePacing service construction must throw
 * ServiceNotAvailableException in a headless environment and must
 * not leave background threads running.
 * @library /test/lib
 * @compile --add-exports java.desktop/sun.awt=ALL-UNNAMED
 * --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED
 * FramePacingTestUtil.java FramePacingHeadlessTest.java
 * @run main/othervm -Djava.awt.headless=true
 * --add-exports java.desktop/sun.awt=ALL-UNNAMED
 * --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED
 * FramePacingHeadlessTest
 */
public class FramePacingHeadlessTest {
    public static void main(String[] args) throws Exception {
        try {
            FramePacingTestUtil.createPlatformService();

            throw new RuntimeException("Expected ServiceNotAvailableException in headless mode");
        } catch (JBRApi.ServiceNotAvailableException expected) {
            // ok
        }

        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("JBR-FramePacing")) {
                throw new RuntimeException("Unexpected pacing thread in headless mode: " + thread.getName());
            }
        }
    }
}
