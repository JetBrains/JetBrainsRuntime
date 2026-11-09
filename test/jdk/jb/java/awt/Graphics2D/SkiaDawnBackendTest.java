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

import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary On Windows Skia runs on Dawn's Direct3D 11 backend unless
 *          sun.java2d.skia.dawn.backend=d3d12 asks for Direct3D 12 first (an
 *          evaluation option: Chrome ships WebGPU on Dawn's D3D12 backend,
 *          Skia tests Graphite on D3D11 first). When Skia is unavailable
 *          there is nothing to test.
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true SkiaDawnBackendTest D3D11
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.dawn.backend=d3d11 SkiaDawnBackendTest D3D11
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.dawn.backend=d3d12 SkiaDawnBackendTest D3D12
 */
public class SkiaDawnBackendTest {

    public static void main(String[] args) {
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia backend unavailable: nothing to test");
            return;
        }
        String description = SKEnvironment.getDescription();
        System.out.println(description);
        if (!description.endsWith("(" + args[0] + ")")) {
            throw new RuntimeException("expected the " + args[0] + " backend: " + description);
        }
        System.out.println("PASSED");
    }
}
