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
 * @summary Regression test for JBR-10654: the converter of Chromium's GPU lists into the Skia
 *          backend's converts small fixtures as expected and stops where it cannot.
 * @library /test/lib
 * @run main SkiaGPUListConverterTest
 */

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jtreg.SkippedException;

/**
 * The converter (jb/project/tools/common/skia-gpu-lists/GPUListConverter.java, run by
 * update_gpu_lists.sh with every Skia bump) on small fixtures: a fingerprint names the definition
 * of exactly that symbol (not one whose name ends with it, not a call site; a symbol defined twice
 * stops the conversion); an exception that converts to no condition stops it (Chromium would void
 * the entry on every Windows machine); an exception for any OS is kept; a version object may carry
 * a comment; an OS version with a nonzero fourth segment and an entry with both devices and an
 * Intel generation stop it. No GPU needed.
 */
public class SkiaGPUListConverterTest {

    /** A fixture in the shape of gpu_control_list.cc: two definitions whose names end alike, and a call site. */
    private static final String SOURCE = """
            // a fixture in the shape of gpu_control_list.cc
            bool GpuControlList::IntelConditions::Contains(
                int candidates) const {
              return candidates > 0;
            }

            void Caller() {
              GpuControlList::Conditions::Contains(1, 2);
            }

            bool GpuControlList::Conditions::Contains(int os,
                                                      int gpu) const {
              return os == gpu;
            }
            """;

    /** The first line of the definition the fingerprint names, and the line that ends it. */
    private static final String DEFINITION_FIRST_LINE = "bool GpuControlList::Conditions::Contains(int os,";
    private static final String DEFINITION_LAST_LINE = "}";

    /** A software_rendering_list entry for Windows and one vendor, without its closing brace. */
    private static final String ENTRY = "{\"id\": 1, \"description\": \"d\", \"os\": {\"type\": \"win\"}, \"vendor_id\": \"0x10de\","
                                        + " \"features\": [\"all\"]";

    private static final List<String> failures = new ArrayList<>();
    private static Path converter;
    private static Path header;

    public static void main(String[] args) throws Exception {
        Path sourceRoot = Path.of(System.getProperty("test.root"), "..", "..").normalize();
        converter = sourceRoot.resolve("jb/project/tools/common/skia-gpu-lists/GPUListConverter.java");
        header = sourceRoot.resolve("jb/project/tools/common/skia-gpu-lists/SKGPUListData.header");
        if (!Files.exists(converter)) {
            throw new SkippedException("no " + converter + " (not a JBR source tree): nothing to test");
        }

        // the fingerprint of Conditions::Contains is that function's, not IntelConditions::Contains's
        String fingerprint = "chromium:src.cc:Conditions::Contains "
                             + sha256(lines(SOURCE, DEFINITION_FIRST_LINE, DEFINITION_LAST_LINE));
        expectConverted("anchored fingerprint", SOURCE, fingerprint, ENTRY + "}",
                        "entry chromium/software_rendering_list#1");
        expectStopped("symbol defined twice", "more than once",
                      SOURCE + "bool GpuControlList::Conditions::Contains(int x) const {\n  return true;\n}\n",
                      fingerprint, ENTRY + "}");
        // an exception that converts to no condition
        expectStopped("empty exception", "no condition", SOURCE, fingerprint,
                      ENTRY + ", \"exceptions\": [{\"multi_gpu_category\": \"active\"}]}");
        // an exception for any OS applies on Windows too
        expectConverted("exception for any OS", SOURCE, fingerprint,
                        ENTRY + ", \"exceptions\": [{\"os\": {\"type\": \"any\"}, \"device_id\": [\"0x1234\"]}]}",
                        "except vendor 0x10de ; devices 0x1234");
        // a comment in a version object (Chromium's lists have them)
        expectConverted("version comment", SOURCE, fingerprint,
                        ENTRY + ", \"driver_version\": {\"op\": \"<\", \"value\": \"8.17\", \"comment\": \"c\"}}",
                        "driver < 8.17");
        // Chromium's OS versions have four segments, the matcher's three
        expectStopped("four-segment OS version", "fourth segment", SOURCE, fingerprint,
                      "{\"id\": 2, \"os\": {\"type\": \"win\", \"version\": {\"op\": \"=\", \"value\": \"10.0.19045.3803\"}},"
                      + " \"features\": [\"all\"]}");
        // devices and an Intel generation together: the devices would be dead
        expectStopped("devices with Intel generation", "intel_gpu_generation", SOURCE, fingerprint,
                      "{\"id\": 3, \"os\": {\"type\": \"win\"}, \"vendor_id\": \"0x8086\", \"device_id\": [\"0x0a16\"],"
                      + " \"intel_gpu_generation\": {\"op\": \"<=\", \"value\": \"8\"}, \"features\": [\"all\"]}");
        // the policy and the command line refuse what they cannot read, with a message, not a stack trace
        expectStoppedByPolicy("map line without APIs", "policy.conf", SOURCE, fingerprint,
                              "map software_rendering_list all\nfingerprint " + fingerprint + "\n");
        expectStoppedByPolicy("ignore line without a feature", "policy.conf", SOURCE, fingerprint,
                              "ignore software_rendering_list\nfingerprint " + fingerprint + "\n");
        expectStoppedByPolicy("exclude line without a reason", "policy.conf", SOURCE, fingerprint,
                              "exclude software_rendering_list 1\nmap software_rendering_list all dawn\nfingerprint "
                              + fingerprint + "\n");
        expectStoppedByArguments("unpaired argument", "argument", SOURCE, fingerprint, "--skia-tag");

        if (!failures.isEmpty()) {
            throw new RuntimeException(failures.size() + " failures:\n  " + String.join("\n  ", failures));
        }
        System.out.println("PASSED");
    }

    /** Lines of text from the one starting with {@code firstLine} to the next one starting with {@code lastLine}. */
    private static String lines(String text, String firstLine, String lastLine) {
        StringBuilder collected = new StringBuilder();
        boolean inside = false;
        for (String line : text.split("\n")) {
            if (line.startsWith(firstLine)) {
                inside = true;
            }
            if (inside) {
                collected.append(line.stripTrailing()).append('\n');
                if (line.startsWith(lastLine) && collected.length() > line.length() + 1) {
                    break;
                }
            }
        }
        return collected.toString();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Runs the converter on the entry and expects success with {@code outputContains} in the generated list. */
    private static void expectConverted(String name, String source, String fingerprint, String entryJSON,
                                        String outputContains) throws Exception {
        Conversion conversion = convert(name, source, fingerprint, entryJSON);
        if (conversion.output().getExitValue() != 0) {
            failures.add(name + ": expected the conversion to succeed, it failed: " + conversion.combinedOutput().strip());
        } else if (!Files.readString(conversion.generatedList()).contains(outputContains)) {
            failures.add(name + ": the list lacks '" + outputContains + "':\n" + Files.readString(conversion.generatedList()));
        }
    }

    /** Runs the converter on the entry and expects it to stop with {@code errorContains} in its message. */
    private static void expectStopped(String name, String errorContains, String source, String fingerprint,
                                      String entryJSON) throws Exception {
        Conversion conversion = convert(name, source, fingerprint, entryJSON);
        if (conversion.output().getExitValue() == 0) {
            failures.add(name + ": expected the conversion to stop, it succeeded");
        } else if (!conversion.combinedOutput().contains(errorContains)) {
            failures.add(name + ": expected '" + errorContains + "' in: " + conversion.combinedOutput().strip());
        }
    }

    /** Runs the converter with the given policy.conf text and expects it to stop with {@code errorContains}. */
    private static void expectStoppedByPolicy(String name, String errorContains, String source, String fingerprint,
                                              String policyText) throws Exception {
        expectStopped(name, errorContains, convert(name, source, fingerprint, ENTRY + "}", policyText));
    }

    /** Runs the converter with extra command-line arguments and expects it to stop with {@code errorContains}. */
    private static void expectStoppedByArguments(String name, String errorContains, String source, String fingerprint,
                                                 String... extraArguments) throws Exception {
        expectStopped(name, errorContains, convert(name, source, fingerprint, ENTRY + "}", defaultPolicy(fingerprint), extraArguments));
    }

    private static void expectStopped(String name, String errorContains, Conversion conversion) {
        if (conversion.output().getExitValue() == 0) {
            failures.add(name + ": expected the conversion to stop, it succeeded");
        } else if (!conversion.combinedOutput().contains(errorContains)) {
            failures.add(name + ": expected '" + errorContains + "' in: " + conversion.combinedOutput().strip());
        } else if (conversion.combinedOutput().contains("Exception")) {
            failures.add(name + ": expected a message, not a stack trace: " + conversion.combinedOutput().strip());
        }
    }

    private static String defaultPolicy(String fingerprint) {
        return "map software_rendering_list all dawn vulkan\nfingerprint " + fingerprint + "\n";
    }

    /** One run of the converter: its output and the list it was to generate. */
    private record Conversion(OutputAnalyzer output, Path generatedList) {
        String combinedOutput() {
            return output.getStdout() + output.getStderr();
        }
    }

    /** Runs the converter on one software-rendering-list entry, with the fixture source and the fingerprint. */
    private static Conversion convert(String name, String source, String fingerprint, String entryJSON) throws Exception {
        return convert(name, source, fingerprint, entryJSON, defaultPolicy(fingerprint));
    }

    private static Conversion convert(String name, String source, String fingerprint, String entryJSON, String policyText,
                                      String... extraArguments) throws Exception {
        Path scratchDirectory = Files.createTempDirectory(Path.of("."), "conv");
        Path sources = scratchDirectory.resolve("sources");
        Files.createDirectories(sources.resolve("chromium/gpu/config"));
        Files.writeString(sources.resolve("chromium/src.cc"), source);
        Files.writeString(sources.resolve("chromium/gpu/config/software_rendering_list.json"),
                          "{\"entries\": [" + entryJSON + "]}");
        Files.writeString(sources.resolve("chromium/gpu/config/gpu_driver_bug_list.json"), "{\"entries\": []}");
        Path policy = scratchDirectory.resolve("policy.conf");
        Files.writeString(policy, policyText);
        Files.copy(header, scratchDirectory.resolve("SKGPUListData.header"));
        Path generatedList = scratchDirectory.resolve("SKGPUListData.java");
        List<String> command = new ArrayList<>(List.of(converter.toString(),
                "--policy", policy.toString(), "--sources", sources.toString(), "--out", generatedList.toString(),
                "--skia-tag", "m0-test", "--chromium-revision", "c", "--dawn-revision", "d"));
        command.addAll(List.of(extraArguments));
        ProcessBuilder processBuilder = ProcessTools.createLimitedTestJavaProcessBuilder(command.toArray(String[]::new));
        OutputAnalyzer output = ProcessTools.executeProcess(processBuilder);
        System.out.println(name + ": exit " + output.getExitValue());
        return new Conversion(output, generatedList);
    }
}
