/*
 * Copyright 2000-2026 JetBrains s.r.o.
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

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * bug JBR-10240
 * @summary Regression test for JBR-10240: verifies that JBR does not excessively poll
 *          xdg-desktop-portal Settings.Read(color-scheme) via D-Bus.
 *          The SystemPropertyWatcher thread should use signal-based notifications
 *          (SettingChanged) instead of polling every second.
 * @requires (os.family == "linux")
 * @run main/othervm/timeout=30 ColorSchemePollingTest
 */
public class ColorSchemePollingTest {

    /**
     * Duration in seconds to monitor D-Bus traffic.
     * Must be long enough to capture multiple poll cycles if polling at 1/sec.
     */
    private static final int MONITOR_DURATION_SEC = 6;

    /**
     * Maximum number of Settings.Read calls allowed during the monitoring window.
     * A signal-based implementation should produce at most 1 call (the initial read).
     * The old 1-second polling loop would produce ~6 calls in 6 seconds.
     * We use 2 as the threshold to allow for the initial read + one possible
     * fallback poll, while catching the 1/sec polling pattern.
     */
    private static final int MAX_ALLOWED_READS = 2;

    public static void main(String[] args) throws Exception {
        // Verify dbus-monitor is available
        if (!isDbusMonitorAvailable()) {
            System.out.println("dbus-monitor not found, skipping test");
            return;
        }

        // Verify D-Bus session is available
        String dbusAddr = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (dbusAddr == null || dbusAddr.isEmpty()) {
            System.out.println("DBUS_SESSION_BUS_ADDRESS not set, skipping test");
            return;
        }

        // Initialize the Toolkit, which triggers initSystemPropertyWatcher()
        // and starts the SystemPropertyWatcher daemon thread.
        Toolkit toolkit = Toolkit.getDefaultToolkit();

        // Give the watcher thread a moment to start and make its first call
        Thread.sleep(2000);

        // Verify the toolkit detected D-Bus support (property should be set)
        Boolean isDark = (Boolean) toolkit.getDesktopProperty("awt.os.theme.isDark");
        if (isDark == null) {
            // D-Bus detection is not active (no portal running, or dbus disabled).
            // The test is not applicable in this environment.
            System.out.println("awt.os.theme.isDark is null — D-Bus theme detection not active, skipping test");
            return;
        }
        System.out.println("Theme detection active, awt.os.theme.isDark = " + isDark);

        // Start dbus-monitor to capture Settings.Read method calls to the portal
        ProcessBuilder pb = new ProcessBuilder(
                "dbus-monitor",
                "--session",
                "--monitor",
                "type='method_call',"
                        + "interface='org.freedesktop.portal.Settings',"
                        + "member='Read',"
                        + "destination='org.freedesktop.portal.Desktop'"
        );
        pb.redirectErrorStream(true);
        Process monitor = pb.start();

        // Drain the output in a background thread to avoid blocking and to
        // capture all data before the process is destroyed.
        StringBuilder outputBuilder = new StringBuilder();
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(monitor.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append('\n');
                }
            } catch (IOException e) {
                // Expected when process is destroyed — stream closes
            }
        }, "dbus-monitor-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // Let dbus-monitor capture traffic for the monitoring duration
        System.out.println("Monitoring D-Bus for " + MONITOR_DURATION_SEC + " seconds...");
        Thread.sleep(MONITOR_DURATION_SEC * 1000L);

        // Stop dbus-monitor and wait for the reader thread to finish
        monitor.destroyForcibly();
        monitor.waitFor(5, TimeUnit.SECONDS);
        readerThread.join(3000);

        String output = outputBuilder.toString();

        // Count Settings.Read method calls.
        // dbus-monitor outputs each method call as a block starting with
        // "method call" and containing the interface/member info.
        // We count lines that indicate a method_call to Settings.Read.
        int readCallCount = 0;
        for (String line : output.split("\n")) {
            if (line.contains("member=Read") && line.contains("interface=org.freedesktop.portal.Settings")) {
                readCallCount++;
            }
        }

        System.out.println("dbus-monitor output:\n" + output);
        System.out.println("Settings.Read call count in " + MONITOR_DURATION_SEC + "s window: " + readCallCount);
        System.out.println("Maximum allowed: " + MAX_ALLOWED_READS);

        if (readCallCount > MAX_ALLOWED_READS) {
            throw new RuntimeException(
                    "Test FAILED (JBR-10240): detected " + readCallCount
                            + " Settings.Read D-Bus calls in " + MONITOR_DURATION_SEC + "s"
                            + " (max allowed: " + MAX_ALLOWED_READS + ")."
                            + " The SystemPropertyWatcher is polling instead of using SettingChanged signals.");
        }

        System.out.println("Test PASSED: Settings.Read call count (" + readCallCount
                + ") is within acceptable limit (" + MAX_ALLOWED_READS + ").");
    }

    private static boolean isDbusMonitorAvailable() {
        try {
            Process p = new ProcessBuilder("which", "dbus-monitor")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
