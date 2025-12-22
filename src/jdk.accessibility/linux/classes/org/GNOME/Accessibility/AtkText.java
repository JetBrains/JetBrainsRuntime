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
import java.text.*;
import java.awt.Rectangle;
import java.awt.Point;
import java.lang.ref.WeakReference;
import java.awt.EventQueue;

/**
 * The ATK Text interface implementation for Java accessibility.
 * <p>
 * This class provides a bridge between Java's AccessibleText interface
 * and the ATK (Accessibility Toolkit) text interface used by assistive
 * technologies.
 */
public class AtkText {

    private final WeakReference<AccessibleContext> accessibleContextWeakRef;
    private final WeakReference<AccessibleText> accessibleTextWeakRef;
    private final WeakReference<AccessibleEditableText> accessibleEditableTextWeakRef;

    protected AtkText(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);

        AccessibleText accessibleText = ac.getAccessibleText();
        if (accessibleText == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleText");
        }
        this.accessibleTextWeakRef = new WeakReference<AccessibleText>(accessibleText);

        this.accessibleEditableTextWeakRef = new WeakReference<AccessibleEditableText>(ac.getAccessibleEditableText());
    }

    public static int getRightStart(int start) {
        return Math.max(start, 0);
    }

    /**
     * Returns a valid end position based on start, end, and text length constraints.
     *
     * @param start           The starting position in the text, which may be invalid.
     * @param end             The ending position, which may be undefined (-1) or invalid.
     * @param charCountInText The total character count in the text.
     * @return A corrected end position.
     */
    public static int getRightEnd(int start, int end, int charCountInText) {
        int rightStart = getRightStart(start);

        // unique case : the end is undefined or an error happened,
        // let's define the right end as charCountInText
        if (end == -1) {
            return charCountInText;
        }

        if (end < 0) { // we processed end == -1 in another statement
            return rightStart;
        } else if (end < rightStart) {
            return rightStart;
        } else if (charCountInText < end) {
            return charCountInText;
        } else {
            return end;
        }
    }

    private int getCurrentWordStart(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        int length = text.length();
        if (offset < 0) offset = 0;
        if (offset >= length) offset = length - 1;

        if (!Character.isLetter(text.codePointAt(offset))) {
            return BreakIterator.DONE;
        }

        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);

        int wordEnd = words.following(offset);
        if (wordEnd == BreakIterator.DONE) return BreakIterator.DONE;

        int wordStart = words.previous();
        return wordStart;
    }

    // Currently unused.
    private int getNextWordStart(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        int length = text.length();

        if (offset < 0) {
            offset = 0;
        } else if (offset > length) {
            offset = length;
        }

        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);
        int segmentStart = words.following(offset);
        int segmentEnd = words.next();

        while (segmentEnd != BreakIterator.DONE) {
            for (int i = segmentStart; i < segmentEnd; i++) {
                if (Character.isLetter(text.codePointAt(i))) {
                    return segmentStart;
                }
            }
            segmentStart = segmentEnd;
            segmentEnd = words.next();
        }

        return BreakIterator.DONE;
    }

    private int getPreviousWordStart(int offset, String text) {
        if (text == null || text.isEmpty()) return BreakIterator.DONE;

        int length = text.length();
        if (offset < 0) offset = 0;
        if (offset > length) offset = length;

        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);

        int segmentEnd = words.preceding(offset);
        if (segmentEnd == BreakIterator.DONE) return BreakIterator.DONE;

        int segmentStart = words.previous();

        while (segmentStart != BreakIterator.DONE) {
            for (int i = segmentStart; i < segmentEnd; i++) {
                if (Character.isLetter(text.codePointAt(i))) {
                    return segmentStart;
                }
            }
            segmentEnd = segmentStart;
            segmentStart = words.previous();
        }

        return BreakIterator.DONE;
    }

    private int getWordEndFromStart(int start, String text) {
        if (start == BreakIterator.DONE || text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);

        int end = words.following(start);
        return (end == BreakIterator.DONE) ? text.length() : end;
    }

    /**
     * Gets the start position of the sentence that contains the given offset.
     *
     * @param offset The character offset within the text
     * @param text   The full text to search within
     * @return The start position of the current sentence, or BreakIterator.DONE if not found
     */
    private int getCurrentSentenceStart(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        int length = text.length();
        if (offset < 0) offset = 0;
        if (offset >= length) offset = length - 1;

        BreakIterator sentences = BreakIterator.getSentenceInstance();
        sentences.setText(text);

        int sentenceEnd = sentences.following(offset);
        if (sentenceEnd == BreakIterator.DONE) return BreakIterator.DONE;

        int sentenceStart = sentences.previous();
        return sentenceStart;
    }

    /**
     * Gets the start position of the next sentence after the given offset.
     *
     * @param offset The character offset within the text
     * @param text   The full text to search within
     * @return The start position of the next sentence, or BreakIterator.DONE if not found
     */
    private int getNextSentenceStart(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        BreakIterator sentences = BreakIterator.getSentenceInstance();
        sentences.setText(text);

        return sentences.following(offset);
    }

    /**
     * Gets the start position of the previous sentence before the given offset.
     *
     * @param offset The character offset within the text
     * @param text   The full text to search within
     * @return The start position of the previous sentence, or BreakIterator.DONE if not found
     */
    private int getPreviousSentenceStart(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        BreakIterator sentences = BreakIterator.getSentenceInstance();
        sentences.setText(text);

        return sentences.preceding(offset);
    }

    /**
     * Gets the end position of a sentence given its start position.
     *
     * @param start The start position of the sentence
     * @param text  The full text to search within
     * @return The end position of the sentence, or BreakIterator.DONE if not found
     */
    private int getSentenceEndFromStart(int start, String text) {
        if (start == BreakIterator.DONE || text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        BreakIterator sentences = BreakIterator.getSentenceInstance();
        sentences.setText(text);

        int end = sentences.following(start);
        return (end == BreakIterator.DONE) ? text.length() : end;
    }

    /**
     * Gets the start position of the line that contains the given offset.
     *
     * @param offset The character offset within the text
     * @param text   The full text to search within
     * @return The start position of the current line
     */
    private int getCurrentLineStart(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int length = text.length();
        if (offset < 0) offset = 0;
        if (offset >= length) offset = length - 1;

        int pos = offset;
        while (pos > 0) {
            if (text.charAt(pos - 1) == '\n') {
                return pos;
            }
            pos--;
        }
        return 0;
    }

    private int getNextLineStart(int offset, String str) {
        int max = str.length();
        while (offset < max) {
            if (str.charAt(offset) == '\n')
                return offset + 1;
            offset += 1;
        }
        return offset;
    }

    private int getPreviousLineStart(int offset, String str) {
        offset -= 2;
        while (offset >= 0) {
            if (str.charAt(offset) == '\n')
                return offset + 1;
            offset -= 1;
        }
        return 0;
    }

    /**
     * Gets the end position of a line given its start position.
     *
     * @param start The start position of the line
     * @param text  The full text to search within
     * @return The end position of the line (position of newline or end of text)
     */
    private int getLineEndFromStart(int start, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int length = text.length();
        if (start < 0) start = 0;
        if (start >= length) return length;

        int pos = start;
        while (pos < length) {
            if (text.charAt(pos) == '\n') {
                return pos;
            }
            pos++;
        }
        return length;
    }

    private int getNextLineEnd(int offset, String str) {
        int max = str.length();
        offset += 1;
        while (offset < max) {
            if (str.charAt(offset) == '\n')
                return offset;
            offset += 1;
        }
        return offset;
    }

    private int getPreviousLineEnd(int offset, String str) {
        offset -= 1;
        while (offset >= 0) {
            if (str.charAt(offset) == '\n')
                return offset;
            offset -= 1;
        }
        return 0;
    }

    /**
     * Gets the start position of the paragraph that contains the given offset.
     * Paragraphs are defined as text blocks separated by newlines.
     *
     * @param offset The character offset within the text
     * @param text   The full text to search within
     * @return The start position of the current paragraph
     */
    private int getCurrentParagraphStart(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int length = text.length();
        if (offset < 0) offset = 0;
        if (offset >= length) offset = length - 1;

        // Search backwards from offset to find the start of the current paragraph
        int pos = offset;
        while (pos > 0) {
            if (text.charAt(pos - 1) == '\n') {
                return pos;
            }
            pos--;
        }
        return 0;
    }

    private int getNextParagraphStart(int offset, String str) {
        int max = str.length();
        while (offset < max) {
            if (offset < max - 1 && str.charAt(offset) == '\n' && str.charAt(offset + 1) == '\n') {
                return offset + 2;
            }
            offset += 1;
        }
        return offset;
    }

    private int getPreviousParagraphStart(int offset, String str) {
        offset -= 2;
        while (offset >= 0) {
            if (offset > 0 && str.charAt(offset) == '\n' && str.charAt(offset - 1) == '\n') {
                return offset + 1;
            }
            offset -= 1;
        }
        return 0;
    }

    /**
     * Gets the end position of a paragraph given its start position.
     * Paragraphs are defined as text blocks separated by newlines.
     *
     * @param start The start position of the paragraph
     * @param text  The full text to search within
     * @return The end position of the paragraph (position of newline or end of text)
     */
    private int getParagraphEndFromStart(int start, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int length = text.length();
        if (start < 0) start = 0;
        if (start >= length) return length;

        int pos = start;
        while (pos < length) {
            if (text.charAt(pos) == '\n') {
                return pos;
            }
            pos++;
        }
        return length;
    }

    @Deprecated
    private StringSequence getTextAtOffset(int offset,
                                           int boundaryType) {
        int characterCount = get_character_count();
        if (offset < 0 || offset > characterCount) {
            return null;
        }

        switch (boundaryType) {
            case AtkTextBoundary.CHAR: {
                if (offset == characterCount) {
                    return null;
                }
                String str = get_text(offset, offset + 1);
                return new StringSequence(str, offset, offset + 1);
            }
            case AtkTextBoundary.WORD_START:
            case AtkTextBoundary.WORD_END: {
                // The returned string will contain the word at the offset if the offset is
                // inside a word and will contain the word before the offset if the offset is not inside a word
                if (offset == characterCount) {
                    return null;
                }

                String fullText = get_text(0, characterCount);

                int start = getCurrentWordStart(offset, fullText);
                int end = getWordEndFromStart(start, fullText);
                if (start == BreakIterator.DONE || end == BreakIterator.DONE) {
                    start = getPreviousWordStart(offset, fullText);
                    end = getWordEndFromStart(start, fullText);
                    if (start == BreakIterator.DONE || end == BreakIterator.DONE) {
                        return null;
                    }
                }
                String str = get_text(start, end);
                return new StringSequence(str, start, end);
            }
            case AtkTextBoundary.SENTENCE_START:
            case AtkTextBoundary.SENTENCE_END: {
                // The returned string will contain the sentence at the offset if the offset 
                // is inside a sentence and will contain the sentence before the offset if the offset is not inside a sentence.
                if (offset == characterCount) {
                    return null;
                }

                String fullText = get_text(0, characterCount);

                int start = getCurrentSentenceStart(offset, fullText);
                int end = getSentenceEndFromStart(start, fullText);
                if (start == BreakIterator.DONE || end == BreakIterator.DONE) {
                    start = getPreviousSentenceStart(offset, fullText);
                    end = getSentenceEndFromStart(start, fullText);
                    if (start == BreakIterator.DONE || end == BreakIterator.DONE) {
                        return null;
                    }
                }
                String str = get_text(start, end);
                return new StringSequence(str, start, end);
            }
            case AtkTextBoundary.LINE_START:
            case AtkTextBoundary.LINE_END: {
                // Returned string is from the line start at or before the offset to the line start after the offset.
                if (offset == characterCount) {
                    return null;
                }

                String fullText = get_text(0, characterCount);

                int start = getCurrentLineStart(offset, fullText);
                int end = getLineEndFromStart(start, fullText);

                String str = get_text(start, end);
                return new StringSequence(str, start, end);
            }
            default: {
                return null;
            }
        }
    }

    // JNI upcalls section

    /**
     * Factory method to create an AtkText instance from an AccessibleContext.
     * Called from native code via JNI.
     *
     * @param ac the AccessibleContext to wrap
     * @return a new AtkText instance, or null if creation fails
     */
    private static AtkText create_atk_text(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return new AtkText(ac);
        }, null);
    }

    /**
     * Gets the specified text from start to end offset.
     * Called from native code via JNI.
     *
     * @param start a starting character offset within the text
     * @param end   an ending character offset within the text, or -1 for the end of the string
     * @return a string containing the text from start up to, but not including end, or null if retrieval fails
     */
    private String get_text(int start, int end) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int rightStart = getRightStart(start);
            int rightEnd = getRightEnd(rightStart, end, accessibleText.getCharCount());

            if (accessibleText instanceof AccessibleExtendedText accessibleExtendedText) {
                return accessibleExtendedText.getTextRange(rightStart, rightEnd);
            }
            StringBuilder builder = new StringBuilder();
            for (int i = rightStart; i <= rightEnd - 1; i++) {
                String textAtIndex = accessibleText.getAtIndex(AccessibleText.CHARACTER, i);
                builder.append(textAtIndex);
            }
            return builder.toString();
        }, null);
    }

    /**
     * Gets the character at the specified offset.
     * Called from native code via JNI.
     *
     * @param offset a character offset within the text
     * @return the character at the specified offset, or '\0' in case of failure
     */
    private char get_character_at_offset(int offset) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return '\0';
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textAtOffset = accessibleText.getAtIndex(AccessibleText.CHARACTER, offset);
            if (textAtOffset == null || textAtOffset.isEmpty()) {
                return '\0';
            }
            return textAtOffset.charAt(0);
        }, '\0');
    }

    /**
     * Gets the offset of the position of the caret (cursor).
     * Called from native code via JNI.
     *
     * @return the character offset of the position of the caret, or -1 if the caret is not located
     * inside the element or in the case of any other failure
     */
    private int get_caret_offset() {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleText.getCaretPosition();
        }, -1);
    }

    /**
     * Gets the bounding box containing the glyph representing the character at a particular text offset.
     * Called from native code via JNI.
     *
     * @param offset    the offset of the text character for which bounding information is required
     * @param coordType specifies whether coordinates are relative to the screen or widget window
     * @return a Rectangle containing the bounding box (x, y, width, height), or null if the extent
     * cannot be obtained. Returns null if all coordinates are set to -1.
     */
    private Rectangle get_character_extents(int offset, int coordType) {
        AccessibleContext ac = accessibleContextWeakRef.get();
        if (ac == null) {
            return null;
        }

        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Rectangle characterBounds = accessibleText.getCharacterBounds(offset);
            if (characterBounds == null) {
                return null;
            }
            Point componentLocation = AtkComponent.getLocationByCoordinateType(ac, coordType);
            if (componentLocation == null) {
                return null;
            }
            characterBounds.x += componentLocation.x;
            characterBounds.y += componentLocation.y;
            return characterBounds;
        }, null);
    }

    /**
     * Gets the character count.
     * Called from native code via JNI.
     *
     * @return the number of characters, or -1 in case of failure
     */
    private int get_character_count() {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            return accessibleText.getCharCount();
        }, 0);
    }

    /**
     * Gets the offset of the character located at coordinates @x and @y. @x and @y
     * are interpreted as being relative to the screen or this widget's window
     * depending on @coords.
     *
     * @param x          int screen x-position of character
     * @param y          int screen y-position of character
     * @param coord_type int specify whether coordinates are relative to the screen or
     *                   widget window
     * @return the offset to the character which is located at the specified
     * @x and @y coordinates or -1 in case of failure.
     */
    private int get_offset_at_point(int x, int y, int coord_type) {
        AccessibleContext ac = accessibleContextWeakRef.get();
        if (ac == null) {
            return -1;
        }
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            Point componentLocation = AtkComponent.getLocationByCoordinateType(ac, coord_type);
            if (componentLocation == null) {
                return -1;
            }
            return accessibleText.getIndexAtPoint(new Point(x - componentLocation.x, y - componentLocation.y));
        }, -1);
    }

    /**
     * Gets the bounding box for text within the specified range.
     * Called from native code via JNI.
     *
     * @param start     the offset of the first text character for which boundary information is required
     * @param end       the offset of the text character after the last character for which boundary information is required
     * @param coordType specifies whether coordinates are relative to the screen or widget window
     * @return a Rectangle filled in with the bounding box, or null if the extents cannot be obtained.
     * Returns null if all rectangle fields are set to -1.
     */
    private Rectangle get_range_extents(int start, int end, int coordType) {
        AccessibleContext ac = accessibleContextWeakRef.get();
        if (ac == null) {
            return null;
        }
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (accessibleText instanceof AccessibleExtendedText accessibleExtendedText) {
                final int rightStart = getRightStart(start);
                final int rightEnd = getRightEnd(rightStart, end, accessibleText.getCharCount());

                Rectangle textBounds = accessibleExtendedText.getTextBounds(rightStart, rightEnd);
                if (textBounds == null) {
                    return null;
                }
                Point componentLocation = AtkComponent.getLocationByCoordinateType(ac, coordType);
                if (componentLocation == null) {
                    return null;
                }
                textBounds.x += componentLocation.x;
                textBounds.y += componentLocation.y;
                return textBounds;
            }
            return null;
        }, null);
    }

    /**
     * Gets the number of selected regions.
     *
     * @param text an #AtkText
     * @return The number of selected regions, or -1 in the case of failure.
     */
    private int get_n_selections() {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String selectedText = accessibleText.getSelectedText();
            if (selectedText != null) {
                return 1;
            }
            return 0;
        }, -1);
    }

    /**
     * Gets the text from the specified selection.
     * Called from native code via JNI.
     *
     * @return a StringSequence containing the selected text and its start and end offsets,
     * or null if there is no selection or retrieval fails
     */
    private StringSequence get_selection() {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int start = accessibleText.getSelectionStart();
            int end = accessibleText.getSelectionEnd();
            String text = accessibleText.getSelectedText();
            if (text == null) {
                return null;
            }
            return new StringSequence(text, start, end);
        }, null);
    }

    /**
     * Adds a selection bounded by the specified offsets.
     * Called from native code via JNI.
     *
     * @param start the starting character offset of the selected region
     * @param end   the offset of the first character after the selected region
     * @return true if successful, false otherwise. Note that Java AccessibleText only supports
     * a single selection, so this will return false if a selection already exists.
     */
    private boolean add_selection(int start, int end) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return false;
        }
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();

        // Java AccessibleText only supports a single selection, so reject if one already exists
        if (accessibleEditableText == null || get_n_selections() > 0) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            final int rightStart = getRightStart(start);
            final int rightEnd = getRightEnd(rightStart, end, accessibleText.getCharCount());

            return set_selection(0, rightStart, rightEnd);
        }, false);
    }

    /**
     * Removes the specified selection.
     * Called from native code via JNI.
     *
     * @param selectionNum the selection number. The selected regions are assigned numbers
     *                     that correspond to how far the region is from the start of the text.
     *                     Since Java only supports a single selection, only 0 is valid.
     * @return true if successful, false otherwise
     */
    private boolean remove_selection(int selectionNum) {
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null || selectionNum > 0) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            accessibleEditableText.selectText(0, 0);
            return true;
        }, false);
    }

    /**
     * Changes the start and end offset of the specified selection.
     * Called from native code via JNI.
     *
     * @param selectionNum the selection number. The selected regions are assigned numbers
     *                     that correspond to how far the region is from the start of the text.
     *                     Since Java only supports a single selection, only 0 is valid.
     * @param start        the new starting character offset of the selection
     * @param end          the new end position (offset immediately past) of the selection
     * @return true if successful, false otherwise
     */
    private boolean set_selection(int selectionNum, int start, int end) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return false;
        }
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null || selectionNum > 0) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            final int rightStart = getRightStart(start);
            final int rightEnd = getRightEnd(rightStart, end, accessibleText.getCharCount());

            accessibleEditableText.selectText(rightStart, rightEnd);
            return true;
        }, false);
    }

    /**
     * Sets the caret (cursor) position to the specified offset.
     * Called from native code via JNI.
     *
     * @param offset the character offset of the new caret position
     * @return true if successful, false otherwise
     */
    private boolean set_caret_offset(int offset) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return false;
        }
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int rightOffset = getRightEnd(0, offset, accessibleText.getCharCount());
            accessibleEditableText.selectText(rightOffset, rightOffset);
            return true;
        }, false);
    }

    /**
     * @param offset       Position.
     * @param boundaryType An AtkTextBoundary.
     * @return A newly allocated string containing the text at offset bounded by the specified boundary_type.
     * @deprecated Please use get_string_at_offset() instead.
     * <p>
     * Returns a newly allocated string containing the text at offset bounded by the specified boundary_type.
     */
    @Deprecated
    private StringSequence get_text_at_offset(int offset, int boundaryType) {
        return getTextAtOffset(offset, boundaryType);
    }

    /**
     * @param offset       Position.
     * @param boundaryType An AtkTextBoundary.
     * @return A newly allocated string containing the text before offset bounded by the specified boundary_type
     * @deprecated Please use get_string_at_offset() instead.
     * <p>
     * Returns a newly allocated string containing the text before offset bounded by the specified boundary_type.
     */
    @Deprecated
    private StringSequence get_text_before_offset(int offset, int boundaryType) {
        // TODO: Implement if required.
        return getTextAtOffset(offset, boundaryType);
    }

    /**
     * @param offset       Position.
     * @param boundaryType An AtkTextBoundary.
     * @return A newly allocated string containing the text after offset bounded by the specified boundary_type.
     * @deprecated Please use get_string_at_offset() instead.
     * <p>
     * Returns newly allocated string containing the text after offset bounded by the specified boundary_type.
     */
    @Deprecated
    private StringSequence get_text_after_offset(int offset, int boundaryType) {
        // TODO:  Implement if required.
        return getTextAtOffset(offset, boundaryType);
    }

    /**
     * Gets a portion of the text exposed through an {@link AtkText} according to a given
     * offset and a specific granularity, along with the start and end offsets defining the
     * boundaries of such a portion of text.
     *
     * @param offset       The position in the text where the extraction starts.
     * @param granularity  The granularity of the text to extract, which can be one of the following:
     *                     - {@link AtkTextGranularity#ATK_TEXT_GRANULARITY_CHAR}: returns the character at the offset.
     *                     - {@link AtkTextGranularity#ATK_TEXT_GRANULARITY_WORD}: returns the word that contains the offset.
     *                     - {@link AtkTextGranularity#ATK_TEXT_GRANULARITY_SENTENCE}: returns the sentence that contains the offset.
     *                     - {@link AtkTextGranularity#ATK_TEXT_GRANULARITY_LINE}: returns the line that contains the offset.
     *                     - {@link AtkTextGranularity#ATK_TEXT_GRANULARITY_PARAGRAPH}: returns the paragraph that contains the offset.
     * @param start_offset (out) The starting character offset of the returned string, or -1 if there is an error (e.g., invalid offset, not implemented).
     * @param end_offset   (out) The offset of the first character after the returned string, or -1 in the case of an error (e.g., invalid offset, not implemented).
     * @return A newly allocated string containing the text at the specified offset, bounded by the specified granularity.
     * The caller is responsible for freeing the returned string using {@code g_free()}.
     * Returns {@code null} if the offset is invalid or no implementation is available.
     * @since 2.10 (in atk)
     */
    private StringSequence get_string_at_offset(int offset, int granularity) {
        int characterCount = get_character_count();
        if (offset < 0 || offset >= characterCount) {
            return null;
        }

        switch (granularity) {
            case AtkTextGranularity.CHAR: {
                String resultText = get_text(offset, offset + 1);
                return new StringSequence(resultText, offset, offset + 1);
            }
            case AtkTextGranularity.WORD: {
                // Granularity is defined by the boundaries of a word,
                // starting at the beginning of the current word and finishing
                // at the beginning of the following one, if present.
                String fullText = get_text(0, characterCount);

                int start = getCurrentWordStart(offset, fullText);
                int end = getWordEndFromStart(start, fullText);
                if (start == BreakIterator.DONE || end == BreakIterator.DONE) {
                    return null;
                } else {
                    String resultText = get_text(start, end);
                    return new StringSequence(resultText, start, end);
                }
            }
            case AtkTextGranularity.SENTENCE: {
                // Granularity is defined by the boundaries of a sentence,
                // starting at the beginning of the current sentence and finishing
                // at the end of the current sentence.
                String fullText = get_text(0, characterCount);

                int start = getCurrentSentenceStart(offset, fullText);
                int end = getSentenceEndFromStart(start, fullText);
                if (start == BreakIterator.DONE || end == BreakIterator.DONE) {
                    return null;
                } else {
                    String resultText = get_text(start, end);
                    return new StringSequence(resultText, start, end);
                }
            }
            case AtkTextGranularity.LINE: {
                // Granularity is defined by the boundaries of a line,
                // starting at the beginning of the current line and finishing
                // at the beginning of the following one, if present.
                String fullText = get_text(0, characterCount);

                int start = getCurrentLineStart(offset, fullText);
                int end = getLineEndFromStart(start, fullText);

                String resultText = get_text(start, end);
                return new StringSequence(resultText, start, end);
            }
            case AtkTextGranularity.PARAGRAPH: {
                // Granularity is defined by the boundaries of a paragraph,
                // starting at the beginning of the current paragraph and finishing
                // at the end of the current paragraph (newline or end of text).
                String fullText = get_text(0, characterCount);

                int start = getCurrentParagraphStart(offset, fullText);
                int end = getParagraphEndFromStart(start, fullText);

                String resultText = get_text(start, end);
                return new StringSequence(resultText, start, end);
            }
            default: {
                return null;
            }
        }
    }

    private record StringSequence(String str, int start_offset, int end_offset) {
    }
}
