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
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions                                         -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=aggressive   -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=aggressive   -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats -XX:+ShenandoahOOMDuringEvacALot ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=static       -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=compact      -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=passive      -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=generational -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=LRU          -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=traversal    -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=connected    -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions                                         -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats -XX:ShenandoahUpdateRefsEarly=off ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=compact      -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats -XX:ShenandoahUpdateRefsEarly=off ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=aggressive   -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats -XX:ShenandoahUpdateRefsEarly=off ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=static       -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats -XX:ShenandoahUpdateRefsEarly=off ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=aggressive   -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats -XX:ShenandoahUpdateRefsEarly=off -XX:+ShenandoahOOMDuringEvacALot ShenandoahStrDedupStress
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCHeuristics=traversal    -XX:+UseStringDeduplication -Xmx1g -Xlog:gc+stats                                   -XX:+ShenandoahOOMDuringEvacALot ShenandoahStrDedupStress
 */

import java.lang.reflect.*;
import java.util.*;
import sun.misc.*;

public class ShenandoahStrDedupStress {
  private static Field valueField;
  private static Unsafe unsafe;

  private static final int UniqueStrings = 20;
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
      int n = rn.nextInt() % 10;
      n = Math.max(n, 2);
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

    for(int index = 0; index < 100000; index ++) {
      generateStrings(astrs, UniqueStrings);
    }

    for (int loop = 1; loop < 10000; loop ++) {
      for (int index = 0; index < 10000; index ++) {
        StringAndId item = astrs.get(index);
        int n = rn.nextInt() % UniqueStrings;
        item.str = "Unique String " + n;
        item.id = n;
      }
    }

    try {
      Thread.sleep(10000);
    } catch (Exception e) {

    }

    verifyDedepString(astrs);
  }
}
