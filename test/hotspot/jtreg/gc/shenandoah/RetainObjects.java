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

/*
 * @test RetainObjects
 * @summary Acceptance tests: collector can deal with retained objects
 * @key gc
 * @requires vm.gc.Shenandoah
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=passive      -XX:+ShenandoahDegeneratedGC -XX:+ShenandoahVerify RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=passive      -XX:-ShenandoahDegeneratedGC -XX:+ShenandoahVerify RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=passive      -XX:+ShenandoahDegeneratedGC                       RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=passive      -XX:-ShenandoahDegeneratedGC                       RetainObjects
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive   -XX:+ShenandoahOOMDuringEvacALot RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive   -XX:+ShenandoahAllocFailureALot  RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive                                    RetainObjects
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=adaptive     -XX:+ShenandoahVerify RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=traversal    -XX:+ShenandoahVerify RetainObjects
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=adaptive     RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=static       RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=compact      RetainObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=traversal    RetainObjects
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:-UseTLAB                            -XX:+ShenandoahVerify RetainObjects
 */

public class RetainObjects {

    static final int COUNT = 10_000_000;
    static final int WINDOW = 10_000;

    static final String[] reachable = new String[WINDOW];

    public static void main(String[] args) throws Exception {
        int rIdx = 0;
        for (int c = 0; c < COUNT; c++) {
            reachable[rIdx] = ("LargeString" + c);
            rIdx++;
            if (rIdx >= WINDOW) {
                rIdx = 0;
            }
        }
    }

}
