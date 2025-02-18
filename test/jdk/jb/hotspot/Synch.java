/*
 * Copyright 2025 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/*
 * @test
 * @summary Verifies that the test finishes in due time
 * @library /test/lib
 * @run main/othervm Synch
 */
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Synch {
    private static int counter = 0;

    private static final Object lock = new Object();

    private static final int NUM_THREADS = 8;

    private static final long TEST_DURATION_SECONDS = 30;

    public static void main(String[] args) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);

        for (int i = 0; i < NUM_THREADS; i++) {
            executor.submit(new AccessTask());
        }

        System.out.println("Warmup");
        TimeUnit.SECONDS.sleep(TEST_DURATION_SECONDS);
        synchronized (lock) {
            counter = 0;
        }

        System.out.println("Starting the test...");
        TimeUnit.SECONDS.sleep(TEST_DURATION_SECONDS);

        executor.shutdownNow();
        System.out.println("Total synchronized accesses per millisecond: " + (long)((double)counter / (TEST_DURATION_SECONDS * 1000)));
    }

    // Task performed by each thread
    static class AccessTask implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (lock) {
                    counter++;
                }
            }
        }
    }
}
