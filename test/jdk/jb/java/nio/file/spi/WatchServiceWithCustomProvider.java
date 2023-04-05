/*
 * Copyright (c) 2008, 2023, Oracle and/or its affiliates. All rights reserved.
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

/* @test
 * @summary Verifies that WatchService works with a custom default
 *          file system installed.
 * @requires os.family == "mac"
 * @library /test/lib/
 * @build TestProvider
 * @modules java.base/sun.nio.fs:+open
 * @run main/othervm -Djava.nio.file.spi.DefaultFileSystemProvider=TestProvider WatchServiceWithCustomProvider
 */

import java.net.URI;
import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;
import java.io.*;
import java.nio.file.spi.FileSystemProvider;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.sun.nio.file.ExtendedWatchEventModifier.*;


public class WatchServiceWithCustomProvider {

    final static String WATCH_ROOT_DIR_NAME = "watch-root";

    static void checkKey(WatchKey key) {
        if (!key.isValid())
            throw new RuntimeException("Key is not valid");
    }

    static void takeExpectedKey(WatchService watcher, WatchKey expected) {
        System.out.println("take events...");
        WatchKey key;
        try {
            key = watcher.take();
        } catch (InterruptedException x) {
            // not expected
            throw new RuntimeException(x);
        }
        if (key != expected)
            throw new RuntimeException("removed unexpected key");
    }

    static void checkExpectedEvent(Iterable<WatchEvent<?>> events,
                                   WatchEvent.Kind<?> expectedKind,
                                   Object expectedContext)
    {
        WatchEvent<?> event = events.iterator().next();
        System.out.format("got event: type=%s, count=%d, context=%s\n",
            event.kind(), event.count(), event.context());
        if (event.kind() != expectedKind)
            throw new RuntimeException("unexpected event");
    }

    /**
     * Simple test of each of the standard events
     */
    static void testEvents(FileSystem fs, Path dir) throws IOException {
        System.out.println("-- Standard Events --");

        Path subdir = dir.resolve("subdir");
        Files.createDirectories(subdir);
        Path name = subdir.resolve("foo");

        try (WatchService watcher = fs.newWatchService()) {
            // --- ENTRY_CREATE ---

            // register for event
            System.out.format("register %s for ENTRY_CREATE\n", dir);
            WatchKey myKey = dir.register(watcher,
                new WatchEvent.Kind<?>[]{ ENTRY_CREATE }, FILE_TREE);
            checkKey(myKey);

            // create file
            Path file = subdir.resolve("foo");
            System.out.format("create %s\n", file);
            Files.createFile(file);

            // remove key and check that we got the ENTRY_CREATE event
            takeExpectedKey(watcher, myKey);
            checkExpectedEvent(myKey.pollEvents(),
                StandardWatchEventKinds.ENTRY_CREATE, name);

            System.out.println("reset key");
            if (!myKey.reset())
                throw new RuntimeException("key has been cancalled");

            System.out.println("OKAY");

            // --- ENTRY_DELETE ---

            System.out.format("register %s for ENTRY_DELETE\n", dir);
            WatchKey deleteKey = dir.register(watcher,
                new WatchEvent.Kind<?>[]{ ENTRY_DELETE }, FILE_TREE);
            if (deleteKey != myKey)
                throw new RuntimeException("register did not return existing key");
            checkKey(deleteKey);

            System.out.format("delete %s\n", file);
            Files.delete(file);
            takeExpectedKey(watcher, myKey);
            checkExpectedEvent(myKey.pollEvents(),
                StandardWatchEventKinds.ENTRY_DELETE, name);

            System.out.println("reset key");
            if (!myKey.reset())
                throw new RuntimeException("key has been cancalled");

            System.out.println("OKAY");

            // create the file for the next test
            Files.createFile(file);

            // --- ENTRY_MODIFY ---

            System.out.format("register %s for ENTRY_MODIFY\n", dir);
            WatchKey newKey = dir.register(watcher,
                new WatchEvent.Kind<?>[]{ ENTRY_MODIFY }, FILE_TREE);
            if (newKey != myKey)
                throw new RuntimeException("register did not return existing key");
            checkKey(newKey);

            System.out.format("update: %s\n", file);
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.APPEND)) {
                out.write("I am a small file".getBytes("UTF-8"));
            }

            // remove key and check that we got the ENTRY_MODIFY event
            takeExpectedKey(watcher, myKey);
            checkExpectedEvent(myKey.pollEvents(),
                StandardWatchEventKinds.ENTRY_MODIFY, name);
            System.out.println("OKAY");

            // done
            Files.delete(file);
        }
    }

    public static void main(String[] args) throws IOException {
        FileSystemProvider provider = ((TestProvider)FileSystems.getDefault().provider()).defaultProvider();
        FileSystem fs = provider.getFileSystem(URI.create("file:///")); // that would be some UnixFileSystem
        Path dir = fs.getPath(WATCH_ROOT_DIR_NAME);

        try {
            testEvents(fs, dir);
        } finally {
            removeAll(dir);
        }
    }

    static void removeAll(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> {
                    try { Files.delete(f); } catch (IOException ignored) {}
            });
        }
    }
}
