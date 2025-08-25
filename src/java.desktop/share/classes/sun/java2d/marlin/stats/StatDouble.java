/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright 2025 JetBrains s.r.o.
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

package sun.java2d.marlin.stats;

import static sun.java2d.marlin.stats.StatLong.trimTo3Digits;

/**
 * Statistics on double values
 */
public final class StatDouble {
    // rolling mean weight (lerp):
    private final static double EMA_ALPHA = 0.25;
    private final static double EMA_ONE_MINUS_ALPHA = 1.0 - EMA_ALPHA;

    public final String name;
    private long count, lastLogCount;
    private double min, max, mean, ema_mean = 0.0, squaredError;

    public StatDouble(final String name) {
        this.name = name;
        reset();
    }

    public void reset() {
        count = 0L;
        lastLogCount = 0L;
        min = Double.POSITIVE_INFINITY;
        max = Double.NEGATIVE_INFINITY;
        mean = 0.0;
        // skip ema_mean = 0.0;
        squaredError = 0.0;
    }

    public void add(final double val) {
        count++;
        if (val < min) {
            min = val;
        }
        if (val > max) {
            max = val;
        }
        // Exponential smoothing (EMA):
        ema_mean = EMA_ALPHA * val + EMA_ONE_MINUS_ALPHA * ema_mean;
        // Welford's algorithm:
        final double oldMean = mean;
        mean += (val - mean) / count;
        squaredError += (val - mean) * (val - oldMean);
    }

    public boolean shouldLog() {
        return (count > lastLogCount);
    }

    public void updateLastLogCount() {
        this.lastLogCount = this.count;
    }

    public long count() {
        return count;
    }

    public double min() {
        return (count != 0L) ? min : Double.NaN;
    }

    public double max() {
        return (count != 0L) ? max : Double.NaN;
    }

    public double mean() {
        return (count != 0L) ? mean : Double.NaN;
    }

    public double ema() {
        return (count != 0L) ? ema_mean : Double.NaN;
    }

    public double variance() {
        return (count != 0L) ? (squaredError / (count - 1L)) : Double.NaN;
    }

    public double stddev() {
        return (count != 0L) ? Math.sqrt(variance()) : Double.NaN;
    }

    public double total() {
        return (count != 0L) ? (mean() * count) : Double.NaN;
    }

    @Override
    public String toString() {
        return toString(new StringBuilder(128)).toString();
    }

    public StringBuilder toString(final StringBuilder sb) {
        sb.append(name).append('[').append(count);
        sb.append("] sum: ").append(trimTo3Digits(total()));
        sb.append(" avg: ").append(trimTo3Digits(mean()));
        sb.append(" stddev: ").append(trimTo3Digits(stddev()));
        sb.append(" ema: ").append(trimTo3Digits(ema()));
        sb.append(" [").append(trimTo3Digits(min())).append(" - ").append(trimTo3Digits(max())).append("]");
        return sb;
    }
}
