/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * @test
 * @summary ResolveSymbolsTest verifies that all JBR symbols are successfully resolved in the environment where
 *          the test is run
 * @requires (os.family == "linux")
 * @run main ResolveSymbolsRealEnv
 */

public class ResolveSymbolsRealEnv extends ResolveSymbolsTestBase {

    /**
     * Run and parse output of ldd.
     * Example:
     * $ ldd /usr/bin/ls
     * 	linux-vdso.so.1 (0x00007fff07744000)
     * 	libselinux.so.1 => /lib/x86_64-linux-gnu/libselinux.so.1 (0x00007f073f4b2000)
     * 	libc.so.6 => /lib/x86_64-linux-gnu/libc.so.6 (0x00007f073f200000)
     * 	libpcre2-8.so.0 => /lib/x86_64-linux-gnu/libpcre2-8.so.0 (0x00007f073f169000)
     * 	/lib64/ld-linux-x86-64.so.2 (0x00007f073f51c000)
     *
     * 	The function returns a list with "/lib/x86_64-linux-gnu/libselinux.so.1", "/lib/x86_64-linux-gnu/libc.so.6",
     * 	"/lib/x86_64-linux-gnu/libpcre2-8.so.0" and "/lib64/ld-linux-x86-64.so.2"
     */
    private List<Path> getDependencies(Path path) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec("ldd " + path);

        List<Path> result = new BufferedReader(new InputStreamReader(process.getInputStream()))
                .lines()
                .map(line -> {
                    // parse expressions like "libc.so.6 => /lib/x86_64-linux-gnu/libc.so.6 (0x00007f931b000000)"
                    String[] columns = line.split("=>");
                    if (columns.length >= 2) {
                        return columns[1].strip().split("\\s+")[0];
                    }
                    // something like this "/lib64/ld-linux-x86-64.so.2 (0x00007f073f51c000)" is expected
                    if (line.contains("ld-linux")) {
                        return line.trim().split("\\s+")[0];
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .map(Paths::get)
                .toList();

        if (process.waitFor() != 0) {
            return null;
        }

        return result;
    }

    protected List<String> getExternalSymbols() throws IOException {
        return getJbrBinaries().stream()
                .flatMap(path -> {
                    try {
                        return getDependencies(path).stream();
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException("Failed to get the JBR dependencies from " + path, e);
                    }
                })
                .distinct()
                .map(path -> {
                    try {
                        System.out.println("Dumping symbols for " + path);
                        return runReadElf(path);
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException("Failed to read elf for " + path, e);
                    }
                })
                .filter(Objects::nonNull)
                .flatMap(s -> parseElf(s).stream())
                .filter(elfSymbol -> symbolsFilter(elfSymbol, ""))
                .filter(elfSymbol -> !elfSymbol.sectionNumber.equals("UND"))
                .map(elfSymbol -> elfSymbol.name)
                .toList();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        new ResolveSymbolsRealEnv().doTest();
    }
}
