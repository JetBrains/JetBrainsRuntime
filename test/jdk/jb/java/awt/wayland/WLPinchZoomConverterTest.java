/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

import sun.awt.wl.WLPinchZoomConverter;

/*
 * @test
 * @summary Verifies conversion of Wayland touchpad pinch gestures into Ctrl + mouse wheel rotations
 * @requires os.family == "linux"
 * @modules java.desktop/sun.awt.wl
 * @run main WLPinchZoomConverterTest
 */
public class WLPinchZoomConverterTest {
    public static void main(String[] args) {
        final var c = new WLPinchZoomConverter(6.0);

        // 2x zoom in and out are symmetric
        check(c.update(2.0) && c.getRoundRotations() == -4 && Math.abs(c.getPreciseRotations() + 4.1588830833596715) < 1e-9,
              "2x zoom in");
        c.reset();
        check(c.update(0.5) && c.getRoundRotations() == 4, "2x zoom out");

        // Fractional rotations of small steps are accumulated, and a round trip gives 0 in total
        c.reset();
        int sum = 0;
        for (int i = 1; i <= 100; i++) {
            check(c.update(Math.pow(1.01, i)), "small step");
            sum += c.getRoundRotations();
        }
        check(sum == (int) (-100 * Math.log(1.01) * 6.0), "the sum of small steps: " + sum);
        for (int i = 99; i >= 0; i--) {
            check(c.update(Math.pow(1.01, i)), "small step back");
            sum += c.getRoundRotations();
        }
        check(sum == 0, "the sum of a round trip: " + sum);

        // Invalid and unchanged scales are ignored
        for (double scale : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 1.0}) {
            check(!c.update(scale), "update(" + scale + ") must be ignored");
        }

        // reset() drops the remainder
        c.update(1.1);
        c.reset();
        check(c.update(1.1) && c.getRoundRotations() == 0, "remainder after reset()");

        System.out.println("PASSED");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new RuntimeException("Test failed: " + message);
        }
    }
}
