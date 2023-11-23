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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * @test
 * @summary ResolveSymbolsTest verifies that the set of imported symbols is
 *          limited by the predefined symbols list.
 *
 * @comment What the test actually does?
 *          - It dumps all available in some environment symbols(hereinafter - external symbols).
 *          - Dumps all exported from JBR symbols. Dumps all imported by JBR symbols and checks
 *            for their presence in the JBR or environment.
 *
 *          From what environment comes the external symbols?
 *          They are from the Docker images used to build JBR and Docker images for JCEF.
 *
 *          How the symbols in the dataset are taken?
 *          There are scripts used for that: jb/build/externalLibs/aarch64/dump_aarch64.sh and
 *          jb/build/externalLibs/x86_64/dump_x86_64.sh
 *
 *          JBR got a new dependency. How to update the test data?
 *          - Run a docker container made from the corresponding image in jb/project/docker
 *          - Find the library whose symbols are missing. This utilities might help:
 *            $ ldconfig -p | grep libNameImLookingFor
 *            $ ldd nameOfJbrLibrary
 *            $ yum whatprovides *libXcursor*
 *
 *          - Dump ELF:
 *            $ echo Library: $lib_path >> $lib_name.txt
 *            $ echo Package: $(rpm -q --whatprovides $lib_path) >> $lib_name.txt
 *            $ echo >> $lib_name.txt
 *            $ readelf --wide --dyn-syms $lib_path >> $lib_name.txt
 *            Put $lib_name.txt to the corresponding directory jb/build/externalLibs/aarch64 or
 *            jb/build/externalLibs/x86_64
 *
 * @requires (os.family == "linux")
 * @run main ResolveSymbolsTestMinEnv
 */

public class ResolveSymbolsTestMinEnv extends ResolveSymbolsTestBase {

    private static String getArch() {
        String arch = System.getProperty("os.arch");
        if (arch.equals("x86_64") || arch.equals("amd64")) {
            return "x86_64";
        } else if (arch.equals("aarch64")) {
            return "aarch64";
        }
        throw new RuntimeException("Unknown os.arch: " + arch);
    }

    protected List<String> getExternalSymbols() throws IOException {
        List<String> result = new ArrayList<>();

        String libsPath = Objects.requireNonNull(ResolveSymbolsTestMinEnv.class.getResource("externalLibs/" + getArch())).getPath();
        try (Stream<Path> stream = Files.list(Paths.get(libsPath))) {
            stream.filter(path -> path.toString().endsWith(".txt")).forEach(path -> {
                try {
                    List<String> symbols = parseElf(Files.readString(path)).stream()
                            .filter(elfSymbol -> ResolveSymbolsTestMinEnv.symbolsFilter(elfSymbol, path.getFileName().toString()))
                            .filter(elfSymbol -> !elfSymbol.sectionNumber.equals("UND"))
                            .map(elfSymbol -> elfSymbol.name)
                            .toList();
                    result.addAll(symbols);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return result;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        new ResolveSymbolsTestMinEnv().doTest();
    }
}
