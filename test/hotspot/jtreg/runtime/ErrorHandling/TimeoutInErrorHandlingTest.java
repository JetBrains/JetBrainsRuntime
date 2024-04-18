/*
 * Copyright (c) 2017, 2022 SAP SE. All rights reserved.
 * Copyright (c) 2023, Red Hat, Inc. and/or its affiliates.
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.Platform;
import jdk.test.lib.process.ProcessTools;

/*
 * @test id=default
 * @bug 8166944
 * @summary Hanging Error Reporting steps may lead to torn error logs
 * @modules java.base/jdk.internal.misc
 * @library /test/lib
 * @requires vm.flagless
 * @requires (vm.debug == true) & (os.family != "windows")
 * @run driver TimeoutInErrorHandlingTest
 * @author Thomas Stuefe (SAP)
 */

/*
 * @test id=with-on-error
 * @bug 8303861
 * @summary Error handling step timeouts should never be blocked by OnError etc.
 * @modules java.base/jdk.internal.misc
 * @library /test/lib
 * @requires vm.flagless
 * @requires (vm.debug == true) & (os.family != "windows")
 * @run driver TimeoutInErrorHandlingTest with-on-error
 */

public class TimeoutInErrorHandlingTest {

    public static final boolean verbose = System.getProperty("verbose") != null;
    // 16 seconds for hs_err generation timeout = 4 seconds per step timeout
    public static final int ERROR_LOG_TIMEOUT = 16;

    public static void main(String[] args) throws Exception {
        /* Start the VM and let it crash. Specify TestUnresponsiveErrorHandler which will
         * let five subsequent error reporting steps hang. The Timeout handling triggered
         * by the WatcherThread should kick in and interrupt those steps. In theory, the
         * text "timeout occurred during error reporting in step .." (the little timeouts)
         * should occur in the error log up to four times, followed by the final big timeout
         * "------ Timeout during error reporting after xx s. ------"
         *
         * Note that there are a number of uncertainties which make writing a 100% foolproof
         * test challenging. The time the error reporting thread takes to react to the
         * timeout triggers is unknown. So it is difficult to predict how many little timeouts
         * will be visible before the big timeout kicks in. Also, once the big timeout hits,
         * error reporting thread and Watcherthread will race. The former writes his last
         * message to the error logs and flushes, the latter waits 200ms and then exits the
         * process without further synchronization with the error reporting thread.
         *
         * Because of all this and the desire to write a bullet proof test which does
         * not fail sporadically, we will not test for the final timeout message nor for all
         * of the optimally expected little timeout messages. We just test for two of the
         * little timeout messages to see that repeated timeout handling is basically working.
         */

        boolean withOnError = false;

        if (args.length > 0) {
            switch (args[0]) {
                case "with-on-error": withOnError = true; break;
                default: throw new RuntimeException("Invalid argument " + args[1]);
            }
        }

        List<String> arguments = new ArrayList<>();
        Collections.addAll(arguments,
                "-XX:+UnlockDiagnosticVMOptions",
                "-Xmx100M",
                "-XX:ErrorHandlerTest=14",
                "-XX:+TestUnresponsiveErrorHandler",
                "-XX:ErrorLogTimeout=" + ERROR_LOG_TIMEOUT,
                "-XX:-CreateCoredumpOnCrash");
        if (withOnError) {
            arguments.add("-XX:OnError=echo hi");
        }
        arguments.add("-version");
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(arguments);

        OutputAnalyzer output_detail = new OutputAnalyzer(pb.start());

        if (verbose) {
            System.err.println("<begin cmd output>");
            System.err.println(output_detail.getOutput());
            System.err.println("<end cmd output>");
        }

        // we should have crashed with a SIGSEGV
        output_detail.shouldMatch("# A fatal error has been detected by the Java Runtime Environment:.*");
        output_detail.shouldMatch("# +(?:SIGSEGV|SIGBUS|EXCEPTION_ACCESS_VIOLATION).*");

        // Unless we specified OnError, VM should have been aborted by WatcherThread
        if (!withOnError) {
            output_detail.shouldMatch(".*timer expired, abort.*");
        }

        // extract hs-err file
        File hs_err_file;
        try {
            hs_err_file = HsErrFileUtils.openHsErrFileFromOutput(output_detail);
        } catch (Exception e) {
            if (!verbose) {
                System.err.println("<begin cmd output>");
                System.err.println(output_detail.getOutput());
                System.err.println("<end cmd output>");
            }
            throw e;
        }

        System.out.println("Found hs_err file. Scanning...");

        Pattern [] pattern = new Pattern[] {
            Pattern.compile(".*timeout occurred during error reporting in step.*"),
            Pattern.compile(".*timeout occurred during error reporting in step.*")
        };

        // Note: we *dont* check for the end marker, since the hs-err file will likely not have
        // one but a global timeout marker. As explained above, we don't check for that one either
        // since it is too instable.
        HsErrFileUtils.checkHsErrFileContent(hs_err_file, pattern, null, false /* check end marker */,true /* verbose */);

        System.out.println("OK.");

    }

}
