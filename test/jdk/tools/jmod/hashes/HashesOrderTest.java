/*
 * Copyright (c) 2022, Huawei Technologies Co., Ltd. All rights reserved.
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
 * @bug 8240903
 * @summary Test consistency of moduleHashes attribute between builds
 * @library /test/lib
 * @run testng HashesOrderTest
 */

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;

import jdk.test.lib.compiler.ModuleInfoMaker;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class HashesOrderTest {
    private ToolProvider JMOD_TOOL = ToolProvider.findFirst("jmod")
        .orElseThrow(() ->
            new RuntimeException("jmod tool not found")
        );

    // buffer size used for reading and writing
    private static final int BUFFER_SIZE = 8192;

    private int NUM_MODULES = 64;
    private Path mods;
    private Path lib1;
    private Path lib2;
    private ModuleInfoMaker builder;

    @Test
    public void test() throws Exception {
        mods = Path.of("mods");
        lib1 = Path.of("lib1");
        lib2 = Path.of("lib2");
        builder = new ModuleInfoMaker(Path.of("src"));

        Files.createDirectories(mods);
        Files.createDirectories(lib1);
        Files.createDirectories(lib2);

        makeModule("ma");
        String moduleName;
        for (int i = 0; i < NUM_MODULES; i++) {
            moduleName = "m" + i + "b";
            makeModule(moduleName, "ma");
            makeJmod(moduleName, lib1);
            makeJmod(moduleName, lib2);
        }
        makeJmod("ma", lib1, "--module-path", lib1.toString(),
                "--hash-modules", ".*");
        Path jmod1 = lib1.resolve("ma.jmod");

        makeJmod("ma", lib2, "--module-path", lib2.toString(),
                "--hash-modules", ".*");
        Path jmod2 = lib2.resolve("ma.jmod");

        assertEquals(mismatch(jmod1, jmod2), -1);
    }

    private void makeModule(String mn, String... deps)
        throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append("module ")
          .append(mn)
          .append(" {")
          .append("\n");
        Arrays.stream(deps)
              .forEach(req -> {
                  sb.append("    requires ");
                  sb.append(req)
                    .append(";\n");
              });
        sb.append("}\n");
        builder.writeJavaFiles(mn, sb.toString());
        builder.compile(mn, mods);
    }

    private void makeJmod(String moduleName, Path libName, String... options) {
        Path mclasses = mods.resolve(moduleName);
        Path outfile = libName.resolve(moduleName + ".jmod");
        List<String> args = new ArrayList<>();
        args.add("create");
        Collections.addAll(args, options);
        Collections.addAll(args, "--class-path", mclasses.toString(),
                           outfile.toString());

        runJmod(args);
    }

    private void runJmod(List<String> args) {
        runJmod(args.toArray(new String[args.size()]));
    }

    private void runJmod(String... args) {
        int rc = JMOD_TOOL.run(System.out, System.out, args);
        System.out.println("jmod " + Arrays.stream(args).collect(Collectors.joining(" ")));
        if (rc != 0) {
            throw new AssertionError("jmod failed: rc = " + rc);
        }
    }

    private long mismatch(Path path, Path path2) throws IOException {
        if (Files.isSameFile(path, path2)) {
            return -1;
        }
        byte[] buffer1 = new byte[BUFFER_SIZE];
        byte[] buffer2 = new byte[BUFFER_SIZE];
        try (InputStream in1 = Files.newInputStream(path);
             InputStream in2 = Files.newInputStream(path2);) {
            long totalRead = 0;
            while (true) {
                int nRead1 = in1.readNBytes(buffer1, 0, BUFFER_SIZE);
                int nRead2 = in2.readNBytes(buffer2, 0, BUFFER_SIZE);

                int i = Arrays.mismatch(buffer1, 0, nRead1, buffer2, 0, nRead2);
                if (i > -1) {
                    return totalRead + i;
                }
                if (nRead1 < BUFFER_SIZE) {
                    // we've reached the end of the files, but found no mismatch
                    return -1;
                }
                totalRead += nRead1;
            }
        }
    }
}
