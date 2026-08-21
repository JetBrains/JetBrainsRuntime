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
 */

import sun.awt.FramePacing;

import jdk.test.lib.Asserts;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @test
 * @key headful
 * @summary Exercises the FramePacing service contract: display id resolution,
 * tick delivery to multiple listeners at roughly the refresh rate,
 * idempotent close with no deliveries afterwards, and subscribe/close
 * churn stability.
 * @library /test/lib
 * @compile --add-exports java.desktop/sun.awt=ALL-UNNAMED
 * --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED
 * FramePacingTestUtil.java FramePacingTest.java
 * @run main/othervm
 * --add-exports java.desktop/sun.awt=ALL-UNNAMED
 * --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED
 * FramePacingTest
 */
public class FramePacingTest {

    public static void main(String[] args) throws Exception {
        FramePacing service = FramePacingTestUtil.createPlatformService();

        Asserts.assertEquals(service.getQuality(), FramePacing.QUALITY_ESTIMATED,
                "shared backend must report ESTIMATED quality");

        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();

        long displayId = service.displayId(gc);
        Asserts.assertNotEquals(displayId, -1L, "default screen must resolve to a display id");
        Asserts.assertEquals(displayId, service.displayId(gc),
                "display id must be stable for the same configuration");

        long period = service.refreshPeriodNanos(displayId);
        Asserts.assertTrue(period >= 0, "refresh period must be non-negative");

        Asserts.assertNull(service.subscribe(-1, (id, t) -> {
                    // No-op
                }),
                "subscribing to an unknown display must return null");

        testTickDelivery(service, displayId, period);
        testCloseSemantics(service, displayId);
        testSubscribeCloseChurn(service, displayId);
    }

    private static void testTickDelivery(FramePacing service, long displayId, long period)
            throws Exception {
        long expectedPeriod = period > 0 ? period : 1_000_000_000L / 60;
        CountDownLatch first = new CountDownLatch(10);
        CountDownLatch second = new CountDownLatch(10);
        long start = System.nanoTime();

        FramePacing.Subscription a = service.subscribe(displayId, (id, t) -> {
            Asserts.assertEquals(id, displayId);
            first.countDown();
        });

        FramePacing.Subscription b =
                service.subscribe(displayId, (id, t) -> second.countDown());

        Asserts.assertNotNull(a);
        Asserts.assertNotNull(b);
        Asserts.assertEquals(a.displayId(), displayId);

        // 10 ticks should arrive within ~10 periods plus generous slack.
        long budgetMillis = TimeUnit.NANOSECONDS.toMillis(expectedPeriod) * 10 * 5 + 2000;
        Asserts.assertTrue(first.await(budgetMillis, TimeUnit.MILLISECONDS),
                "first listener did not receive 10 ticks in time");
        Asserts.assertTrue(second.await(budgetMillis, TimeUnit.MILLISECONDS),
                "second listener did not receive 10 ticks in time");

        long elapsed = System.nanoTime() - start;
        Asserts.assertTrue(elapsed >= expectedPeriod * 5,
                "10 ticks arrived faster than half the nominal rate allows: " + elapsed + "ns");

        a.close();
        b.close();
    }

    private static void testCloseSemantics(FramePacing service, long displayId)
            throws Exception {
        AtomicLong count = new AtomicLong();
        FramePacing.Subscription s =
                service.subscribe(displayId, (id, t) -> count.incrementAndGet());

        Asserts.assertNotNull(s);

        Thread.sleep(300);

        Asserts.assertTrue(count.get() > 0, "listener must have received ticks before close");

        s.close();
        s.close(); // idempotent

        Thread.sleep(100); // grace: one in-flight delivery may complete
        long afterClose = count.get();
        Thread.sleep(500);

        Asserts.assertEquals(count.get(), afterClose, "no deliveries may start after close");
    }

    private static void testSubscribeCloseChurn(FramePacing service, long displayId) {
        for (int i = 0; i < 1000; i++) {
            FramePacing.Subscription s = service.subscribe(displayId, (id, t) -> {
                // No-op
            });
            Asserts.assertNotNull(s, "subscribe failed at iteration " + i);
            s.close();
        }

        // All clocks stopped: pacing threads must wind down.
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (countPacingThreads() == 0) return;
            Thread.onSpinWait();
        }

        throw new RuntimeException("Pacing threads still alive after all subscriptions closed");
    }

    private static int countPacingThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("JBR-FramePacing") && thread.isAlive()) count++;
        }

        return count;
    }
}
