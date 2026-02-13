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
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

/**
 * The ATK Hypertext interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleHypertext interface
 * and the ATK (Accessibility Toolkit) hypertext interface.
 * Hypertext allows navigation through documents containing
 * embedded links or hyperlinks.
 */
public class AtkHypertext extends AtkText {

    private final WeakReference<AccessibleHypertext> accessibleHypertextRef;

    private AtkHypertext(AccessibleContext ac) {
        super(ac);

        assert EventQueue.isDispatchThread();

        AccessibleText accessibleText = ac.getAccessibleText();
        if (accessibleText instanceof AccessibleHypertext accessibleHypertext) {
            accessibleHypertextRef = new WeakReference<AccessibleHypertext>(accessibleHypertext);
        } else {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleHypertext");
        }
    }

    /* JNI upcalls section */

    /**
     * Factory method to create an AtkHypertext instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkHypertext instance, or null if creation fails
     */
    private static AtkHypertext create_atk_hypertext(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkHypertext(ac);
        }, null);
    }

    /**
     * Gets the link in this hypertext document at the specified index.
     * Called from native code via JNI.
     *
     * @param linkIndex an integer specifying the desired link (zero-based)
     * @return the AtkHyperlink at the specified index, or null if not available
     */
    private AtkHyperlink get_link(int linkIndex) {
        if (accessibleHypertextRef == null) {
            return null;
        }
        AccessibleHypertext accessibleHypertext = accessibleHypertextRef.get();
        if (accessibleHypertext == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleHyperlink link = accessibleHypertext.getLink(linkIndex);
            if (link != null) {
                return AtkHyperlink.createAtkHyperlink(link);
            }
            return null;
        }, null);
    }

    /**
     * Gets the number of links within this hypertext document.
     * Called from native code via JNI.
     *
     * @return the number of links within this hypertext document
     */
    private int get_n_links() {
        if (accessibleHypertextRef == null) {
            return 0;
        }
        AccessibleHypertext accessibleHypertext = accessibleHypertextRef.get();
        if (accessibleHypertext == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleHypertext.getLinkCount();
        }, 0);
    }

    /**
     * Gets the index into the array of hyperlinks that is associated with
     * the character specified by charIndex.
     * Called from native code via JNI.
     *
     * @param charIndex a character index
     * @return an index into the array of hyperlinks in this hypertext,
     * or -1 if there is no hyperlink associated with this character
     */
    private int get_link_index(int charIndex) {
        if (accessibleHypertextRef == null) {
            return -1;
        }
        AccessibleHypertext accessibleHypertext = accessibleHypertextRef.get();
        if (accessibleHypertext == null) {
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleHypertext.getLinkIndex(charIndex);
        }, -1);
    }
}
