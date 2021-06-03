/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package sun.lwawt.macosx;

import sun.lwawt.macosx.concurrent.Dispatch;

import java.awt.EventQueue;
import java.awt.AWTError;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

@SuppressWarnings("removal")
public class CThreading {
    static String APPKIT_THREAD_NAME = "AWT-AppKit";

    static boolean isEventQueue() {
        return EventQueue.isDispatchThread();
    }

    private static native boolean isMainThread();

    public static boolean isAppKit() {
        if (APPKIT_THREAD_NAME.equals(Thread.currentThread().getName())) return true;

        if (isMainThread()) {
            Thread.currentThread().setName(APPKIT_THREAD_NAME);
            return true;
        }
        return false;
    }


    static boolean assertEventQueue() {
        final boolean isEventQueue = isEventQueue();
        assert isEventQueue : "Threading violation: not EventQueue thread";
        return isEventQueue;
    }

    static boolean assertNotEventQueue() {
        final boolean isNotEventQueue = isEventQueue();
        assert isNotEventQueue :     "Threading violation: EventQueue thread";
        return isNotEventQueue;
    }

    static boolean assertAppKit() {
        final boolean isAppKitThread = isAppKit();
        assert isAppKitThread : "Threading violation: not AppKit thread";
        return isAppKitThread;
    }

    static boolean assertNotAppKit() {
        final boolean isNotAppKitThread = !isAppKit();
        assert isNotAppKitThread : "Threading violation: AppKit thread";
        return isNotAppKitThread;
    }

    public static <V> V executeOnAppKit(final Callable<V> command) throws Throwable {
        if (!isAppKit()) {
            Dispatch dispatch = Dispatch.getInstance();

            if (dispatch == null) {
                throw new AWTError("Could not get Dispatch object");
            }

            Callable<V> commandWithTNameFix = () -> {
                if (!APPKIT_THREAD_NAME.equals(Thread.currentThread().getName())) {
                    Thread.currentThread().setName(APPKIT_THREAD_NAME);
                }

                return command.call();
            };

            FutureTask<V> future = new FutureTask<>(commandWithTNameFix);

            dispatch.getNonBlockingMainQueueExecutor().execute(future);

            try {
                return future.get();
            } catch (InterruptedException e) {
                throw new AWTError(e.getMessage());
            } catch (ExecutionException e) {
                throw e.getCause();
            }
        } else
            return command.call();
    }

    public static <V> V privilegedExecuteOnAppKit(Callable<V> command)
            throws Exception {
        try {
            return java.security.AccessController.doPrivileged(
                    (PrivilegedExceptionAction<V>) () -> {
                        //noinspection TryWithIdenticalCatches
                        try {
                            return executeOnAppKit(command);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Error e) {
                            throw e;
                        } catch (Throwable throwable) {
                            throw new Exception(throwable);
                        }
                    }
            );
        } catch (PrivilegedActionException e) {
            throw e.getException();
        }
    }

    public static void executeOnAppKit(Runnable command) {
        if (!isAppKit()) {
            Dispatch dispatch = Dispatch.getInstance();

            if (dispatch != null) {
                dispatch.getNonBlockingMainQueueExecutor().execute(command);
            }
            else {
                throw new AWTError("Could not get Dispatch object");
            }
        } else
            command.run();
    }
}
