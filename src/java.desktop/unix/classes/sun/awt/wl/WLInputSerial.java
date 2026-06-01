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

/**
 * Groups information about a serial number of a Wayland input event.
 * A stale serial could still be useful if there's no fresh serial to replace it.
 */
public record WLInputSerial(long serial, boolean isFresh) {
    public static final WLInputSerial INVALID = new WLInputSerial();

    public WLInputSerial() {
        this(-1, false);
    }

    public WLInputSerial(long serial) {
        this(serial, serial != -1);
    }

    public boolean isStale() {
        return !isFresh;
    }

    public boolean isValid() {
        return serial != -1;
    }

    public WLInputSerial makeStale() {
        return new WLInputSerial(serial, false);
    }

    public WLInputSerial freshOrElse(WLInputSerial fallback) {
        if (!fallback.isValid()) {
            return this;
        }
        return isFresh ? this : fallback;
    }

    public boolean isNewerThan(WLInputSerial compare) {
        if (!isValid()) {
            return false;
        }

        if (!compare.isValid()) {
            return true;
        }

        // a serial can theoretically wrap around its uint32 range
        long delta = ((1L << 32) + serial - compare.serial) % (1L << 32);

        // no wrap around, serial > compare.serial
        return 0 < delta && delta < (1L << 31);
    }

    public WLInputSerial newerOrElse(WLInputSerial compare, WLInputSerial fallback) {
        if (isNewerThan(compare)) {
            return this;
        } else {
            return fallback;
        }
    }
}
