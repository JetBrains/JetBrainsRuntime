/*
 * Copyright (c) 2016, 2018, Red Hat, Inc. All rights reserved.
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
 * @test AllocObjects
 * @summary Acceptance tests: collector can withstand allocation
 * @key gc
 * @requires vm.gc.Shenandoah
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=passive      -XX:+ShenandoahDegeneratedGC     -XX:+ShenandoahVerify AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=passive      -XX:-ShenandoahDegeneratedGC     -XX:+ShenandoahVerify AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=passive      -XX:+ShenandoahDegeneratedGC                           AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=passive      -XX:-ShenandoahDegeneratedGC                           AllocObjects
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive   -XX:+ShenandoahOOMDuringEvacALot -XX:+ShenandoahVerify AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive   -XX:+ShenandoahAllocFailureALot  -XX:+ShenandoahVerify AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive   -XX:+ShenandoahOOMDuringEvacALot                       AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive   -XX:+ShenandoahAllocFailureALot                        AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive                                                          AllocObjects
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=adaptive     -XX:+ShenandoahVerify AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=traversal    -XX:+ShenandoahVerify AllocObjects
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=adaptive     AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=static       AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=compact      AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=traversal    AllocObjects
 *
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=adaptive   -XX:+ShenandoahSuspendibleWorkers AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=static     -XX:+ShenandoahSuspendibleWorkers AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=compact    -XX:+ShenandoahSuspendibleWorkers AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=traversal  -XX:+ShenandoahSuspendibleWorkers AllocObjects
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -Xmx1g -Xms1g -XX:ShenandoahGCHeuristics=aggressive -XX:+ShenandoahSuspendibleWorkers AllocObjects
 */

import java.util.Random;

public class AllocObjects {

    static final long TARGET_MB = Long.getLong("target", 10_000); // 10 Gb allocation

    static volatile Object sink;

    public static void main(String[] args) throws Exception {
        long count = TARGET_MB * 1024 * 1024 / 16;
        for (long c = 0; c < count; c++) {
            sink = new Object();
        }
    }

}
