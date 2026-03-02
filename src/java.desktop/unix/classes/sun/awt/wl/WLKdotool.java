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

import sun.util.logging.PlatformLogger;

import javax.swing.Timer;
import java.awt.Point;
import java.io.BufferedReader;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class WLKdotool {
    private static final PlatformLogger LOG = PlatformLogger.getLogger(WLKdotool.class.getName());
    private static final Pattern POSITION_PATTERN = Pattern.compile("Position:\\s*([\\d.]+),([\\d.]+)");

    private static final int DEBOUNCE_MS = 200;

    private static final ConcurrentHashMap<String, Optional<Point>> locationCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Timer> getLocationTimers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Timer> setLocationTimers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Point> pendingSetLocation = new ConcurrentHashMap<>();

    static Point getWindowLocation(String appId) {
        Timer timer = getLocationTimers.computeIfAbsent(appId, id -> {
            Timer t = new Timer(DEBOUNCE_MS, e -> queryAndCacheWindowLocation(id));
            t.setRepeats(false);
            return t;
        });
        timer.restart();

        Optional<Point> cached = locationCache.get(appId);
        if (cached != null) {
            return cached.orElse(null);
        }
        // First call for this appId — must query synchronously.
        return queryAndCacheWindowLocation(appId);
    }

    private static Point queryAndCacheWindowLocation(String appId) {
        Point location = queryWindowLocation(appId);
        locationCache.put(appId, Optional.ofNullable(location));
        return location;
    }

    private static Point queryWindowLocation(String appId) {
        try {
            Process process = new ProcessBuilder(
                    "kdotool", "search", "--class", "^" + appId + "$", "getwindowgeometry"
            ).redirectErrorStream(true).start();
            try (BufferedReader reader = process.inputReader()) {
                if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = POSITION_PATTERN.matcher(line);
                        if (m.find()) {
                            int x = Math.round(Float.parseFloat(m.group(1)));
                            int y = Math.round(Float.parseFloat(m.group(2)));
                            return new Point(x, y);
                        }
                    }
                }
            } finally {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            logWarning("kdotool failed for appId=" + appId + ": " + e);
        }
        return null;
    }

    static void setWindowLocation(String appId, int x, int y) {
        Point target = new Point(x, y);
        pendingSetLocation.put(appId, target);
        // Cache the value so that 'get location' calls see it immediately.
        // Note that this might in theory create a situation when 'get location' that came later
        // is executed before 'set location'. As a result, we would see outdated location.
        locationCache.put(appId, Optional.of(target));

        Timer timer = setLocationTimers.computeIfAbsent(appId, id -> {
            Timer t = new Timer(DEBOUNCE_MS, e -> flushSetWindowLocation(id));
            t.setRepeats(false);
            return t;
        });
        timer.restart();
    }

    private static void flushSetWindowLocation(String appId) {
        Point target = pendingSetLocation.remove(appId);
        try {
            Process process = new ProcessBuilder(
                    "kdotool", "search", "--class", "^" + appId + "$", "windowmove", String.valueOf(target.x), String.valueOf(target.y)
            ).redirectErrorStream(true).start();
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) {
                locationCache.put(appId, Optional.of(target));
            } else {
                locationCache.put(appId, Optional.empty());
                logWarning("kdotool windowmove failed for appId=" + appId + ", exit=" + process.exitValue());
            }
            process.destroyForcibly();
        } catch (Exception e) {
            logWarning("kdotool windowmove failed for appId=" + appId + ": " + e);
        }
    }

    private static void logWarning(String msg) {
        if (LOG.isLoggable(PlatformLogger.Level.WARNING)) {
            LOG.warning(msg);
        }
    }
}
