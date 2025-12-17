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
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.swing.text.*;
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

/**
 * The ATK EditableText interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleEditableText interface
 * and the ATK (Accessibility Toolkit) editable text interface.
 */
public class AtkEditableText extends AtkText {

    private final WeakReference<AccessibleEditableText> accessibleEditableTextWeakRef;

    private AtkEditableText(AccessibleContext ac) {
        super(ac);

        assert EventQueue.isDispatchThread();

        AccessibleEditableText accessibleEditableText = ac.getAccessibleEditableText();
        if (accessibleEditableText == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleEditableText");
        }

        accessibleEditableTextWeakRef = new WeakReference<AccessibleEditableText>(accessibleEditableText);
    }

    /* JNI upcalls section */

    /**
     * Factory method to create an AtkEditableText instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkEditableText instance, or null if creation fails
     */
    private static AtkEditableText create_atk_editable_text(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkEditableText(ac);
        }, null);
    }

    /**
     * Sets the text contents to the specified string.
     * Called from native code via JNI.
     *
     * @param textContent the string to set as the text contents
     */
    private void set_text_contents(String textContent) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            accessibleEditableText.setTextContents(textContent);
        });
    }

    /**
     * Inserts text at the specified position.
     * Called from native code via JNI.
     *
     * @param textToInsert the string to insert
     * @param position     the position at which to insert the text
     */
    private void insert_text(String textToInsert, int position) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        if (position < 0) {
            position = 0;
        }

        final int finalPosition = position;
        AtkUtil.invokeInSwing(() -> {
            accessibleEditableText.insertTextAtIndex(finalPosition, textToInsert);
        });
    }

    /**
     * Copies text from the specified start and end positions to the system clipboard.
     * Called from native code via JNI.
     *
     * @param start the start position
     * @param end   the end position
     */
    private void copy_text(int start, int end) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        int charCount = AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleEditableText.getCharCount();
        }, -1);

        if (charCount == -1) {
            return;
        }

        final int rightStart = getRightStart(start);
        final int rightEnd = getRightEnd(rightStart, end, charCount);
        AtkUtil.invokeInSwing(() -> {
            String textRange = accessibleEditableText.getTextRange(rightStart, rightEnd);
            if (textRange != null) {
                StringSelection stringSelection = new StringSelection(textRange);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, stringSelection);
            }
        });
    }

    /**
     * Cuts text from the specified start and end positions.
     * Called from native code via JNI.
     *
     * @param start the start position
     * @param end   the end position
     */
    private void cut_text(int start, int end) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            accessibleEditableText.cut(start, end);
        });
    }

    /**
     * Deletes text from the specified start and end positions.
     * Called from native code via JNI.
     *
     * @param start the start position
     * @param end   the end position
     */
    private void delete_text(int start, int end) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            accessibleEditableText.delete(start, end);
        });
    }

    /**
     * Pastes text from the system clipboard at the specified position.
     * Called from native code via JNI.
     *
     * @param position the position at which to paste the text
     */
    private void paste_text(int position) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            accessibleEditableText.paste(position);
        });
    }

    /**
     * Sets attributes for the text between two indices.
     * Called from native code via JNI.
     *
     * @param attributeSet the AttributeSet for the text
     * @param start        the start index of the text
     * @param end          the end index of the text
     * @return true if attributes were successfully set, false otherwise
     */
    private boolean set_run_attributes(AttributeSet attributeSet, int start, int end) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            accessibleEditableText.setAttributes(start, end, attributeSet);
            return true;
        }, false);
    }
}
