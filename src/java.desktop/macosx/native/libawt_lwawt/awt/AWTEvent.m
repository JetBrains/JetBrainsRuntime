/*
 * Copyright (c) 2011, 2019, Oracle and/or its affiliates. All rights reserved.
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

#import "java_awt_event_InputEvent.h"
#import "java_awt_event_KeyEvent.h"
#import "LWCToolkit.h"

#import "JNIUtilities.h"
#import "AWTEvent.h"

#import <sys/time.h>
#import <Carbon/Carbon.h>

/*
 * Table to map typed characters to their Java virtual key equivalent and back.
 * We use the incoming unichar (ignoring all modifiers) and try to figure out
 * which virtual key code is appropriate. A lot of them just have direct
 * mappings (the function keys, arrow keys, etc.) so they aren't a problem.
 * We had to do something a little funky to catch the keys on the numeric
 * key pad (i.e. using event mask to distinguish between period on regular
 * keyboard and decimal on keypad). We also have to do something incredibly
 * hokey with regards to the shifted punctuation characters. For examples,
 * consider '&' which is usually Shift-7.  For the Java key typed events,
 * that's no problem, we just say pass the unichar. But for the
 * KeyPressed/Released events, we need to identify the virtual key code
 * (which roughly correspond to hardware keys) which means we are supposed
 * to say the virtual 7 key was pressed.  But how are we supposed to know
 * when we get a punctuation char what was the real hardware key was that
 * was pressed?  Although '&' often comes from Shift-7 the keyboard can be
 * remapped!  I don't think there really is a good answer, and hopefully
 * all good applets are only interested in logical key typed events not
 * press/release.  Meanwhile, we are hard-coding the shifted punctuation
 * to trigger the virtual keys that are the expected ones under a standard
 * keymapping. Looking at Windows & Mac, they don't actually do this, the
 * Mac seems to just put the ascii code in for the shifted punctuation
 * (which means they actually end up with bogus key codes on the Java side),
 * Windows I can't even figure out what it's doing.
 */
#define KL_STANDARD java_awt_event_KeyEvent_KEY_LOCATION_STANDARD
#define KL_NUMPAD   java_awt_event_KeyEvent_KEY_LOCATION_NUMPAD
#define KL_UNKNOWN  java_awt_event_KeyEvent_KEY_LOCATION_UNKNOWN
#define KL_LEFT  java_awt_event_KeyEvent_KEY_LOCATION_LEFT
#define KL_RIGHT  java_awt_event_KeyEvent_KEY_LOCATION_RIGHT

struct KeyTableEntry
{
    unsigned short keyCode;
    unichar character;
    BOOL variesBetweenLayouts;
    jint javaKeyLocation;
    jint javaKeyCode;
};

static const struct KeyTableEntry unknownKeyEntry = {
    0xFFFF, 0, NO, KL_UNKNOWN, java_awt_event_KeyEvent_VK_UNDEFINED
};

static const struct KeyTableEntry keyTable[] =
{
    // Do not reorder the key codes! They are in ascending numeric order without gaps (from 0x00 to 0x7F).
    // Characters in the second column are not the characters that UCKeyTranslate returns with the specified key code.
    // Instead, they are the NSEvent.characters sent by macOS when pressing the specified key on the US layout.
    // They are currently only used to determine whether the NextAppWindow shortcut was pressed.
    // They are not used for KEY_TYPED events or anything of this sort.
    {kVK_ANSI_A,                            'a',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_A},
    {kVK_ANSI_S,                            's',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_S},
    {kVK_ANSI_D,                            'd',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_D},
    {kVK_ANSI_F,                            'f',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_F},
    {kVK_ANSI_H,                            'h',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_H},
    {kVK_ANSI_G,                            'g',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_G},
    {kVK_ANSI_Z,                            'z',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_Z},
    {kVK_ANSI_X,                            'x',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_X},
    {kVK_ANSI_C,                            'c',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_C},
    {kVK_ANSI_V,                            'v',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_V},
    {kVK_ISO_Section,                       '\xa7', YES, KL_STANDARD, 0x1000000 + 0x00A7},
    {kVK_ANSI_B,                            'b',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_B},
    {kVK_ANSI_Q,                            'q',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_Q},
    {kVK_ANSI_W,                            'w',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_W},
    {kVK_ANSI_E,                            'e',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_E},
    {kVK_ANSI_R,                            'r',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_R},
    {kVK_ANSI_Y,                            'y',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_Y},
    {kVK_ANSI_T,                            't',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_T},
    {kVK_ANSI_1,                            '1',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_1},
    {kVK_ANSI_2,                            '2',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_2},
    {kVK_ANSI_3,                            '3',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_3},
    {kVK_ANSI_4,                            '4',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_4},
    {kVK_ANSI_6,                            '6',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_6},
    {kVK_ANSI_5,                            '5',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_5},
    {kVK_ANSI_Equal,                        '=',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_EQUALS},
    {kVK_ANSI_9,                            '9',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_9},
    {kVK_ANSI_7,                            '7',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_7},
    {kVK_ANSI_Minus,                        '-',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_MINUS},
    {kVK_ANSI_8,                            '8',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_8},
    {kVK_ANSI_0,                            '0',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_0},
    {kVK_ANSI_RightBracket,                 ']',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_CLOSE_BRACKET},
    {kVK_ANSI_O,                            'o',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_O},
    {kVK_ANSI_U,                            'u',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_U},
    {kVK_ANSI_LeftBracket,                  '[',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_OPEN_BRACKET},
    {kVK_ANSI_I,                            'i',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_I},
    {kVK_ANSI_P,                            'p',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_P},
    {kVK_Return, NSCarriageReturnCharacter,         NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_ENTER},
    {kVK_ANSI_L,                            'l',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_L},
    {kVK_ANSI_J,                            'j',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_J},
    {kVK_ANSI_Quote,                        '\'',   YES, KL_STANDARD, java_awt_event_KeyEvent_VK_QUOTE},
    {kVK_ANSI_K,                            'k',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_K},
    {kVK_ANSI_Semicolon,                    ';',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_SEMICOLON},
    {kVK_ANSI_Backslash,                    '\\',   YES, KL_STANDARD, java_awt_event_KeyEvent_VK_BACK_SLASH},
    {kVK_ANSI_Comma,                        ',',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_COMMA},
    {kVK_ANSI_Slash,                        '/',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_SLASH},
    {kVK_ANSI_N,                            'n',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_N},
    {kVK_ANSI_M,                            'm',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_M},
    {kVK_ANSI_Period,                       '.',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_PERIOD},
    {kVK_Tab, NSTabCharacter,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_TAB},
    {kVK_Space,                             ' ',    NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_SPACE},
    {kVK_ANSI_Grave,                        '`',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_BACK_QUOTE},
    {kVK_Delete, NSDeleteCharacter,                 NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_BACK_SPACE},
    {0x34,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
    {kVK_Escape,                            '\x1b', NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_ESCAPE},
    {kVK_RightCommand,                      0,      NO,  KL_RIGHT,    java_awt_event_KeyEvent_VK_META},
    {kVK_Command,                           0,      NO,  KL_LEFT,     java_awt_event_KeyEvent_VK_META},
    {kVK_Shift,                             0,      NO,  KL_LEFT,     java_awt_event_KeyEvent_VK_SHIFT},
    {kVK_CapsLock,                          0,      NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_CAPS_LOCK},
    {kVK_Option,                            0,      NO,  KL_LEFT,     java_awt_event_KeyEvent_VK_ALT},
    {kVK_Control,                           0,      NO,  KL_LEFT,     java_awt_event_KeyEvent_VK_CONTROL},
    {kVK_RightShift,                        0,      NO,  KL_RIGHT,    java_awt_event_KeyEvent_VK_SHIFT},
    {kVK_RightOption,                       0,      NO,  KL_RIGHT,    java_awt_event_KeyEvent_VK_ALT},
    {kVK_RightControl,                      0,      NO,  KL_RIGHT,    java_awt_event_KeyEvent_VK_CONTROL},
    {kVK_Function,                          0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED},
    {kVK_F17, NSF17FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F17},
    {kVK_ANSI_KeypadDecimal,                '.',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_DECIMAL},
    {0x42,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
    {kVK_ANSI_KeypadMultiply,               '*',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_MULTIPLY},
    {0x44,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
    {kVK_ANSI_KeypadPlus,                   '+',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_ADD},
    {0x46,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
    {kVK_ANSI_KeypadClear, NSClearLineFunctionKey,  NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_CLEAR},
    {kVK_VolumeUp,                          0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED},
    {kVK_VolumeDown,                        0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED},
    {kVK_Mute,                              0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED},
    {kVK_ANSI_KeypadDivide,                 '/',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_DIVIDE},
    {kVK_ANSI_KeypadEnter, NSEnterCharacter,        NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_ENTER},
    {0x4D,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
    {kVK_ANSI_KeypadMinus,                  '-',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_SUBTRACT},
    {kVK_F18, NSF18FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F18},
    {kVK_F19, NSF19FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F19},
    {kVK_ANSI_KeypadEquals,                 '=',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_EQUALS},
    {kVK_ANSI_Keypad0,                      '0',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD0},
    {kVK_ANSI_Keypad1,                      '1',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD1},
    {kVK_ANSI_Keypad2,                      '2',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD2},
    {kVK_ANSI_Keypad3,                      '3',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD3},
    {kVK_ANSI_Keypad4,                      '4',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD4},
    {kVK_ANSI_Keypad5,                      '5',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD5},
    {kVK_ANSI_Keypad6,                      '6',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD6},
    {kVK_ANSI_Keypad7,                      '7',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD7},
    {kVK_F20, NSF20FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F20},
    {kVK_ANSI_Keypad8,                      '8',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD8},
    {kVK_ANSI_Keypad9,                      '9',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_NUMPAD9},
    {kVK_JIS_Yen,                           '\xa5', YES, KL_STANDARD, 0x1000000 + 0x00A5},
    {kVK_JIS_Underscore,                    '_',    YES, KL_STANDARD, java_awt_event_KeyEvent_VK_UNDERSCORE},
    {kVK_JIS_KeypadComma,                   ',',    NO,  KL_NUMPAD,   java_awt_event_KeyEvent_VK_COMMA},
    {kVK_F5, NSF5FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F5},
    {kVK_F6, NSF6FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F6},
    {kVK_F7, NSF7FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F7},
    {kVK_F3, NSF3FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F3},
    {kVK_F8, NSF8FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F8},
    {kVK_F9, NSF9FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F9},
    {kVK_JIS_Eisu,                          0,      NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_ALPHANUMERIC},
    {kVK_F11, NSF11FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F11},
    {kVK_JIS_Kana,                          0,      NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_KATAKANA},
    {kVK_F13, NSF13FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F13},
    {kVK_F16, NSF16FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F16},
    {kVK_F14, NSF14FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F14},
    {0x6C,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
    {kVK_F10, NSF10FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F10},
    {0x6E,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
    {kVK_F12, NSF12FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F12},
    {0x70,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
    {kVK_F15, NSF15FunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F15},
    {kVK_Help, NSHelpFunctionKey,                   NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_HELP},
    {kVK_Home, NSHomeFunctionKey,                   NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_HOME},
    {kVK_PageUp, NSPageUpFunctionKey,               NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_PAGE_UP},
    {kVK_ForwardDelete, NSDeleteFunctionKey,        NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_DELETE},
    {kVK_F4, NSF4FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F4},
    {kVK_End, NSEndFunctionKey,                     NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_END},
    {kVK_F2, NSF2FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F2},
    {kVK_PageDown, NSPageDownFunctionKey,           NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_PAGE_DOWN},
    {kVK_F1, NSF1FunctionKey,                       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_F1},
    {kVK_LeftArrow, NSLeftArrowFunctionKey,         NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_LEFT},
    {kVK_RightArrow, NSRightArrowFunctionKey,       NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_RIGHT},
    {kVK_DownArrow, NSDownArrowFunctionKey,         NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_DOWN},
    {kVK_UpArrow, NSUpArrowFunctionKey,             NO,  KL_STANDARD, java_awt_event_KeyEvent_VK_UP},
    {0x7F,                                  0,      NO,  KL_UNKNOWN,  java_awt_event_KeyEvent_VK_UNDEFINED}, // undefined
};

static const struct KeyTableEntry keyTableJISOverride[] = {
    {0x18, '^', YES, KL_STANDARD, java_awt_event_KeyEvent_VK_CIRCUMFLEX},
    {0x1E, '[', YES, KL_STANDARD, java_awt_event_KeyEvent_VK_OPEN_BRACKET},
    {0x21, '@', YES, KL_STANDARD, java_awt_event_KeyEvent_VK_AT},
    {0x27, ':', YES, KL_STANDARD, java_awt_event_KeyEvent_VK_COLON},
    {0x2A, ']', YES, KL_STANDARD, java_awt_event_KeyEvent_VK_CLOSE_BRACKET},
    // Some other keys are already handled in the previous table, no need to repeat them here
};

/*
 * This table was stolen from the Windows implementation for mapping
 * Unicode values to VK codes for dead keys.  On Windows, some layouts
 * return ASCII punctuation for dead accents, while some return spacing
 * accent chars, so both should be listed.  However, in all of the
 * keyboard layouts I tried only the Unicode values are used.
 */
struct CharToVKEntry {
    UniChar c;
    jint javaKey;
};
static const struct CharToVKEntry charToDeadVKTable[] = {
    {0x0060, java_awt_event_KeyEvent_VK_DEAD_GRAVE},
    {0x0027, java_awt_event_KeyEvent_VK_DEAD_ACUTE},
    {0x00B4, java_awt_event_KeyEvent_VK_DEAD_ACUTE},
    {0x0384, java_awt_event_KeyEvent_VK_DEAD_ACUTE}, // Unicode "GREEK TONOS" -- Greek keyboard, semicolon key
    {0x005E, java_awt_event_KeyEvent_VK_DEAD_CIRCUMFLEX},
    {0x007E, java_awt_event_KeyEvent_VK_DEAD_TILDE},
    {0x02DC, java_awt_event_KeyEvent_VK_DEAD_TILDE}, // Unicode "SMALL TILDE"
    {0x00AF, java_awt_event_KeyEvent_VK_DEAD_MACRON},
    {0x02D8, java_awt_event_KeyEvent_VK_DEAD_BREVE},
    {0x02D9, java_awt_event_KeyEvent_VK_DEAD_ABOVEDOT},
    {0x00A8, java_awt_event_KeyEvent_VK_DEAD_DIAERESIS},
    {0x00B0, java_awt_event_KeyEvent_VK_DEAD_ABOVERING},
    {0x02DA, java_awt_event_KeyEvent_VK_DEAD_ABOVERING},
    {0x02DD, java_awt_event_KeyEvent_VK_DEAD_DOUBLEACUTE},
    {0x02C7, java_awt_event_KeyEvent_VK_DEAD_CARON},
    {0x00B8, java_awt_event_KeyEvent_VK_DEAD_CEDILLA},
    {0x02DB, java_awt_event_KeyEvent_VK_DEAD_OGONEK},
    {0x037A, java_awt_event_KeyEvent_VK_DEAD_IOTA},
    {0x309B, java_awt_event_KeyEvent_VK_DEAD_VOICED_SOUND},
    {0x309C, java_awt_event_KeyEvent_VK_DEAD_SEMIVOICED_SOUND},
    {0,0}
};

// This table is analogous to a similar one found in sun.awt.ExtendedKeyCodes.
// It governs translating the unicode codepoints into the proper VK_ values for keys that have one.
// It only deals with punctuation characters and certain exceptional letters from extended latin set.
// Also see test/jdk/jb/sun/awt/macos/InputMethodTest/KeyCodesTest.java
static const struct CharToVKEntry extraCharToVKTable[] = {
    {0x0021, java_awt_event_KeyEvent_VK_EXCLAMATION_MARK},
    {0x0022, java_awt_event_KeyEvent_VK_QUOTEDBL},
    {0x0023, java_awt_event_KeyEvent_VK_NUMBER_SIGN},
    {0x0024, java_awt_event_KeyEvent_VK_DOLLAR},
    {0x0026, java_awt_event_KeyEvent_VK_AMPERSAND},
    {0x0027, java_awt_event_KeyEvent_VK_QUOTE},
    {0x0028, java_awt_event_KeyEvent_VK_LEFT_PARENTHESIS},
    {0x0029, java_awt_event_KeyEvent_VK_RIGHT_PARENTHESIS},
    {0x002A, java_awt_event_KeyEvent_VK_ASTERISK},
    {0x002B, java_awt_event_KeyEvent_VK_PLUS},
    {0x002C, java_awt_event_KeyEvent_VK_COMMA},
    {0x002D, java_awt_event_KeyEvent_VK_MINUS},
    {0x002E, java_awt_event_KeyEvent_VK_PERIOD},
    {0x002F, java_awt_event_KeyEvent_VK_SLASH},
    {0x003A, java_awt_event_KeyEvent_VK_COLON},
    {0x003B, java_awt_event_KeyEvent_VK_SEMICOLON},
    {0x003C, java_awt_event_KeyEvent_VK_LESS},
    {0x003D, java_awt_event_KeyEvent_VK_EQUALS},
    {0x003E, java_awt_event_KeyEvent_VK_GREATER},
    {0x0040, java_awt_event_KeyEvent_VK_AT},
    {0x005B, java_awt_event_KeyEvent_VK_OPEN_BRACKET},
    {0x005C, java_awt_event_KeyEvent_VK_BACK_SLASH},
    {0x005D, java_awt_event_KeyEvent_VK_CLOSE_BRACKET},
    {0x005E, java_awt_event_KeyEvent_VK_CIRCUMFLEX},
    {0x005F, java_awt_event_KeyEvent_VK_UNDERSCORE},
    {0x0060, java_awt_event_KeyEvent_VK_BACK_QUOTE},
    {0x007B, java_awt_event_KeyEvent_VK_BRACELEFT},
    {0x007D, java_awt_event_KeyEvent_VK_BRACERIGHT},
    {0x00A1, java_awt_event_KeyEvent_VK_INVERTED_EXCLAMATION_MARK},

    // These are the extended latin characters which have a non-obvious key code.
    // Their key codes are derived from the upper case instead of the lower case.
    // It probably has to do with how these key codes are reported on Windows.
    // Translating these characters to the key codes corresponding to their uppercase versions
    // makes getExtendedKeyCodeForChar consistent with how these events are reported on macOS.
    {0x00E4, 0x01000000+0x00C4},
    {0x00E5, 0x01000000+0x00C5},
    {0x00E6, 0x01000000+0x00C6},
    {0x00E7, 0x01000000+0x00C7},
    {0x00F1, 0x01000000+0x00D1},
    {0x00F6, 0x01000000+0x00D6},
    {0x00F8, 0x01000000+0x00D8},

    {0x20AC, java_awt_event_KeyEvent_VK_EURO_SIGN},
    {0, 0}
};

// TODO: some constants below are part of CGS (private interfaces)...
// for now we will look at the raw key code to determine left/right status
// but not sure this is foolproof...
static struct _nsKeyToJavaModifier
{
    NSUInteger nsMask;
    //NSUInteger cgsLeftMask;
    //NSUInteger cgsRightMask;
    unsigned short leftKeyCode;
    unsigned short rightKeyCode;
    jint javaExtMask;
    jint javaMask;
    jint javaKey;
}
const nsKeyToJavaModifierTable[] =
{
    {
        NSAlphaShiftKeyMask,
        0,
        0,
        0, // no Java equivalent
        0, // no Java equivalent
        java_awt_event_KeyEvent_VK_CAPS_LOCK
    },
    {
        NSShiftKeyMask,
        //kCGSFlagsMaskAppleShiftKey,
        //kCGSFlagsMaskAppleRightShiftKey,
        56,
        60,
        java_awt_event_InputEvent_SHIFT_DOWN_MASK,
        java_awt_event_InputEvent_SHIFT_MASK,
        java_awt_event_KeyEvent_VK_SHIFT
    },
    {
        NSControlKeyMask,
        //kCGSFlagsMaskAppleControlKey,
        //kCGSFlagsMaskAppleRightControlKey,
        59,
        62,
        java_awt_event_InputEvent_CTRL_DOWN_MASK,
        java_awt_event_InputEvent_CTRL_MASK,
        java_awt_event_KeyEvent_VK_CONTROL
    },
    {
        NSCommandKeyMask,
        //kCGSFlagsMaskAppleLeftCommandKey,
        //kCGSFlagsMaskAppleRightCommandKey,
        55,
        54,
        java_awt_event_InputEvent_META_DOWN_MASK,
        java_awt_event_InputEvent_META_MASK,
        java_awt_event_KeyEvent_VK_META
    },
    {
        NSAlternateKeyMask,
        //kCGSFlagsMaskAppleLeftAlternateKey,
        //kCGSFlagsMaskAppleRightAlternateKey,
        58,
        61,
        java_awt_event_InputEvent_ALT_DOWN_MASK,
        java_awt_event_InputEvent_ALT_MASK,
        java_awt_event_KeyEvent_VK_ALT
    },
    // NSNumericPadKeyMask
    {
        NSHelpKeyMask,
        0,
        0,
        0, // no Java equivalent
        0, // no Java equivalent
        java_awt_event_KeyEvent_VK_HELP
    },
    // NSFunctionKeyMask
    {0, 0, 0, 0, 0, 0}
};


/*
 * Almost all unicode characters just go from NS to Java with no translation.
 *  For the few exceptions, we handle it here with this small table.
 */
#define ALL_NS_KEY_MODIFIERS_MASK \
    (NSShiftKeyMask | NSControlKeyMask | NSAlternateKeyMask | NSCommandKeyMask)

static struct _char {
    NSUInteger modifier;
    unichar nsChar;
    unichar javaChar;
}
const charTable[] = {
    // map enter on keypad to same as return key
    {0,                         NSEnterCharacter,          NSNewlineCharacter},

    // [3134616] return newline instead of carriage return
    {0,                         NSCarriageReturnCharacter, NSNewlineCharacter},

    // "delete" means backspace in Java
    {ALL_NS_KEY_MODIFIERS_MASK, NSDeleteCharacter,         NSBackspaceCharacter},
    {ALL_NS_KEY_MODIFIERS_MASK, NSDeleteFunctionKey,       NSDeleteCharacter},

    // back-tab is only differentiated from tab by Shift flag
    {NSShiftKeyMask,            NSBackTabCharacter,        NSTabCharacter},

    {0, 0, 0}
};

unichar NsCharToJavaChar(unichar nsChar, NSUInteger modifiers, BOOL spaceKeyTyped)
{
    const struct _char *cur;
    // Mask off just the keyboard modifiers from the event modifier mask.
    NSUInteger testableFlags = (modifiers & ALL_NS_KEY_MODIFIERS_MASK);

    // walk through table & find the match
    for (cur = charTable; cur->nsChar != 0 ; cur++) {
        // <rdar://Problem/3476426> Need to determine if we are looking at
        // a plain keypress or a modified keypress.  Don't adjust the
        // character of a keypress with a modifier.
        if (cur->nsChar == nsChar) {
            if (cur->modifier == 0 && testableFlags == 0) {
                // If the modifier field is 0, that means to transform
                // this character if no additional keyboard modifiers are set.
                // This lets ctrl-C be reported as ctrl-C and not transformed
                // into Newline.
                return cur->javaChar;
            } else if (cur->modifier != 0 &&
                       (testableFlags & cur->modifier) == testableFlags)
            {
                // Likewise, if the modifier field is nonzero, that means
                // transform this character if only these modifiers are
                // set in the testable flags.
                return cur->javaChar;
            }
        }
    }

    if (nsChar >= NSUpArrowFunctionKey && nsChar <= NSModeSwitchFunctionKey) {
        return java_awt_event_KeyEvent_CHAR_UNDEFINED;
    }

    // nsChar receives value 0 when SPACE key is typed.
    if (nsChar == 0 && spaceKeyTyped == YES) {
        return java_awt_event_KeyEvent_VK_SPACE;
    }

    // otherwise return character unchanged
    return nsChar;
}

static const struct KeyTableEntry* GetKeyTableEntryForKeyCode(unsigned short keyCode) {
    static const size_t keyTableSize = sizeof(keyTable) / sizeof(struct KeyTableEntry);
    static const size_t keyTableJISOverrideSize = sizeof(keyTableJISOverride) / sizeof(struct KeyTableEntry);
    BOOL isJIS = KBGetLayoutType(LMGetKbdType()) == kKeyboardJIS;

    const struct KeyTableEntry* usKey = &unknownKeyEntry;

    if (keyCode < keyTableSize) {
        usKey = &keyTable[keyCode];
    }

    if (isJIS) {
        for (int i = 0; i < keyTableJISOverrideSize; ++i) {
            if (keyTableJISOverride[i].keyCode == keyCode) {
                usKey = &keyTableJISOverride[i];
                break;
            }
        }
    }

    return usKey;
}

TISInputSourceRef GetCurrentUnderlyingLayout(BOOL useNationalLayouts) {
    // TISCopyCurrentKeyboardLayoutInputSource() should always return a key layout
    // that has valid unicode character data for use with the UCKeyTranslate() function.
    // This is more robust than checking whether the current input source has key layout data
    // and then falling back to the override input source if it doesn't. This is because some
    // custom IMEs don't set the override input source properly.

    TISInputSourceRef currentLayout = TISCopyCurrentKeyboardLayoutInputSource();
    Boolean currentAscii = currentLayout == nil ? NO :
                           CFBooleanGetValue((CFBooleanRef) TISGetInputSourceProperty(currentLayout, kTISPropertyInputSourceIsASCIICapable));
    TISInputSourceRef underlyingLayout = (!useNationalLayouts || currentAscii) ? currentLayout : nil;

    return underlyingLayout;
}

struct KeyCodeTranslationResult TranslateKeyCodeUsingLayout(TISInputSourceRef layout, unsigned short keyCode)
{
    struct KeyCodeTranslationResult result = {
        .character = (unichar)0,
        .isSuccess = NO,
        .isDead = NO,
        .isTyped = NO
    };

    const struct KeyTableEntry* usKey = GetKeyTableEntryForKeyCode(keyCode);

    if (usKey == &unknownKeyEntry) {
        return result;
    }

    if (!usKey->variesBetweenLayouts) {
        result.isSuccess = YES;
        result.character = usKey->character;
        if (result.character != 0 && !(result.character >= NSUpArrowFunctionKey && result.character <= NSModeSwitchFunctionKey)) {
            result.isTyped = YES;
        }
        return result;
    }

    if (layout == nil) {
        // use the US layout
        result.isSuccess = YES;
        if (usKey->character != 0) {
            result.character = usKey->character;
            result.isTyped = YES;
        }
        return result;
    }

    CFDataRef uchr = (CFDataRef)TISGetInputSourceProperty(layout, kTISPropertyUnicodeKeyLayoutData);
    if (uchr == nil) {
        return result;
    }
    const UCKeyboardLayout *keyboardLayout = (const UCKeyboardLayout*)CFDataGetBytePtr(uchr);
    if (keyboardLayout == NULL) {
        return result;
    }

    UInt32 modifierKeyState = 0;
    UInt32 deadKeyState = 0;
    const UniCharCount maxStringLength = 255;
    UniCharCount actualStringLength = 0;
    UniChar unicodeString[maxStringLength];

    // get the deadKeyState
    OSStatus status = UCKeyTranslate(keyboardLayout,
                                     keyCode, kUCKeyActionDown, modifierKeyState,
                                     LMGetKbdType(), 0,
                                     &deadKeyState,
                                     maxStringLength,
                                     &actualStringLength, unicodeString);

    if (status != noErr) {
        return result;
    }

    if (deadKeyState == 0) {
        result.isSuccess = YES;
        result.isDead = NO;

        if (actualStringLength > 0) {
            result.isTyped = YES;

            // This is the character that will determine the Java key code of this key.
            // There are some keys (even on ASCII-capable key layouts) that produce more than one
            // code point (not just more than one UTF-16 code unit mind you!). It's unclear how one
            // would go around constructing an extended key code for these keys. Luckily, if we
            // use the last code unit to construct the extended key codes, there won't be any collisions
            // among the standard macOS ASCII-capable key layouts. That seems good enough to me.
            result.character = unicodeString[actualStringLength - 1];
        }

        return result;
    }

    deadKeyState = 0;

    // Extract the dead key non-combining character
    status = UCKeyTranslate(keyboardLayout,
                            keyCode, kUCKeyActionDown, modifierKeyState,
                            LMGetKbdType(), kUCKeyTranslateNoDeadKeysMask,
                            &deadKeyState,
                            maxStringLength,
                            &actualStringLength, unicodeString);

    if (status != noErr) {
        return result;
    }

    result.isSuccess = YES;
    result.isDead = YES;

    if (actualStringLength > 0) {
        result.character = unicodeString[actualStringLength - 1];
    }

    return result;
}

/*
 * This is the function that uses the table above to take incoming
 * NSEvent keyCodes and translate to the Java virtual key code.
 */
static void
NsCharToJavaVirtualKeyCode(unsigned short key, const BOOL useNationalLayouts,
                           jint *keyCode, jint *keyLocation)
{
    // This is going to be a lengthy explanation about what it is that we need to achieve in this function.
    // It took me quite a while to figure out myself, so hopefully it will be useful to others as well.
    // I will describe the desired behavior when useNationalLayouts = true. Setting this parameter to false should
    // ideally make the behavior identical to the one of OpenJDK, barring a few obvious bugfixes, like JBR-3860.
    //
    // For clarity here's what I mean by certain phrases:
    //   - Input source: what macOS calls "Keyboard layout input source", so excluding emoji pickers and handwriting
    //   - Key layout: Input source in the com.apple.keylayout namespace, i.e. a "simple" keyboard
    //   - IME: Input source in the com.apple.inputmethod namespace, i.e. a "complex" input method
    //   - Physical layout: A property of the physical keyboard device that has to do with the physical location of keys and their mapping to key codes
    //   - Underlying key layout: The key layout which actually translates the keys that the user presses to Java events.
    //   - Key code: A macOS virtual key code (the property `keyCode` on `NSEvent`)
    //   - Java key code: The values returned by `KeyEvent.getKeyCode()`
    //   - Key: Keyboard key without any modifiers
    //   - Combo: Keyboard key with modifiers
    //   - Dead key/combo: A key/combo that sets a dead key state when interpreted using UCKeyTranslate
    //
    // Whenever I refer to a key on the physical keyboard I will use the US layout.
    //
    // This function needs to determine the following:
    //   - Java key code
    //   - Java key location
    //
    // Obtaining the key location is fairly easy, since it can be looked up in the table by key code and physical layout type.
    //
    // To understand how to obtain the Java key code, let's take a look at the types of input sources that we want to handle:
    //   - Latin-based key layouts (ABC, German, French, Spanish, etc)
    //   - Non-latin-based key layouts (Arabic, Armenian, Russian, etc)
    //   - Latin-based IMEs (Pinyin, Cantonese - Phonetic, Japanese Romaji, etc.)
    //   - Non-latin-based IMEs (Korean, Zhuyin, Japanese Kana, etc.)
    //
    // These are possible physical layouts supported on macOS:
    //   - ANSI (North America, most of Asia and others)
    //   - ISO (Europe, Latin America, Middle East and others)
    //   - JIS (Japan)
    //
    // As a rule, any input source can be used on any physical layout.
    // This might cause some key codes to correspond to different characters on the same input source.
    //
    // Basically we want the following behavior:
    //   - Latin-based key layouts should report their own keys unchanged.
    //   - Other input sources should report the key on their underlying key layout.
    //
    // Latin-based IMEs make it easy to determine the underlying key layout.
    // macOS allows us to obtain a copy of the input source by calling TISCopyCurrentKeyboardLayoutInputSource().
    // There's also the TISCopyInputMethodKeyboardLayoutOverride() function, but certain IMs (Google Japanese IME)
    // don't set it properly.
    //
    // Non-latin-based key layouts and IMEs will use the US key layout as the underlying one.
    // This is the behavior of native apps.
    //
    // Java has builtin key codes for most characters that can appear at the base layer of various key layouts.
    // The rest are constructed like this: 0x01000000 + codePoint. All keys on builtin ASCII-capable layouts produce
    // no surrogate pairs, but some of them can produce strings containing more than one code point. These need to be
    // dealt with carefully as to avoid having different keys produce same Java key codes.
    //
    // Here's the various groups of named Java key codes that we need to handle:
    //   - Fixed keys that don't vary between input sources: VK_SPACE, VK_SHIFT, VK_NUMPAD0-VK_NUMPAD9, VK_F1-VK_F24, etc.
    //   - Dead keys: VK_DEAD_ACUTE, VK_DEAD_GRAVE, etc.
    //   - Punctuation: VK_PLUS, VK_SLASH, VK_SEMICOLON, etc.
    //   - Latin letters: VK_A-VK_Z
    //   - Numbers: VK_0-VK_9
    //
    // Fixed keys are hardcoded in keyTable and keyTableJISOverride.
    //
    // Dead keys need to be mapped into the corresponding VK_DEAD_ key codes in the same way the normal keys are mapped,
    // that is using an underlying layout. This is done by using the UCKeyTranslate function together with charToDeadVKTable.
    // It is possible to extract a (usually) non-combining dead key character by calling UCKeyTranslate
    // with the kUCKeyTranslateNoDeadKeysMask option.
    //
    // Punctuation is hardcoded in extraCharToVKTable. Latin letters and numbers are dealt with separately.
    //
    // Bonus! What does it mean to have the "national layouts" disabled? In my opinion this simply means that
    // the underlying key layout is the one that the user currently uses, or the override key layout for the input method
    // that the user currently uses. I think this approach strikes the right balance between preserving compatibility
    // with OpenJDK where it matters, while at the same time fixing a lot of annoying bugs.

    // Find out which key does the key code correspond to in the US/ABC key layout.
    // Need to take into account that the same virtual key code may correspond to
    // different keys depending on the physical layout.

    const struct KeyTableEntry* usKey = GetKeyTableEntryForKeyCode(key);

    // Determine the underlying layout.
    // If underlyingLayout is nil then fall back to using the usKey.

    TISInputSourceRef underlyingLayout = GetCurrentUnderlyingLayout(useNationalLayouts);

    // Default to returning the US key data.
    *keyCode = usKey->javaKeyCode;
    *keyLocation = usKey->javaKeyLocation;

    if (underlyingLayout == nil || !usKey->variesBetweenLayouts) {
        return;
    }

    // Translate the key using the underlying key layout.
    struct KeyCodeTranslationResult translatedKey = TranslateKeyCodeUsingLayout(underlyingLayout, key);

    // Test whether this key is dead.
    if (translatedKey.isDead) {
        for (const struct CharToVKEntry *map = charToDeadVKTable; map->c != 0; ++map) {
            if (translatedKey.character == map->c) {
                *keyCode = map->javaKey;
                return;
            }
        }

        // No builtin VK_DEAD_ constant for this dead key,
        // nothing better to do than to fall back to the extended key code.
        // This can happen on the following ascii-capable key layouts:
        //   - Apache (com.apple.keylayout.Apache)
        //   - Chickasaw (com.apple.keylayout.Chickasaw)
        //   - Choctaw (com.apple.keylayout.Choctaw)
        //   - Navajo (com.apple.keylayout.Navajo)
        //   - Vietnamese (com.apple.keylayout.Vietnamese)
        // Vietnamese layout is unique among these in that the "dead key" is actually a self-containing symbol,
        // that can be modified by an accent typed after it. In essence, it's like a dead key in reverse:
        // the user should first type the letter and only then the necessary accent.
        // This way the key code would be what the user expects.

        *keyCode = 0x1000000 + translatedKey.character;
        return;
    }

    unichar ch = 0;

    if (translatedKey.isTyped) {
        ch = translatedKey.character;
    }

    // Together with the following two checks (letters and digits) this table
    // properly handles all keys that have corresponding VK_ codes.
    // Unfortunately not all keys are like that. They are handled separately.

    for (const struct CharToVKEntry *map = extraCharToVKTable; map->c != 0; ++map) {
        if (map->c == ch) {
            *keyCode = map->javaKey;
            return;
        }
    }

    if (ch >= 'a' && ch <= 'z') {
        // key is a basic latin letter
        *keyCode = java_awt_event_KeyEvent_VK_A + ch - 'a';
        return;
    }

    if (ch >= '0' && ch <= '9') {
        // key is a digit
        // numpad digits are already handled, since they don't vary between layouts
        *keyCode = java_awt_event_KeyEvent_VK_0 + ch - '0';
        return;
    }

    if (useNationalLayouts || [[NSCharacterSet letterCharacterSet] characterIsMember:ch]) {
        // If useNationalLayouts = false, then we only convert the key codes for letters here.
        // This is the default behavior in OpenJDK and I don't think it's a good idea to change that.

        // Otherwise we also need to report characters other than letters.
        // If we ended up in this branch, this means that the character doesn't have its own VK_ code.
        // Apart from letters, this is the case for characters like the Section Sign (U+00A7)
        // on the French keyboard (key ANSI_6) or Pound Sign (U+00A3) on the Italian – QZERTY keyboard (key ANSI_8).

        *keyCode = 0x01000000 + ch;
    }
}

/*
 * This returns the java key data for the key NSEvent modifiers
 * (after NSFlagChanged).
 */
static void
NsKeyModifiersToJavaKeyInfo(NSUInteger nsFlags, unsigned short eventKeyCode,
                            jint *javaKeyCode,
                            jint *javaKeyLocation,
                            jint *javaKeyType)
{
    static NSUInteger sPreviousNSFlags = 0;

    const struct _nsKeyToJavaModifier* cur;
    NSUInteger oldNSFlags = sPreviousNSFlags;
    NSUInteger changedNSFlags = oldNSFlags ^ nsFlags;
    sPreviousNSFlags = nsFlags;

    *javaKeyCode = java_awt_event_KeyEvent_VK_UNDEFINED;
    *javaKeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_UNKNOWN;
    *javaKeyType = java_awt_event_KeyEvent_KEY_PRESSED;

    for (cur = nsKeyToJavaModifierTable; cur->nsMask != 0; ++cur) {
        if (changedNSFlags & cur->nsMask) {
            *javaKeyCode = cur->javaKey;
            *javaKeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_STANDARD;
            // TODO: uses SPI...
            //if (changedNSFlags & cur->cgsLeftMask) {
            //    *javaKeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_LEFT;
            //} else if (changedNSFlags & cur->cgsRightMask) {
            //    *javaKeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_RIGHT;
            //}
            if (eventKeyCode == cur->leftKeyCode) {
                *javaKeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_LEFT;
            } else if (eventKeyCode == cur->rightKeyCode) {
                *javaKeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_RIGHT;
            }
            *javaKeyType = (cur->nsMask & nsFlags) ?
                java_awt_event_KeyEvent_KEY_PRESSED :
                java_awt_event_KeyEvent_KEY_RELEASED;
            break;
        }
    }
}

/*
 * This returns the java modifiers for a key NSEvent.
 */
jint NsKeyModifiersToJavaModifiers(NSUInteger nsFlags, BOOL isExtMods)
{
    jint javaModifiers = 0;
    const struct _nsKeyToJavaModifier* cur;

    for (cur = nsKeyToJavaModifierTable; cur->nsMask != 0; ++cur) {
        if ((cur->nsMask & nsFlags) != 0) {
            //This code will consider the mask value for left alt as well as
            //right alt, but that should be ok, since right alt contains left alt
            //mask value.
            javaModifiers |= isExtMods ? cur->javaExtMask : cur->javaMask;
        }
    }

    return javaModifiers;
}

/*
 * This returns the NSEvent flags for java key modifiers.
 */
NSUInteger JavaModifiersToNsKeyModifiers(jint javaModifiers, BOOL isExtMods)
{
    NSUInteger nsFlags = 0;
    const struct _nsKeyToJavaModifier* cur;

    for (cur = nsKeyToJavaModifierTable; cur->nsMask != 0; ++cur) {
        jint mask = isExtMods? cur->javaExtMask : cur->javaMask;
        if ((mask & javaModifiers) != 0) {
            nsFlags |= cur->nsMask;
        }
    }

    // special case
    jint mask = isExtMods? java_awt_event_InputEvent_ALT_GRAPH_DOWN_MASK :
                           java_awt_event_InputEvent_ALT_GRAPH_MASK;

    if ((mask & javaModifiers) != 0) {
        nsFlags |= NSAlternateKeyMask;
    }

    return nsFlags;
}


jint GetJavaMouseModifiers(NSUInteger modifierFlags)
{
    // Mousing needs the key modifiers
    jint modifiers = NsKeyModifiersToJavaModifiers(modifierFlags, YES);


    /*
     * Ask Quartz about mouse buttons state
     */

    if (CGEventSourceButtonState(kCGEventSourceStateCombinedSessionState,
                                 kCGMouseButtonLeft)) {
        modifiers |= java_awt_event_InputEvent_BUTTON1_DOWN_MASK;
    }

    if (CGEventSourceButtonState(kCGEventSourceStateCombinedSessionState,
                                 kCGMouseButtonRight)) {
        modifiers |= java_awt_event_InputEvent_BUTTON3_DOWN_MASK;
    }

    if (CGEventSourceButtonState(kCGEventSourceStateCombinedSessionState,
                                 kCGMouseButtonCenter)) {
        modifiers |= java_awt_event_InputEvent_BUTTON2_DOWN_MASK;
    }

    NSInteger extraButton = 3;
    for (; extraButton < gNumberOfButtons; extraButton++) {
        if (CGEventSourceButtonState(kCGEventSourceStateCombinedSessionState,
                                 extraButton)) {
            modifiers |= gButtonDownMasks[extraButton];
        }
    }

    return modifiers;
}

jlong UTC(NSEvent *event) {
    struct timeval tv;
    if (gettimeofday(&tv, NULL) == 0) {
        long long sec = (long long)tv.tv_sec;
        return (sec*1000) + (tv.tv_usec/1000);
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_java_awt_AWTEvent_nativeSetSource
    (JNIEnv *env, jobject self, jobject newSource)
{
}

/*
 * Class:     sun_lwawt_macosx_NSEvent
 * Method:    nsToJavaModifiers
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL
Java_sun_lwawt_macosx_NSEvent_nsToJavaModifiers
(JNIEnv *env, jclass cls, jint modifierFlags)
{
    jint jmodifiers = 0;

JNI_COCOA_ENTER(env);

    jmodifiers = GetJavaMouseModifiers(modifierFlags);

JNI_COCOA_EXIT(env);

    return jmodifiers;
}

/*
 * Class:     sun_lwawt_macosx_NSEvent
 * Method:    nsToJavaKeyInfo
 * Signature: ([I[I)V
 */
JNIEXPORT void JNICALL
Java_sun_lwawt_macosx_NSEvent_nsToJavaKeyInfo
(JNIEnv *env, jclass cls, jintArray inData, jintArray outData)
{
JNI_COCOA_ENTER(env);

    jboolean copy = JNI_FALSE;
    jint *data = (*env)->GetIntArrayElements(env, inData, &copy);
    CHECK_NULL(data);

    // in  = [keyCode, useNationalLayouts]
    jshort keyCode = (jshort)data[0];
    BOOL useNationalLayouts = (data[1] != 0);

    jint jkeyCode = java_awt_event_KeyEvent_VK_UNDEFINED;
    jint jkeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_UNKNOWN;

    NsCharToJavaVirtualKeyCode((unsigned short)keyCode,
                               useNationalLayouts,
                               &jkeyCode, &jkeyLocation);

    // out = [jkeyCode, jkeyLocation];
    (*env)->SetIntArrayRegion(env, outData, 0, 1, &jkeyCode);
    (*env)->SetIntArrayRegion(env, outData, 1, 1, &jkeyLocation);

    (*env)->ReleaseIntArrayElements(env, inData, data, 0);

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_NSEvent
 * Method:    nsKeyModifiersToJavaKeyInfo
 * Signature: ([I[I)V
 */
JNIEXPORT void JNICALL
Java_sun_lwawt_macosx_NSEvent_nsKeyModifiersToJavaKeyInfo
(JNIEnv *env, jclass cls, jintArray inData, jintArray outData)
{
JNI_COCOA_ENTER(env);

    jboolean copy = JNI_FALSE;
    jint *data = (*env)->GetIntArrayElements(env, inData, &copy);
    CHECK_NULL(data);

    // in  = [modifierFlags, keyCode]
    jint modifierFlags = data[0];
    jshort keyCode = (jshort)data[1];

    jint jkeyCode = java_awt_event_KeyEvent_VK_UNDEFINED;
    jint jkeyLocation = java_awt_event_KeyEvent_KEY_LOCATION_UNKNOWN;
    jint jkeyType = java_awt_event_KeyEvent_KEY_PRESSED;

    NsKeyModifiersToJavaKeyInfo(modifierFlags,
                                keyCode,
                                &jkeyCode,
                                &jkeyLocation,
                                &jkeyType);

    // out = [jkeyCode, jkeyLocation, jkeyType];
    (*env)->SetIntArrayRegion(env, outData, 0, 1, &jkeyCode);
    (*env)->SetIntArrayRegion(env, outData, 1, 1, &jkeyLocation);
    (*env)->SetIntArrayRegion(env, outData, 2, 1, &jkeyType);

    (*env)->ReleaseIntArrayElements(env, inData, data, 0);

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_NSEvent
 * Method:    nsToJavaChar
 * Signature: (CI)C
 */
JNIEXPORT jint JNICALL
Java_sun_lwawt_macosx_NSEvent_nsToJavaChar
(JNIEnv *env, jclass cls, jchar nsChar, jint modifierFlags, jboolean spaceKeyTyped)
{
    jchar javaChar = 0;

JNI_COCOA_ENTER(env);

    javaChar = NsCharToJavaChar(nsChar, modifierFlags, spaceKeyTyped);

JNI_COCOA_EXIT(env);

    return javaChar;
}
