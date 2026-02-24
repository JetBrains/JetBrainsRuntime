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
 * This class provides a bridge between Java's {@link AccessibleText}
 * interface and the ATK (Accessibility Toolkit) text interface used by
 * assistive technologies.
 * <p>
 * <strong>Offset conventions:</strong> ATK uses "character offsets" in the
 * exposed UTF-8 text stream. Across the JNI boundary we standardize on Unicode
 * code point offsets. Java Swing text component indices, however, are typically
 * UTF-16 indices. Therefore, all offsets received from native code are treated
 * as code point offsets and converted to UTF-16 indices before calling into
 * {@link AccessibleText}.
 */
public class AtkText {

    private final WeakReference<AccessibleContext> accessibleContextWeakRef;
    private final WeakReference<AccessibleText> accessibleTextWeakRef;
    private final WeakReference<AccessibleEditableText> accessibleEditableTextWeakRef; // May reference null

    protected AtkText(AccessibleContext ac) {
        assert EventQueue.isDispatchThread();

        if (ac == null) {
            throw new IllegalArgumentException("AccessibleContext must be not null");
        }

        this.accessibleContextWeakRef = new WeakReference<AccessibleContext>(ac);

        AccessibleText accessibleText = ac.getAccessibleText();
        if (accessibleText == null) {
            throw new IllegalArgumentException("AccessibleContext must have AccessibleText");
        }
        this.accessibleTextWeakRef = new WeakReference<AccessibleText>(accessibleText);

        this.accessibleEditableTextWeakRef = new WeakReference<AccessibleEditableText>(ac.getAccessibleEditableText());
    }

    /**
     * Clamps a value to the inclusive range [min, max].
     *
     * @param value The value to clamp
     * @param min   The lower bound (inclusive)
     * @param max   The upper bound (inclusive)
     * @return The value constrained to the range [min, max]
     */
    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /**
     * Converts a code point offset (ATK/JNI convention) to a UTF-16 index
     * (Swing text convention) for the given full text.
     *
     * @param codePointIndex The code point offset to convert
     * @param text            The full text string
     * @return The corresponding UTF-16 index, or -1 if conversion fails
     */
    static int codePointIndexToUtf16Index(int codePointIndex, String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        int cpCount = text.codePointCount(0, text.length());
        int clampedCodePointIndex = clamp(codePointIndex, 0, cpCount);

        try {
            return text.offsetByCodePoints(0, clampedCodePointIndex);
        } catch (IndexOutOfBoundsException e) {
            return -1;
        }
    }

    /**
     * Converts a UTF-16 char index to a code point offset.
     *
     * @param utf16Index The UTF-16 char index
     * @param text       The full text string
     * @return The corresponding code point offset
     */
    private static int utf16IndexToCodePointIndex(int utf16Index, String text) {
        if (text == null) {
            return 0;
        }

        int clampedUtf16Index = clamp(utf16Index, 0, text.length());
        return text.codePointCount(0, clampedUtf16Index);
    }

    /**
     * Converts a code point range (ATK/JNI convention) into a UTF-16 index range
     * (Swing text convention) for the given full text.
     *
     * @param startCodePointIndex start code point offset (inclusive)
     * @param endCodePointIndex   end code point offset (exclusive), or -1 for end-of-text
     * @param text                 the full text string
     * @return an int array [startUtf16Index, endUtf16Index], or null if conversion fails
     */
    static int[] codePointRangeToUtf16Range(String text, int startCodePointIndex, int endCodePointIndex) {
        if (text == null) {
            text = "";
        }

        int codePointCount = text.codePointCount(0, text.length());

        int startCodePoint = (startCodePointIndex < 0) ? 0 : clamp(startCodePointIndex, 0, codePointCount);

        int endCodePoint;
        if (endCodePointIndex == -1) {
            endCodePoint = codePointCount;
        } else if (endCodePointIndex < 0) {
            endCodePoint = startCodePoint;
        } else {
            endCodePoint = clamp(endCodePointIndex, startCodePoint, codePointCount);
        }

        try {
            int startUtf16Index = text.offsetByCodePoints(0, startCodePoint);
            int endUtf16Index = text.offsetByCodePoints(0, endCodePoint);

            return new int[]{startUtf16Index, endUtf16Index};
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * Gets the start position of the word that contains the given UTF-16 index.
     *
     * @param utf16Index The UTF-16 character index within the text
     * @param text       The full text to search within
     * @return The UTF-16 start position of the current word, or {@link BreakIterator#DONE}
     * if not found or if the word segment contains no letters or digits
     */
    private int getCurrentWordStart(int utf16Index, String text) {
        if (text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        utf16Index = clamp(utf16Index, 0, text.length() - 1);

        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);

        int segmentEnd = words.following(utf16Index);
        if (segmentEnd == BreakIterator.DONE) {
            return BreakIterator.DONE;
        }

        int segmentStart = words.previous();
        if (segmentStart == BreakIterator.DONE) {
            return BreakIterator.DONE;
        }

        if (!containsLetterOrDigit(text, segmentStart, segmentEnd)) {
            return BreakIterator.DONE;
        }

        return segmentStart;
    }

    /**
     * Checks whether the specified UTF-16 range contains at least one
     * Unicode letter or digit.
     *
     * @param text            Source text (may be null)
     * @param startUtf16Index Start of the range (inclusive, UTF-16 index)
     * @param endUtf16Index   End of the range (exclusive, UTF-16 index)
     * @return {@code true} if at least one Unicode letter or digit is found
     * within the specified range; {@code false} otherwise
     */
    private static boolean containsLetterOrDigit(String text, int startUtf16Index, int endUtf16Index) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        int from = clamp(startUtf16Index, 0, text.length());
        int to = clamp(endUtf16Index, from, text.length());

        int currentUtf16Index = from;
        while (currentUtf16Index < to) {
            int codePoint = text.codePointAt(currentUtf16Index);
            if (Character.isLetterOrDigit(codePoint)) {
                return true;
            }
            currentUtf16Index += Character.charCount(codePoint);
        }

        return false;
    }

    /**
     * Gets the start position of the previous word before the given UTF-16 index.
     *
     * @param utf16Index The UTF-16 character index within the text
     * @param text       The full text to search within
     * @return The UTF-16 start position of the previous word, or {@link BreakIterator#DONE}
     * if no previous word is found or if no word segments contain letters or digits
     */
    private int getPreviousWordStart(int utf16Index, String text) {
        if (text == null || text.isEmpty()) return BreakIterator.DONE;

        utf16Index = clamp(utf16Index, 0, text.length());

        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);

        int segmentEnd = words.preceding(utf16Index);
        if (segmentEnd == BreakIterator.DONE) return BreakIterator.DONE;

        int segmentStart = words.previous();

        while (segmentStart != BreakIterator.DONE) {
            if (containsLetterOrDigit(text, segmentStart, segmentEnd)) {
                return segmentStart;
            }
            segmentEnd = segmentStart;
            segmentStart = words.previous();
        }

        return BreakIterator.DONE;
    }

    private int getWordEndFromStart(int startUtf16Index, String text) {
        if (startUtf16Index == BreakIterator.DONE || text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);

        int endUtf16Index = words.following(startUtf16Index);
        return (endUtf16Index == BreakIterator.DONE) ? text.length() : endUtf16Index;
    }

    /**
     * Gets the start position of the sentence that contains the given offset.
     *
     * @param utf16Index The UTF-16 character index within the text
     * @param text       The full text to search within
     * @return The start position of the current sentence, or BreakIterator.DONE if not found
     */
    private int getCurrentSentenceStart(int utf16Index, String text) {
        if (text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        utf16Index = clamp(utf16Index, 0, text.length() - 1);

        BreakIterator sentences = BreakIterator.getSentenceInstance();
        sentences.setText(text);

        int sentenceEnd = sentences.following(utf16Index);
        if (sentenceEnd == BreakIterator.DONE) return BreakIterator.DONE;

        int sentenceStart = sentences.previous();
        return sentenceStart;
    }

    /**
     * Gets the start position of the previous sentence before the given offset.
     *
     * @param utf16Index The UTF-16 character index within the text
     * @param text       The full text to search within
     * @return The start position of the previous sentence, or BreakIterator.DONE if not found
     */
    private int getPreviousSentenceStart(int utf16Index, String text) {
        if (text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        utf16Index = clamp(utf16Index, 0, text.length());

        BreakIterator sentences = BreakIterator.getSentenceInstance();
        sentences.setText(text);

        return sentences.preceding(utf16Index);
    }

    /**
     * Gets the end position of a sentence given its start position.
     *
     * @param startUtf16Index The start position of the sentence
     * @param text  The full text to search within
     * @return The end position of the sentence, or BreakIterator.DONE if not found
     */
    private int getSentenceEndFromStart(int startUtf16Index, String text) {
        if (startUtf16Index == BreakIterator.DONE || text == null || text.isEmpty()) {
            return BreakIterator.DONE;
        }

        BreakIterator sentences = BreakIterator.getSentenceInstance();
        sentences.setText(text);

        int endUtf16Index = sentences.following(startUtf16Index);
        return (endUtf16Index == BreakIterator.DONE) ? text.length() : endUtf16Index;
    }

    /**
     * Gets the start position of the line that contains the given offset.
     *
     * @param utf16Index The UTF-16 character index within the text
     * @param text       The full text to search within
     * @return The start position of the current line
     */
    private int getCurrentLineStart(int utf16Index, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        utf16Index = clamp(utf16Index, 0, text.length() - 1);

        int newlineUtf16Index = text.lastIndexOf('\n', utf16Index - 1);
        return (newlineUtf16Index == -1) ? 0 : newlineUtf16Index + 1;
    }

    /**
     * Gets the end position of a line given its start position.
     *
     * @param startUtf16Index The start position of the line
     * @param text  The full text to search within
     * @return The end position of the line (position of newline or end of text)
     */
    private int getLineEndFromStart(int startUtf16Index, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        startUtf16Index = clamp(startUtf16Index, 0, text.length());

        int newlineUtf16Index = text.indexOf('\n', startUtf16Index);
        return (newlineUtf16Index == -1) ? text.length() : newlineUtf16Index;
    }

    /**
     * Gets the start position of the paragraph that contains the given offset.
     * Paragraphs are defined as text blocks separated by blank lines (double newlines)
     *
     * @param utf16Index The UTF-16 character index within the text
     * @param text       The full text to search within
     * @return The start position of the current paragraph
     */
    private int getCurrentParagraphStart(int utf16Index, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        utf16Index = clamp(utf16Index, 0, text.length() - 1);

        int blankLineUtf16Index = text.lastIndexOf("\n\n", utf16Index);
        return (blankLineUtf16Index != -1) ? blankLineUtf16Index + 2 : 0;
    }

    /**
     * Gets the end position of a paragraph given its start position.
     * Paragraphs are defined as text blocks separated by blank lines (double newlines).
     *
     * @param startUtf16Index The start position of the paragraph
     * @param text  The full text to search within
     * @return The end position of the paragraph (position before blank line or end of text)
     * <p>
     * TODO: Paragraphs are defined by "\n\n". Real text may include:
     * - Windows newlines "\r\n"
     * - multiple blank lines with whitespace
     * - other paragraph separators
     */
    private int getParagraphEndFromStart(int startUtf16Index, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        startUtf16Index = clamp(startUtf16Index, 0, text.length());

        int blankLineUtf16Index = text.indexOf("\n\n", startUtf16Index);
        return (blankLineUtf16Index != -1) ? blankLineUtf16Index : text.length();
    }

    @Deprecated
    private StringSequence getTextAtOffset(int codePointOffset, int boundaryType) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textContent = extractText(accessibleText);

            int cpCount = textContent.codePointCount(0, textContent.length());
            if (codePointOffset < 0 || codePointOffset > cpCount) {
                return null;
            }

            int utf16Index = codePointIndexToUtf16Index(codePointOffset, textContent);
            if (utf16Index == -1) {
                return null;
            }

            switch (boundaryType) {
                case AtkTextBoundary.CHAR: {
                    if (utf16Index == textContent.length()) {
                        return null;
                    }
                    try {
                        int codePoint = textContent.codePointAt(utf16Index);
                        int charCount = Character.charCount(codePoint);
                        String text = textContent.substring(utf16Index, utf16Index + charCount);

                        int startCodePoint = utf16IndexToCodePointIndex(utf16Index, textContent);
                        int endCodePoint = utf16IndexToCodePointIndex(utf16Index + charCount, textContent);
                        return new StringSequence(text, startCodePoint, endCodePoint);
                    } catch (IndexOutOfBoundsException e) {
                        return null;
                    }
                }

                case AtkTextBoundary.WORD_START: {
                    // ATK docs: if offset is inside a word -> that word; otherwise -> word before offset.
                    if (utf16Index == textContent.length()) {
                        return null;
                    }

                    int startUtf16Index = getCurrentWordStart(utf16Index, textContent);
                    int endUtf16Index = getWordEndFromStart(startUtf16Index, textContent);
                    if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                        startUtf16Index = getPreviousWordStart(utf16Index, textContent);
                        endUtf16Index = getWordEndFromStart(startUtf16Index, textContent);
                        if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                            return null;
                        }
                    }

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String text = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePoint = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePoint = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(text, startCodePoint, endCodePoint);
                }

                case AtkTextBoundary.WORD_END: {
                    // Only return a word if offset is inside a word (no "fallback to previous word")
                    if (utf16Index == textContent.length()) {
                        return null;
                    }

                    int startUtf16Index = getCurrentWordStart(utf16Index, textContent);
                    int endUtf16Index = getWordEndFromStart(startUtf16Index, textContent);
                    if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                        return null;
                    }

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String text = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePoint = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePoint = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(text, startCodePoint, endCodePoint);
                }

                case AtkTextBoundary.SENTENCE_START: {
                    // ATK docs: if offset is inside a sentence -> that sentence; otherwise -> sentence before offset.
                    if (utf16Index == textContent.length()) {
                        return null;
                    }

                    int startUtf16Index = getCurrentSentenceStart(utf16Index, textContent);
                    int endUtf16Index = getSentenceEndFromStart(startUtf16Index, textContent);
                    if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                        startUtf16Index = getPreviousSentenceStart(utf16Index, textContent);
                        endUtf16Index = getSentenceEndFromStart(startUtf16Index, textContent);
                        if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                            return null;
                        }
                    }

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String text = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePoint = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePoint = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(text, startCodePoint, endCodePoint);
                }

                case AtkTextBoundary.SENTENCE_END: {
                    // Only return a sentence if offset is inside a sentence (no "fallback to previous sentence")
                    if (utf16Index == textContent.length()) {
                        return null;
                    }

                    int startUtf16Index = getCurrentSentenceStart(utf16Index, textContent);
                    int endUtf16Index = getSentenceEndFromStart(startUtf16Index, textContent);
                    if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                        return null;
                    }

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String text = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePoint = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePoint = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(text, startCodePoint, endCodePoint);
                }

                case AtkTextBoundary.LINE_START:
                case AtkTextBoundary.LINE_END: {
                    if (utf16Index == textContent.length()) {
                        return null;
                    }

                    int startUtf16Index = getCurrentLineStart(utf16Index, textContent);
                    int endUtf16Index = getLineEndFromStart(startUtf16Index, textContent);

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String text = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePoint = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePoint = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(text, startCodePoint, endCodePoint);
                }

                default:
                    return null;
            }
        }, null);
    }

    /**
     * Extracts the full text content from an AccessibleText instance.
     *
     * @param accessibleText the AccessibleText to extract text from
     * @return the full text content as a String (never null, but may be empty)
     * <p>
     * TODO: This can be slow depending on the Accessible implementation (potentially O(n2) if getAtIndex is expensive).
     * Possible optimizations (if performance becomes an issue):
     * - If AccessibleExtendedText is available, prefer getTextRange(0, end) for full extraction.
     * - Cache textContent within a single JNI call if multiple operations need it (right now each method
     *   recomputes it, which is fine for correctness but may be heavy).
     */
    private String extractText(AccessibleText accessibleText) {
        int charCount = accessibleText.getCharCount();
        StringBuilder sb = new StringBuilder(charCount);

        for (int i = 0; i < charCount; i++) {
            String s = accessibleText.getAtIndex(AccessibleText.CHARACTER, i);
            if (s != null) {
                sb.append(s);
            }
        }

        return sb.toString();
    }

    private record StringSequence(String text, int startCodePoint, int endCodePoint) {
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
     * @param startCodePointIndex a starting code point offset within the text
     * @param endCodePointIndex   an ending code point offset within the text, or -1 for the end of the string
     * @return a string containing the text from start up to, but not including end, or null if retrieval fails
     */
    private String get_text(int startCodePointIndex, int endCodePointIndex) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textContent = extractText(accessibleText);

            int[] utf16range = codePointRangeToUtf16Range(textContent, startCodePointIndex, endCodePointIndex);
            if (utf16range == null) {
                return null;
            }

            int startUtf16Index = utf16range[0];
            int endUtf16Index = utf16range[1];

            if (accessibleText instanceof AccessibleExtendedText accessibleExtendedText) {
                return accessibleExtendedText.getTextRange(startUtf16Index, endUtf16Index);
            }

            return textContent.substring(startUtf16Index, endUtf16Index);
        }, null);
    }

    /**
     * Gets the character (Unicode code point) at the specified offset.
     * Called from native code via JNI.
     *
     * @param codePointOffset a code point offset within the text
     * @return the Unicode code point at the specified offset, or 0 in case of failure
     */
    private int get_character_at_offset(int codePointOffset) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return 0;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textContent = extractText(accessibleText);

            int cpCount = textContent.codePointCount(0, textContent.length());
            if (codePointOffset < 0 || codePointOffset >= cpCount) {
                return 0;
            }

            int utf16Index = codePointIndexToUtf16Index(codePointOffset, textContent);
            if (utf16Index == -1) {
                return 0;
            }

            return textContent.codePointAt(utf16Index);
        }, 0);
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
            int caretUtf16Index = accessibleText.getCaretPosition();
            if (caretUtf16Index < 0) {
                return -1;
            }

            String textContent = extractText(accessibleText);

            int clampedCaretUtf16Index = clamp(caretUtf16Index, 0, textContent.length());

            return textContent.codePointCount(0, clampedCaretUtf16Index);
        }, -1);
    }

    /**
     * Gets the bounding box containing the glyph representing the character at a particular text offset.
     * Called from native code via JNI.
     *
     * @param codePointOffset the code point offset of the text character for which bounding information is required
     * @param coordType       specifies whether coordinates are relative to the screen or widget window
     * @return a Rectangle containing the bounding box (x, y, width, height), or null if the extent
     * cannot be obtained. Returns null if all coordinates are set to -1.
     */
    private Rectangle get_character_extents(int codePointOffset, int coordType) {
        AccessibleContext ac = accessibleContextWeakRef.get();
        if (ac == null) {
            return null;
        }

        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textContent = extractText(accessibleText);

            int codePointCount = textContent.codePointCount(0, textContent.length());

            if (codePointOffset < 0 || codePointOffset >= codePointCount) {
                return null;
            }

            int utf16Index = codePointIndexToUtf16Index(codePointOffset, textContent);
            if (utf16Index == -1) {
                return null;
            }

            Rectangle characterBounds = accessibleText.getCharacterBounds(utf16Index);
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
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textContent = extractText(accessibleText);
            return textContent.codePointCount(0, textContent.length());
        }, -1);
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

            int utf16Index = accessibleText.getIndexAtPoint(new Point(x - componentLocation.x, y - componentLocation.y));

            if (utf16Index < 0) {
                return -1;
            }

            String textContent = extractText(accessibleText);

            int textLength = textContent.length();
            if (utf16Index >= textLength) {
                return -1;
            }

            if (Character.isLowSurrogate(textContent.charAt(utf16Index)) &&
                    utf16Index > 0 &&
                    Character.isHighSurrogate(textContent.charAt(utf16Index - 1))) {
                utf16Index -= 1;
            }

            return textContent.codePointCount(0, utf16Index);
        }, -1);
    }

    /**
     * Gets the bounding box for text within the specified range.
     * Called from native code via JNI.
     *
     * @param startCodePointIndex the code point offset of the first text character for which boundary information is required
     * @param endCodePointIndex   the code point offset of the text character after the last character for which boundary information is required
     * @param coordType           specifies whether coordinates are relative to the screen or widget window
     * @return a Rectangle filled in with the bounding box, or null if the extents cannot be obtained.
     * Returns null if all rectangle fields are set to -1.
     */
    private Rectangle get_range_extents(int startCodePointIndex, int endCodePointIndex, int coordType) {
        AccessibleContext ac = accessibleContextWeakRef.get();
        if (ac == null) {
            return null;
        }
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            if (!(accessibleText instanceof AccessibleExtendedText accessibleExtendedText)) {
                return null;
            }

            String textContent = extractText(accessibleText);

            int[] utf16Range = codePointRangeToUtf16Range(textContent, startCodePointIndex, endCodePointIndex);
            if (utf16Range == null) {
                return null;
            }

            int startUtf16Index = utf16Range[0];
            int endUtf16Index = utf16Range[1];

            Rectangle textBounds = accessibleExtendedText.getTextBounds(startUtf16Index, endUtf16Index);
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
        }, null);
    }

    /**
     * Gets the number of selected regions.
     * Called from native code via JNI.
     *
     * @return The number of selected regions, or -1 in the case of failure.
     */
    private int get_n_selections() {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return -1;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String selectedText = accessibleText.getSelectedText();
            int selectionStart = accessibleText.getSelectionStart();
            int selectionEnd = accessibleText.getSelectionEnd();
            return (selectedText != null && selectionStart != selectionEnd) ? 1 : 0;
        }, -1);
    }

    /**
     * Gets the text from the specified selection.
     * Called from native code via JNI.
     *
     * @return a StringSequence containing the selected text and its start and end code point offsets,
     * or null if there is no selection or retrieval fails
     */
    private StringSequence get_selection() {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            int selectionStart = accessibleText.getSelectionStart();
            int selectionEnd = accessibleText.getSelectionEnd();
            String selectedText = accessibleText.getSelectedText();

            if (selectedText == null || selectionStart == selectionEnd) {
                return null;
            }

            String textContent = extractText(accessibleText);

            int startCodePointIndex = utf16IndexToCodePointIndex(selectionStart, textContent);
            int endCodePointIndex = utf16IndexToCodePointIndex(selectionEnd, textContent);

            return new StringSequence(selectedText, startCodePointIndex, endCodePointIndex);
        }, null);
    }

    /**
     * Adds a selection bounded by the specified offsets.
     * Called from native code via JNI.
     *
     * @param startCodePointIndex the starting code point offset of the selected region
     * @param endCodePointIndex   the code point offset of the first character after the selected region
     * @return true if successful, false otherwise. Note that Java AccessibleText only supports
     * a single selection, so this will return false if a selection already exists.
     */
    private boolean add_selection(int startCodePointIndex, int endCodePointIndex) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return false;
        }
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String selectedText = accessibleText.getSelectedText();
            int selectionStart = accessibleText.getSelectionStart();
            int selectionEnd = accessibleText.getSelectionEnd();
            if (selectedText != null && selectionStart != selectionEnd) {
                // Java AccessibleText only supports a single selection, so reject if one already exists
                return false;
            }

            String textContent = extractText(accessibleText);

            int[] utf16Range = codePointRangeToUtf16Range(textContent, startCodePointIndex, endCodePointIndex);
            if (utf16Range == null) {
                return false;
            }

            int startUtf16Index = utf16Range[0];
            int endUtf16Index = utf16Range[1];

            startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
            endUtf16Index = clamp(endUtf16Index, 0, textContent.length());
            if (startUtf16Index > endUtf16Index) startUtf16Index = endUtf16Index;

            accessibleEditableText.selectText(startUtf16Index, endUtf16Index);
            return true;
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
     * @param selectionNum        the selection number. The selected regions are assigned numbers
     *                            that correspond to how far the region is from the start of the text.
     *                            Since Java only supports a single selection, only 0 is valid.
     * @param startCodePointIndex the new starting code point offset of the selection
     * @param endCodePointIndex   the new end code point offset (offset immediately past) of the selection
     * @return true if successful, false otherwise
     */
    private boolean set_selection(int selectionNum, int startCodePointIndex, int endCodePointIndex) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return false;
        }
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null || selectionNum > 0) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textContent = extractText(accessibleText);

            int[] utf16Range = codePointRangeToUtf16Range(textContent, startCodePointIndex, endCodePointIndex);
            if (utf16Range == null) {
                return false;
            }

            int startUtf16Index = utf16Range[0];
            int endUtf16Index = utf16Range[1];

            accessibleEditableText.selectText(startUtf16Index, endUtf16Index);
            return true;
        }, false);
    }

    /**
     * Sets the caret (cursor) position to the specified offset.
     * Called from native code via JNI.
     *
     * @param codePointOffset the code point offset of the new caret position
     * @return true if successful, false otherwise
     */
    private boolean set_caret_offset(int codePointOffset) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return false;
        }
        AccessibleEditableText accessibleEditableText = accessibleEditableTextWeakRef.get();
        if (accessibleEditableText == null) {
            return false;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textContent = extractText(accessibleText);

            int codePointCount = textContent.codePointCount(0, textContent.length());
            int caretCodePoint = clamp(codePointOffset, 0, codePointCount);

            int caretUtf16Index = codePointIndexToUtf16Index(caretCodePoint, textContent);
            if (caretUtf16Index == -1) {
                return false;
            }

            accessibleEditableText.selectText(caretUtf16Index, caretUtf16Index);
            return true;
        }, false);
    }

    /**
     * @param codePointOffset Code point offset position.
     * @param boundaryType    An AtkTextBoundary.
     * @return A newly allocated string containing the text at offset bounded by the specified boundary_type.
     * @deprecated Please use get_string_at_offset() instead.
     * <p>
     * Returns a newly allocated string containing the text at offset bounded by the specified boundary_type.
     */
    @Deprecated
    private StringSequence get_text_at_offset(int codePointOffset, int boundaryType) {
        return getTextAtOffset(codePointOffset, boundaryType);
    }

    /**
     * Gets a portion of the text exposed through an {@link AtkText} according to a given
     * offset and a specific granularity, along with the start and end offsets defining the
     * boundaries of such a portion of text.
     *
     * @param codePointOffset The code point offset in the text where the extraction starts.
     * @param granularity     The granularity of the text to extract, which can be one of the following:
     *                        - {@link AtkTextGranularity#CHAR}: returns the character at the offset.
     *                        - {@link AtkTextGranularity#WORD}: returns the word that contains the offset.
     *                        - {@link AtkTextGranularity#SENTENCE}: returns the sentence that contains the offset.
     *                        - {@link AtkTextGranularity#LINE}: returns the line that contains the offset.
     *                        - {@link AtkTextGranularity#PARAGRAPH}: returns the paragraph that contains the offset.
     * @return A StringSequence containing the text at the specified offset bounded by the specified granularity,
     * along with the start and end offsets. Returns {@code null} if the offset is invalid or no implementation is available.
     * @since ATK 2.10
     */
    private StringSequence get_string_at_offset(int codePointOffset, int granularity) {
        AccessibleText accessibleText = accessibleTextWeakRef.get();
        if (accessibleText == null) {
            return null;
        }

        return AtkUtil.invokeInSwingAndWait(() -> {
            String textContent = extractText(accessibleText);

            int codePointCount = textContent.codePointCount(0, textContent.length());
            if (codePointOffset < 0 || codePointOffset >= codePointCount) {
                return null;
            }

            int utf16Index = codePointIndexToUtf16Index(codePointOffset, textContent);
            if (utf16Index == -1) {
                return null;
            }

            switch (granularity) {
                case AtkTextGranularity.CHAR: {
                    int codePoint = textContent.codePointAt(utf16Index);
                    int charCount = Character.charCount(codePoint);

                    int startUtf16Index = utf16Index;
                    int endUtf16Index = utf16Index + charCount;
                    if (endUtf16Index > textContent.length()) {
                        return null;
                    }

                    String resultText = textContent.substring(startUtf16Index, endUtf16Index);

                    int startCodePoint = codePointOffset;
                    int endCodePoint = codePointOffset + 1;
                    return new StringSequence(resultText, startCodePoint, endCodePoint);
                }

                case AtkTextGranularity.WORD: {
                    // ATK docs: word at offset if offset is inside a word; otherwise word before offset.
                    int startUtf16Index = getCurrentWordStart(utf16Index, textContent);
                    int endUtf16Index = getWordEndFromStart(startUtf16Index, textContent);
                    if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                        startUtf16Index = getPreviousWordStart(utf16Index, textContent);
                        endUtf16Index = getWordEndFromStart(startUtf16Index, textContent);
                        if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                            return null;
                        }
                    }

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String resultText = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePoint = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePoint = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(resultText, startCodePoint, endCodePoint);
                }

                case AtkTextGranularity.SENTENCE: {
                    // ATK docs: sentence at offset if offset is inside a sentence; otherwise sentence before offset.
                    int startUtf16Index = getCurrentSentenceStart(utf16Index, textContent);
                    int endUtf16Index = getSentenceEndFromStart(startUtf16Index, textContent);
                    if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                        startUtf16Index = getPreviousSentenceStart(utf16Index, textContent);
                        endUtf16Index = getSentenceEndFromStart(startUtf16Index, textContent);
                        if (startUtf16Index == BreakIterator.DONE || endUtf16Index == BreakIterator.DONE) {
                            return null;
                        }
                    }

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String resultText = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePointIndex = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePointIndex = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(resultText, startCodePointIndex, endCodePointIndex);
                }

                case AtkTextGranularity.LINE: {
                    int startUtf16Index = getCurrentLineStart(utf16Index, textContent);
                    int endUtf16Index = getLineEndFromStart(startUtf16Index, textContent);

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String resultText = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePointIndex = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePointIndex = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(resultText, startCodePointIndex, endCodePointIndex);
                }

                case AtkTextGranularity.PARAGRAPH: {
                    int startUtf16Index = getCurrentParagraphStart(utf16Index, textContent);
                    int endUtf16Index = getParagraphEndFromStart(startUtf16Index, textContent);

                    startUtf16Index = clamp(startUtf16Index, 0, textContent.length());
                    endUtf16Index = clamp(endUtf16Index, startUtf16Index, textContent.length());

                    String resultText = textContent.substring(startUtf16Index, endUtf16Index);
                    int startCodePointIndex = utf16IndexToCodePointIndex(startUtf16Index, textContent);
                    int endCodePointIndex = utf16IndexToCodePointIndex(endUtf16Index, textContent);
                    return new StringSequence(resultText, startCodePointIndex, endCodePointIndex);
                }

                default:
                    return null;
            }
        }, null);
    }
}
