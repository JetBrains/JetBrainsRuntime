/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.awt.wl;

import java.util.ArrayDeque;

// Same exponential decay model like in GTK4: v(t) = v0 * exp(-f * t),
// so position is v0 / f * (1 - exp(-f * t)) and total distance is v0 / f.
public final class WLKineticScrollPhysics {
    private static final long UINT32_MASK = 0xFFFFFFFFL;
    private static final long VELOCITY_WINDOW_MS = 150;
    private static final double FRICTION = 4.0;
    private static final double MIN_START_VELOCITY = 50;
    private static final double MAX_VELOCITY = 8000;
    private static final double STOP_VELOCITY = 10;

    private record Sample(long timeMs, double dx, double dy) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private double velocityX;
    private double velocityY;
    private double elapsedSeconds;
    private double positionX;
    private double positionY;
    private double deltaX;
    private double deltaY;

    public void addSample(long timeMs, double dx, double dy) {
        dropSamplesOlderThanWindow(timeMs);
        samples.addLast(new Sample(timeMs & UINT32_MASK, dx, dy));
    }

    public void clearSamples() {
        samples.clear();
    }

    public boolean startFling(long stopTimeMs) {
        stop();
        dropSamplesOlderThanWindow(stopTimeMs);
        if (samples.size() < 2) {
            return false;
        }
        final long intervalMs = (samples.getLast().timeMs - samples.getFirst().timeMs) & UINT32_MASK;
        if (intervalMs == 0) {
            return false;
        }

        // first sample delta happened before its timestamp, so don't count it in distance
        double distanceX = -samples.getFirst().dx;
        double distanceY = -samples.getFirst().dy;
        for (Sample sample : samples) {
            distanceX += sample.dx;
            distanceY += sample.dy;
        }
        final double vx = distanceX * 1000 / intervalMs;
        final double vy = distanceY * 1000 / intervalMs;
        final double v = Math.hypot(vx, vy);
        if (v == 0 || v < MIN_START_VELOCITY) {
            return false;
        }
        final double clamp = Math.min(1, MAX_VELOCITY / v);
        velocityX = vx * clamp;
        velocityY = vy * clamp;
        return true;
    }

    public boolean advance(double elapsedMs) {
        deltaX = 0;
        deltaY = 0;
        if (!isFlinging()) {
            return false;
        }

        elapsedSeconds += Math.max(0, elapsedMs) / 1000;
        final double decay = Math.exp(-FRICTION * elapsedSeconds);
        final boolean finished = Math.hypot(velocityX, velocityY) * decay < STOP_VELOCITY;
        final double travelled = finished ? 1 : 1 - decay;
        deltaX = velocityX / FRICTION * travelled - positionX;
        deltaY = velocityY / FRICTION * travelled - positionY;
        positionX += deltaX;
        positionY += deltaY;
        if (finished) {
            stop();
        }
        return !finished;
    }

    public double getDeltaX() {
        return deltaX;
    }

    public double getDeltaY() {
        return deltaY;
    }

    public boolean isFlinging() {
        return velocityX != 0 || velocityY != 0;
    }

    public void stop() {
        velocityX = 0;
        velocityY = 0;
        elapsedSeconds = 0;
        positionX = 0;
        positionY = 0;
    }

    private void dropSamplesOlderThanWindow(long timeMs) {
        while (!samples.isEmpty() && ((timeMs - samples.getFirst().timeMs) & UINT32_MASK) > VELOCITY_WINDOW_MS) {
            samples.removeFirst();
        }
    }
}
