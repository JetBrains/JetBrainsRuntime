/*
 * Copyright 2000-2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.awt.event.KeyEvent;
import java.util.HashMap;

/*
 * Class containing common key functionality
 */
public class Key {

    // KeyEvent.VK_ constant name corresponding to the key on US keyboard layout
    private final String vkName;
    // KeyChars mapped to the key on the current keyboard layout
    private final MappedKeyChars mappedKeyChars;

    Key(String vkName, MappedKeyChars mappedKeyChars) {
        this.vkName = vkName;
        this.mappedKeyChars = mappedKeyChars;
    }

    // Returns the virtual key code for US keyboard layout.
    // Robot only knows US keyboard layout.
    // So to press some key on the current layout, one needs to pass corresponding US layout key code to Robot.
    // So every key stores corresponding KeyEvent.VK_ constant name to provide mapping to US layout.
    int getKeyCode_US() {
        try {
            return KeyEvent.class.getField(vkName).getInt(null);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    };

    // Returns key code for the current layout.
    // Key that generates VK_ code when using a US keyboard layout also generates a unique code for other layout.
    // Test firstly determines char mapped to the key on the current layout
    // and then uses KeyEvent.getExtendedKeyCodeForChar(c) to get the key code.
    int getKeyCode() {
        KeyChar keyChar = mappedKeyChars.getKeyChar();
        char ch = keyChar.getChar();
        if (latinKeyCodesMap.containsKey(ch)) {
            // TODO Fix this in jbruntime
            // KeyEvent.getExtendedKeyCodeForChar(ch) does not return corresponding VK_ constant for non-English keys
            return latinKeyCodesMap.get(ch);
        } else if (keyChar.isDead() && deadKeyCodesMap.containsKey(ch)) {
            // KeyEvent.getExtendedKeyCodeForChar(ch) does not return corresponding VK_ constant for dead keys
            return deadKeyCodesMap.get(ch);
        } else {
            return KeyEvent.getExtendedKeyCodeForChar(ch);
        }
    }

    // Returns key char for the current layout
    public char getChar(Modifier modifier) {
        return mappedKeyChars.getKeyChar(modifier).getChar();
    }

    // Checks if no char is mapped to the modifier + key
    public boolean isCharNull(Modifier modifier) {
        return (mappedKeyChars.getKeyChar(modifier).getChar() == Character.MIN_VALUE);
    }

    // Checks if modifier + key is a shortcut
    public boolean isCharUndefined(Modifier modifier) {
        return (mappedKeyChars.getKeyChar(modifier).getChar() == Character.MAX_VALUE);
    }

    // Checks if key is a dead key, no modifiers
    public boolean isDead() {
        return mappedKeyChars.getKeyChar().isDead();
    }

    // Checks if modifier + key is a dead key, always false for shortcut
    public boolean isDead(Modifier modifier) {
        return mappedKeyChars.getKeyChar(modifier).isDead();
    }

    // TODO Remove this map when KeyEvent.getExtendedKeyCodeForChar(ch) is fixed for latin keys in jbruntime
    // Map storing latin chars and corresponding VK_ codes
    private static final HashMap<Character, Integer> latinKeyCodesMap = new HashMap<Character, Integer>() {
        {
            // Please see:
            // jbruntime/src/java.desktop/share/classes/java/awt/event/KeyEvent.java

            put((char) 0x00E0, KeyEvent.VK_A_WITH_GRAVE);
            put((char) 0x00E1, KeyEvent.VK_A_WITH_ACUTE);
            put((char) 0x00E2, KeyEvent.VK_A_WITH_CIRCUMFLEX);
            put((char) 0x00E3, KeyEvent.VK_A_WITH_TILDE);
            put((char) 0x00E4, KeyEvent.VK_A_WITH_DIAERESIS);
            put((char) 0x00E5, KeyEvent.VK_A_WITH_RING_ABOVE);
            put((char) 0x00E6, KeyEvent.VK_AE);
            put((char) 0x00E7, KeyEvent.VK_C_WITH_CEDILLA);
            put((char) 0x00E8, KeyEvent.VK_E_WITH_GRAVE);
            put((char) 0x00E9, KeyEvent.VK_E_WITH_ACUTE);
            put((char) 0x00EA, KeyEvent.VK_E_WITH_CIRCUMFLEX);
            put((char) 0x00EB, KeyEvent.VK_E_WITH_DIAERESIS);
            put((char) 0x00EC, KeyEvent.VK_I_WITH_GRAVE);
            put((char) 0x00ED, KeyEvent.VK_I_WITH_GRAVE);
            put((char) 0x00EE, KeyEvent.VK_I_WITH_CIRCUMFLEX);
            put((char) 0x00EF, KeyEvent.VK_I_WITH_DIAERESIS);
            put((char) 0x00F0, KeyEvent.VK_ETH);
            put((char) 0x00F1, KeyEvent.VK_N_WITH_TILDE);
            put((char) 0x00F2, KeyEvent.VK_O_WITH_GRAVE);
            put((char) 0x00F3, KeyEvent.VK_O_WITH_ACUTE);
            put((char) 0x00F4, KeyEvent.VK_O_WITH_CIRCUMFLEX);
            put((char) 0x00F5, KeyEvent.VK_O_WITH_TILDE);
            put((char) 0x00F6, KeyEvent.VK_O_WITH_DIAERESIS);
            put((char) 0x00F7, KeyEvent.VK_DIVISION_SIGN);
            put((char) 0x00F8, KeyEvent.VK_O_WITH_SLASH);
            put((char) 0x00F9, KeyEvent.VK_U_WITH_GRAVE);
            put((char) 0x00FA, KeyEvent.VK_U_WITH_ACUTE);
            put((char) 0x00FB, KeyEvent.VK_U_WITH_CIRCUMFLEX);
            put((char) 0x00FC, KeyEvent.VK_U_WITH_DIAERESIS);
            put((char) 0x00FD, KeyEvent.VK_Y_WITH_ACUTE);
            put((char) 0x00FE, KeyEvent.VK_THORN);
            put((char) 0x00FF, KeyEvent.VK_Y_WITH_DIAERESIS);
        }
    };

    // Map storing possible dead key chars and corresponding VK_ codes
    private static final HashMap<Character, Integer> deadKeyCodesMap = new HashMap<Character, Integer>() {
        {
            // Please see:
            // jbruntime/src/java.desktop/windows/native/libawt/windows/awt_Component.cpp
            // jbruntime/src/java.desktop/macosx/native/libawt_lwawt/awt/AWTEvent.m

            put((char) 0x02D9, KeyEvent.VK_DEAD_ABOVEDOT);
            put((char) 0x02DA, KeyEvent.VK_DEAD_ABOVERING);
            put((char) 0x00B4, KeyEvent.VK_DEAD_ACUTE);              // ACUTE ACCENT
            put((char) 0x0384, KeyEvent.VK_DEAD_ACUTE);              // GREEK TONOS
            // TODO Should test map ' to acute as sometimes it may add either acute or cedilla to the next key
            //put((char) 0x0027 /* ' */, KeyEvent.VK_DEAD_ACUTE);      // APOSTROPHE
            put((char) 0x02D8, KeyEvent.VK_DEAD_BREVE);
            put((char) 0x02C7, KeyEvent.VK_DEAD_CARON);
            put((char) 0x00B8, KeyEvent.VK_DEAD_CEDILLA);            // CEDILLA
            put((char) 0x002C /* , */, KeyEvent.VK_DEAD_CEDILLA);    // COMMA
            put((char) 0x02C6, KeyEvent.VK_DEAD_CIRCUMFLEX);         // MODIFIER LETTER CIRCUMFLEX ACCENT
            put((char) 0x005E, KeyEvent.VK_DEAD_CIRCUMFLEX);         // CIRCUMFLEX ACCENT
            put((char) 0x00A8, KeyEvent.VK_DEAD_DIAERESIS);          // DIAERESIS
            put((char) 0x0022 /* " */, KeyEvent.VK_DEAD_DIAERESIS);  // QUOTATION MARK
            put((char) 0x02DD, KeyEvent.VK_DEAD_DOUBLEACUTE);
            put((char) 0x0060, KeyEvent.VK_DEAD_GRAVE);
            put((char) 0x037A, KeyEvent.VK_DEAD_IOTA);
            put((char) 0x02C9, KeyEvent.VK_DEAD_MACRON);             // MODIFIER LETTER MACRON
            put((char) 0x00AF, KeyEvent.VK_DEAD_MACRON);             // MACRON
            put((char) 0x02DB, KeyEvent.VK_DEAD_OGONEK);
            put((char) 0x02DC, KeyEvent.VK_DEAD_TILDE);              // SMALL TILDE
            put((char) 0x007E, KeyEvent.VK_DEAD_TILDE);              // TILDE
            put((char) 0x309B, KeyEvent.VK_DEAD_VOICED_SOUND);
            put((char) 0x309C, KeyEvent.VK_DEAD_SEMIVOICED_SOUND);
        }
    };
}
