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

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * @test
 * @summary Regression test for JBR-9776 A decorated frame or dialog must keep AppKit's default
 *          NSWindow.animationBehavior.
 * @key headful
 * @requires (os.family == "mac")
 * @run main/othervm --enable-native-access=ALL-UNNAMED
 *      --add-opens java.desktop/java.awt=ALL-UNNAMED
 *      --add-opens java.desktop/sun.lwawt=ALL-UNNAMED
 *      --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED
 *      WindowAnimationBehavior
 * @run main/othervm -Dapple.awt.window.animation=true --enable-native-access=ALL-UNNAMED
 *      --add-opens java.desktop/java.awt=ALL-UNNAMED
 *      --add-opens java.desktop/sun.lwawt=ALL-UNNAMED
 *      --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED
 *      WindowAnimationBehavior animationEnabled
 */

public class WindowAnimationBehavior {

    // NSWindowAnimationBehavior
    private static final long DEFAULT = 0;
    private static final long NONE = 2;

    private static MethodHandle selRegisterName;
    private static MethodHandle objcMsgSendLong;

    private record Case(String label, Window window, boolean decorated) {}

    public static void main(String[] args) throws Exception {
        // The -Dapple.awt.window.animation=true escape hatch must restore AppKit's default for
        // every window kind, on top of whatever the decorated/undecorated rule says.
        boolean animationEnabled = args.length > 0 && "animationEnabled".equals(args[0]);

        initObjectiveCBridge();

        JFrame decoratedFrame = new JFrame("decorated-frame");
        JFrame undecoratedFrame = new JFrame("undecorated-frame");
        undecoratedFrame.setUndecorated(true);
        JDialog decoratedDialog = new JDialog(decoratedFrame, "decorated-dialog");
        JWindow undecoratedWindow = new JWindow(decoratedFrame);

        List<Case> cases = List.of(
                new Case("JFrame (decorated)", decoratedFrame, true),
                new Case("JDialog (decorated)", decoratedDialog, true),
                new Case("JFrame (undecorated)", undecoratedFrame, false),
                new Case("JWindow (undecorated)", undecoratedWindow, false));

        List<String> failures = new ArrayList<>();
        try {
            // animationBehavior is applied in AWTWindow.initWithPlatformWindow, i.e. when the
            // peer is created. The windows never have to be shown, which keeps the test free of
            // timing assumptions and stops it stealing focus.
            SwingUtilities.invokeAndWait(() -> {
                for (Case c : cases) {
                    c.window().addNotify();
                }
            });

            for (Case c : cases) {
                long expected = (c.decorated() || animationEnabled) ? DEFAULT : NONE;
                long actual = animationBehavior(nsWindowPtr(c.window()));
                System.out.printf("%-22s animationBehavior=%d (%s), expected %d (%s)%n",
                        c.label(), actual, name(actual), expected, name(expected));
                if (actual != expected) {
                    failures.add(c.label() + ": animationBehavior=" + actual + " (" + name(actual)
                            + "), expected " + expected + " (" + name(expected) + ")");
                }
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Case c : cases) {
                    c.window().dispose();
                }
            });
        }

        if (!failures.isEmpty()) {
            throw new RuntimeException("JBR-9776: wrong NSWindow.animationBehavior"
                    + (animationEnabled ? " with -Dapple.awt.window.animation=true" : "")
                    + ": " + String.join("; ", failures));
        }
    }

    private static String name(long behavior) {
        if (behavior == DEFAULT) return "NSWindowAnimationBehaviorDefault";
        if (behavior == NONE) return "NSWindowAnimationBehaviorNone";
        return "NSWindowAnimationBehavior(" + behavior + ")";
    }

    private static void initObjectiveCBridge() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup objc = SymbolLookup
                .libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global())
                .or(linker.defaultLookup());
        selRegisterName = linker.downcallHandle(
                objc.find("sel_registerName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        // NSInteger -[NSWindow animationBehavior] -- no arguments beyond the implicit self/_cmd.
        objcMsgSendLong = linker.downcallHandle(
                objc.find("objc_msgSend").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    private static long animationBehavior(long nsWindowPtr) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment selector = (MemorySegment) selRegisterName.invokeExact(
                    arena.allocateFrom("animationBehavior"));
            return (long) objcMsgSendLong.invokeExact(MemorySegment.ofAddress(nsWindowPtr), selector);
        } catch (Throwable t) {
            throw new RuntimeException("objc_msgSend(animationBehavior) failed", t);
        }
    }

    /** Component.peer -> LWWindowPeer.getPlatformWindow() -> CFRetainedResource.ptr (the NSWindow). */
    private static long nsWindowPtr(Window window) throws Exception {
        Field peerField = Component.class.getDeclaredField("peer");
        peerField.setAccessible(true);
        Object peer = peerField.get(window);
        if (peer == null) {
            throw new IllegalStateException("No peer for " + window);
        }
        Method getPlatformWindow = peer.getClass().getMethod("getPlatformWindow");
        getPlatformWindow.setAccessible(true);
        Object platformWindow = getPlatformWindow.invoke(peer);
        Field ptr = Class.forName("sun.lwawt.macosx.CFRetainedResource").getDeclaredField("ptr");
        ptr.setAccessible(true);
        long value = ptr.getLong(platformWindow);
        if (value == 0) {
            throw new IllegalStateException("Null NSWindow pointer for " + window);
        }
        return value;
    }
}
