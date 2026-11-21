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

package sun.awt;

import java.util.ArrayDeque;

// Same exponential decay model like in GTK4: v(t) = v0 * exp(-f * t),
// so position is v0 / f * (1 - exp(-f * t)) and total distance is v0 / f.
public final class KineticScrollPhysics {
    private static final long UINT32_MASK = 0xFFFFFFFFL;
    public static final long DEFAULT_VELOCITY_WINDOW_MS = 150;
    public static final double DEFAULT_FRICTION = 4.0;
    public static final double DEFAULT_MIN_START_VELOCITY = 50;
    public static final double DEFAULT_MAX_VELOCITY = 8000;
    public static final double DEFAULT_STOP_VELOCITY = 10;

    private record Sample(long timeMs, double dx, double dy) {}

    private final long velocityWindowMs;
    private final double friction;
    private final double minStartVelocity;
    private final double maxVelocity;
    private final double stopVelocity;
    private final long minSampleIntervalMs;

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private double velocityX;
    private double velocityY;
    private double elapsedSeconds;
    private double positionX;
    private double positionY;
    private double deltaX;
    private double deltaY;

    public KineticScrollPhysics() {
        this(DEFAULT_VELOCITY_WINDOW_MS,
             DEFAULT_FRICTION,
             DEFAULT_MIN_START_VELOCITY,
             DEFAULT_MAX_VELOCITY,
             DEFAULT_STOP_VELOCITY,
             1);
    }

    public KineticScrollPhysics(long velocityWindowMs,
                                double friction,
                                double minStartVelocity,
                                double maxVelocity,
                                double stopVelocity) {
        this(velocityWindowMs, friction, minStartVelocity, maxVelocity, stopVelocity, 1);
    }

    public KineticScrollPhysics(long velocityWindowMs,
                                double friction,
                                double minStartVelocity,
                                double maxVelocity,
                                double stopVelocity,
                                long minSampleIntervalMs) {
        this.velocityWindowMs = velocityWindowMs;
        this.friction = friction;
        this.minStartVelocity = minStartVelocity;
        this.maxVelocity = maxVelocity;
        this.stopVelocity = stopVelocity;
        this.minSampleIntervalMs = Math.max(1, minSampleIntervalMs);
    }

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
        final long rawIntervalMs = (samples.getLast().timeMs - samples.getFirst().timeMs) & UINT32_MASK;
        if (rawIntervalMs == 0) {
            return false;
        }
        final long intervalMs = Math.max(rawIntervalMs, (samples.size() - 1) * minSampleIntervalMs);

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
        if (v == 0 || v < minStartVelocity) {
            return false;
        }
        final double clamp = Math.min(1, maxVelocity / v);
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
        final double decay = Math.exp(-friction * elapsedSeconds);
        final boolean finished = Math.hypot(velocityX, velocityY) * decay < stopVelocity;
        final double travelled = 1 - decay;
        deltaX = velocityX / friction * travelled - positionX;
        deltaY = velocityY / friction * travelled - positionY;
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
        while (!samples.isEmpty() && ((timeMs - samples.getFirst().timeMs) & UINT32_MASK) > velocityWindowMs) {
            samples.removeFirst();
        }
    }
}
