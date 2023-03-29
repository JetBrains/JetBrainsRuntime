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

/*
 * @test
 * @run main JBRApiTest
 */

import com.jetbrains.JBR;
import com.jetbrains.JBRFileDialog;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

public class JBRApiTest {

    public static void main(String[] args) throws Exception {
        if (!JBR.getApiVersion().matches("\\w+\\.\\d+\\.\\d+\\.\\d+")) throw new Error("Invalid JBR API version: " + JBR.getApiVersion());
        if (!JBR.isAvailable()) throw new Error("JBR API is not available");
        checkMetadata();
        testServices();
    }

    private static void checkMetadata() throws Exception {
        Class<?> metadata = Class.forName(JBR.class.getName() + "$Metadata");
        Field field = metadata.getDeclaredField("KNOWN_SERVICES");
        field.setAccessible(true);
        List<String> knownServices = List.of((String[]) field.get(null));
        if (!knownServices.contains("com.jetbrains.JBR$ServiceApi")) {
            throw new Error("com.jetbrains.JBR$ServiceApi was not found in known services of com.jetbrains.JBR$Metadata");
        }
    }

    private static void testServices() {
        Objects.requireNonNull(JBR.getExtendedGlyphCache().getSubpixelResolution());
        Objects.requireNonNull(JBRFileDialog.get(new FileDialog((Frame) null)));
        Objects.requireNonNull(JBR.getAccessibleAnnouncer());
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            Objects.requireNonNull(JBR.getRoundedCornersManager());
        }
    }
}
