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

import jdk.internal.misc.InnocuousThread;
import sun.util.logging.PlatformLogger;

import javax.swing.Timer;
import java.awt.Point;
import java.io.BufferedReader;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class WLKWinHelper {
    private static final PlatformLogger LOG = PlatformLogger.getLogger(WLKWinHelper.class.getName());

    private static final AtomicInteger windowIdCounter = new AtomicInteger((int) System.currentTimeMillis());

    static String generateAppID() {
        return "jbr-kwin-window-" + windowIdCounter.incrementAndGet();
    }

    private static final ConcurrentHashMap<String, Optional<Point>> locationCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Timer> getLocationTimers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Timer> setLocationTimers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Point> pendingSetLocation = new ConcurrentHashMap<>();
    private static final int DEBOUNCE_MS = 200;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = InnocuousThread.newThread("WLKWinHelper", r);
        t.setDaemon(true);
        return t;
    });

    // Python script that connects to session DBus, registers a callback object,
    // loads a KWin script to find the window and report its position, then prints "x,y" to stdout.
    private static final String GET_LOCATION_PYTHON_SCRIPT = """
        import dbus, dbus.service, dbus.mainloop.glib, sys, tempfile, os, traceback
        from gi.repository import GLib

        app_id = sys.argv[1]

        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        bus = dbus.SessionBus()
        my_name = bus.get_unique_name()

        loop = GLib.MainLoop()

        result = [None]
        class H(dbus.service.Object):
            @dbus.service.method('com.jbr.KWinHelper', in_signature='sdd', out_signature='')
            def ReportLocation(self, aid, x, y):
                result[0] = (int(x), int(y))
                loop.quit()
        _ = H(bus, '/p')

        js = '''
        var windows = workspace.windowList();
        for (var i = 0; i < windows.length; i++) {
            var w = windows[i];
            if (w.resourceClass === "__APP_ID__") {
                var g = w.frameGeometry;
                callDBus("__BUS_NAME__", "/p", "com.jbr.KWinHelper", "ReportLocation", "__APP_ID__", g.x, g.y);
                break;
            }
        }
        '''
        js = js.replace('__BUS_NAME__', my_name).replace('__APP_ID__', app_id)
        fd, path = tempfile.mkstemp(suffix='.js', prefix='jbr-kwin-get-location-')
        os.write(fd, js.encode())
        os.close(fd)

        try:
            kwin = dbus.Interface(bus.get_object('org.kde.KWin', '/Scripting'), 'org.kde.kwin.Scripting')
            plugin = 'jbr-kwin-location-' + str(os.getpid())
            sid = kwin.loadScript(path, plugin, signature='ss')
            dbus.Interface(bus.get_object('org.kde.KWin', '/Scripting/Script' + str(sid)), 'org.kde.kwin.Script').run()
            GLib.timeout_add(2000, loop.quit)
            loop.run()
            kwin.unloadScript(plugin)
        except Exception:
            print("PYTHON_ERROR", flush=True)
            traceback.print_exc()
        finally:
            os.unlink(path)
        if result[0]:
            print(str(result[0][0]) + ',' + str(result[0][1]), flush=True)
        """;
    private static final Pattern POSITION_PATTERN = Pattern.compile("(\\d+),(\\d+)");

    private static final String SET_LOCATION_PYTHON_SCRIPT = """
        import dbus, dbus.service, dbus.mainloop.glib, sys, tempfile, os, traceback
        from gi.repository import GLib

        app_id = sys.argv[1]
        target_x = sys.argv[2]
        target_y = sys.argv[3]

        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        bus = dbus.SessionBus()
        my_name = bus.get_unique_name()

        loop = GLib.MainLoop()

        result = [None]
        class H(dbus.service.Object):
            @dbus.service.method('com.jbr.KWinHelper', in_signature='s', out_signature='')
            def ReportResult(self, msg):
                result[0] = msg
                loop.quit()
        _ = H(bus, '/p')

        js = '''
         var windows = workspace.windowList();
         var found = false;
         for (var i = 0; i < windows.length; i++) {
             var w = windows[i];
             if (w.resourceClass === "__APP_ID__") {
                 var g = w.frameGeometry;
                 w.frameGeometry = {x: __X__, y: __Y__, width: g.width, height: g.height};
                 found = true;
                 break;
             }
         }
         if (found) {
             callDBus("__BUS_NAME__", "/p", "com.jbr.KWinHelper", "ReportResult", "OK");
         }
        '''
        js = js.replace('__BUS_NAME__', my_name).replace('__APP_ID__', app_id)
        js = js.replace('__X__', target_x).replace('__Y__', target_y)
        fd, path = tempfile.mkstemp(suffix='.js', prefix='jbr-kwin-set-location-')
        os.write(fd, js.encode())
        os.close(fd)

        try:
            kwin = dbus.Interface(bus.get_object('org.kde.KWin', '/Scripting'), 'org.kde.kwin.Scripting')
            plugin = 'jbr-kwin-set-location-' + str(os.getpid())
            sid = kwin.loadScript(path, plugin, signature='ss')
            dbus.Interface(bus.get_object('org.kde.KWin', '/Scripting/Script' + str(sid)), 'org.kde.kwin.Script').run()
            GLib.timeout_add(2000, loop.quit)
            loop.run()
            kwin.unloadScript(plugin)
        except Exception:
            print("PYTHON_ERROR", flush=True)
            traceback.print_exc()
        finally:
            os.unlink(path)
        if result[0]:
            print(result[0], flush=True)
        """;

    static Point getWindowLocation(String appId) {
        Timer timer = getLocationTimers.computeIfAbsent(appId, id -> {
            Timer t = new Timer(DEBOUNCE_MS, e -> executor.submit(() -> queryAndCacheWindowLocation(id)));
            t.setRepeats(false);
            return t;
        });
        timer.restart();

        Optional<Point> cached = locationCache.get(appId);
        if (cached != null) {
            Point result = cached.map(Point::new).orElse(null);
            logFine("getWindowLocation appId=" + appId + " cached=" + result);
            return result;
        }
        // First call for this appId — must query synchronously.
        logFine("getWindowLocation appId=" + appId + " cache miss, querying synchronously");
        Point location = queryAndCacheWindowLocation(appId);
        return location != null ? new Point(location) : null;
    }

    private static Point queryAndCacheWindowLocation(String appId) {
        Point location = queryWindowLocation(appId);
        locationCache.put(appId, Optional.ofNullable(location));
        return location;
    }

    private static Point queryWindowLocation(String appId) {
        logFine("queryWindowLocation appId=" + appId);
        try {
            Process process = new ProcessBuilder(
                    "python3", "-c", GET_LOCATION_PYTHON_SCRIPT, appId
            ).redirectErrorStream(true).start();
            try {
                String output;
                try (BufferedReader reader = process.inputReader()) {
                    output = reader.readAllAsString();
                }
                if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    logFine("queryWindowLocation output: " + output);
                    Matcher m = POSITION_PATTERN.matcher(output);
                    if (m.find()) {
                        Point p = new Point(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                        logFine("queryWindowLocation result=" + p.x + "," + p.y);
                        return p;
                    } else {
                        logWarning("queryWindowLocation: no position in output for appId=" + appId + ": " + output);
                    }
                } else {
                    logWarning("queryWindowLocation: process failed for appId=" + appId + " exit=" + process.exitValue());
                }
            } finally {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            logWarning("queryWindowLocation failed for appId=" + appId + ": " + e);
        }
        return null;
    }

    static void setWindowLocation(String appId, int x, int y) {
        Point target = new Point(x, y);
        logFine("setWindowLocation appId=" + appId + " target=" + x + "," + y);
        pendingSetLocation.put(appId, target);
        // Cache the value so that 'get location' calls see it immediately.
        // Note that this might in theory create a situation when 'get location' that came later
        // is executed before 'set location'. As a result, we would see outdated location.
        locationCache.put(appId, Optional.of(target));

        Timer timer = setLocationTimers.computeIfAbsent(appId, id -> {
            Timer t = new Timer(DEBOUNCE_MS, e -> executor.submit(() -> flushSetWindowLocation(id)));
            t.setRepeats(false);
            return t;
        });
        timer.restart();
    }

    private static void flushSetWindowLocation(String appId) {
        Point target = pendingSetLocation.remove(appId);
        logFine("flushSetWindowLocation appId=" + appId + " target=" + target);
        if (target == null) {
            logWarning("flushSetWindowLocation: no pending target for appId=" + appId);
            return;
        }
        try {
            Process process = new ProcessBuilder(
                    "python3", "-c", SET_LOCATION_PYTHON_SCRIPT,
                    appId, String.valueOf(target.x), String.valueOf(target.y)
            ).redirectErrorStream(true).start();
            try {
                String output;
                try (BufferedReader reader = process.inputReader()) {
                    output = reader.readAllAsString();
                }
                if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 && output.contains("OK")) {
                    logFine("setLocation succeeded for appId=" + appId + " target=" + target.x + "," + target.y);
                    locationCache.put(appId, Optional.of(target));
                } else {
                    logWarning("setLocation failed for appId=" + appId + " target=" + target.x + "," + target.y + ": " + output);
                    locationCache.put(appId, Optional.empty());
                }
            } finally {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            logWarning("setLocation failed for appId=" + appId + ": " + e);
            locationCache.put(appId, Optional.empty());
        }
    }

    private static void logFine(String msg) {
        if (LOG.isLoggable(PlatformLogger.Level.FINE)) {
            LOG.fine(msg);
        }
    }

    private static void logWarning(String msg) {
        if (LOG.isLoggable(PlatformLogger.Level.WARNING)) {
            LOG.warning(msg);
        }
    }
}
