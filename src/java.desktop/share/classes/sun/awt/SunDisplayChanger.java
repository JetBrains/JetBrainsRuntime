/*
 * Copyright (c) 2000, 2020, Oracle and/or its affiliates. All rights reserved.
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

package sun.awt;

import java.awt.IllegalComponentStateException;
import java.util.*;
import java.util.function.Consumer;

import sun.util.logging.PlatformLogger;

/**
 * This class is used to aid in keeping track of DisplayChangedListeners and
 * notifying them when a display change has taken place. DisplayChangedListeners
 * are notified when the display's bit depth is changed, or when a top-level
 * window has been dragged onto another screen.
 *
 * It is safe for a DisplayChangedListener to be added while the list is being
 * iterated.
 *
 * The displayChanged() call is propagated after some occurrence (either
 * due to user action or some other application) causes the display mode
 * (e.g., depth or resolution) to change.  All heavyweight components need
 * to know when this happens because they need to create new surfaceData
 * objects based on the new depth.
 *
 * displayChanged() is also called on Windows when they are moved from one
 * screen to another on a system equipped with multiple displays.
 */
public class SunDisplayChanger {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.multiscreen.SunDisplayChanger");

    // Create a new synchronized map with initial capacity of one listener.
    // It is asserted that the most common case is to have one GraphicsDevice
    // and one top-level Window.
    private Map<DisplayChangedListener, Void> listeners =
        Collections.synchronizedMap(new WeakHashMap<DisplayChangedListener, Void>(1));

    public SunDisplayChanger() {}

    /*
     * Add a DisplayChangeListener to this SunDisplayChanger so that it is
     * notified when the display is changed.
     */
    public void add(DisplayChangedListener theListener) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            if (theListener == null) {
                log.fine("Assertion (theListener != null) failed");
            }
        }
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("Adding listener: " + theListener);
        }
        listeners.put(theListener, null);
    }

    /*
     * Remove the given DisplayChangeListener from this SunDisplayChanger.
     */
    public void remove(DisplayChangedListener theListener) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            if (theListener == null) {
                log.fine("Assertion (theListener != null) failed");
            }
        }
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("Removing listener: " + theListener);
        }
        listeners.remove(theListener);
    }

    /*
     * Notify our list of DisplayChangedListeners that a display change has
     * taken place by calling their displayChanged() methods.
     */
    public void notifyListeners() {
        if (log.isLoggable(PlatformLogger.Level.FINEST)) {
            log.finest("notifyListeners");
        }
        notifyChanged(DisplayChangedListener::displayChanged, "displayChanged");
    }

    /*
     * Notify our list of DisplayChangedListeners that a palette change has
     * taken place by calling their paletteChanged() methods.
     */
    public void notifyPaletteChanged() {
        if (log.isLoggable(PlatformLogger.Level.FINEST)) {
            log.finest("notifyPaletteChanged");
        }
        notifyChanged(DisplayChangedListener::paletteChanged, "paletteChanged");
    }

    private void notifyChanged(Consumer<DisplayChangedListener> callback, String callbackName) {
        // This method is implemented by making a clone of the set of listeners,
        // and then iterating over the clone.  This is because during the course
        // of responding to a display change, it may be appropriate for a
        // DisplayChangedListener to add or remove itself from a SunDisplayChanger.
        // If the set itself were iterated over, rather than a clone, it is
        // trivial to get a ConcurrentModificationException by having a
        // DisplayChangedListener remove itself from its list.
        // Because all display change handling is done on the event thread,
        // synchronization provides no protection against modifying the listener
        // list while in the middle of iterating over it.  -bchristi 7/10/2001

        // Preserve "weakness" of the original map to avoid OOME.
        WeakHashMap<DisplayChangedListener, Void> cloneMap;

        synchronized(listeners) {
            cloneMap = new WeakHashMap<>(listeners);
        }

        for (DisplayChangedListener current : cloneMap.keySet()) {
            try {
                if (log.isLoggable(PlatformLogger.Level.FINEST)) {
                    log.finest(callbackName + " for listener: " + current);
                }
                callback.accept(current);
            } catch (IllegalComponentStateException e) {
                // This DisplayChangeListener is no longer valid.  Most
                // likely, a top-level window was dispose()d, but its
                // Java objects have not yet been garbage collected.  In any
                // case, we no longer need to track this listener, though we
                // do need to remove it from the original list, not the clone.
                listeners.remove(current);
            }
        }
    }
}
