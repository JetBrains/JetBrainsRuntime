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
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/*
 * @test
 * @summary A clock that runs out of listeners decides to stop on its own tick
 * thread and only afterwards retires its registry entry under the service
 * lock, so subscribe() can find it mid-teardown. Two windows are covered.
 * After the stop flag is set, subscribe() must not adopt the dying clock, or
 * the caller gets a live Subscription that never ticks. Before the flag is
 * set, the tick thread has already emptied the listener list, and subscribe()
 * must not read that emptiness as "no clock is running yet", or it starts a
 * second tick thread alongside the first. Both states are planted directly
 * rather than raced for, so the test is deterministic.
 * @key headful
 * @library /test/lib
 * @compile --add-exports java.desktop/sun.awt=ALL-UNNAMED
 * --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED
 * FramePacingTestUtil.java FramePacingSubscribeRaceTest.java
 * @run main/othervm
 * --add-exports java.desktop/sun.awt=ALL-UNNAMED
 * --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED
 * --add-opens java.desktop/sun.awt=ALL-UNNAMED
 * FramePacingSubscribeRaceTest
 */
public class FramePacingSubscribeRaceTest {

    /** Listeners are held weakly by the service; this keeps them alive. */
    private static final List<FramePacing.Listener> anchors = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        FramePacing service = FramePacingTestUtil.createPlatformService();

        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();
        long displayId = service.displayId(gc);
        Asserts.assertNotEquals(displayId, -1L, "default screen must resolve to a display id");

        testSubscribeAfterStopFlagIsSet(service, displayId);
        testSubscribeAfterListenerListWasSwept(service, displayId);

        Reference.reachabilityFence(anchors);
    }

    /**
     * The tick thread has set the stop flag but has not yet retired the
     * registry entry. A subscriber arriving now must get a working clock.
     */
    private static void testSubscribeAfterStopFlagIsSet(FramePacing service, long displayId)
            throws Exception {
        FramePacing.Subscription anchor = subscribe(service, displayId, new AtomicLong());
        Object clock = registeredClock(service, displayId);
        Asserts.assertNotNull(clock, "no clock was registered for the display");

        setStopped(clock, true);

        AtomicLong ticks = new AtomicLong();
        FramePacing.Subscription victim = subscribe(service, displayId, ticks);
        Asserts.assertNotNull(victim, "subscribe returned null for a present display");

        Asserts.assertTrue(await(() -> ticks.get() > 0),
                "subscription handed out while the clock was stopping never ticked");

        victim.close();
        anchor.close();
        awaitQuiescent(displayId);
    }

    /**
     * The tick thread has swept the listener list empty but has not yet
     * decided to stop. A subscriber arriving now must join the running clock,
     * not start a second one.
     */
    private static void testSubscribeAfterListenerListWasSwept(FramePacing service, long displayId)
            throws Exception {
        FramePacing.Subscription anchor = subscribe(service, displayId, new AtomicLong());
        Object clock = registeredClock(service, displayId);
        Asserts.assertNotNull(clock, "no clock was registered for the display");

        // What the sweep in the tick loop leaves behind when every listener
        // has been collected, before the stop condition is evaluated.
        listenerRefs(clock).clear();

        Set<Thread> before = pacingThreads(displayId);
        Asserts.assertEquals(before.size(), 1, "expected exactly one tick thread before subscribing");

        AtomicLong ticks = new AtomicLong();
        FramePacing.Subscription second = subscribe(service, displayId, ticks);

        // Thread identity, not a count: the first thread may legitimately
        // retire on its own at any moment, but a *new* one appearing means
        // subscribe() started a second tick source for one display.
        Set<Thread> after = pacingThreads(displayId);
        after.removeAll(before);
        Asserts.assertTrue(after.isEmpty(),
                "subscribe() started a second tick thread for display " + displayId
                        + " (" + after.size() + " new)");

        if (second != null) second.close();
        anchor.close();
        awaitQuiescent(displayId);
    }

    /**
     * A closed clock's thread only notices at its next tick, so it outlives
     * close() by up to one period. Waiting for it keeps the tick-thread counts
     * in these tests independent of each other.
     */
    private static void awaitQuiescent(long displayId) throws InterruptedException {
        Asserts.assertTrue(await(() -> pacingThreads(displayId).isEmpty()),
                "tick threads for display " + displayId + " outlived their subscriptions");
    }

    private static FramePacing.Subscription subscribe(FramePacing service, long displayId,
                                                      AtomicLong counter) {
        FramePacing.Listener listener = (id, timeNanos) -> counter.incrementAndGet();
        anchors.add(listener);
        return service.subscribe(displayId, listener);
    }

    private static Set<Thread> pacingThreads(long displayId) {
        String name = "JBR-FramePacing-" + displayId;
        Set<Thread> found = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && name.equals(thread.getName())) found.add(thread);
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Object registeredClock(FramePacing service, long displayId) throws Exception {
        Field field = FramePacing.class.getDeclaredField("clocks");
        field.setAccessible(true);
        return ((Map<Long, ?>) field.get(service)).get(displayId);
    }

    private static void setStopped(Object clock, boolean value) throws Exception {
        Field field = clock.getClass().getDeclaredField("stopped");
        field.setAccessible(true);
        field.setBoolean(clock, value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listenerRefs(Object clock) throws Exception {
        Field field = clock.getClass().getDeclaredField("listenerRefs");
        field.setAccessible(true);
        return (List<Object>) field.get(clock);
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) return false;
            Thread.sleep(10);
        }
        return true;
    }
}
