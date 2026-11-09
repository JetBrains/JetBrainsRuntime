/*
 * Copyright 2026 JetBrains s.r.o.
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

/*
 * @test
 * @summary Regression test for JBR-10654: Skia runs on Dawn over Direct3D 11 unless
 *          sun.java2d.skia.dawn.api=d3d12 asks Dawn to try Direct3D 12 first.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaDawnBackendTest D3D11
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.dawn.api=d3d11 SkiaDawnBackendTest D3D11
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.dawn.api=d3d12 SkiaDawnBackendTest D3D12
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jtreg.SkippedException;
import sun.java2d.skia.SKEnvironment;

/**
 * Direct3D 12 is a triage option: Chrome ships WebGPU on Dawn's D3D12 backend, Skia tests
 * Graphite on D3D11 first. Without Dawn there is nothing to test. Dawn falls back to the other
 * Direct3D version where the one asked for has no adapter; with Dawn itself unavailable
 * SKGraphicsAPIFallback must say why it went on to Vulkan (sun.java2d.skia.verbose,
 * {@link PipelineComparison#requireAPI}), else the fallback would skip this test silently;
 * {@link SKEnvironment#runningOn} names
 * the version that runs ("(D3D12)"), and the fallback is told apart from a defect by Dawn's own
 * report in the Java2D trace of a child JVM (J2D_TRACE_LEVEL, "DawnSK: no D3D12 adapter"): only
 * then is there nothing to test; Direct3D 12 with an adapter that did not run fails.
 */
public class SkiaDawnBackendTest {

    /** J2D_TRACE_LEVEL at which the Dawn platform reports a missing adapter: J2D_TRACE_INFO (Trace.h). */
    private static final String TRACE_LEVEL_INFO = "3";

    /** What the Dawn platform traces when Direct3D 12 has no adapter (DawnSKPlatform.cpp). */
    private static final String NO_D3D12_ADAPTER = "DawnSK: no D3D12 adapter";

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "child" -> System.out.println("on " + SKEnvironment.runningOn());
            case "D3D11" -> {
                // the verbose report tells a Dawn that fell back to Vulkan apart from one that never ran
                String verbose = PipelineComparison.startCapturingVerbose();
                PipelineComparison.requireAPI("Dawn", "D3D11", verbose);
                System.out.println("PASSED");
            }
            case "D3D12" -> checkDirect3D12InChild();
            default -> throw new IllegalArgumentException(mode);
        }
    }

    private static void checkDirect3D12InChild() throws Exception {
        PipelineComparison.requireAPI("Dawn", null, null);
        Path trace = Path.of("dawn-backend-trace.txt").toAbsolutePath();
        ProcessBuilder processBuilder = ProcessTools.createTestJavaProcessBuilder(PipelineComparison.skiaChildCommand(
                List.of("-Dsun.java2d.skia.pipelineCache=off", "-Dsun.java2d.skia.dawn.api=d3d12"),
                SkiaDawnBackendTest.class, "child"));
        processBuilder.environment().put("J2D_TRACE_LEVEL", TRACE_LEVEL_INFO);
        processBuilder.environment().put("J2D_TRACE_FILE", trace.toString());
        OutputAnalyzer output = ProcessTools.executeProcess(processBuilder);
        output.shouldHaveExitValue(0);
        String childRunningOn = output.firstMatch("on (.*)", 1);
        String traced = Files.exists(trace) ? Files.readString(trace) : "";
        System.out.println("child: " + childRunningOn + "\ntrace:\n" + traced);
        if (childRunningOn != null && childRunningOn.contains(" Graphite/Dawn on ") && childRunningOn.endsWith("(D3D12)")) {
            System.out.println("PASSED");
            return;
        }
        if (traced.contains(NO_D3D12_ADAPTER)) {
            throw new SkippedException("no Direct3D 12 adapter here (" + childRunningOn + "): nothing to test");
        }
        throw new RuntimeException("Direct3D 12 was asked for and Dawn did not report it missing, yet Skia runs on "
                + childRunningOn);
    }
}
