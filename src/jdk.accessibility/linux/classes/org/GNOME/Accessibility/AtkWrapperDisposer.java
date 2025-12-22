/*
 * Copyright 2025 JetBrains s.r.o.
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

import javax.accessibility.*;
import java.util.*;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.PhantomReference;
import java.security.PrivilegedAction;

import jdk.internal.misc.InnocuousThread;
import sun.util.logging.PlatformLogger;

/**
 * Manages the association between an AccessibleContext
 * and native resources. Can be used to create such
 * associations and ensures that the native resource associated
 * with the AccessibleContext is released when the AccessibleContext
 * is garbage collected.
 * <p>
 * Java classes are fully responsible for creating associations between
 * an AccessibleContext and native resources. For example, when a JNI upcall method
 * returns an AccessibleContext, native assumes that the corresponding native resource
 * has already been created and the association between the AccessibleContext and the
 * native resource exists.
 */
@SuppressWarnings("removal")
public class AtkWrapperDisposer implements Runnable {
    // Reference queue that holds objects ready for garbage collection
    private static final ReferenceQueue<AccessibleContext> queue = new ReferenceQueue<>();

    // Maps PhantomReferences and their associated native resource pointer
    private static final Map<PhantomReference<AccessibleContext>, Long> phantomMap = new HashMap<>();

    // Maps AccessibleContext object with native resource pointer
    private static final WeakHashMap<AccessibleContext, Long> weakHashMap = new WeakHashMap<>();

    private static final Object lock = new Object();
    private static final PlatformLogger log = PlatformLogger.getLogger("org.GNOME.Accessibility.AtkWrapperDisposer");
    private static volatile AtkWrapperDisposer INSTANCE = null;

    private AtkWrapperDisposer() {
    }

    private void init() {
        Thread t = InnocuousThread.newThread("Atk Wrapper Disposer", INSTANCE, Thread.MAX_PRIORITY);
        t.setContextClassLoader(null);
        t.setDaemon(true);
        t.start();
    }

    public static synchronized AtkWrapperDisposer getInstance() {
        if (INSTANCE == null) {
            synchronized (AtkWrapperDisposer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AtkWrapperDisposer();
                    INSTANCE.init();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Monitors the reference queue and releases native resources when an associated
     * AccessibleContext is garbage collected. The native resource is released using
     * {@link AtkWrapper#releaseNativeResources}.
     */
    public void run() {
        while (true) {
            try {
                // When an AccessibleContext is freed, release the associated native resource
                Reference<? extends AccessibleContext> obj = queue.remove();
                long nativeReference;
                synchronized (lock) {
                    nativeReference = phantomMap.remove(obj);
                }
                AtkWrapper.releaseNativeResources(nativeReference);
                obj.clear();
                obj = null;
            } catch (Exception e) {
                if (log.isLoggable(PlatformLogger.Level.SEVERE)) {
                    log.severe("Exception while removing reference: ", e);
                }
            }
        }
    }

    /**
     * Associates an AccessibleContext with a newly created native resource. If the AccessibleContext
     * is not already registered, a new native resource pointer is created using
     * {@link AtkWrapper#createNativeResources}
     *
     * @param ac The AccessibleContext to associate with a native resource.
     */
    public void addRecord(AccessibleContext ac) {
        if (ac == null) {
            return;
        }
        synchronized (lock) {
            if (!weakHashMap.containsKey(ac)) {
                long nativeReference = AtkWrapper.createNativeResources(ac);
                if (nativeReference != -1) {
                    PhantomReference<AccessibleContext> phantomReference = new PhantomReference<>(ac, queue);
                    phantomMap.put(phantomReference, nativeReference);
                    weakHashMap.put(ac, nativeReference);
                }
            }
        }
    }

    private long getRecord(AccessibleContext ac) {
        synchronized (lock) {
            if (weakHashMap.containsKey(ac)) {
                return weakHashMap.get(ac);
            }
        }
        return -1;
    }

    // JNI upcalls section

    /**
     * Retrieves the native resource associated with the given AccessibleContext.
     * If no record exists, returns -1.
     *
     * @param ac The AccessibleContext whose native resource is requested.
     * @return The native resource pointer associated with the given AccessibleContext,
     * or -1 if no record exists.
     */
    private static long get_resource(AccessibleContext ac) {
        return AtkWrapperDisposer.getInstance().getRecord(ac);
    }
}