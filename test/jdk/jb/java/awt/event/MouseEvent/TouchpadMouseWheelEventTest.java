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

import sun.awt.AWTAccessor;
import sun.awt.KineticScroller;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @test
 * @summary Verify causedByTouchEvent preservation across retargeting, Windows direct scroll scale/timestamp, and full rollback flag
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.awt
 *          java.desktop/sun.awt.windows:+open
 * @run main/othervm --enable-native-access=ALL-UNNAMED TouchpadMouseWheelEventTest disabled
 * @run main/othervm --enable-native-access=ALL-UNNAMED -Dsun.awt.windows.directManipulation.enabled=true TouchpadMouseWheelEventTest
 */
public class TouchpadMouseWheelEventTest {
    private static final int DIRECT_SCROLL_PHASE_BEGIN  = 0;
    private static final int DIRECT_SCROLL_PHASE_UPDATE = 1;
    private static final int DIRECT_SCROLL_PHASE_END    = 2;

    public static void main(String[] args) throws Exception {
        final AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.compareAndSet(null, e));

        final boolean expectDisabled = args.length > 0 && "disabled".equals(args[0]);
        testAccessorContract();

        JFrame frame = null;
        try {
            final AtomicReference<JFrame> frameRef = new AtomicReference<>();
            final AtomicReference<JScrollPane> scrollPaneRef = new AtomicReference<>();
            final AtomicReference<JPanel> innerContentRef = new AtomicReference<>();
            final AtomicReference<JPanel> directTargetRef = new AtomicReference<>();

            SwingUtilities.invokeAndWait(() -> {
                JFrame f = new JFrame("TouchpadMouseWheelEventTest");
                f.setLayout(new BorderLayout());

                JPanel innerContent = new JPanel();
                innerContent.setPreferredSize(new Dimension(300, 800));
                JScrollPane scrollPane = new JScrollPane(innerContent);
                scrollPane.setPreferredSize(new Dimension(300, 200));

                JPanel directTarget = new JPanel();
                directTarget.setPreferredSize(new Dimension(300, 100));

                f.add(scrollPane, BorderLayout.CENTER);
                f.add(directTarget, BorderLayout.SOUTH);
                f.pack();
                f.setLocationRelativeTo(null);
                f.setVisible(true);

                frameRef.set(f);
                scrollPaneRef.set(scrollPane);
                innerContentRef.set(innerContent);
                directTargetRef.set(directTarget);
            });

            frame = frameRef.get();
            if (expectDisabled) {
                testDirectManipulationDisabledRollback(frame, scrollPaneRef.get(), innerContentRef.get());
            } else {
                testContainerRetargetingAndScrollPaneBubbling(
                        frame, scrollPaneRef.get(), innerContentRef.get(), directTargetRef.get());

                testWindowsDirectScrollScaleTimestampAndFling(
                        frame, scrollPaneRef.get(), innerContentRef.get());
            }
        } finally {
            final JFrame f = frame;
            if (f != null) {
                SwingUtilities.invokeAndWait(f::dispose);
            }
        }
        SwingUtilities.invokeAndWait(() -> {});
        if (uncaught.get() != null) {
            throw new RuntimeException("Uncaught exception during test", uncaught.get());
        }
    }

    private static void testAccessorContract() {
        JPanel source = new JPanel();
        AWTAccessor.MouseEventAccessor accessor = AWTAccessor.getMouseEventAccessor();

        MouseWheelEvent wheelEvent = new MouseWheelEvent(
                source, MouseEvent.MOUSE_WHEEL, 1234L, 0, 10, 20, 10, 20, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1, 1.0);
        check(!accessor.isCausedByTouchEvent(wheelEvent),
                "Default MouseWheelEvent must have causedByTouchEvent=false");

        accessor.setCausedByTouchEvent(wheelEvent, true);
        check(accessor.isCausedByTouchEvent(wheelEvent),
                "setCausedByTouchEvent(true) must set causedByTouchEvent");

        accessor.setCausedByTouchEvent(wheelEvent, false);
        check(!accessor.isCausedByTouchEvent(wheelEvent),
                "causedByTouchEvent must be clearable");
    }

    private static void testContainerRetargetingAndScrollPaneBubbling(
            JFrame frame, JScrollPane scrollPane, JPanel innerContent, JPanel directTarget) throws Exception {
        AWTAccessor.MouseEventAccessor accessor = AWTAccessor.getMouseEventAccessor();
        List<MouseWheelEvent> directEvents = new CopyOnWriteArrayList<>();
        List<MouseWheelEvent> scrollPaneEvents = new CopyOnWriteArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            directTarget.addMouseWheelListener(directEvents::add);
            scrollPane.addMouseWheelListener(scrollPaneEvents::add);
        });

        // 1. Retargeting directly from heavyweight JFrame to lightweight child with MouseWheelListener
        SwingUtilities.invokeAndWait(() -> {
            Point ptInFrame = SwingUtilities.convertPoint(directTarget, 20, 20, frame);
            Point ptOnScreen = new Point(ptInFrame);
            SwingUtilities.convertPointToScreen(ptOnScreen, frame);

            MouseWheelEvent touchpadWheel = new MouseWheelEvent(
                    frame, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                    ptInFrame.x, ptInFrame.y, ptOnScreen.x, ptOnScreen.y,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1, 1.0);
            accessor.setCausedByTouchEvent(touchpadWheel, true);
            frame.dispatchEvent(touchpadWheel);

            MouseWheelEvent physicalWheel = new MouseWheelEvent(
                    frame, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                    ptInFrame.x, ptInFrame.y, ptOnScreen.x, ptOnScreen.y,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1, 1.0);
            frame.dispatchEvent(physicalWheel);
        });

        check(directEvents.size() == 2, "Expected 2 retargeted events on directTarget, got " + directEvents.size());
        check(directEvents.get(0).getSource() == directTarget, "Event must be retargeted to directTarget");
        check(accessor.isCausedByTouchEvent(directEvents.get(0)),
                "Container retargeting must preserve causedByTouchEvent=true");
        check(!accessor.isCausedByTouchEvent(directEvents.get(1)),
                "Physical wheel event must remain causedByTouchEvent=false after retargeting");

        // 2. Retargeting to inner lightweight panel without listener + ancestor forwarding to JScrollPane
        SwingUtilities.invokeAndWait(() -> {
            Point ptInFrame = SwingUtilities.convertPoint(innerContent, 30, 30, frame);
            Point ptOnScreen = new Point(ptInFrame);
            SwingUtilities.convertPointToScreen(ptOnScreen, frame);

            MouseWheelEvent touchpadWheel = new MouseWheelEvent(
                    frame, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                    ptInFrame.x, ptInFrame.y, ptOnScreen.x, ptOnScreen.y,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1, 1.5);
            accessor.setCausedByTouchEvent(touchpadWheel, true);
            frame.dispatchEvent(touchpadWheel);

            MouseWheelEvent physicalWheel = new MouseWheelEvent(
                    frame, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                    ptInFrame.x, ptInFrame.y, ptOnScreen.x, ptOnScreen.y,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1, 1.0);
            frame.dispatchEvent(physicalWheel);
        });

        check(scrollPaneEvents.size() >= 2,
                "Expected at least 2 wheel events bubbled to JScrollPane, got " + scrollPaneEvents.size());
        check(scrollPaneEvents.get(0).getSource() == scrollPane, "Event must bubble to JScrollPane");
        check(accessor.isCausedByTouchEvent(scrollPaneEvents.get(0)),
                "JScrollPane ancestor retargeting must preserve causedByTouchEvent=true");
        check(!accessor.isCausedByTouchEvent(scrollPaneEvents.get(1)),
                "Physical wheel event bubbled to JScrollPane must have causedByTouchEvent=false");

        // Exercise Component's ancestor forwarding without LightweightDispatcher.
        // This path relies on AWTEvent.copyPrivateDataInto.
        scrollPaneEvents.clear();
        SwingUtilities.invokeAndWait(() -> {
            Point screen = innerContent.getLocationOnScreen();
            MouseWheelEvent touchpadWheel = new MouseWheelEvent(
                    innerContent, MouseEvent.MOUSE_WHEEL, 1234L, 0,
                    30, 30, screen.x + 30, screen.y + 30,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 0, 0.5);
            accessor.setCausedByTouchEvent(touchpadWheel, true);
            innerContent.dispatchEvent(touchpadWheel);
            innerContent.dispatchEvent(new MouseWheelEvent(
                    innerContent, MouseEvent.MOUSE_WHEEL, 5678L, 0,
                    30, 30, screen.x + 30, screen.y + 30,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1, 1.0));
        });
        check(scrollPaneEvents.size() == 2, "Expected two events forwarded by Component");
        MouseWheelEvent forwarded = scrollPaneEvents.get(0);
        check(forwarded.getSource() == scrollPane && accessor.isCausedByTouchEvent(forwarded),
                "Component ancestor forwarding must preserve causedByTouchEvent");
        check(forwarded.getWhen() == 1234L && forwarded.getPreciseWheelRotation() == 0.5,
                "Component ancestor forwarding must preserve timestamp and precise rotation");
        check(!accessor.isCausedByTouchEvent(scrollPaneEvents.get(1)),
                "Component ancestor forwarding must keep physical wheel events unmarked");
    }

    private static void testWindowsDirectScrollScaleTimestampAndFling(
            JFrame frame, JScrollPane scrollPane, JPanel innerContent) throws Exception {
        Object peer = AWTAccessor.getComponentAccessor().getPeer(frame);
        Class<?> wCompPeerClass = Class.forName("sun.awt.windows.WComponentPeer");
        check(wCompPeerClass.isInstance(peer), "Expected WComponentPeer on Windows, got " + peer);

        Class<?> kineticScrollerClass = Class.forName("sun.awt.windows.WKineticScroller");
        Field enabledField = kineticScrollerClass.getDeclaredField("ENABLED");
        enabledField.setAccessible(true);
        check(enabledField.getBoolean(null), "WKineticScroller.ENABLED must be true when enabled");
        check(isDirectManipulationDllLoaded(),
                "DirectManipulation.dll should be loaded when DirectManipulation is enabled");

        Method handleDirectScroll = wCompPeerClass.getDeclaredMethod(
                "handleDirectScroll",
                double.class, double.class, int.class,
                int.class, int.class, int.class, int.class, int.class);
        handleDirectScroll.setAccessible(true);

        testKineticVelocityCalibration(kineticScrollerClass);

        AWTAccessor.MouseEventAccessor accessor = AWTAccessor.getMouseEventAccessor();
        List<MouseWheelEvent> received = new CopyOnWriteArrayList<>();
        AtomicLong dispatchTimestamp = new AtomicLong();

        final Point ptInFrameRef = new Point();
        final Point ptOnScreenRef = new Point();
        SwingUtilities.invokeAndWait(() -> {
            Point p = SwingUtilities.convertPoint(innerContent, 40, 40, frame);
            ptInFrameRef.setLocation(p);
            Point s = new Point(p);
            SwingUtilities.convertPointToScreen(s, frame);
            ptOnScreenRef.setLocation(s);
            scrollPane.addMouseWheelListener(e -> {
                dispatchTimestamp.set(System.currentTimeMillis());
                received.add(e);
            });
        });

        final int x = ptInFrameRef.x;
        final int y = ptInFrameRef.y;
        final int xAbs = ptOnScreenRef.x;
        final int yAbs = ptOnScreenRef.y;

        // 1. Check macOS-aligned scale: 1 logical px = 0.1 preciseWheelRotation, scrollAmount = 1, WHEEL_UNIT_SCROLL
        SwingUtilities.invokeAndWait(() -> {
            try {
                handleDirectScroll.invoke(peer, 0.0, 0.0, DIRECT_SCROLL_PHASE_BEGIN, x, y, xAbs, yAbs, 0);
                handleDirectScroll.invoke(peer, 0.0, 15.0, DIRECT_SCROLL_PHASE_UPDATE, x, y, xAbs, yAbs, 0);
                handleDirectScroll.invoke(peer, 0.0, 10.0, DIRECT_SCROLL_PHASE_UPDATE, x, y, xAbs, yAbs, 0);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        check(received.size() == 2, "Expected 2 direct scroll events on EDT, got " + received.size());
        MouseWheelEvent ev1 = received.get(0);
        MouseWheelEvent ev2 = received.get(1);
        check(ev1.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL, "scrollType must be WHEEL_UNIT_SCROLL");
        check(ev1.getScrollAmount() == 1, "scrollAmount must be 1, got " + ev1.getScrollAmount());
        check(Math.abs(ev1.getPreciseWheelRotation() - 1.5) < 1e-9,
                "15 px must produce preciseWheelRotation=1.5, got " + ev1.getPreciseWheelRotation());
        check(ev1.getWheelRotation() == 1, "1.5 rotation must accumulate to 1, got " + ev1.getWheelRotation());
        check(accessor.isCausedByTouchEvent(ev1), "Direct scroll event must be marked causedByTouchEvent=true");

        check(Math.abs(ev2.getPreciseWheelRotation() - 1.0) < 1e-9,
                "10 px must produce preciseWheelRotation=1.0, got " + ev2.getPreciseWheelRotation());
        // 0.5 leftover + 1.0 = 1.5 -> 1 integer step
        check(ev2.getWheelRotation() == 1, "0.5 + 1.0 rotation must accumulate to 1, got " + ev2.getWheelRotation());
        check(accessor.isCausedByTouchEvent(ev2), "Second direct scroll event must be marked causedByTouchEvent=true");

        // 2. Check honest 'when' timestamp of the first coalesced frame when EDT is busy
        received.clear();
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        CountDownLatch flushed = new CountDownLatch(1);

        EventQueue.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        check(edtBlocked.await(5, TimeUnit.SECONDS), "Timed out waiting to block EDT");

        handleDirectScroll.invoke(peer, 0.0, 0.0, DIRECT_SCROLL_PHASE_BEGIN, x, y, xAbs, yAbs, 0);
        long tBeforeFirst = System.currentTimeMillis();
        handleDirectScroll.invoke(peer, 0.0, 12.0, DIRECT_SCROLL_PHASE_UPDATE, x, y, xAbs, yAbs, 0);
        long tAfterFirst = System.currentTimeMillis();

        Thread.sleep(60);
        handleDirectScroll.invoke(peer, 0.0, 18.0, DIRECT_SCROLL_PHASE_UPDATE, x, y, xAbs, yAbs, 0);
        Thread.sleep(40);

        releaseEdt.countDown();
        EventQueue.invokeLater(flushed::countDown);
        check(flushed.await(5, TimeUnit.SECONDS), "Timed out waiting for EDT flush");

        check(received.size() == 1, "Expected 1 coalesced event after EDT stall, got " + received.size());
        MouseWheelEvent coalesced = received.get(0);
        long actualDispatchMs = dispatchTimestamp.get();

        check(Math.abs(coalesced.getPreciseWheelRotation() - 3.0) < 1e-9,
                "Coalesced 12+18=30 px must produce preciseWheelRotation=3.0, got "
                        + coalesced.getPreciseWheelRotation());
        check(coalesced.getWhen() >= tBeforeFirst && coalesced.getWhen() <= tAfterFirst,
                "Coalesced event getWhen()=" + coalesced.getWhen()
                        + " must match first frame arrival [" + tBeforeFirst + ".." + tAfterFirst + "]");
        long visibleAgeMs = actualDispatchMs - coalesced.getWhen();
        check(visibleAgeMs >= 80,
                "EDT stall age (dispatch - getWhen) should be >= 80 ms, got " + visibleAgeMs + " ms");
        check(accessor.isCausedByTouchEvent(coalesced),
                "Coalesced direct scroll event must preserve causedByTouchEvent=true");

        // A new gesture may clear a batch while its EDT flush is still queued.
        // Its first update must establish a new timestamp even though a flush
        // is already scheduled, and no delta from the old batch may escape.
        received.clear();
        CountDownLatch blockedAgain = new CountDownLatch(1);
        CountDownLatch releaseAgain = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            blockedAgain.countDown();
            try {
                check(releaseAgain.await(5, TimeUnit.SECONDS), "Timed out releasing EDT");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        check(blockedAgain.await(5, TimeUnit.SECONDS), "Timed out blocking EDT again");
        long freshBefore;
        long freshAfter;
        try {
            handleDirectScroll.invoke(peer, 0.0, 100.0, DIRECT_SCROLL_PHASE_UPDATE, x, y, xAbs, yAbs, 0);
            handleDirectScroll.invoke(peer, 0.0, 0.0, DIRECT_SCROLL_PHASE_BEGIN, x, y, xAbs, yAbs, 0);
            Thread.sleep(30);
            freshBefore = System.currentTimeMillis();
            handleDirectScroll.invoke(peer, 0.0, -5.0, DIRECT_SCROLL_PHASE_UPDATE, x, y, xAbs, yAbs, 0);
            freshAfter = System.currentTimeMillis();
            Thread.sleep(30);
        } finally {
            releaseAgain.countDown();
        }
        SwingUtilities.invokeAndWait(() -> {});
        check(received.size() == 1, "Expected only the new gesture's batch");
        MouseWheelEvent fresh = received.get(0);
        check(Math.abs(fresh.getPreciseWheelRotation() + 0.5) < 1e-9,
                "A cleared batch must not contribute deltas to the next gesture");
        check(fresh.getWhen() >= freshBefore && fresh.getWhen() <= freshAfter,
                "A cleared batch must not contribute its timestamp to the next gesture");

        // 3. Check KineticScroller fling events also carry causedByTouchEvent=true
        List<MouseWheelEvent> flingEvents = new CopyOnWriteArrayList<>();
        CountDownLatch flingLatch = new CountDownLatch(1);

        handleDirectScroll.invoke(peer, 0.0, 0.0, DIRECT_SCROLL_PHASE_BEGIN, x, y, xAbs, yAbs, 0);
        for (int i = 0; i < 5; i++) {
            handleDirectScroll.invoke(peer, 0.0, 25.0, DIRECT_SCROLL_PHASE_UPDATE, x, y, xAbs, yAbs, 0);
            Thread.sleep(8);
        }
        // Flush active updates first, then attach listener for fling-only events
        SwingUtilities.invokeAndWait(() -> scrollPane.addMouseWheelListener(e -> {
            flingEvents.add(e);
            flingLatch.countDown();
        }));
        handleDirectScroll.invoke(peer, 0.0, 0.0, DIRECT_SCROLL_PHASE_END, x, y, xAbs, yAbs, 0);

        check(flingLatch.await(2, TimeUnit.SECONDS), "Expected KineticScroller fling events after fast swipe");
        check(!flingEvents.isEmpty(), "Expected at least one fling MouseWheelEvent");
        MouseWheelEvent flingEvent = flingEvents.get(0);
        check(accessor.isCausedByTouchEvent(flingEvent), "KineticScroller fling event must have causedByTouchEvent=true");
        check(flingEvent.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL && flingEvent.getScrollAmount() == 1,
                "KineticScroller fling event must use WHEEL_UNIT_SCROLL and scrollAmount=1");

        // Stop active fling cleanly
        handleDirectScroll.invoke(peer, 0.0, 0.0, DIRECT_SCROLL_PHASE_BEGIN, x, y, xAbs, yAbs, 0);
    }

    private static void testDirectManipulationDisabledRollback(
            JFrame frame, JScrollPane scrollPane, JPanel innerContent) throws Exception {
        Object peer = AWTAccessor.getComponentAccessor().getPeer(frame);
        Class<?> wCompPeerClass = Class.forName("sun.awt.windows.WComponentPeer");
        check(wCompPeerClass.isInstance(peer), "Expected WComponentPeer on Windows, got " + peer);

        Class<?> kineticScrollerClass = Class.forName("sun.awt.windows.WKineticScroller");
        Field enabledField = kineticScrollerClass.getDeclaredField("ENABLED");
        enabledField.setAccessible(true);
        check(!enabledField.getBoolean(null), "WKineticScroller.ENABLED must be false by default");

        Field peerScrollerField = wCompPeerClass.getDeclaredField("kineticScroller");
        peerScrollerField.setAccessible(true);
        check(peerScrollerField.get(peer) == null,
                "WComponentPeer.kineticScroller must not be allocated when directManipulation is disabled");
        Field sharedScrollerField = kineticScrollerClass.getDeclaredField("scroller");
        sharedScrollerField.setAccessible(true);
        check(sharedScrollerField.get(null) == null,
                "WKineticScroller.scroller must not be allocated when directManipulation is disabled");

        check(!isDirectManipulationDllLoaded(),
                "DirectManipulation.dll must not be loaded by default when directManipulation is disabled");

        List<MouseWheelEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch wheelLatch = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> scrollPane.addMouseWheelListener(e -> {
            received.add(e);
            wheelLatch.countDown();
        }));

        Method handleDirectScroll = wCompPeerClass.getDeclaredMethod(
                "handleDirectScroll",
                double.class, double.class, int.class,
                int.class, int.class, int.class, int.class, int.class);
        handleDirectScroll.setAccessible(true);
        handleDirectScroll.invoke(peer, 0.0, 0.0, DIRECT_SCROLL_PHASE_BEGIN, 10, 10, 10, 10, 0);
        handleDirectScroll.invoke(peer, 0.0, 25.0, DIRECT_SCROLL_PHASE_UPDATE, 10, 10, 10, 10, 0);
        handleDirectScroll.invoke(peer, 0.0, 0.0, DIRECT_SCROLL_PHASE_END, 10, 10, 10, 10, 0);
        SwingUtilities.invokeAndWait(() -> {});
        check(received.isEmpty(), "handleDirectScroll must be a complete no-op when disabled");

        // Verify WComponentPeer.postEvent works cleanly for all event types without kineticScroller
        Method postEvent = wCompPeerClass.getDeclaredMethod("postEvent", AWTEvent.class);
        postEvent.setAccessible(true);

        final Point ptInFrame = new Point();
        final Point ptOnScreen = new Point();
        SwingUtilities.invokeAndWait(() -> {
            Point p = SwingUtilities.convertPoint(innerContent, 30, 30, frame);
            ptInFrame.setLocation(p);
            Point s = new Point(p);
            SwingUtilities.convertPointToScreen(s, frame);
            ptOnScreen.setLocation(s);
        });

        long now = System.currentTimeMillis();
        postEvent.invoke(peer, new MouseEvent(frame, MouseEvent.MOUSE_PRESSED, now, 0,
                ptInFrame.x, ptInFrame.y, ptOnScreen.x, ptOnScreen.y, 1, false, MouseEvent.BUTTON1));
        postEvent.invoke(peer, new KeyEvent(frame, KeyEvent.KEY_PRESSED, now, 0,
                KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
        postEvent.invoke(peer, new MouseWheelEvent(frame, MouseEvent.MOUSE_WHEEL, now, 0,
                ptInFrame.x, ptInFrame.y, ptOnScreen.x, ptOnScreen.y, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1, 1.0));

        check(wheelLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for regular MouseWheelEvent");
        check(received.size() == 1, "Expected 1 regular MouseWheelEvent, got " + received.size());
        check(!AWTAccessor.getMouseEventAccessor().isCausedByTouchEvent(received.get(0)),
                "Regular MouseWheelEvent must have causedByTouchEvent=false");
    }

    private static boolean isDirectManipulationDllLoaded() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
            MethodHandle getModuleHandleW = Linker.nativeLinker().downcallHandle(
                    kernel32.findOrThrow("GetModuleHandleW"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            byte[] utf16Z = ("DirectManipulation.dll\0").getBytes(StandardCharsets.UTF_16LE);
            MemorySegment nameSeg = arena.allocate(utf16Z.length, 2);
            MemorySegment.copy(utf16Z, 0, nameSeg, ValueLayout.JAVA_BYTE, 0, utf16Z.length);
            MemorySegment handle = (MemorySegment) getModuleHandleW.invokeExact(nameSeg);
            return !MemorySegment.NULL.equals(handle);
        } catch (Throwable t) {
            throw new Exception("Failed to query GetModuleHandleW", t);
        }
    }

    private static void testKineticVelocityCalibration(Class<?> kineticScrollerClass) throws Exception {
        Field scaleField = kineticScrollerClass.getDeclaredField("VECTOR_SCALE");
        scaleField.setAccessible(true);
        double scale = scaleField.getDouble(null);
        Field scrollerField = kineticScrollerClass.getDeclaredField("scroller");
        scrollerField.setAccessible(true);
        KineticScroller scroller = (KineticScroller) scrollerField.get(null);
        SwingUtilities.invokeAndWait(() -> {
            try {
                scroller.stop();
                scroller.clearSamples();
                scroller.addSample(0, 0, 0);
                scroller.addSample(100, 0, 16 * scale);
                check(!scroller.startFling(100, () -> 8_333_333L, (dx, dy, last) -> false),
                        "160 px/s must remain below the original 175 px/s fling threshold");
                scroller.clearSamples();
                scroller.addSample(0, 0, 0);
                scroller.addSample(100, 0, 18 * scale);
                check(scroller.startFling(100, () -> 8_333_333L, (dx, dy, last) -> false),
                        "180 px/s must remain above the original fling threshold");
            } finally {
                scroller.stop();
                scroller.clearSamples();
            }
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new RuntimeException(message);
        }
    }
}
