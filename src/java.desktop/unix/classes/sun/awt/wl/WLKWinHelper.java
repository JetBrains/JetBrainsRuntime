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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class WLKWinHelper {
    private static final PlatformLogger LOG = PlatformLogger.getLogger(WLKWinHelper.class.getName());

    private static final boolean enabled =
            Boolean.parseBoolean(System.getProperty("sun.awt.wl.UseKWinWindowLocation", "false"));
    private static final AtomicInteger windowIdCounter = new AtomicInteger(0);

    static boolean isEnabled() {
        return enabled;
    }

    static String generateAppID() {
        return enabled ? "jbr-kwin-window-" + windowIdCounter.incrementAndGet() : null;
    }

    private static final ConcurrentHashMap<String, Optional<Point>> locationCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Timer> getLocationTimers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Timer> setLocationTimers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Point> pendingSetLocation = new ConcurrentHashMap<>();
    private static final int DEBOUNCE_MS = 200;

    // Python script that connects to session DBus, registers a callback object,
    // loads a KWin script to find the window and report its position, then prints "x,y" to stdout.
    private static final String GET_LOCATION_PYTHON_SCRIPT = """
        import dbus, dbus.service, dbus.mainloop.glib, sys, tempfile, os
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
        finally:
            os.unlink(path)
        if result[0]:
            print(str(result[0][0]) + ',' + str(result[0][1]), flush=True)
        """;
    private static final Pattern POSITION_PATTERN = Pattern.compile("(\\d+),(\\d+)");

    private static final String SET_LOCATION_JS_SCRIPT = """
        var windows = workspace.windowList();
        for (var i = 0; i < windows.length; i++) {
            var w = windows[i];
            if (w.resourceClass === "%s") {
                var g = w.frameGeometry;
                w.frameGeometry = {x: %d, y: %d, width: g.width, height: g.height};
                break;
            }
        }
        """;
    private static final Pattern SCRIPT_ID_PATTERN = Pattern.compile("int32\\s+(\\d+)");

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
                    "python3", "-c", GET_LOCATION_PYTHON_SCRIPT, appId
            ).redirectErrorStream(true).start();
            try {
                String output;
                try (BufferedReader reader = process.inputReader()) {
                    output = reader.readAllAsString();
                }
                if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    Matcher m = POSITION_PATTERN.matcher(output);
                    if (m.find()) {
                        return new Point(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                    }
                }
            } finally {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            logWarning("KWin get location failed for appId=" + appId + ": " + e);
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
        String script = String.format(SET_LOCATION_JS_SCRIPT, appId, target.x, target.y);
        if (runKWinScript(script)) {
            locationCache.put(appId, Optional.of(target));
        } else {
            locationCache.put(appId, Optional.empty());
            logWarning("KWin script windowmove failed for appId=" + appId);
        }
    }

    private static boolean runKWinScript(String script) {
        Path scriptFile = null;
        try {
            scriptFile = Files.createTempFile("jbr-kwin-set-location", ".js");
            Files.writeString(scriptFile, script);
            String pluginName = scriptFile.getFileName().toString();

            Process load = new ProcessBuilder(
                    "dbus-send", "--session", "--dest=org.kde.KWin", "--print-reply",
                    "/Scripting", "org.kde.kwin.Scripting.loadScript",
                    "string:" + scriptFile.toAbsolutePath(), "string:" + pluginName
            ).redirectErrorStream(true).start();
            String loadOutput;
            try (BufferedReader reader = load.inputReader()) {
                loadOutput = reader.readAllAsString();
            }
            if (!(load.waitFor(2, TimeUnit.SECONDS) && load.exitValue() == 0)) {
                load.destroyForcibly();
                return false;
            }

            Matcher matcher = SCRIPT_ID_PATTERN.matcher(loadOutput);
            if (!matcher.find()) {
                return false;
            }
            String scriptId = matcher.group(1);

            Process run = new ProcessBuilder(
                    "dbus-send", "--session", "--dest=org.kde.KWin", "--print-reply",
                    "/Scripting/Script" + scriptId, "org.kde.kwin.Script.run"
            ).redirectErrorStream(true).start();
            if (!(run.waitFor(2, TimeUnit.SECONDS) && run.exitValue() == 0)) {
                run.destroyForcibly();
                return false;
            }

            Process unload = new ProcessBuilder(
                    "dbus-send", "--session", "--dest=org.kde.KWin", "--print-reply",
                    "/Scripting", "org.kde.kwin.Scripting.unloadScript",
                    "string:" + pluginName
            ).redirectErrorStream(true).start();
            if (!(unload.waitFor(2, TimeUnit.SECONDS) && unload.exitValue() == 0)) {
                unload.destroyForcibly();
            }

            return true;
        } catch (Exception e) {
            logWarning("KWin script execution failed: " + e);
            return false;
        } finally {
            if (scriptFile != null) {
                try {
                    Files.deleteIfExists(scriptFile);
                } catch (IOException ignored) {}
            }
        }
    }

    private static void logWarning(String msg) {
        if (LOG.isLoggable(PlatformLogger.Level.WARNING)) {
            LOG.warning(msg);
        }
    }
}
