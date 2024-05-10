/*
 * Copyright 2024 JetBrains s.r.o.
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

package com.jetbrains;

/**
 * This is a JBR API for text-input related functionality
 */
public interface TextInput {
    /**
     * Custom text components that do not extend {@link java.awt.TextComponent} or {@link javax.swing.text.JTextComponent}
     * should subscribe to this event. When receiving it, they should select the text range of UTF-16 code units starting
     * at index {@param begin} of length {@param length}.
     * <p>
     * It is expected, that {@link java.awt.event.KeyEvent#KEY_TYPED}, or
     * {@link java.awt.event.InputMethodEvent} events will immediately follow. They will insert new text in place of the
     * old text pointed to by this event's range.
     */
    interface SelectTextRangeEvent {
        /**
         * @return an AWT component that is the target of this event
         */
        Object getSource();

        /**
         * @return first UTF-16 code unit index of the replacement range
         */
        int getBegin();

        /**
         * @return length of the replacement range in UTF-16 code units
         */
        int getLength();
    }

    /**
     * Event listener interface for all events supported by this API.
     */
    interface EventListener {
        void handleSelectTextRangeEvent(SelectTextRangeEvent event);
    }

    /**
     * Add global event listener for text input-related events
     *
     * @param listener listener
     */
    void addEventListener(EventListener listener);
}
