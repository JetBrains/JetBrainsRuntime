/*
 * Copyright 2000-2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;

public class CheckVersion {
    private static final Map<String, Function<String, String>> FILE_TRANSFORMERS = Map.of(
            "com/jetbrains/JBR.java", c -> {
                // Exclude API version from hash calculation
                int versionMethodIndex = c.indexOf("getApiVersion()");
                int versionStartIndex = c.indexOf("\"", versionMethodIndex) + 1;
                int versionEndIndex = c.indexOf("\"", versionStartIndex);
                return c.substring(0, versionStartIndex) + c.substring(versionEndIndex);
            }
    );

    private static Path gensrc;

    /**
     * <ul>
     *     <li>$0 - absolute path to {@code JetBrainsRuntime/src/jetbrains.api} dir</li>
     *     <li>$1 - absolute path to gensrc dir ({@code JetBrainsRuntime/build/<conf>/jbr-api/gensrc})</li>
     *     <li>$2 - true if hash mismatch is an error</li>
     * </ul>
     */
    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        Path versionFile = Path.of(args[0]).resolve("version.properties");
        gensrc = Path.of(args[1]);
        boolean error = args[2].equals("true");

        Properties props = new Properties();
        props.load(Files.newInputStream(versionFile));
        String hash = SourceHash.calculate();

        if (hash.equals(props.getProperty("HASH"))) return;
        System.err.println("================================================================================");
        if (error) {
            System.err.println("Error: jetbrains.api code was changed, hash and API version must be updated in " + versionFile);
        } else {
            System.err.println("Warning: jetbrains.api code was changed, " +
                    "update hash and increment API version in " + versionFile + " before committing these changes");
        }
        System.err.println("HASH = " + hash);
        if (!error) System.err.println("DO NOT COMMIT YOUR CHANGES WITH THIS WARNING");
        System.err.println("================================================================================");
        if (error) System.exit(-1);
    }

    private static class SourceHash {

        private static String calculate() throws NoSuchAlgorithmException, IOException {
            MessageDigest hash = MessageDigest.getInstance("MD5");
            calculate(gensrc, hash);
            byte[] digest = hash.digest();
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(String.format("%X", b));
            }
            return result.toString();
        }

        private static void calculate(Path dir, MessageDigest hash) throws IOException {
            for (Entry f : findFiles(dir)) {
                hash.update(f.name.getBytes(StandardCharsets.UTF_8));
                hash.update(f.content.getBytes(StandardCharsets.UTF_8));
            }
        }

        private static List<Entry> findFiles(Path dir) throws IOException {
            List<Entry> files = new ArrayList<>();
            FileVisitor<Path> fileFinder = new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path rel = dir.relativize(file);
                        StringBuilder name = new StringBuilder();
                        for (int i = 0; i < rel.getNameCount(); i++) {
                            if (!name.isEmpty()) name.append('/');
                            name.append(rel.getName(i));
                        }
                        String content = Files.readString(file);
                        String fileName = name.toString();
                        files.add(new Entry(FILE_TRANSFORMERS.getOrDefault(fileName, c -> c).apply(content), fileName));
                        return FileVisitResult.CONTINUE;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
            Files.walkFileTree(dir, fileFinder);
            files.sort(Comparator.comparing(Entry::name));
            return files;
        }

        private record Entry(String content, String name) {}
    }
}
