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
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Hashtable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Collections;

import sun.java2d.DisposerRecord;
import sun.awt.util.ThreadGroupUtils;

/**
 * Manages the disposal of native resources associated with AccessibleContext.
 * It uses PhantomReference to track object deallocation and automatically release related native resources.
 */
@SuppressWarnings("removal")
public class AtkWrapperDisposer implements Runnable {
    // Reference queue that holds objects ready for garbage collection
    private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    // Map storing PhantomReferences and their associated native resource pointer
    private static final Map<PhantomReference<Object>, Long> phantomMap = new HashMap<>();

    // WeakHashMap that associates AccessibleContext object with native resource pointer
    private static final WeakHashMap<Object, Long> weakHashMap = new WeakHashMap<>();

    private static final Object lock = new Object();
    private static AtkWrapperDisposer INSTANCE = null;

    private AtkWrapperDisposer() {
    }

    private void init() {
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            String name = "Atk Wrapper Disposer";
            ThreadGroup rootTG = ThreadGroupUtils.getRootThreadGroup();
            Thread t = new Thread(rootTG, INSTANCE, name, 0);
            t.setContextClassLoader(null);
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
            return null;
        });
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
     * Monitors the reference queue and releases native resources
     * when an associated AccessibleContext is garbage collected.
     */
    public void run() {
        while (true) {
            try {
                // When an AccessibleContext is freed, release associated native resources
                Reference<?> obj = queue.remove();
                long nativeReference;
                synchronized (lock) {
                    nativeReference = phantomMap.remove(obj);
                }
                AtkWrapper.releaseNativeResources(nativeReference);
                obj.clear();
                obj = null;
            } catch (Exception e) {
                System.out.println("Exception while removing reference.");
            }
        }
    }

    /**
     * Associates a native resource with an AccessibleContext.
     * If the context is not already registered, gets a new native resource pointer
     * and stores the mapping.
     *
     * @param ac The AccessibleContext to associate with a native resource.
     */
    public void addRecord(AccessibleContext ac) {
        synchronized (lock) {
            if (!weakHashMap.containsKey(ac)) {
                long nativeReference = AtkWrapper.createNativeResources(ac);
                if (nativeReference != -1) {
                    PhantomReference<Object> phantomReference = new PhantomReference<>(ac, queue);
                    phantomMap.put(phantomReference, nativeReference);
                }
                weakHashMap.put(ac, nativeReference);
            }
        }
    }

    /**
     * Retrieves the native resource associated with the given AccessibleContext.
     * If no record exists, a new one is created and returned.
     *
     * @param ac The AccessibleContext whose native resource is requested.
     * @return The native resource pointer associated with the given AccessibleContext.
     */
    public long getRecord(AccessibleContext ac) {
        synchronized (lock) {
            if (weakHashMap.containsKey(ac)) {
                return weakHashMap.get(ac);
            }
            addRecord(ac);
            return weakHashMap.get(ac);
        }
    }
}