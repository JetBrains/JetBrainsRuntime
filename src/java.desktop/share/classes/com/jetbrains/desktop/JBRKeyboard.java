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

package com.jetbrains.desktop;

import com.jetbrains.exported.JBRApi;
import sun.awt.AWTAccessor;
import sun.awt.event.KeyEventProcessing;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

/**
 * Implementation for platform-agnostic parts of JBR keyboard API
 */
@JBRApi.Service
@JBRApi.Provides("Keyboard")
public class JBRKeyboard {
    /** (Integer) Java key code as if the keyboard was a US QWERTY one */
    public static final String US_KEYCODE = "US_KEYCODE";

    /** (Integer) VK_DEAD_* keycode for the base key, or VK_UNDEFINED if the base key is not dead */
    public static final String DEAD_KEYCODE = "DEAD_KEYCODE";

    /** (Integer) VK_DEAD_* keycode for the key with modifiers, or VK_UNDEFINED if the key with modifiers is not dead */
    public static final String DEAD_KEYSTROKE = "DEAD_KEYSTROKE";

    /** (String) The characters that this specific event has generated as a sequence of KEY_TYPED events */
    public static final String CHARACTERS = "CHARACTERS";

    /** (Integer) Raw platform-dependent keycode for the specific key on the keyboard */
    public static final String RAW_KEYCODE = "RAW_KEYCODE";

    private static JBRKeyboard create() {
        try {
            var implMacOS = Class.forName("sun.lwawt.macosx.JBRKeyboardMacOS");
            var instance = implMacOS.getDeclaredConstructor().newInstance();
            return (JBRKeyboard)instance;
        } catch (ClassNotFoundException e) {
            return new JBRKeyboard();
        } catch (InvocationTargetException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    protected Object getKeyEventProperty(KeyEvent event, String name) {
        Map<String, Object> properties = AWTAccessor.getKeyEventAccessor().getJBRExtraProperties(event);
        return (properties == null) ? null : properties.get(name);
    }

    public int getKeyEventUSKeyCode(KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_PRESSED || event.getID() == KeyEvent.KEY_RELEASED) {
            var prop = getKeyEventProperty(event, US_KEYCODE);
            if (!(prop instanceof Integer)) {
                throw new UnsupportedOperationException("US_KEYCODE property is invalid");
            }
            return (Integer)prop;
        } else {
            throw new IllegalArgumentException("event is not KEY_PRESSED or KEY_RELEASED");
        }
    }

    public int getKeyEventDeadKeyCode(KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_PRESSED || event.getID() == KeyEvent.KEY_RELEASED) {
            var prop = getKeyEventProperty(event, DEAD_KEYCODE);
            if (!(prop instanceof Integer)) {
                throw new UnsupportedOperationException("DEAD_KEYCODE property is invalid");
            }
            return (Integer)prop;
        } else {
            throw new IllegalArgumentException("event is not KEY_PRESSED or KEY_RELEASED");
        }
    }

    public int getKeyEventDeadKeyStroke(KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_PRESSED || event.getID() == KeyEvent.KEY_RELEASED) {
            var prop = getKeyEventProperty(event, DEAD_KEYSTROKE);
            if (!(prop instanceof Integer)) {
                throw new UnsupportedOperationException("DEAD_KEYSTROKE property is invalid");
            }
            return (Integer)prop;
        } else {
            throw new IllegalArgumentException("event is not KEY_PRESSED or KEY_RELEASED");
        }
    }

    public String getKeyEventCharacters(KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_RELEASED) {
            return "";
        }

        if (event.getID() == KeyEvent.KEY_TYPED) {
            return String.valueOf(event.getKeyChar());
        }

        if (event.getID() == KeyEvent.KEY_PRESSED) {
            var prop = getKeyEventProperty(event, CHARACTERS);
            if (!(prop instanceof String)) {
                throw new UnsupportedOperationException("CHARACTERS property is invalid");
            }
            return (String)prop;
        }

        throw new IllegalArgumentException("event with unknown ID");
    }

    public String getCurrentKeyboardLayout() {
        throw new UnsupportedOperationException("getCurrentKeyboardLayout() not implemented for this platform");
    }

    public List<String> getEnabledKeyboardLayouts() {
        throw new UnsupportedOperationException("getEnabledKeyboardLayouts() not implemented for this platform");
    }

    public void setReportNationalKeyCodes(boolean value) {
        EventQueue.invokeLater(() -> {
            KeyEventProcessing.useNationalLayouts = value;
        });
    }

    void setConvertDeadKeyCodesToNormal(boolean value) {
        EventQueue.invokeLater(() -> {
            KeyEventProcessing.reportDeadKeysAsNormal = value;
        });
    }
}
