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
    private static final double FRICTION = 4;
    private static final double STOP_VELOCITY = 10;

    public static void main(String[] args) {
        // 10 units per 10 ms = 1000 units/s, with friction 4 total distance is 1000 / 4 = 250
        var p = new KineticScrollPhysics();
        addSamples(p, 0, 10, -10);
        check(p.startFling(100));
        double sumX = 0, sumY = 0, previousDeltaX = Double.MAX_VALUE;
        int ticks = 0;
        while (p.advance(8)) {
            check(p.getDeltaX() >= 0 && p.getDeltaY() <= 0);
            sumX += p.getDeltaX();
            sumY += p.getDeltaY();
            check(++ticks < 10_000);
            previousDeltaX = p.getDeltaX();
        }
        // the fling does not jump at its end
        check(p.getDeltaX() < previousDeltaX);
        sumX += p.getDeltaX();
        sumY += p.getDeltaY();
        check(equalsFlingDistance(sumX, 250) && equalsFlingDistance(sumY, -250));
        check(!p.isFlinging());

        // pause > 150 ms before stop
        p = new KineticScrollPhysics();
        addSamples(p, 0, 10, 0);
        check(!p.startFling(100 + 151));

        // pause of 100 ms before stop: samples at 50..100 remain, 50 units over 150 ms
        p = new KineticScrollPhysics();
        addSamples(p, 0, 10, 0);
        check(p.startFling(200));
        sumX = 0;
        while (p.advance(8)) {
            sumX += p.getDeltaX();
        }
        sumX += p.getDeltaX();
        check(equalsFlingDistance(sumX, 50 * 1000 / 150.0 / 4));

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
        check(equalsFlingDistance(sumX, 8000 / 4.0));

        // uint32 timestamp overflow
        p = new KineticScrollPhysics();
        addSamples(p, 0xFFFFFFF0L, 10, 0);
        check(p.startFling(0xFFFFFFF0L + 100));
        p.advance(1);
        check(equals(p.getDeltaX(), 250 * (1 - Math.exp(-4 * 0.001))));

        // like in GTK4, a fling in the direction of the previous one that is still going on gets a part of its velocity
        p = new KineticScrollPhysics();
        addSamples(p, 0, 10, 0);
        check(p.startFling(100));
        addSamples(p, 300, 10, 0);
        check(p.startFling(400));
        final double lastVelocity = 1000 * Math.exp(-FRICTION * 0.3);
        final double accumulatedVelocity = 1000 + lastVelocity * (1000 - 0.33 * lastVelocity) / (lastVelocity - 0.33 * lastVelocity);
        p.advance(1);
        check(equals(p.getDeltaX(), accumulatedVelocity / FRICTION * (1 - Math.exp(-FRICTION * 0.001))));

        // a fling in the opposite direction does not
        addSamples(p, 600, -10, 0);
        check(p.startFling(700));
        p.advance(1);
        check(equals(p.getDeltaX(), -250 * (1 - Math.exp(-4 * 0.001))));

        p = new KineticScrollPhysics();
        addSamples(p, 0, 10, 0);
        check(p.startFling(100) && p.advance(0) && p.getDeltaX() == 0);
        p.stop();
        check(!p.isFlinging() && !p.advance(8));
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

    private static boolean equals(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    // a fling is over once it is slower than STOP_VELOCITY, i.e. up to STOP_VELOCITY / FRICTION before the full distance
    private static boolean equalsFlingDistance(double distance, double fullDistance) {
        return Math.abs(fullDistance - distance) < STOP_VELOCITY / FRICTION;
    }
}
