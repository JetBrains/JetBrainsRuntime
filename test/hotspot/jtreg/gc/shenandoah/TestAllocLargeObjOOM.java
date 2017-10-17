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
 * @test TestAllocLargeObjOOM
 * @key gc
 * @summary Test allocation of object larger than heap should result OOM
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @run main/othervm -Xmx10m -XX:+UseShenandoahGC TestAllocLargeObjOOM
 */
import jdk.test.lib.Utils;
import jdk.test.lib.Asserts;
import java.lang.ref.SoftReference;
import java.util.LinkedList;
import java.util.Random;

public class TestAllocLargeObjOOM {
  public static void main(String[] args) {
        try {
            Random random = new Random(123);
            long total_memory = Runtime.getRuntime().totalMemory();
            int size = (int)((total_memory + 7) / 8);
            long[] large_array = new long[size];

            System.out.println(large_array[random.nextInt() % size]);
            Asserts.assertTrue(false, "Should not reach here");
        } catch (OutOfMemoryError e) {
            Asserts.assertTrue(true, "Expected OOM");
        }
  }
}
