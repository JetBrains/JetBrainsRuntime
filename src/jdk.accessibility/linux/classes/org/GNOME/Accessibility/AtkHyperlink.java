/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.GNOME.Accessibility;

import javax.accessibility.*;
import java.awt.EventQueue;
import java.lang.ref.WeakReference;

/**
 * The ATK Hyperlink implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleHyperlink interface
 * and the ATK (Accessibility Toolkit) hyperlink interface.
 */
public class AtkHyperlink {

    private final WeakReference<AccessibleHyperlink> accessibleHyperlinkWeakRef;

    private AtkHyperlink(AccessibleHyperlink accessibleHyperlink) {
        assert EventQueue.isDispatchThread();
        accessibleHyperlinkWeakRef = new WeakReference<AccessibleHyperlink>(accessibleHyperlink);
    }

    static AtkHyperlink createAtkHyperlink(AccessibleHyperlink accessibleHyperlink) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkHyperlink(accessibleHyperlink);
        }, null);
    }

    /* JNI upcalls section */

    /**
     * Gets the URI associated with the anchor specified by the index.
     * Called from native code via JNI.
     *
     * @param index the zero-based index specifying the desired anchor
     * @return a string specifying the URI, or null if not available
     */
    private String get_uri(int index) {
        AccessibleHyperlink accessibleHyperlink = accessibleHyperlinkWeakRef.get();
        if (accessibleHyperlink == null || index < 0) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Object accessibleActionObject = accessibleHyperlink.getAccessibleActionObject(index);
            if (accessibleActionObject != null) {
                return accessibleActionObject.toString();
            }
            return null;
        }, null);
    }

    /**
     * Returns the accessible object associated with this hyperlink's nth anchor.
     * Called from native code via JNI.
     *
     * @param index the zero-based index specifying the desired anchor
     * @return the AccessibleContext associated with this hyperlink's index-th anchor,
     * or null if not available
     */
    private AccessibleContext get_object(int index) {
        AccessibleHyperlink accessibleHyperlink = accessibleHyperlinkWeakRef.get();
        if (accessibleHyperlink == null || index < 0) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Object anchor = accessibleHyperlink.getAccessibleActionAnchor(index);
            if (anchor instanceof Accessible accessible) {
                AccessibleContext accessibleContext = accessible.getAccessibleContext();
                if (accessibleContext != null) {
                    AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
                }
                return accessibleContext;
            }
            return null;
        }, null);
    }

    /**
     * Gets the index with the hypertext document at which this link ends.
     * Called from native code via JNI.
     *
     * @return the index with the hypertext document at which this link ends,
     * or 0 if an error happened.
     */
    private int get_end_index() {
        AccessibleHyperlink accessibleHyperlink = accessibleHyperlinkWeakRef.get();
        if (accessibleHyperlink == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleHyperlink.getEndIndex();
        }, 0);
    }

    /**
     * Gets the index with the hypertext document at which this link begins.
     * Called from native code via JNI.
     *
     * @return the index with the hypertext document at which this link begins,
     * or 0 if an error happened
     */
    private int get_start_index() {
        AccessibleHyperlink accessibleHyperlink = accessibleHyperlinkWeakRef.get();
        if (accessibleHyperlink == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleHyperlink.getStartIndex();
        }, 0);
    }

    /**
     * Determines whether this link is still valid.
     * Called from native code via JNI.
     * <p>
     * Since the document that a link is associated with may have changed,
     * this method returns true if the link is still valid (with respect to
     * the document it references) and false otherwise.
     *
     * @return true if the link is still valid, false otherwise
     */
    private boolean is_valid() {
        AccessibleHyperlink accessibleHyperlink = accessibleHyperlinkWeakRef.get();
        if (accessibleHyperlink == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleHyperlink.isValid();
        }, false);
    }

    /**
     * Gets the number of anchors associated with this hyperlink.
     * Called from native code via JNI (jaw_hyperlink_get_n_anchors in jawhyperlink.c).
     *
     * @return the number of anchors associated with this hyperlink
     */
    private int get_n_anchors() {
        AccessibleHyperlink accessibleHyperlink = accessibleHyperlinkWeakRef.get();
        if (accessibleHyperlink == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleHyperlink.getAccessibleActionCount();
        }, 0);
    }
}
