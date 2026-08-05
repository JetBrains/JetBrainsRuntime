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
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.jetbrains.exported.JBRApiSupport;

/**
 * @test
 * @key headful
 * @summary Listener lifecycle under the weak-reference registry: an abandoned
 * subscription (listener and Subscription both unreachable, close()
 * never called) must be reclaimed and its clock must self-stop; a
 * listener the client still holds must keep receiving ticks across
 * GC cycles; close() after the listener was collected must stay
 * safe; and a throwing listener must not starve its siblings.
 * @library /test/lib
 * @compile --add-exports java.desktop/sun.awt=ALL-UNNAMED
 * --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED
 * FramePacingTestUtil.java FramePacingListenerLifecycleTest.java
 * @run main/othervm
 * --add-exports java.desktop/sun.awt=ALL-UNNAMED
 * --add-exports java.base/com.jetbrains.exported=ALL-UNNAMED
 * FramePacingListenerLifecycleTest
 */
public class FramePacingListenerLifecycleTest {

    public static void main(String[] args) throws Exception {
        FramePacing service = FramePacingTestUtil.createPlatformService();

        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();
        long displayId = service.displayId(gc);
        Asserts.assertNotEquals(displayId, -1L, "default screen must resolve to a display id");

        testProxiedListenerFollowsClientAnchor(service, displayId);
        testAbandonedSubscriptionIsReclaimed(service, displayId);
        testHeldListenerSurvivesGc(service, displayId);
        testCloseAfterListenerCollected(service, displayId);
        testThrowingListenerDoesNotStarveSiblings(service, displayId);
    }

    /**
     * Simulates a listener arriving through the JBR API boundary: the service
     * sees a generated proxy wrapper that nothing outside the service
     * references (the wrapper holds the client's listener, never the other
     * way around). Ticks must keep flowing while the client holds their
     * Subscription — the wrapper is pinned by it — and everything must be
     * reclaimed once the subscription is abandoned without close().
     */
    private static void testProxiedListenerFollowsClientAnchor(FramePacing service, long displayId)
            throws Exception {
        AtomicLong tickCount = new AtomicLong();
        FramePacing.Subscription s = subscribeThroughFakeProxy(service, displayId, tickCount);
        Asserts.assertNotNull(s, "subscribe failed for proxied listener");

        // The wrapper has no strong refs outside the service; only the
        // Subscription pins it. Ticks must survive the reap.
        forceGc();

        long before = tickCount.get();

        // Wait up to 5s for more ticks to come in after the GC pass.
        Asserts.assertTrue(await(() -> tickCount.get() > before),
                "proxied listener stopped ticking while the client still holds the Subscription");

        // Client abandons the Subscription without close(): wrapper becomes
        // unreachable, the sweep reclaims it, the clock self-stops.
        s = null;
        forceGc();

        awaitNoPacingThreads("clock did not self-stop after the proxied subscription was abandoned");
    }

    /**
     * Subscribes via a fake proxy wrapper; only the returned Subscription escapes.
     */
    private static FramePacing.Subscription subscribeThroughFakeProxy(FramePacing service,
                                                                      long displayId,
                                                                      AtomicLong count) {
        final class FakeProxyListener
                implements FramePacing.Listener, JBRApiSupport.Proxy {
            private final Object clientListener = new Object(); // what a real wrapper holds

            @Override
            public void onTick(long id, long timeNanos) {
                count.incrementAndGet();
            }

            @Override
            public Object $getProxyTarget() {
                return clientListener;
            }
        }
        return service.subscribe(displayId, new FakeProxyListener());
    }

    /**
     * A client that drops both its listener and its Subscription without
     * calling close() must not leak: once GC reclaims the references, the
     * clock's stale-reference sweep empties the registry and the pacing
     * thread winds down on its own.
     */
    private static void testAbandonedSubscriptionIsReclaimed(FramePacing service, long displayId)
            throws Exception {
        AtomicLong count = subscribeAndAbandon(service, displayId);

        // Ticks flowed while the listener was reachable.
        Asserts.assertTrue(await(() -> count.get() > 0),
                "abandoned-subscription listener never ticked");

        forceGc();

        awaitNoPacingThreads("clock did not self-stop after its abandoned listener was collected");
    }

    /**
     * Subscribes with a listener and Subscription that become unreachable on return.
     */
    private static AtomicLong subscribeAndAbandon(FramePacing service, long displayId) {
        AtomicLong count = new AtomicLong();
        FramePacing.Subscription s =
                service.subscribe(displayId, (id, t) -> count.incrementAndGet());
        Asserts.assertNotNull(s, "subscribe failed");

        // Deliberately no close(): both the lambda and s go unreachable here.
        return count;
    }

    /**
     * A listener the client still strongly holds must keep receiving ticks
     * no matter how many GC cycles run.
     */
    private static void testHeldListenerSurvivesGc(FramePacing service, long displayId)
            throws Exception {
        AtomicLong count = new AtomicLong();
        FramePacing.Listener held = (id, t) -> count.incrementAndGet();
        FramePacing.Subscription s = service.subscribe(displayId, held);
        Asserts.assertNotNull(s);

        forceGc();

        long before = count.get();

        // Wait up to 5s for more ticks to come in after the GC pass.
        Asserts.assertTrue(await(() -> count.get() > before),
                "held listener stopped ticking after GC");

        s.close();
        Reference.reachabilityFence(held);
    }

    /**
     * close() must stay safe and fully clean up when the listener was
     * already collected: no exception, and the pacing thread winds down.
     */
    private static void testCloseAfterListenerCollected(FramePacing service, long displayId)
            throws Exception {
        FramePacing.Subscription s = subscribeDroppingListener(service, displayId);

        forceGc();

        s.close();
        s.close(); // still idempotent

        awaitNoPacingThreads("pacing thread survived close() after listener collection");
    }

    /**
     * Subscribes and returns only the Subscription; the listener goes unreachable on return.
     */
    private static FramePacing.Subscription subscribeDroppingListener(FramePacing service,
                                                                      long displayId) {
        FramePacing.Subscription s = service.subscribe(displayId, (id, t) -> { /* No-op */ });
        Asserts.assertNotNull(s, "subscribe failed");
        return s;
    }

    /**
     * A listener that throws on every tick must be isolated: its sibling on
     * the same clock keeps receiving ticks.
     */
    private static void testThrowingListenerDoesNotStarveSiblings(FramePacing service,
                                                                  long displayId)
            throws Exception {
        CountDownLatch siblingTicks = new CountDownLatch(10);
        FramePacing.Listener throwing = (id, t) -> {
            throw new RuntimeException("deliberate test exception");
        };
        FramePacing.Listener sibling = (id, t) -> siblingTicks.countDown();

        FramePacing.Subscription a = service.subscribe(displayId, throwing);
        FramePacing.Subscription b = service.subscribe(displayId, sibling);
        Asserts.assertNotNull(a);
        Asserts.assertNotNull(b);

        Asserts.assertTrue(siblingTicks.await(10, TimeUnit.SECONDS),
                "sibling listener starved by a throwing listener");

        a.close();
        b.close();
        Reference.reachabilityFence(throwing);
        Reference.reachabilityFence(sibling);

        awaitNoPacingThreads("pacing threads survived closing all subscriptions");
    }

    /**
     * Reaps weak references deterministically: repeated System.gc() with a
     * short wait (per review guidance), verified against a canary reference
     * so the test fails loudly if the GC did not actually collect.
     */
    private static void forceGc() throws InterruptedException {
        Object sacrifice = new Object();
        WeakReference<Object> canary = new WeakReference<>(sacrifice);
        sacrifice = null;

        for (int i = 0; i < 10 && canary.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        Asserts.assertNull(canary.get(),
                "GC did not reap the canary reference; weak-ref semantics untestable here");
    }

    /**
     * Polls the condition every 10 ms for up to 5 seconds.
     *
     * @return true as soon as the condition holds, false if it never did
     */
    private static boolean await(BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) return false;
            Thread.sleep(10);
        }
        return true;
    }

    private static void awaitNoPacingThreads(String message) throws InterruptedException {
        if (!await(() -> countPacingThreads() == 0)) {
            throw new RuntimeException(message);
        }
    }

    private static int countPacingThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("JBR-FramePacing") && thread.isAlive()) count++;
        }
        return count;
    }
}
