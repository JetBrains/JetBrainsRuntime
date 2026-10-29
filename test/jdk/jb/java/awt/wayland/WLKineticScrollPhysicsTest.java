/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

import sun.awt.wl.WLKineticScrollPhysics;

/*
 * @test
 * @requires os.family == "linux"
 * @modules java.desktop/sun.awt.wl
 * @run main WLKineticScrollPhysicsTest
 */
public class WLKineticScrollPhysicsTest {
    public static void main(String[] args) {
        // 10 units per 10 ms = 1000 units/s, with friction 4 total distance is 1000 / 4 = 250
        var p = new WLKineticScrollPhysics();
        addSamples(p, 0, 10, -10);
        check(p.startFling(100));
        double sumX = 0, sumY = 0;
        int ticks = 0;
        while (p.advance(8)) {
            check(p.getDeltaX() >= 0 && p.getDeltaY() <= 0);
            sumX += p.getDeltaX();
            sumY += p.getDeltaY();
            check(++ticks < 10_000);
        }
        sumX += p.getDeltaX();
        sumY += p.getDeltaY();
        check(Math.abs(sumX - 250) < 1e-6 && Math.abs(sumY + 250) < 1e-6);
        check(!p.isFlinging());

        // pause > 150 ms before stop
        p = new WLKineticScrollPhysics();
        addSamples(p, 0, 10, 0);
        check(!p.startFling(100 + 151));

        // too slow
        p = new WLKineticScrollPhysics();
        p.addSample(0, 1, 0);
        p.addSample(100, 1, 0);
        check(!p.startFling(100));

        // clamp to max velocity (8000)
        p = new WLKineticScrollPhysics();
        addSamples(p, 0, 1000, 0);
        check(p.startFling(100));
        sumX = 0;
        while (p.advance(8)) {
            sumX += p.getDeltaX();
        }
        sumX += p.getDeltaX();
        check(Math.abs(sumX - 8000 / 4.0) < 1e-6);

        // uint32 timestamp overflow
        p = new WLKineticScrollPhysics();
        addSamples(p, 0xFFFFFFF0L, 10, 0);
        check(p.startFling(0xFFFFFFF0L + 100));
        p.advance(1);
        check(Math.abs(p.getDeltaX() - 250 * (1 - Math.exp(-4 * 0.001))) < 1e-6);

        p = new WLKineticScrollPhysics();
        addSamples(p, 0, 10, 0);
        check(p.startFling(100) && p.advance(0) && p.getDeltaX() == 0);
        p.stop();
        check(!p.isFlinging() && !p.advance(8));
    }

    private static void addSamples(WLKineticScrollPhysics p, long startTime, double dx, double dy) {
        for (int i = 0; i <= 10; i++) {
            p.addSample(startTime + i * 10, dx, dy);
        }
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new RuntimeException();
        }
    }
}
