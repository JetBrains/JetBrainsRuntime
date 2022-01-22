/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
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

package jdk.testlibrary;

import static jdk.testlibrary.Asserts.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Utility class for verifying output and exit value from a {@code Process}.
 *
 * @deprecated  This class is deprecated. Use the one from
 *              {@code <root>/test/lib/jdk/test/lib/process}
 *
 */
@Deprecated
public final class OutputAnalyzer {
    private final OutputBuffer output;
    private final String stdout;
    private final String stderr;
    private final int exitValue;    // useless now. output contains exit value.

    /**
     * Create an OutputAnalyzer, a utility class for verifying output and exit
     * value from a Process.
     * <p>
     * OutputAnalyzer should never be instantiated directly -
     * use {@linkplain ProcessTools#executeProcess(ProcessBuilder)} instead
     *
     * @param process
     *            Process to analyze
     * @throws IOException
     *             If an I/O error occurs.
     */
    OutputAnalyzer(Process process) throws IOException {
        output = new OutputBuffer(process);
        exitValue = -1;
        this.stdout = null;
        this.stderr = null;
    }

    /**
     * Create an OutputAnalyzer, a utility class for verifying output.
     *
     * @param buf
     *            String buffer to analyze
     */
    OutputAnalyzer(String buf) {
        this(buf, buf);
    }

    /**
     * Create an OutputAnalyzer, a utility class for verifying output
     *
     * @param stdout
     *            stdout buffer to analyze
     * @param stderr
     *            stderr buffer to analyze
     */
    OutputAnalyzer(String stdout, String stderr) {
        this.output = null;
        this.stdout = stdout;
        this.stderr = stderr;
        exitValue = -1;
    }

    /**
     * Verify that the stdout and stderr contents of output buffer contains the
     * string
     *
     * @param expectedString
     *            String that buffer should contain
     * @throws RuntimeException
     *             If the string was not found
     */
    public OutputAnalyzer shouldContain(String expectedString) {
        if (!getStdout().contains(expectedString)
                && !getStderr().contains(expectedString)) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + expectedString
                    + "' missing from stdout/stderr \n");
        }
        return this;
    }

    /**
     * Verify that the stdout contents of output buffer contains the string
     *
     * @param expectedString
     *            String that buffer should contain
     * @throws RuntimeException
     *             If the string was not found
     */
    public OutputAnalyzer stdoutShouldContain(String expectedString) {
        if (!getStdout().contains(expectedString)) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + expectedString
                    + "' missing from stdout \n");
        }
        return this;
    }

    /**
     * Verify that the stderr contents of output buffer contains the string
     *
     * @param expectedString
     *            String that buffer should contain
     * @throws RuntimeException
     *             If the string was not found
     */
    public OutputAnalyzer stderrShouldContain(String expectedString) {
        if (!getStderr().contains(expectedString)) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + expectedString
                    + "' missing from stderr \n");
        }
        return this;
    }

    /**
     * Verify that the stdout and stderr contents of output buffer does not
     * contain the string
     *
     * @param notExpectedString
     *            String that the buffer should not contain
     * @throws RuntimeException
     *             If the string was found
     */
    public OutputAnalyzer shouldNotContain(String notExpectedString) {
        if (getStdout().contains(notExpectedString)) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + notExpectedString
                    + "' found in stdout \n");
        }
        if (getStderr().contains(notExpectedString)) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + notExpectedString
                    + "' found in stderr \n");
        }
        return this;
    }

    /**
     * Verify that the stdout contents of output buffer does not contain the
     * string
     *
     * @param notExpectedString
     *            String that the buffer should not contain
     * @throws RuntimeException
     *             If the string was found
     */
    public OutputAnalyzer stdoutShouldNotContain(String notExpectedString) {
        if (getStdout().contains(notExpectedString)) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + notExpectedString
                    + "' found in stdout \n");
        }
        return this;
    }

    /**
     * Verify that the stderr contents of output buffer does not contain the
     * string
     *
     * @param notExpectedString
     *            String that the buffer should not contain
     * @throws RuntimeException
     *             If the string was found
     */
    public OutputAnalyzer stderrShouldNotContain(String notExpectedString) {
        if (getStderr().contains(notExpectedString)) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + notExpectedString
                    + "' found in stderr \n");
        }
        return this;
    }

    /**
     * Verify that the stdout and stderr contents of output buffer matches the
     * pattern
     *
     * @param pattern
     * @throws RuntimeException
     *             If the pattern was not found
     */
    public OutputAnalyzer shouldMatch(String pattern) {
        Matcher stdoutMatcher = Pattern.compile(pattern, Pattern.MULTILINE)
                .matcher(getStdout());
        Matcher stderrMatcher = Pattern.compile(pattern, Pattern.MULTILINE)
                .matcher(getStderr());
        if (!stdoutMatcher.find() && !stderrMatcher.find()) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + pattern
                    + "' missing from stdout/stderr \n");
        }
        return this;
    }

    /**
     * Verify that the stdout contents of output buffer matches the pattern
     *
     * @param pattern
     * @throws RuntimeException
     *             If the pattern was not found
     */
    public OutputAnalyzer stdoutShouldMatch(String pattern) {
        Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(
                getStdout());
        if (!matcher.find()) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + pattern
                    + "' missing from stdout \n");
        }
        return this;
    }

    /**
     * Verify that the stderr contents of output buffer matches the pattern
     *
     * @param pattern
     * @throws RuntimeException
     *             If the pattern was not found
     */
    public OutputAnalyzer stderrShouldMatch(String pattern) {
        Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(
                getStderr());
        if (!matcher.find()) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + pattern
                    + "' missing from stderr \n");
        }
        return this;
    }

    /**
     * Verify that the stdout and stderr contents of output buffer does not
     * match the pattern
     *
     * @param pattern
     * @throws RuntimeException
     *             If the pattern was found
     */
    public OutputAnalyzer shouldNotMatch(String pattern) {
        Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(
                getStdout());
        if (matcher.find()) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + pattern + "' found in stdout: '"
                    + matcher.group() + "' \n");
        }
        matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(getStderr());
        if (matcher.find()) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + pattern + "' found in stderr: '"
                    + matcher.group() + "' \n");
        }
        return this;
    }

    /**
     * Verify that the stdout contents of output buffer does not match the
     * pattern
     *
     * @param pattern
     * @throws RuntimeException
     *             If the pattern was found
     */
    public OutputAnalyzer stdoutShouldNotMatch(String pattern) {
        Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(
                getStdout());
        if (matcher.find()) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + pattern + "' found in stdout \n");
        }
        return this;
    }

    /**
     * Verify that the stderr contents of output buffer does not match the
     * pattern
     *
     * @param pattern
     * @throws RuntimeException
     *             If the pattern was found
     */
    public OutputAnalyzer stderrShouldNotMatch(String pattern) {
        Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(
                getStderr());
        if (matcher.find()) {
            reportDiagnosticSummary();
            throw new RuntimeException("'" + pattern + "' found in stderr \n");
        }
        return this;
    }

    /**
     * Get the captured group of the first string matching the pattern. stderr
     * is searched before stdout.
     *
     * @param pattern
     *            The multi-line pattern to match
     * @param group
     *            The group to capture
     * @return The matched string or null if no match was found
     */
    public String firstMatch(String pattern, int group) {
        Matcher stderrMatcher = Pattern.compile(pattern, Pattern.MULTILINE)
                .matcher(getStderr());
        Matcher stdoutMatcher = Pattern.compile(pattern, Pattern.MULTILINE)
                .matcher(getStdout());
        if (stderrMatcher.find()) {
            return stderrMatcher.group(group);
        }
        if (stdoutMatcher.find()) {
            return stdoutMatcher.group(group);
        }
        return null;
    }

    /**
     * Get the first string matching the pattern. stderr is searched before
     * stdout.
     *
     * @param pattern
     *            The multi-line pattern to match
     * @return The matched string or null if no match was found
     */
    public String firstMatch(String pattern) {
        return firstMatch(pattern, 0);
    }

    /**
     * Verify the exit value of the process
     *
     * @param expectedExitValue
     *            Expected exit value from process
     * @throws RuntimeException
     *             If the exit value from the process did not match the expected
     *             value
     */
    public OutputAnalyzer shouldHaveExitValue(int expectedExitValue) {
        if (getExitValue() != expectedExitValue) {
            reportDiagnosticSummary();
            throw new RuntimeException("Expected to get exit value of ["
                    + expectedExitValue + "]\n");
        }
        return this;
    }

    /**
     * Report summary that will help to diagnose the problem Currently includes:
     * - standard input produced by the process under test - standard output -
     * exit code Note: the command line is printed by the ProcessTools
     */
    private OutputAnalyzer reportDiagnosticSummary() {
        String msg = " stdout: [" + getStdout() + "];\n" + " stderr: [" + getStderr()
                + "]\n" + " exitValue = " + getExitValue() + "\n";

        System.err.println(msg);
        return this;
    }

    /**
     * Get the contents of the output buffer (stdout and stderr)
     *
     * @return Content of the output buffer
     */
    public String getOutput() {
        return getStdout() + getStderr();
    }

    /**
     * Get the contents of the stdout buffer
     *
     * @return Content of the stdout buffer
     */
    public String getStdout() {
        return output == null ? stdout : output.getStdout();
    }

    /**
     * Get the contents of the stderr buffer
     *
     * @return Content of the stderr buffer
     */
    public String getStderr() {
        return output == null ? stderr : output.getStderr();
    }

    /**
     * Get the process exit value
     *
     * @return Process exit value
     */
    public int getExitValue() {
        return output == null ? exitValue : output.getExitValue();
    }


    /**
     * Print the stdout buffer to the given {@code PrintStream}.
     *
     * @return this OutputAnalyzer
     */
    public OutputAnalyzer outputTo(PrintStream out) {
        out.println(getStdout());
        return this;
    }

    /**
     * Print the stderr buffer to the given {@code PrintStream}.
     *
     * @return this OutputAnalyzer
     */
    public OutputAnalyzer errorTo(PrintStream out) {
        out.println(getStderr());
        return this;
    }


    /**
     * Get the contents of the output buffer (stdout and stderr)
     * as a list of strings. Output will be split at new-lines as
     * recognized by java.io.BufferedReader.readLine().
     *
     * @return Contents of the output buffer as list of strings
     */
    public List<String> asLines() {
        return asLines(getOutput());
    }

    private List<String> asLines(String buffer) {
        return new BufferedReader(new StringReader(buffer))
            .lines().collect(Collectors.toList());
    }

    /**
     * Check if there is a line matching {@code pattern} and return its index
     *
     * @param pattern Matching pattern
     * @param fromIndex Start matching after so many lines skipped
     * @return Index of first matching line
     */
    private int indexOf(List<String> lines, String pattern, int fromIndex) {
        for (int i = fromIndex; i < lines.size(); i++) {
            if (lines.get(i).matches(pattern)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @see #shouldMatchByLine(String, String, String)
     */
    public int shouldMatchByLine(String pattern) {
        return shouldMatchByLine(null, null, pattern);
    }

    /**
     * @see #stdoutShouldMatchByLine(String, String, String)
     */
    public int stdoutShouldMatchByLine(String pattern) {
        return stdoutShouldMatchByLine(null, null, pattern);
    }

    /**
     * @see #shouldMatchByLine(String, String, String)
     */
    public int shouldMatchByLineFrom(String from, String pattern) {
        return shouldMatchByLine(from, null, pattern);
    }

    /**
     * @see #shouldMatchByLine(String, String, String)
     */
    public int shouldMatchByLineTo(String to, String pattern) {
        return shouldMatchByLine(null, to, pattern);
    }

    /**
     * Verify that the stdout and stderr contents of output buffer match the
     * {@code pattern} line by line. The whole output could be matched or
     * just a subset of it.
     *
     * @param from
     *            The line (excluded) from where output will be matched.
     *            Set {@code from} to null for matching from the first line.
     * @param to
     *            The line (excluded) until where output will be matched.
     *            Set {@code to} to null for matching until the last line.
     * @param pattern
     *            Matching pattern
     * @return Count of lines which match the {@code pattern}
     */
    public int shouldMatchByLine(String from, String to, String pattern) {
        return shouldMatchByLine(getOutput(), from, to, pattern);
    }

    /**
     * Verify that the stdout contents of output buffer matches the
     * {@code pattern} line by line. The whole stdout could be matched or
     * just a subset of it.
     *
     * @param from
     *            The line (excluded) from where stdout will be matched.
     *            Set {@code from} to null for matching from the first line.
     * @param to
     *            The line (excluded) until where stdout will be matched.
     *            Set {@code to} to null for matching until the last line.
     * @param pattern
     *            Matching pattern
     * @return Count of lines which match the {@code pattern}
     */
    public int stdoutShouldMatchByLine(String from, String to, String pattern) {
        return shouldMatchByLine(getStdout(), from, to, pattern);
    }

    private int shouldMatchByLine(String buffer, String from, String to, String pattern) {
        List<String> lines = asLines(buffer);

        int fromIndex = 0;
        if (from != null) {
            fromIndex = indexOf(lines, from, 0) + 1; // + 1 -> apply 'pattern' to lines after 'from' match
            assertGreaterThan(fromIndex, 0,
                    "The line/pattern '" + from + "' from where the output should match can not be found");
        }

        int toIndex = lines.size();
        if (to != null) {
            toIndex = indexOf(lines, to, fromIndex);
            assertGreaterThan(toIndex, fromIndex,
                    "The line/pattern '" + to + "' until where the output should match can not be found");
        }

        List<String> subList = lines.subList(fromIndex, toIndex);
        assertFalse(subList.isEmpty(), "There are no lines to check:"
                + " range " + fromIndex + ".." + toIndex + ", subList = " + subList);
        int matchedCount = 0;
        for (String line : subList) {
            assertTrue(line.matches(pattern),
                    "The line '" + line + "' does not match pattern '" + pattern + "'");
            matchedCount++;
        }

        return matchedCount;
    }

}
