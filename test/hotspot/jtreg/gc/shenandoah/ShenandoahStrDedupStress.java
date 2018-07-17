/*
 * Copyright (c) 2017, Red Hat, Inc. and/or its affiliates.
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

 /*
 * @test TestShenandoahStringDedup.java
 * @summary Test Shenandoah string deduplication implementation
 * @key gc
 * @library /test/lib
 * @modules java.base/jdk.internal.misc:open
 * @modules java.base/java.lang:open
 *          java.management
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -DtargetStrings=3000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=aggressive -DtargetStrings=2000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=aggressive -XX:+ShenandoahOOMDuringEvacALot -DtargetStrings=2000000
 *                    ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=static -DtargetStrings=4000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=compact
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=passive -DtargetOverwrites=40000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=generational
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=LRU
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=traversal
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=connected
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahUpdateRefsEarly=off -DtargetStrings=3000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=compact -XX:ShenandoahUpdateRefsEarly=off -DtargetStrings=2000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=aggressive -XX:ShenandoahUpdateRefsEarly=off -DtargetStrings=2000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=static -XX:ShenandoahUpdateRefsEarly=off -DtargetOverwrites=4000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=aggressive -XX:ShenandoahUpdateRefsEarly=off -XX:+ShenandoahOOMDuringEvacALot -DtargetStrings=2000000
 *                   ShenandoahStrDedupStress
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication -Xmx512M -Xlog:gc+stats
 *                   -XX:ShenandoahGCHeuristics=traversal -XX:+ShenandoahOOMDuringEvacALot
 *                   ShenandoahStrDedupStress
 */

import java.lang.reflect.*;
import java.util.*;
import sun.misc.*;

public class ShenandoahStrDedupStress {
  private static Field valueField;
  private static Unsafe unsafe;

  private static long TARGET_STRINGS = Long.getLong("targetStrings", 2_500_000);
  private static long TARGET_OVERWRITES = Long.getLong("targetOverwrites", 600_000);

  private static final int UNIQUE_STRINGS = 20;
  static {
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      unsafe = (Unsafe)field.get(null);

      valueField = String.class.getDeclaredField("value");
      valueField.setAccessible(true);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Object getValue(String string) {
    try {
      return valueField.get(string);
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
  }

  static class StringAndId {
    private String str;
    private int    id;
    public StringAndId(String str, int id) {
      this.str = str;
      this.id = id;
    }

    public String str() { return str; }
    public int    id()  { return id;  }
  }

  private static void generateStrings(ArrayList<StringAndId> strs, int unique_strs) {
    Random rn = new Random();
    for (int u = 0; u < unique_strs; u ++) {
      int n = Math.abs(rn.nextInt() % 2);
      for (int index = 0; index < n; index ++) {
          strs.add(new StringAndId("Unique String " + u, u));
      }
    }
  }

  private static int verifyDedepString(ArrayList<StringAndId> strs) {
    HashMap<Object, StringAndId> seen = new HashMap<>();
    int total = 0;
    int dedup = 0;

    for (StringAndId item : strs) {
      total ++;
      StringAndId existing_item = seen.get(getValue(item.str()));
      if (existing_item == null) {
        seen.put(getValue(item.str()), item);
      } else {
        if (item.id() != existing_item.id() ||
            !item.str().equals(existing_item.str())) {
          System.out.println("StringDedup error:");
          System.out.println("id: " + item.id() + " != " + existing_item.id());
          System.out.println("or String: " + item.str() + " != " + existing_item.str());
          throw new RuntimeException("StringDedup Test failed");
        } else {
          dedup ++;
        }
      }
    }
    System.out.println("Dedup: " + dedup + "/" + total + " unique: "  + (total - dedup));
    return (total - dedup);
  }

  static volatile ArrayList<StringAndId> astrs = new ArrayList<>();
  public static void main(String[] args) {
    Random rn = new Random();

    long gen_iterations = TARGET_STRINGS * 2 / UNIQUE_STRINGS;

    for(long index = 0; index < gen_iterations; index ++) {
      generateStrings(astrs, UNIQUE_STRINGS);
    }

    for (long loop = 1; loop < TARGET_OVERWRITES; loop ++) {
      int arr_size = astrs.size();
      int index = Math.abs(rn.nextInt()) % arr_size;
      StringAndId item = astrs.get(index);
      int n = Math.abs(rn.nextInt() % UNIQUE_STRINGS);
      item.str = "Unique String " + n;
      item.id = n;
    }

    verifyDedepString(astrs);
  }
}
