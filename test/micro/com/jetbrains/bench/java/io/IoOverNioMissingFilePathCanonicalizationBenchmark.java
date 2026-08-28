/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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
package com.jetbrains.bench.java.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JBR-10418 IoOverNio slows down file canonicalization in comparison to native IO
 * depending on the number of missing segments in the file path.
 * IO call goes through a single syscall while NIO call throws exceptions.
 * */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
public class IoOverNioMissingFilePathCanonicalizationBenchmark {
    @Param("100")
    private int canonicalizationIterations;

    @Param("10")
    private int fullyMissingPathDepth;

    @Param("10")
    private int existingPathWithMissingFileDepth;

    private String missingPathWithMissingFile;

    private String existingPathWithMissingFile;

    private Path existingRoot;

    private Path existingPath;

    @Setup
    public void init() throws IOException {
        Path fullyMissingPath = Path.of("").toAbsolutePath().getRoot();
        for (int i = 0; i < fullyMissingPathDepth; ++i) {
            fullyMissingPath = fullyMissingPath.resolve("dir");
        }
        missingPathWithMissingFile = fullyMissingPath.resolve("missingFile.txt").toString();

        existingRoot = Files.createTempDirectory("canonicalizationBench").toRealPath();
        existingPath = existingRoot;
        for (int i = 0; i < existingPathWithMissingFileDepth; ++i) {
            existingPath = existingPath.resolve("dir");
        }
        Files.createDirectories(existingPath);
        existingPathWithMissingFile = existingPath.resolve("missingFile.txt").toString();
    }

    @TearDown
    public void cleanup() throws IOException {
        for (Path dirToDelete = existingPath; dirToDelete.startsWith(existingRoot); dirToDelete = dirToDelete.getParent()) {
            Files.deleteIfExists(dirToDelete);
        }
    }

    @Benchmark
    @Fork(jvmArgsAppend = "-Djbr.java.io.use.nio=false")
    public void missingFileCanonicalizationBenchOnNativeIo(Blackhole bh) throws IOException {
        missingFileCanonicalizationBench(bh);
    }

    @Benchmark
    @Fork(jvmArgsAppend = "-Djbr.java.io.use.nio=true")
    public void missingFileCanonicalizationBenchOnIoOverNio(Blackhole bh) throws IOException {
        missingFileCanonicalizationBench(bh);
    }

    private void missingFileCanonicalizationBench(Blackhole bh) throws IOException {
        for (int i = 0; i < canonicalizationIterations; ++i) {
            File file = new File(missingPathWithMissingFile);
            bh.consume(file.getCanonicalPath());
        }
    }

    @Benchmark
    @Fork(jvmArgsAppend = "-Djbr.java.io.use.nio=false")
    public void missingFileInExistingPathCanonicalizationBenchOnNativeIo(Blackhole bh) throws IOException {
        missingFileInExistingPathCanonicalizationBench(bh);
    }

    @Benchmark
    @Fork(jvmArgsAppend = "-Djbr.java.io.use.nio=true")
    public void missingFileInExistingPathCanonicalizationBenchOnIoOverNio(Blackhole bh) throws IOException {
        missingFileInExistingPathCanonicalizationBench(bh);
    }

    private void missingFileInExistingPathCanonicalizationBench(Blackhole bh) throws IOException {
        for (int i = 0; i < canonicalizationIterations; ++i) {
            File file = new File(existingPathWithMissingFile);
            bh.consume(file.getCanonicalPath());
        }
    }
}
