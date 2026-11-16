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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import sun.java2d.skia.SKEnvironment;

/*
 * @test
 * @summary Regression test for JBR-10654: Skia runs on Dawn over Direct3D 11.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true SKDawnBackendTest
 */

/*
 * Without Dawn there is nothing to test (Skia does not start without an adapter of the API asked
 * for); SKEnvironment.runningOn names the Direct3D version that runs ("(D3D11)").
 */

void main() {
    // a run on another API than asked for, which the start would have to report, fails
    String verbose = PipelineComparison.startCapturingVerbose();
    PipelineComparison.requireAPI("Dawn", verbose);
    String runningOn = SKEnvironment.runningOn();
    if (!runningOn.endsWith("(D3D11)")) {
        throw new RuntimeException("Dawn does not run on Direct3D 11: " + runningOn);
    }
    System.out.println("PASSED");
}
