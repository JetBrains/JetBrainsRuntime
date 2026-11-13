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

import sun.awt.KineticScrollPhysics;

/*
 * @test
 * @modules java.desktop/sun.awt
 * @run main WLKineticScrollPhysicsTest
 */
public class WLKineticScrollPhysicsTest {
    public static void main(String[] args) {
        // 10 units per 10 ms = 1000 units/s, with friction 4 total distance is 1000 / 4 = 250
        var p = new KineticScrollPhysics();
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
        check(Math.abs(sumX - 250) < 3.0 && Math.abs(sumY + 250) < 3.0);
        check(Math.abs(p.getDeltaX()) < 0.1 && Math.abs(p.getDeltaY()) < 0.1);
        check(!p.isFlinging());

        // pause > 150 ms before stop
        p = new KineticScrollPhysics();
        addSamples(p, 0, 10, 0);
        check(!p.startFling(100 + 151));

        // too slow
        p = new KineticScrollPhysics();
        p.addSample(0, 1, 0);
        p.addSample(100, 1, 0);
        check(!p.startFling(100));

        // clamp to max velocity (8000)
        p = new KineticScrollPhysics();
        addSamples(p, 0, 1000, 0);
        check(p.startFling(100));
        sumX = 0;
        while (p.advance(8)) {
            sumX += p.getDeltaX();
        }
        sumX += p.getDeltaX();
        check(Math.abs(sumX - 8000 / 4.0) < 3.0);

        // uint32 timestamp overflow
        p = new KineticScrollPhysics();
        addSamples(p, 0xFFFFFFF0L, 10, 0);
        check(p.startFling(0xFFFFFFF0L + 100));
        p.advance(1);
        check(Math.abs(p.getDeltaX() - 250 * (1 - Math.exp(-4 * 0.001))) < 1e-6);

        p = new KineticScrollPhysics();
        addSamples(p, 0, 10, 0);
        check(p.startFling(100) && p.advance(0) && p.getDeltaX() == 0);
        p.stop();
        check(!p.isFlinging() && !p.advance(8));

        // parameterized physics (lower friction = longer distance, minSampleIntervalMs clamps burst velocity)
        p = new KineticScrollPhysics(120, 2.0, 50, 3500, 8, 8);
        p.addSample(0, 10, 0);
        p.addSample(1, 16, 0); // 1 ms raw interval clamped to 8 ms -> v = 16 * 1000 / 8 = 2000, distance = 2000 / 2.0 = 1000
        check(p.startFling(5));
        sumX = 0;
        while (p.advance(8)) {
            sumX += p.getDeltaX();
        }
        sumX += p.getDeltaX();
        check(Math.abs(sumX - 1000.0) < 5.0);
    }

    private static void addSamples(KineticScrollPhysics p, long startTime, double dx, double dy) {
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
