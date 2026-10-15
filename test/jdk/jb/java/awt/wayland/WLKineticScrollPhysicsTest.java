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

import sun.awt.wl.WLKineticScrollPhysics;

/*
 * @test
 * @summary Verifies the physics of the kinetic scrolling implemented for Wayland touchpads
 * @requires os.family == "linux"
 * @modules java.desktop/sun.awt.wl
 * @run main WLKineticScrollPhysicsTest
 */
public class WLKineticScrollPhysicsTest {
    public static void main(String[] args) {
        // 10 units every 10 ms give 1000 units/s along each axis, so the whole distance is 1000/4 = 250
        var p = newPhysics();
        addSamples(p, 0, 10, -10);
        check(p.startFling(100), "the fling must start");
        double sumX = 0, sumY = 0;
        int ticks = 0;
        while (p.advance(8)) {
            check(p.getDeltaX() >= 0 && p.getDeltaY() <= 0, "the direction must be preserved");
            sumX += p.getDeltaX();
            sumY += p.getDeltaY();
            check(++ticks < 10_000, "the fling must finish");
        }
        sumX += p.getDeltaX();
        sumY += p.getDeltaY();
        check(Math.abs(sumX - 250) < 1e-6 && Math.abs(sumY + 250) < 1e-6, "the distance: " + sumX + ", " + sumY);
        check(!p.isFlinging(), "the fling must be finished");

        // Samples older than the velocity window (150 ms) before the stop are ignored
        p = newPhysics();
        addSamples(p, 0, 10, 0);
        check(!p.startFling(100 + 151), "no fling after a pause");

        // Too slow: 1 unit every 100 ms = 10 units/s
        p = newPhysics();
        p.addSample(0, 1, 0);
        p.addSample(100, 1, 0);
        check(!p.startFling(100), "no fling for a slow movement");

        // The velocity is clamped to 8000 units/s
        p = newPhysics();
        addSamples(p, 0, 1000, 0);
        check(p.startFling(100), "the fast fling must start");
        sumX = 0;
        while (p.advance(8)) {
            sumX += p.getDeltaX();
        }
        sumX += p.getDeltaX();
        check(Math.abs(sumX - 8000 / 4.0) < 1e-6, "the clamped distance: " + sumX);

        // Timestamps wrap around 2^32
        p = newPhysics();
        addSamples(p, 0xFFFFFFF0L, 10, 0);
        check(p.startFling(0xFFFFFFF0L + 100), "the fling must start after the timestamp wrap-around");
        p.advance(1);
        check(Math.abs(p.getDeltaX() - 250 * (1 - Math.exp(-4 * 0.001))) < 1e-6, "the first step after the wrap-around");

        // stop() and advance(0)
        p = newPhysics();
        addSamples(p, 0, 10, 0);
        check(p.startFling(100) && p.advance(0) && p.getDeltaX() == 0, "advance(0) gives no movement");
        p.stop();
        check(!p.isFlinging() && !p.advance(8), "stop() finishes the fling");

        System.out.println("PASSED");
    }

    private static WLKineticScrollPhysics newPhysics() {
        return new WLKineticScrollPhysics(150, 4.0, 50, 8000, 10);
    }

    // 11 samples with the step of 10 ms starting from startTime
    private static void addSamples(WLKineticScrollPhysics p, long startTime, double dx, double dy) {
        for (int i = 0; i <= 10; i++) {
            p.addSample(startTime + i * 10, dx, dy);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new RuntimeException("Test failed: " + message);
        }
    }
}
