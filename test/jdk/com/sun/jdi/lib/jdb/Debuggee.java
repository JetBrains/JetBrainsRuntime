/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
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

package lib.jdb;

import jdk.test.lib.Utils;
import jdk.test.lib.process.ProcessTools;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Helper class to run java debuggee and parse agent listening transport/address.
 * Usage:
 *   1)
 *      Debugee debugee = Debuggee.launcher("MyClass").setTransport("dt_shmem").launch();
 *      try {
 *          String transport = debuggee.getTransport();
 *          String addr = debuggee.getAddress();
 *      } finally {
 *          debuggee.shutdown();
 *      }
 *   2) (using try-with-resource)
 *      try (Debugee debugee = Debuggee.launcher("MyClass").launch()) {
 *          String transport = debuggee.getTransport();
 *          String addr = debuggee.getAddress();
 *      }
 *   3)
 *      ProcessBuilder pb = Debuggee.launcher("MyClass").setSuspended(false).prepare();
 *      ProcessTools.executeProcess(pb);
 */
public class Debuggee implements Closeable {

    public static Launcher launcher(String mainClass) {
        return new Launcher(mainClass);
    }

    public static class Launcher {
        private final String mainClass;
        private final List<String> options = new LinkedList<>();
        private String vmOptions = null;
        private String transport = "dt_socket";
        private String address = null;
        private boolean suspended = true;
        private String onthrow = "";
        private boolean waitForPortPrint = true;
        private String expectedOutputBeforeThrow = "";

        private Launcher(String mainClass) {
            this.mainClass = mainClass;
        }
        public Launcher addOption(String option) {
            options.add(option);
            return this;
        }
        public Launcher addOptions(List<String> options) {
            this.options.addAll(options);
            return this;
        }
        public Launcher addVMOptions(String vmOptions) {
            this.vmOptions = vmOptions;
            return this;
        }
        // default is "dt_socket"
        public Launcher setTransport(String value) {
            transport = value;
            return this;
        }
        // default is "null" (auto-generate)
        public Launcher setAddress(String value) {
            address = value;
            return this;
        }
        // default is "true"
        public Launcher setSuspended(boolean value) {
            suspended = value;
            return this;
        }

        // required to pass non null port with address and emit string before the throw
        public Launcher enableOnThrow(String value, String expectedOutputBeforeThrow) {
            this.onthrow = value;
            this.waitForPortPrint = false;
            this.expectedOutputBeforeThrow = expectedOutputBeforeThrow;
            return this;
        }

        public ProcessBuilder prepare() {
            List<String> debuggeeArgs = new LinkedList<>();
            if (vmOptions != null) {
                debuggeeArgs.add(vmOptions);
            }
            String onthrowArgs = onthrow.isEmpty() ? "" : ",onthrow=" + onthrow + ",launch=exit";
            debuggeeArgs.add("-agentlib:jdwp=transport=" + transport
                    + (address == null ? "" : ",address=" + address)
                    + ",server=y,suspend=" + (suspended ? "y" : "n")
                    + onthrowArgs);
            debuggeeArgs.addAll(options);
            debuggeeArgs.add(mainClass);
            return ProcessTools.createTestJvm(debuggeeArgs);
        }

        public Debuggee launch(String name) {
            return new Debuggee(prepare(), name, waitForPortPrint, expectedOutputBeforeThrow);
        }
        public Debuggee launch() {
            return launch("debuggee");
        }
    }

    // starts the process, waits for "Listening for transport" output and detects transport/address
    private Debuggee(ProcessBuilder pb, String name, boolean waitForPortPrint, String expectedOutputBeforeThrow) {
        // debuggeeListen[0] - transport, debuggeeListen[1] - address
        String[] debuggeeListen = new String[2];
        Pattern listenRegexp = Pattern.compile("Listening for transport \\b(.+)\\b at address: \\b(.+)\\b");
        if (!waitForPortPrint) {
            try {
                p = ProcessTools.startProcess(name, pb, s -> {output.add(s);}, s -> {
                    return s.equals(expectedOutputBeforeThrow);
                }, 30, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException | TimeoutException ex) {
                throw new RuntimeException("failed to launch debuggee", ex);
            }
            transport = null;
            address = null;
            return;
        }
        try {
            p = ProcessTools.startProcess(name, pb,
                    s -> output.add(s),  // output consumer
                    s -> {  // warm-up predicate
                        Matcher m = listenRegexp.matcher(s);
                        if (!m.matches()) {
                            return false;
                        }
                        debuggeeListen[0] = m.group(1);
                        debuggeeListen[1] = m.group(2);
                        return true;
                    },
                    30, TimeUnit.SECONDS);
            transport = debuggeeListen[0];
            address = debuggeeListen[1];
        } catch (IOException | InterruptedException | TimeoutException ex) {
            throw new RuntimeException("failed to launch debuggee", ex);
        }
    }

    private final Process p;
    private final List<String> output = new LinkedList<>();
    private final String transport;
    private final String address;

    public void shutdown() {
        try {
            close();
        } catch (IOException ex) {
            // ignore
        }
    }

    // waits until the process shutdown or crash
    public boolean waitFor(long timeout, TimeUnit unit) {
        try {
            return p.waitFor(Utils.adjustTimeout(timeout), unit);
        } catch (InterruptedException e) {
            return false;
        }
    }

    // returns the whole debuggee output as a string
    public String getOutput() {
        return output.stream().collect(Collectors.joining(Utils.NEW_LINE));
    }

    String getTransport() {
        if (transport == null) {
            throw new IllegalStateException("transport is not available");
        }
        return transport;
    }

    public String getAddress() {
        if (address == null) {
            throw new IllegalStateException("address is not available");
        }
        return address;
    }

    @Override
    public void close() throws IOException {
        if (p.isAlive()) {
            p.destroy();
        }
    }

}
