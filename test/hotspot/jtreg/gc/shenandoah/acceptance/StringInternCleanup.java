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
 * @test StringInternCleanup
 * @summary Check that Shenandoah cleans up interned strings
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m                                         -XX:+ShenandoahVerify StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=passive      -XX:+ShenandoahVerify StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=adaptive     -XX:+ShenandoahVerify StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=static       -XX:+ShenandoahVerify StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=connected    -XX:+ShenandoahVerify StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=generational -XX:+ShenandoahVerify StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=LRU          -XX:+ShenandoahVerify StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=traversal    -XX:+ShenandoahVerify StringInternCleanup
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m                                                               StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=passive                            StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=adaptive                           StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=static                             StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=compact                            StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=aggressive                         StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=connected                          StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=generational                       StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=LRU                                StringInternCleanup
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+ClassUnloadingWithConcurrentMark -Xmx64m -XX:ShenandoahGCHeuristics=traversal                          StringInternCleanup
 *
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:-ClassUnloadingWithConcurrentMark -Xmx64m                                                               StringInternCleanup
 */

public class StringInternCleanup {

  static final int COUNT = 1_000_000;
  static final int WINDOW = 1_000;

  static final String[] reachable = new String[WINDOW];

  public static void main(String[] args) throws Exception {
    int rIdx = 0;
    for (int c = 0; c < COUNT; c++) {
      reachable[rIdx] = ("LargeInternedString" + c).intern();
      rIdx++;
      if (rIdx >= WINDOW) {
        rIdx = 0;
      }
    }
  }

}
