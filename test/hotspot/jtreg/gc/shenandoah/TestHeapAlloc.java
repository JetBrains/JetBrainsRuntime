/*
 * Copyright (c) 2017, 2018, Red Hat, Inc. All rights reserved.
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
 * @test TestHeapAlloc
 * @summary Fail to expand Java heap should not result fatal errors
 * @key gc
 * @requires vm.gc.Shenandoah
 * @requires (vm.debug == true)
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @ignore // heap is not resized in a conventional manner, ignore for now
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.Platform;

import java.util.*;
import java.util.concurrent.*;

public class TestHeapAlloc {

  public static void main(String[] args) throws Exception {
    if (!Platform.isDebugBuild()) {
      System.out.println("Test requires debug build. Please silently for release build");
      return;
    }

    ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(
        true,
        "-Xmx1G",
        "-Xms256M",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseShenandoahGC",
        "-XX:ShenandoahFailHeapExpansionAfter=50",
        "-Xlog:gc+region=debug",
        AllocALotObjects.class.getName());
    OutputAnalyzer output = new OutputAnalyzer(pb.start());
    output.shouldContain("Artificially fails heap expansion");
    output.shouldHaveExitValue(0);
  }

  public static class AllocALotObjects {
    public static void main(String[] args) {
      try {
        ArrayList<String> list = new ArrayList<>();
        long count = 500 * 1024 * 1024 / 16; // 500MB allocation
        for (long index = 0; index < count; index ++) {
          String sink = "string " + index;
          list.add(sink);
        }

        int index = ThreadLocalRandom.current().nextInt(0, (int)count);
        System.out.print(list.get(index));
      } catch (OutOfMemoryError e) {
      }
    }
  }

}
