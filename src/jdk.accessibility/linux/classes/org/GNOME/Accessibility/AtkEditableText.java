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

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleEditableText;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.lang.ref.WeakReference;
import javax.swing.text.AttributeSet;
import java.awt.AWTError;

/**
 * The ATK EditableText interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's {@link AccessibleEditableText}
 * interface and the ATK (Accessibility Toolkit) editable text interface.
 * <p>
 * <strong>Offset conventions:</strong> ATK uses "character offsets" in the
 * exposed UTF-8 text stream. Across the JNI boundary we standardize on Unicode
 * code point offsets. Java Swing text component indices, however, are typically
 * UTF-16 indices. Therefore, all offsets received from native code are treated
 * as code point offsets and converted to UTF-16 indices before calling into
 * {@link AccessibleEditableText}.
 */
public class AtkEditableText extends AtkText {

    private final WeakReference<AccessibleEditableText> accessibleEditableTextWeakRef;

    private AtkEditableText(AccessibleContext ac) {
        super(ac);

        assert EventQueue.isDispatchThread();

        if (ac == null) {
            throw new IllegalArgumentException("AccessibleContext must be not null");
        }

        AccessibleEditableText accessibleEditableText = ac.getAccessibleEditableText();
        if (accessibleEditableText == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleEditableText");
        }

        this.accessibleEditableTextWeakRef = new WeakReference<AccessibleEditableText>(accessibleEditableText);
    }

    /**
     * Extracts the full text content from an AccessibleEditableText instance.
     *
     * @param accessibleEditableText the AccessibleEditableText to extract text from
     * @return the full text content as a String, or null
     */
    private static String extractText(AccessibleEditableText accessibleEditableText) {
        try {
            int charCount = accessibleEditableText.getCharCount();
            if (charCount < 0) {
                return null;
            }
            String text = accessibleEditableText.getTextRange(0, charCount);
            return (text != null) ? text : null;
        } catch (Exception e) {
            return null;
        }
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
     * @param textToInsert      the string to insert
     * @param codePointIndex the code point offset at which to insert the text
     */
    private void insert_text(String textToInsert, int codePointIndex) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null || textToInsert == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            String textContent = extractText(accessibleEditableText);
            if (textContent == null) {
                return;
            }
            int utf16Index = AtkText.codePointIndexToUtf16Index(codePointIndex, textContent);
            if (utf16Index == -1) {
                return;
            }

            accessibleEditableText.insertTextAtIndex(utf16Index, textToInsert);
        });
    }

    /**
     * Copies text from the specified start and end positions to the system clipboard.
     * Called from native code via JNI.
     *
     * @param startCodePointIndex the start code point offset
     * @param endCodePointIndex   the end code point offset (or -1 for end-of-text)
     */
    private void copy_text(int startCodePointIndex, int endCodePointIndex) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            String textContent = extractText(accessibleEditableText);
            if (textContent == null) {
                return;
            }

            int[] utf16Range = AtkText.codePointRangeToUtf16Range(textContent, startCodePointIndex, endCodePointIndex);
            if (utf16Range == null) {
                return;
            }

            String textToCopy = textContent.substring(utf16Range[0], utf16Range[1]);

            StringSelection stringSelection = new StringSelection(textToCopy);

            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, stringSelection);
            } catch (AWTError e) {
                return;
            }
        });
    }

    /**
     * Cuts text from the specified start and end positions.
     * Called from native code via JNI.
     *
     * @param startCodePointIndex the start code point offset
     * @param endCodePointIndex   the end code point offset (or -1 for end-of-text)
     */
    private void cut_text(int startCodePointIndex, int endCodePointIndex) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            String textContent = extractText(accessibleEditableText);
            if (textContent == null) {
                return;
            }

            int[] utf16Range = AtkText.codePointRangeToUtf16Range(textContent, startCodePointIndex, endCodePointIndex);
            if (utf16Range == null) {
                return;
            }

            accessibleEditableText.cut(utf16Range[0], utf16Range[1]);
        });
    }

    /**
     * Deletes text from the specified start and end positions.
     * Called from native code via JNI.
     *
     * @param startCodePointIndex the start code point offset
     * @param endCodePointIndex   the end code point offset (or -1 for end-of-text)
     */
    private void delete_text(int startCodePointIndex, int endCodePointIndex) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            String textContent = extractText(accessibleEditableText);
            if (textContent == null) {
                return;
            }

            int[] utf16Range = AtkText.codePointRangeToUtf16Range(textContent, startCodePointIndex, endCodePointIndex);
            if (utf16Range == null) {
                return;
            }

            accessibleEditableText.delete(utf16Range[0], utf16Range[1]);
        });
    }

    /**
     * Pastes text from the system clipboard at the specified position.
     * Called from native code via JNI.
     *
     * @param codePointOffset the code point offset at which to paste the text
     */
    private void paste_text(int codePointOffset) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return;
        }

        AtkUtil.invokeInSwing(() -> {
            String textContent = extractText(accessibleEditableText);
            if (textContent == null) {
                return;
            }

            int utf16Offset = AtkText.codePointIndexToUtf16Index(codePointOffset, textContent);
            if (utf16Offset == -1) {
                return;
            }

            accessibleEditableText.paste(utf16Offset);
        });
    }
}
