/*
 * Copyright (c) 2018, 2019, Red Hat, Inc. and/or its affiliates.
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

/* @test
 * @requires (os.arch =="x86_64" | os.arch == "amd64" | os.arch=="x86" | os.arch=="i386") & !vm.graal.enabled & vm.gc.Shenandoah
 *
 * @run main/othervm/native -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -XX:ShenandoahGCMode=passive    -XX:+ShenandoahDegeneratedGC -Xcomp -Xmx512M -XX:+CriticalJNINatives CriticalNativeArgs
 * @run main/othervm/native -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -XX:ShenandoahGCMode=passive    -XX:-ShenandoahDegeneratedGC -Xcomp -Xmx512M -XX:+CriticalJNINatives CriticalNativeArgs
 *
 * @run main/othervm/native -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -XX:ShenandoahGCHeuristics=aggressive -Xcomp -Xmx512M -XX:+CriticalJNINatives CriticalNativeArgs
 *
 * @run main/othervm/native -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC                                                                        -Xcomp -Xmx256M -XX:+CriticalJNINatives CriticalNativeArgs
 * @run main/othervm/native -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCMode=iu        -Xcomp -Xmx512M -XX:+CriticalJNINatives CriticalNativeArgs
 * @run main/othervm/native -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions -XX:ShenandoahGCMode=iu -XX:ShenandoahGCHeuristics=aggressive -Xcomp -Xmx512M -XX:+CriticalJNINatives CriticalNativeArgs
 */
public class CriticalNativeArgs {
    static {
        System.loadLibrary("CriticalNative");
    }

    static native boolean isNull(int[] a);

    public static void main(String[] args) {
        int[] arr = new int[2];

        if (isNull(arr)) {
            throw new RuntimeException("Should not be null");
        }

        if (!isNull(null)) {
            throw new RuntimeException("Should be null");
        }
    }
}
