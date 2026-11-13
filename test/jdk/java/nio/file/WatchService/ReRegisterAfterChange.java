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

/* @test
 * @summary A change made just before a directory is registered again is still reported
 * @run main ReRegisterAfterChange
 */
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public class ReRegisterAfterChange {

    public static void main(String[] args) throws Exception {
        for (int delayMillis : new int[] {0, 20, 100}) {
            check(delayMillis);
        }
    }

    // Modifies a file in a watched directory, registers the directory again delayMillis later,
    // and expects the modification to be reported exactly once.
    private static void check(int delayMillis) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("re-register");
        Path file = Files.writeString(dir.resolve("watched.txt"), "before");
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            // let the time stamp of the modification differ from the one the registration recorded
            Thread.sleep(1100);
            Files.writeString(file, "after");
            Thread.sleep(delayMillis);
            WatchKey sameKey = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            if (sameKey != key) {
                throw new RuntimeException("Registering the directory again returned another key");
            }

            List<String> events = new ArrayList<>();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < until) {
                WatchKey signalled = watcher.poll(100, TimeUnit.MILLISECONDS);
                if (signalled != null) {
                    for (WatchEvent<?> event : signalled.pollEvents()) {
                        events.add(event.kind().name() + " " + event.context());
                    }
                    signalled.reset();
                    // keep polling a little longer to catch a duplicate
                    until = Math.min(until, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
                }
            }
            System.out.println("re-registered " + delayMillis + " ms after the change: " + events);
            if (!events.equals(List.of("ENTRY_MODIFY watched.txt"))) {
                throw new RuntimeException("Expected one ENTRY_MODIFY for watched.txt when the directory is registered again "
                        + delayMillis + " ms after the change, got " + events);
            }
        }
    }
}
