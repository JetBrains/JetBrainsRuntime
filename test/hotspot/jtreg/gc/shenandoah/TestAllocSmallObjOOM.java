/*
 * Copyright (c) 2016 Red Hat, Inc. and/or its affiliates.
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
 *
 */

/**
 * @test TestAllocSmallObjOOM
 * @summary Test allocation of small object to result OOM, but not to crash JVM
 * @modules java.base/jdk.internal.misc
 * @library /test/lib
 * @run main/othervm TestAllocSmallObjOOM
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;


public class TestAllocSmallObjOOM {

    public static void main(String[] args) {
        try {
            // Small heap size should result OOM during loading of system classes
            ProcessBuilder pb = ProcessTools.createJavaProcessBuilder("-Xmx5m",  "-XX:+UseShenandoahGC");
            OutputAnalyzer analyzer = new OutputAnalyzer(pb.start());
            analyzer.shouldHaveExitValue(1);
            analyzer.shouldContain("java.lang.OutOfMemoryError: Java heap space");
        } catch (Exception e) {
        }

    }
}
