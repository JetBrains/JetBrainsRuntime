/*
 * Copyright (c) 2018, Red Hat, Inc.
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

package org.GNOME.Accessibility;

import javax.swing.*;
import java.util.concurrent.*;

import sun.util.logging.PlatformLogger;

/**
 * AtkUtil:
 * That class is used to wrap the callback on Java object
 * to avoid the concurrency of AWT objects.
 *
 * @autor Giuseppe Capaldo
 */
public class AtkUtil {
    private static final PlatformLogger log = PlatformLogger.getLogger("org.GNOME.Accessibility.AtkUtil");

    private AtkUtil() {
    }

    /**
     * invokeInSwingAndWait:
     * Invoked when we need to make an asynchronous callback on
     * a Java object and this callback has a return value.
     * this method doesn't launch any exception because in case
     * of problems it prints a warning and returns the default
     * value that is passed to it
     *
     * @param function A Callable object that return T value
     * @param d        A T object that is returned if an exception occurs
     * @return The return value of the original function or the
     * default value. Obviously if the value is a primitive type
     * this will automatically wrapped from java in the
     * corresponding object
     */
    public static <T> T invokeInSwingAndWait(Callable<T> function, T d) {
        if (SwingUtilities.isEventDispatchThread()) {
            // We are already running in the EDT, we can call it directly
            try {
                return function.call();
            } catch (Exception ex) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.severe("Error occurred while executing function. Returning default value.", ex);
                }
                return d;
            }
        }

        RunnableFuture<T> wf = new FutureTask<>(function);
        SwingUtilities.invokeLater(wf);
        try {
            return wf.get();
        } catch (InterruptedException | ExecutionException ex) {
            if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                log.severe("Swing task execution interrupted or failed, returning default value.", ex);
            }
            return d;
        }
    }

    /**
     * invokeInSwing:
     * Invoked when we need to make an asynchronous callback on
     * some Java object and this callback hasn't a return value.
     *
     * @param function A Runnable object that doesn't return some value
     */
    public static void invokeInSwing(Runnable function) {
        if (SwingUtilities.isEventDispatchThread()) {
            // We are already running in the EDT, we can call it directly
            function.run();
            return;
        }

        SwingUtilities.invokeLater(function);
    }

}
