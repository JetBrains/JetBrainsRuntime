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

import java.awt.event.*;
import java.util.HashMap;

import sun.awt.AWTAccessor;

/**
 * Repeats structure of Atk.KeyEventStruct encapsulating information about a key event.
 * Using to create Atk.KeyEventStruct instance.
 * <p>
 * struct AtkKeyEventStruct {
 * gint type;
 * guint state;
 * guint keyval;
 * gint length;
 * gchar* string;
 * guint16 keycode;
 * guint32 timestamp;
 * }
 */
public class AtkKeyEvent {

    // Symbols mapped to X11 keysym names
    private static final HashMap<String, String> nonAlphaNumericMap;

    private static final int ATK_KEY_EVENT_PRESSED = 0;
    private static final int ATK_KEY_EVENT_RELEASED = 1;

    static {
        // Non-alphanumeric symbols that need to be mapped to X11 keysym names
        nonAlphaNumericMap = new HashMap<>(40);
        nonAlphaNumericMap.put("!", "exclam");
        nonAlphaNumericMap.put("@", "at");
        nonAlphaNumericMap.put("#", "numbersign");
        nonAlphaNumericMap.put("$", "dollar");
        nonAlphaNumericMap.put("%", "percent");
        nonAlphaNumericMap.put("^", "asciicircum");
        nonAlphaNumericMap.put("&", "ampersand");
        nonAlphaNumericMap.put("*", "asterisk");
        nonAlphaNumericMap.put("(", "parenleft");
        nonAlphaNumericMap.put(")", "parenright");
        nonAlphaNumericMap.put("-", "minus");
        nonAlphaNumericMap.put("_", "underscore");
        nonAlphaNumericMap.put("=", "equal");
        nonAlphaNumericMap.put("+", "plus");
        nonAlphaNumericMap.put("\\", "backslash");
        nonAlphaNumericMap.put("|", "bar");
        nonAlphaNumericMap.put("`", "grave");
        nonAlphaNumericMap.put("~", "asciitilde");
        nonAlphaNumericMap.put("[", "bracketleft");
        nonAlphaNumericMap.put("{", "braceleft");
        nonAlphaNumericMap.put("]", "bracketright");
        nonAlphaNumericMap.put("}", "braceright");
        nonAlphaNumericMap.put(";", "semicolon");
        nonAlphaNumericMap.put(":", "colon");
        nonAlphaNumericMap.put("'", "apostrophe");
        nonAlphaNumericMap.put("\"", "quotedbl");
        nonAlphaNumericMap.put(",", "comma");
        nonAlphaNumericMap.put("<", "less");
        nonAlphaNumericMap.put(".", "period");
        nonAlphaNumericMap.put(">", "greater");
        nonAlphaNumericMap.put("/", "slash");
        nonAlphaNumericMap.put("?", "question");
    }

    private final int type;
    private final boolean isShiftKeyDown;
    private final boolean isCtrlKeyDown;
    private final boolean isAltKeyDown;
    private final boolean isMetaKeyDown;
    private final boolean isAltGrKeyDown;
    /* A keysym value corresponding to those used by GDK and X11. see /usr/X11/include/keysymdef.h.
     * FIXME: in current implementation we get this value from GNOMEKeyInfo.gdkKeyCode that are defined manually GNOMEKeyMapping.initializeMap,
     *  doesn't look good.
     */
    private final int keyval;
    /*
     * Either a string approximating the text that would result
     * from this keypress, or a symbolic name for this keypress.
     */
    private final String string;
    // The raw hardware code that generated the key event.
    private final long keycode;
    // A timestamp in milliseconds indicating when the event occurred.
    private final long timestamp;

    public AtkKeyEvent(KeyEvent e) {
        //type
        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED:
            case KeyEvent.KEY_TYPED:
                type = ATK_KEY_EVENT_PRESSED; // 0
                break;
            case KeyEvent.KEY_RELEASED:
                type = ATK_KEY_EVENT_RELEASED; // 1
                break;
            default:
                type = ATK_KEY_EVENT_PRESSED; // 0
        }

        //modifiers
        int modifierMask = e.getModifiersEx();
        isShiftKeyDown = (modifierMask & InputEvent.SHIFT_DOWN_MASK) != 0;
        isCtrlKeyDown = (modifierMask & InputEvent.CTRL_DOWN_MASK) != 0;
        isAltKeyDown = (modifierMask & InputEvent.ALT_DOWN_MASK) != 0;
        isMetaKeyDown = (modifierMask & InputEvent.META_DOWN_MASK) != 0;
        isAltGrKeyDown = (modifierMask & InputEvent.ALT_GRAPH_DOWN_MASK) != 0;

        GNOMEKeyMapping.GNOMEKeyInfo keyInfo = GNOMEKeyMapping.getKey(e);

        int tempKeyval;
        String tempString;
        if (keyInfo != null) {
            tempKeyval = keyInfo.gdkKeyCode();
            tempString = keyInfo.gdkKeyString();
        } else {
            if (e.getKeyChar() == KeyEvent.CHAR_UNDEFINED) {
                tempKeyval = 0;
                tempString = KeyEvent.getKeyText(e.getKeyCode());
                if (tempString == null) tempString = "";
            } else {
                tempKeyval = e.getKeyChar();
                tempString = String.valueOf(tempKeyval);
            }
        }

        keycode = keyInfo != null ? keyInfo.gdkKeyCode() : e.getKeyCode();
        timestamp = e.getWhen();

        String nonAlphaNumericString = nonAlphaNumericMap.get(tempString);
        if (nonAlphaNumericString != null) {
            tempString = nonAlphaNumericString;
        }

        keyval = tempKeyval;
        string = tempString;
    }
}

class GNOMEKeyMapping {

    private static HashMap<Integer, GNOMEKeyInfo> keyMap = null;

    /* Used to offset VK for NUMPAD keys that don't have a VK_KP_* equivalent.
     * At present max VK_* value is 0x0000FFFF
     * Also need to support Left/Right variations.
     */
    private static final int NUMPAD_OFFSET = 0xFEFE0000;
    private static final int LEFT_OFFSET = 0xFEFD0000;
    private static final int RIGHT_OFFSET = 0xFEFC0000;

    static {
        initializeMap();
    }

    private GNOMEKeyMapping() {
    }

    public static GNOMEKeyInfo getKey(KeyEvent e) {
        GNOMEKeyInfo gdkKeyInfo;
        int javaKeyCode = e.getKeyCode();
        int javaKeyLocation = e.getKeyLocation();

        if (javaKeyLocation == KeyEvent.KEY_LOCATION_NUMPAD)
            javaKeyCode += NUMPAD_OFFSET;
        else if (javaKeyLocation == KeyEvent.KEY_LOCATION_LEFT)
            javaKeyCode += LEFT_OFFSET;
        else if (javaKeyLocation == KeyEvent.KEY_LOCATION_RIGHT)
            javaKeyCode += RIGHT_OFFSET;

        if ((gdkKeyInfo = keyMap.get(javaKeyCode)) != null) {
            return gdkKeyInfo;
        } else {
            return null;
        }
    }

    private static void initializeMap() {
        keyMap = new HashMap<>(146); // Currently only 110, so allocate 110 / 0.75

        keyMap.put(KeyEvent.VK_COLON, new GNOMEKeyInfo(0x20a1, "ColonSign")); // GDK_ColonSign
        keyMap.put(KeyEvent.VK_EURO_SIGN, new GNOMEKeyInfo(0x20ac, "EuroSign")); // GDK_EuroSign
        keyMap.put(KeyEvent.VK_BACK_SPACE, new GNOMEKeyInfo(0xFF08, "BackSpace")); // GDK_BackSpace
        keyMap.put(KeyEvent.VK_TAB, new GNOMEKeyInfo(0xFF09, "Tab")); // GDK_Tab
        keyMap.put(KeyEvent.VK_CLEAR, new GNOMEKeyInfo(0xFF0B, "Clear")); // GDK_Clear
        keyMap.put(KeyEvent.VK_ENTER, new GNOMEKeyInfo(0xFF0D, "Return")); // GDK_Return
        keyMap.put(KeyEvent.VK_PAUSE, new GNOMEKeyInfo(0xFF13, "Pause")); // GDK_Pause
        keyMap.put(KeyEvent.VK_SCROLL_LOCK, new GNOMEKeyInfo(0xFF14, "Scroll_Lock")); // GDK_Scroll_Lock
        keyMap.put(KeyEvent.VK_ESCAPE, new GNOMEKeyInfo(0xFF1B, "Escape")); // GDK_Escape
        keyMap.put(KeyEvent.VK_KANJI, new GNOMEKeyInfo(0xFF21, "Kanji")); // GDK_Kanji
        keyMap.put(KeyEvent.VK_HIRAGANA, new GNOMEKeyInfo(0xFF25, "Hiragana")); // GDK_Hiragana
        keyMap.put(KeyEvent.VK_KATAKANA, new GNOMEKeyInfo(0xFF26, "Katakana")); // GDK_Katakana
        keyMap.put(KeyEvent.VK_KANA_LOCK, new GNOMEKeyInfo(0xFF2D, "Kana_Lock")); // GDK_Kana_Lock
        keyMap.put(KeyEvent.VK_KANA, new GNOMEKeyInfo(0xFF2E, "Kana_Shift")); // GDK_Kana_Shift
        keyMap.put(KeyEvent.VK_KANJI, new GNOMEKeyInfo(0xFF37, "Kanji_Bangou")); // GDK_Kanji_Bangou

        keyMap.put(KeyEvent.VK_HOME, new GNOMEKeyInfo(0xFF50, "Home")); // GDK_Home
        keyMap.put(KeyEvent.VK_LEFT, new GNOMEKeyInfo(0xFF51, "Left")); // GDK_Left
        keyMap.put(KeyEvent.VK_UP, new GNOMEKeyInfo(0xFF52, "Up")); // GDK_Up
        keyMap.put(KeyEvent.VK_RIGHT, new GNOMEKeyInfo(0xFF53, "Right")); // GDK_Right
        keyMap.put(KeyEvent.VK_DOWN, new GNOMEKeyInfo(0xFF54, "Down")); // GDK_Down
        keyMap.put(KeyEvent.VK_PAGE_UP, new GNOMEKeyInfo(0xFF55, "Page_Up")); // GDK_Page_Up
        keyMap.put(KeyEvent.VK_PAGE_DOWN, new GNOMEKeyInfo(0xFF56, "Page_Down")); // GDK_Page_Down
        keyMap.put(KeyEvent.VK_END, new GNOMEKeyInfo(0xFF57, "End")); // GDK_End
        keyMap.put(KeyEvent.VK_PRINTSCREEN, new GNOMEKeyInfo(0xFF61, "Print")); // GDK_Print
        keyMap.put(KeyEvent.VK_INSERT, new GNOMEKeyInfo(0xFF63, "Insert")); // GDK_Insert
        keyMap.put(KeyEvent.VK_UNDO, new GNOMEKeyInfo(0xFF65, "Undo")); // GDK_Undo
        keyMap.put(KeyEvent.VK_AGAIN, new GNOMEKeyInfo(0xFF66, "Redo")); // GDK_Redo
        keyMap.put(KeyEvent.VK_FIND, new GNOMEKeyInfo(0xFF68, "Find")); // GDK_Find
        keyMap.put(KeyEvent.VK_CANCEL, new GNOMEKeyInfo(0xFF69, "Cancel")); // GDK_Cancel
        keyMap.put(KeyEvent.VK_HELP, new GNOMEKeyInfo(0xFF6A, "Help")); // GDK_Help
        keyMap.put(KeyEvent.VK_ALT_GRAPH, new GNOMEKeyInfo(0xFF7E, "Mode_Switch")); // GDK_Mode_Switch
        keyMap.put(KeyEvent.VK_NUM_LOCK, new GNOMEKeyInfo(0xFF7F, "Num_Lock")); // GDK_Num_Lock
        keyMap.put(KeyEvent.VK_KP_LEFT, new GNOMEKeyInfo(0xFF96, "KP_Left")); // GDK_KP_Left
        keyMap.put(KeyEvent.VK_KP_UP, new GNOMEKeyInfo(0xFF97, "KP_Up")); // GDK_KP_Up
        keyMap.put(KeyEvent.VK_KP_RIGHT, new GNOMEKeyInfo(0xFF98, "KP_Right")); // GDK_KP_Right
        keyMap.put(KeyEvent.VK_KP_DOWN, new GNOMEKeyInfo(0xFF99, "KP_Down")); // GDK_KP_Dow

        // For Key's that are NUMPAD, but no VK_KP_* equivalent exists
        // NOTE: Some syms do have VK_KP equivalents, but may or may not have
        // KeyLocation() set to NUMPAD - so these are in twice with and
        // without the offset..
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUM_LOCK, new GNOMEKeyInfo(0xFF7F, "Num_Lock")); // GDK_Num_Lock
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_ENTER, new GNOMEKeyInfo(0xFF8D, "KP_Enter")); // GDK_KP_Enter
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_HOME, new GNOMEKeyInfo(0xFF95, "KP_Home")); // GDK_KP_Home
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_KP_LEFT, new GNOMEKeyInfo(0xFF96, "KP_Left")); // GDK_KP_Left
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_KP_UP, new GNOMEKeyInfo(0xFF97, "KP_Up")); // GDK_KP_Up
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_KP_RIGHT, new GNOMEKeyInfo(0xFF98, "KP_Right")); // GDK_KP_Right
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_KP_DOWN, new GNOMEKeyInfo(0xFF99, "KP_Down")); // GDK_KP_Down
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_PAGE_UP, new GNOMEKeyInfo(0xFF9A, "KP_Page_Up")); // GDK_KP_Page_Up
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_PAGE_DOWN, new GNOMEKeyInfo(0xFF9B, "KP_Page_Down")); // GDK_KP_Page_Down
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_END, new GNOMEKeyInfo(0xFF9C, "KP_End")); // GDK_KP_End
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_BEGIN, new GNOMEKeyInfo(0xFF9D, "KP_Begin")); // GDK_KP_Begin
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_INSERT, new GNOMEKeyInfo(0xFF9E, "KP_Insert")); // GDK_KP_Insert
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_DELETE, new GNOMEKeyInfo(0xFF9F, "KP_Delete")); // GDK_KP_Delete
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_MULTIPLY, new GNOMEKeyInfo(0xFFAA, "KP_Multiply")); // GDK_KP_Multiply
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_ADD, new GNOMEKeyInfo(0xFFAB, "KP_Add")); // GDK_KP_Add
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_SEPARATOR, new GNOMEKeyInfo(0xFFAC, "KP_Separator")); // GDK_KP_Separator
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_SUBTRACT, new GNOMEKeyInfo(0xFFAD, "KP_Subtract")); // GDK_KP_Subtract
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_DECIMAL, new GNOMEKeyInfo(0xFFAE, "KP_Decimal")); // GDK_KP_Decimal
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_DIVIDE, new GNOMEKeyInfo(0xFFAF, "KP_Divide")); // GDK_KP_Divide
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD0, new GNOMEKeyInfo(0xFFB0, "KP_0")); // GDK_KP_0
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD1, new GNOMEKeyInfo(0xFFB1, "KP_1")); // GDK_KP_1
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD2, new GNOMEKeyInfo(0xFFB2, "KP_2")); // GDK_KP_2
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD3, new GNOMEKeyInfo(0xFFB3, "KP_3")); // GDK_KP_3
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD4, new GNOMEKeyInfo(0xFFB4, "KP_4")); // GDK_KP_4
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD5, new GNOMEKeyInfo(0xFFB5, "KP_5")); // GDK_KP_5
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD6, new GNOMEKeyInfo(0xFFB6, "KP_6")); // GDK_KP_6
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD7, new GNOMEKeyInfo(0xFFB7, "KP_7")); // GDK_KP_7
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD8, new GNOMEKeyInfo(0xFFB8, "KP_8")); // GDK_KP_8
        keyMap.put(NUMPAD_OFFSET + KeyEvent.VK_NUMPAD9, new GNOMEKeyInfo(0xFFB9, "KP_9")); // GDK_KP_9

        keyMap.put(KeyEvent.VK_NUMPAD0, new GNOMEKeyInfo(0xFFB0, "KP_0")); // GDK_KP_0
        keyMap.put(KeyEvent.VK_NUMPAD1, new GNOMEKeyInfo(0xFFB1, "KP_1")); // GDK_KP_1
        keyMap.put(KeyEvent.VK_NUMPAD2, new GNOMEKeyInfo(0xFFB2, "KP_2")); // GDK_KP_2
        keyMap.put(KeyEvent.VK_NUMPAD3, new GNOMEKeyInfo(0xFFB3, "KP_3")); // GDK_KP_3
        keyMap.put(KeyEvent.VK_NUMPAD4, new GNOMEKeyInfo(0xFFB4, "KP_4")); // GDK_KP_4
        keyMap.put(KeyEvent.VK_NUMPAD5, new GNOMEKeyInfo(0xFFB5, "KP_5")); // GDK_KP_5
        keyMap.put(KeyEvent.VK_NUMPAD6, new GNOMEKeyInfo(0xFFB6, "KP_6")); // GDK_KP_6
        keyMap.put(KeyEvent.VK_NUMPAD7, new GNOMEKeyInfo(0xFFB7, "KP_7")); // GDK_KP_7
        keyMap.put(KeyEvent.VK_NUMPAD8, new GNOMEKeyInfo(0xFFB8, "KP_8")); // GDK_KP_8
        keyMap.put(KeyEvent.VK_NUMPAD9, new GNOMEKeyInfo(0xFFB9, "KP_9")); // GDK_KP_9
        keyMap.put(KeyEvent.VK_F1, new GNOMEKeyInfo(0xFFBE, "F1")); // GDK_F1
        keyMap.put(KeyEvent.VK_F2, new GNOMEKeyInfo(0xFFBF, "F2")); // GDK_F2
        keyMap.put(KeyEvent.VK_F3, new GNOMEKeyInfo(0xFFC0, "F3")); // GDK_F3
        keyMap.put(KeyEvent.VK_F4, new GNOMEKeyInfo(0xFFC1, "F4")); // GDK_F4
        keyMap.put(KeyEvent.VK_F5, new GNOMEKeyInfo(0xFFC2, "F5")); // GDK_F5
        keyMap.put(KeyEvent.VK_F6, new GNOMEKeyInfo(0xFFC3, "F6")); // GDK_F6
        keyMap.put(KeyEvent.VK_F7, new GNOMEKeyInfo(0xFFC4, "F7")); // GDK_F7
        keyMap.put(KeyEvent.VK_F8, new GNOMEKeyInfo(0xFFC5, "F8")); // GDK_F8
        keyMap.put(KeyEvent.VK_F9, new GNOMEKeyInfo(0xFFC6, "F9")); // GDK_F9
        keyMap.put(KeyEvent.VK_F10, new GNOMEKeyInfo(0xFFC7, "F10")); // GDK_F10
        keyMap.put(KeyEvent.VK_F11, new GNOMEKeyInfo(0xFFC8, "F11")); // GDK_F11
        keyMap.put(KeyEvent.VK_F12, new GNOMEKeyInfo(0xFFC9, "F12")); // GDK_F12
        keyMap.put(KeyEvent.VK_F13, new GNOMEKeyInfo(0xFFCA, "F13")); // GDK_F13
        keyMap.put(KeyEvent.VK_F14, new GNOMEKeyInfo(0xFFCB, "F14")); // GDK_F14
        keyMap.put(KeyEvent.VK_F15, new GNOMEKeyInfo(0xFFCC, "F15")); // GDK_F15
        keyMap.put(KeyEvent.VK_F16, new GNOMEKeyInfo(0xFFCD, "F16")); // GDK_F16
        keyMap.put(KeyEvent.VK_F17, new GNOMEKeyInfo(0xFFCE, "F17")); // GDK_F17
        keyMap.put(KeyEvent.VK_F18, new GNOMEKeyInfo(0xFFCF, "F18")); // GDK_F18
        keyMap.put(KeyEvent.VK_F19, new GNOMEKeyInfo(0xFFD0, "F19")); // GDK_F19
        keyMap.put(KeyEvent.VK_F20, new GNOMEKeyInfo(0xFFD1, "F20")); // GDK_F20
        keyMap.put(KeyEvent.VK_F21, new GNOMEKeyInfo(0xFFD2, "F21")); // GDK_F21
        keyMap.put(KeyEvent.VK_F22, new GNOMEKeyInfo(0xFFD3, "F22")); // GDK_F22
        keyMap.put(KeyEvent.VK_F23, new GNOMEKeyInfo(0xFFD4, "F23")); // GDK_F23
        keyMap.put(KeyEvent.VK_F24, new GNOMEKeyInfo(0xFFD5, "F24")); // GDK_F24

        keyMap.put(KeyEvent.VK_SHIFT, new GNOMEKeyInfo(0xFFE2, "Shift_R")); // GDK_Shift_R
        keyMap.put(KeyEvent.VK_CONTROL, new GNOMEKeyInfo(0xFFE4, "Control_R")); // GDK_Control_R
        keyMap.put(KeyEvent.VK_CAPS_LOCK, new GNOMEKeyInfo(0xFFE5, "Caps_Lock")); // GDK_Caps_Lock
        keyMap.put(KeyEvent.VK_META, new GNOMEKeyInfo(0xFFE8, "Meta_R")); // GDK_Meta_R
        keyMap.put(KeyEvent.VK_ALT, new GNOMEKeyInfo(0xFFEA, "Alt_R")); // GDK_Alt_R
        keyMap.put(KeyEvent.VK_DELETE, new GNOMEKeyInfo(0xFFFF, "Delete")); // GDK_Delete

        // Left & Right Variations, default (set above) will be right...
        keyMap.put(LEFT_OFFSET + KeyEvent.VK_SHIFT, new GNOMEKeyInfo(0xFFE1, "Shift_L")); // GDK_Shift_L
        keyMap.put(RIGHT_OFFSET + KeyEvent.VK_SHIFT, new GNOMEKeyInfo(0xFFE2, "Shift_R")); // GDK_Shift_R
        keyMap.put(LEFT_OFFSET + KeyEvent.VK_CONTROL, new GNOMEKeyInfo(0xFFE3, "Control_L")); // GDK_Control_L
        keyMap.put(RIGHT_OFFSET + KeyEvent.VK_CONTROL, new GNOMEKeyInfo(0xFFE4, "Control_R")); // GDK_Control_R
        keyMap.put(LEFT_OFFSET + KeyEvent.VK_META, new GNOMEKeyInfo(0xFFE7, "Meta_L")); // GDK_Meta_L
        keyMap.put(RIGHT_OFFSET + KeyEvent.VK_META, new GNOMEKeyInfo(0xFFE8, "Meta_R")); // GDK_Meta_R
        keyMap.put(LEFT_OFFSET + KeyEvent.VK_ALT, new GNOMEKeyInfo(0xFFE9, "Alt_L")); // GDK_Alt_L
        keyMap.put(RIGHT_OFFSET + KeyEvent.VK_ALT, new GNOMEKeyInfo(0xFFEA, "Alt_R")); // GDK_Alt_R
    }

    record GNOMEKeyInfo(int gdkKeyCode, String gdkKeyString) {
    }
}

