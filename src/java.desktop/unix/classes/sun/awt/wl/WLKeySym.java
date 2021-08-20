/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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

package sun.awt.wl;
import java.util.Hashtable;

class WLKeySym {

    private WLKeySym() {}

    record KeyDescriptor(int javaKeyCode, int keyLocation)  {
        static final KeyDescriptor UNDEFINED_INSTANCE = new KeyDescriptor(
                java.awt.event.KeyEvent.VK_UNDEFINED,
                java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN);

        static KeyDescriptor fromXKBCode(long code) {
            return xkbKeymap.getOrDefault(code, UNDEFINED_INSTANCE);
        }
    }

    static boolean xkbCodeIsModifier(long code) {
        return code == XK_Shift_L || code == XK_Shift_R || code == XK_Control_L
                || code == XK_Control_R || code == XK_Alt_L || code == XK_Alt_R
                || code == XK_Meta_L || code == XK_Meta_R || code == XK_Caps_Lock
                || code == XK_Shift_Lock;
    }

    private static final Hashtable<Long, KeyDescriptor> xkbKeymap = new Hashtable<>();

    public static final long XK_BackSpace = 0xFF08 ; /* back space, back char */
    public static final long XK_Tab = 0xFF09 ;
    public static final long XK_Linefeed = 0xFF0A ; /* Linefeed, LF */
    public static final long XK_Clear = 0xFF0B ;
    public static final long XK_Return = 0xFF0D ; /* Return, enter */
    public static final long XK_Pause = 0xFF13 ; /* Pause, hold */
    public static final long XK_Scroll_Lock = 0xFF14 ;
    public static final long XK_Sys_Req = 0xFF15 ;
    public static final long XK_Escape = 0xFF1B ;
    public static final long XK_Delete = 0xFFFF ; /* Delete, rubout */



    /* International & multi-key character composition */

    public static final long XK_Multi_key = 0xFF20 ; /* Multi-key character compose */
    public static final long XK_Codeinput = 0xFF37 ;
    public static final long XK_SingleCandidate = 0xFF3C ;
    public static final long XK_MultipleCandidate = 0xFF3D ;
    public static final long XK_PreviousCandidate = 0xFF3E ;

    /* Japanese keyboard support */

    public static final long XK_Kanji = 0xFF21 ; /* Kanji, Kanji convert */
    public static final long XK_Muhenkan = 0xFF22 ; /* Cancel Conversion */
    public static final long XK_Henkan_Mode = 0xFF23 ; /* Start/Stop Conversion */
    public static final long XK_Henkan = 0xFF23 ; /* Alias for Henkan_Mode */
    public static final long XK_Romaji = 0xFF24 ; /* to Romaji */
    public static final long XK_Hiragana = 0xFF25 ; /* to Hiragana */
    public static final long XK_Katakana = 0xFF26 ; /* to Katakana */
    public static final long XK_Hiragana_Katakana = 0xFF27 ; /* Hiragana/Katakana toggle */
    public static final long XK_Zenkaku = 0xFF28 ; /* to Zenkaku */
    public static final long XK_Hankaku = 0xFF29 ; /* to Hankaku */
    public static final long XK_Zenkaku_Hankaku = 0xFF2A ; /* Zenkaku/Hankaku toggle */
    public static final long XK_Touroku = 0xFF2B ; /* Add to Dictionary */
    public static final long XK_Massyo = 0xFF2C ; /* Delete from Dictionary */
    public static final long XK_Kana_Lock = 0xFF2D ; /* Kana Lock */
    public static final long XK_Kana_Shift = 0xFF2E ; /* Kana Shift */
    public static final long XK_Eisu_Shift = 0xFF2F ; /* Alphanumeric Shift */
    public static final long XK_Eisu_toggle = 0xFF30 ; /* Alphanumeric toggle */
    public static final long XK_Kanji_Bangou = 0xFF37 ; /* Codeinput */
    public static final long XK_Zen_Koho = 0xFF3D ; /* Multiple/All Candidate(s) */
    public static final long XK_Mae_Koho = 0xFF3E ; /* Previous Candidate */

    /* 0xFF31 thru 0xFF3F are under XK_KOREAN */

    /* Cursor control & motion */

    public static final long XK_Home = 0xFF50 ;
    public static final long XK_Left = 0xFF51 ; /* Move left, left arrow */
    public static final long XK_Up = 0xFF52 ; /* Move up, up arrow */
    public static final long XK_Right = 0xFF53 ; /* Move right, right arrow */
    public static final long XK_Down = 0xFF54 ; /* Move down, down arrow */
    public static final long XK_Prior = 0xFF55 ; /* Prior, previous */
    public static final long XK_Page_Up = 0xFF55 ;
    public static final long XK_Next = 0xFF56 ; /* Next */
    public static final long XK_Page_Down = 0xFF56 ;
    public static final long XK_End = 0xFF57 ; /* EOL */
    public static final long XK_Begin = 0xFF58 ; /* BOL */


    /* Misc Functions */

    public static final long XK_Select = 0xFF60 ; /* Select, mark */
    public static final long XK_Print = 0xFF61 ;
    public static final long XK_Execute = 0xFF62 ; /* Execute, run, do */
    public static final long XK_Insert = 0xFF63 ; /* Insert, insert here */
    public static final long XK_Undo = 0xFF65 ; /* Undo, oops */
    public static final long XK_Redo = 0xFF66 ; /* redo, again */
    public static final long XK_Menu = 0xFF67 ;
    public static final long XK_Find = 0xFF68 ; /* Find, search */
    public static final long XK_Cancel = 0xFF69 ; /* Cancel, stop, abort, exit */
    public static final long XK_Help = 0xFF6A ; /* Help */
    public static final long XK_Break = 0xFF6B ;
    public static final long XK_Mode_switch = 0xFF7E ; /* Character set switch */
    public static final long XK_script_switch = 0xFF7E ; /* Alias for mode_switch */
    public static final long XK_Num_Lock = 0xFF7F ;

    /* Keypad Functions, keypad numbers cleverly chosen to map to ascii */

    public static final long XK_KP_Space = 0xFF80 ; /* space */
    public static final long XK_KP_Tab = 0xFF89 ;
    public static final long XK_KP_Enter = 0xFF8D ; /* enter */
    public static final long XK_KP_F1 = 0xFF91 ; /* PF1, KP_A, ... */
    public static final long XK_KP_F2 = 0xFF92 ;
    public static final long XK_KP_F3 = 0xFF93 ;
    public static final long XK_KP_F4 = 0xFF94 ;
    public static final long XK_KP_Home = 0xFF95 ;
    public static final long XK_KP_Left = 0xFF96 ;
    public static final long XK_KP_Up = 0xFF97 ;
    public static final long XK_KP_Right = 0xFF98 ;
    public static final long XK_KP_Down = 0xFF99 ;
    public static final long XK_KP_Prior = 0xFF9A ;
    public static final long XK_KP_Page_Up = 0xFF9A ;
    public static final long XK_KP_Next = 0xFF9B ;
    public static final long XK_KP_Page_Down = 0xFF9B ;
    public static final long XK_KP_End = 0xFF9C ;
    public static final long XK_KP_Begin = 0xFF9D ;
    public static final long XK_KP_Insert = 0xFF9E ;
    public static final long XK_KP_Delete = 0xFF9F ;
    public static final long XK_KP_Equal = 0xFFBD ; /* equals */
    public static final long XK_KP_Multiply = 0xFFAA ;
    public static final long XK_KP_Add = 0xFFAB ;
    public static final long XK_KP_Separator = 0xFFAC ; /* separator, often comma */
    public static final long XK_KP_Subtract = 0xFFAD ;
    public static final long XK_KP_Decimal = 0xFFAE ;
    public static final long XK_KP_Divide = 0xFFAF ;

    public static final long XK_KP_0 = 0xFFB0 ;
    public static final long XK_KP_1 = 0xFFB1 ;
    public static final long XK_KP_2 = 0xFFB2 ;
    public static final long XK_KP_3 = 0xFFB3 ;
    public static final long XK_KP_4 = 0xFFB4 ;
    public static final long XK_KP_5 = 0xFFB5 ;
    public static final long XK_KP_6 = 0xFFB6 ;
    public static final long XK_KP_7 = 0xFFB7 ;
    public static final long XK_KP_8 = 0xFFB8 ;
    public static final long XK_KP_9 = 0xFFB9 ;



    /*
     * Auxilliary Functions; note the duplicate definitions for left and right
     * function keys;  Sun keyboards and a few other manufactures have such
     * function key groups on the left and/or right sides of the keyboard.
     * We've not found a keyboard with more than 35 function keys total.
     */

    public static final long XK_F1 = 0xFFBE ;
    public static final long XK_F2 = 0xFFBF ;
    public static final long XK_F3 = 0xFFC0 ;
    public static final long XK_F4 = 0xFFC1 ;
    public static final long XK_F5 = 0xFFC2 ;
    public static final long XK_F6 = 0xFFC3 ;
    public static final long XK_F7 = 0xFFC4 ;
    public static final long XK_F8 = 0xFFC5 ;
    public static final long XK_F9 = 0xFFC6 ;
    public static final long XK_F10 = 0xFFC7 ;
    public static final long XK_F11 = 0xFFC8 ;
    public static final long XK_L1 = 0xFFC8 ;
    public static final long XK_F12 = 0xFFC9 ;
    public static final long XK_L2 = 0xFFC9 ;
    public static final long XK_F13 = 0xFFCA ;
    public static final long XK_L3 = 0xFFCA ;
    public static final long XK_F14 = 0xFFCB ;
    public static final long XK_L4 = 0xFFCB ;
    public static final long XK_F15 = 0xFFCC ;
    public static final long XK_L5 = 0xFFCC ;
    public static final long XK_F16 = 0xFFCD ;
    public static final long XK_L6 = 0xFFCD ;
    public static final long XK_F17 = 0xFFCE ;
    public static final long XK_L7 = 0xFFCE ;
    public static final long XK_F18 = 0xFFCF ;
    public static final long XK_L8 = 0xFFCF ;
    public static final long XK_F19 = 0xFFD0 ;
    public static final long XK_L9 = 0xFFD0 ;
    public static final long XK_F20 = 0xFFD1 ;
    public static final long XK_L10 = 0xFFD1 ;
    public static final long XK_F21 = 0xFFD2 ;
    public static final long XK_R1 = 0xFFD2 ;
    public static final long XK_F22 = 0xFFD3 ;
    public static final long XK_R2 = 0xFFD3 ;
    public static final long XK_F23 = 0xFFD4 ;
    public static final long XK_R3 = 0xFFD4 ;
    public static final long XK_F24 = 0xFFD5 ;
    public static final long XK_R4 = 0xFFD5 ;
    public static final long XK_F25 = 0xFFD6 ;
    public static final long XK_R5 = 0xFFD6 ;
    public static final long XK_F26 = 0xFFD7 ;
    public static final long XK_R6 = 0xFFD7 ;
    public static final long XK_F27 = 0xFFD8 ;
    public static final long XK_R7 = 0xFFD8 ;
    public static final long XK_F28 = 0xFFD9 ;
    public static final long XK_R8 = 0xFFD9 ;
    public static final long XK_F29 = 0xFFDA ;
    public static final long XK_R9 = 0xFFDA ;
    public static final long XK_F30 = 0xFFDB ;
    public static final long XK_R10 = 0xFFDB ;
    public static final long XK_F31 = 0xFFDC ;
    public static final long XK_R11 = 0xFFDC ;
    public static final long XK_F32 = 0xFFDD ;
    public static final long XK_R12 = 0xFFDD ;
    public static final long XK_F33 = 0xFFDE ;
    public static final long XK_R13 = 0xFFDE ;
    public static final long XK_F34 = 0xFFDF ;
    public static final long XK_R14 = 0xFFDF ;
    public static final long XK_F35 = 0xFFE0 ;
    public static final long XK_R15 = 0xFFE0 ;

    /* Modifiers */

    public static final long XK_Shift_L = 0xFFE1 ; /* Left shift */
    public static final long XK_Shift_R = 0xFFE2 ; /* Right shift */
    public static final long XK_Control_L = 0xFFE3 ; /* Left control */
    public static final long XK_Control_R = 0xFFE4 ; /* Right control */
    public static final long XK_Caps_Lock = 0xFFE5 ; /* Caps lock */
    public static final long XK_Shift_Lock = 0xFFE6 ; /* Shift lock */

    public static final long XK_Meta_L = 0xFFE7 ; /* Left meta */
    public static final long XK_Meta_R = 0xFFE8 ; /* Right meta */
    public static final long XK_Alt_L = 0xFFE9 ; /* Left alt */
    public static final long XK_Alt_R = 0xFFEA ; /* Right alt */
    public static final long XK_Super_L = 0xFFEB ; /* Left super */
    public static final long XK_Super_R = 0xFFEC ; /* Right super */
    public static final long XK_Hyper_L = 0xFFED ; /* Left hyper */
    public static final long XK_Hyper_R = 0xFFEE ; /* Right hyper */

    /*
     * ISO 9995 Function and Modifier Keys
     * Byte 3 = 0xFE
     */

    public static final long XK_ISO_Lock = 0xFE01 ;
    public static final long XK_ISO_Level2_Latch = 0xFE02 ;
    public static final long XK_ISO_Level3_Shift = 0xFE03 ;
    public static final long XK_ISO_Level3_Latch = 0xFE04 ;
    public static final long XK_ISO_Level3_Lock = 0xFE05 ;
    public static final long XK_ISO_Group_Shift = 0xFF7E ; /* Alias for mode_switch */
    public static final long XK_ISO_Group_Latch = 0xFE06 ;
    public static final long XK_ISO_Group_Lock = 0xFE07 ;
    public static final long XK_ISO_Next_Group = 0xFE08 ;
    public static final long XK_ISO_Next_Group_Lock = 0xFE09 ;
    public static final long XK_ISO_Prev_Group = 0xFE0A ;
    public static final long XK_ISO_Prev_Group_Lock = 0xFE0B ;
    public static final long XK_ISO_First_Group = 0xFE0C ;
    public static final long XK_ISO_First_Group_Lock = 0xFE0D ;
    public static final long XK_ISO_Last_Group = 0xFE0E ;
    public static final long XK_ISO_Last_Group_Lock = 0xFE0F ;

    public static final long XK_ISO_Left_Tab = 0xFE20 ;
    public static final long XK_ISO_Move_Line_Up = 0xFE21 ;
    public static final long XK_ISO_Move_Line_Down = 0xFE22 ;
    public static final long XK_ISO_Partial_Line_Up = 0xFE23 ;
    public static final long XK_ISO_Partial_Line_Down = 0xFE24 ;
    public static final long XK_ISO_Partial_Space_Left = 0xFE25 ;
    public static final long XK_ISO_Partial_Space_Right = 0xFE26 ;
    public static final long XK_ISO_Set_Margin_Left = 0xFE27 ;
    public static final long XK_ISO_Set_Margin_Right = 0xFE28 ;
    public static final long XK_ISO_Release_Margin_Left = 0xFE29 ;
    public static final long XK_ISO_Release_Margin_Right = 0xFE2A ;
    public static final long XK_ISO_Release_Both_Margins = 0xFE2B ;
    public static final long XK_ISO_Fast_Cursor_Left = 0xFE2C ;
    public static final long XK_ISO_Fast_Cursor_Right = 0xFE2D ;
    public static final long XK_ISO_Fast_Cursor_Up = 0xFE2E ;
    public static final long XK_ISO_Fast_Cursor_Down = 0xFE2F ;
    public static final long XK_ISO_Continuous_Underline = 0xFE30 ;
    public static final long XK_ISO_Discontinuous_Underline = 0xFE31 ;
    public static final long XK_ISO_Emphasize = 0xFE32 ;
    public static final long XK_ISO_Center_Object = 0xFE33 ;
    public static final long XK_ISO_Enter = 0xFE34 ;

    public static final long XK_dead_grave = 0xFE50 ;
    public static final long XK_dead_acute = 0xFE51 ;
    public static final long XK_dead_circumflex = 0xFE52 ;
    public static final long XK_dead_tilde = 0xFE53 ;
    public static final long XK_dead_macron = 0xFE54 ;
    public static final long XK_dead_breve = 0xFE55 ;
    public static final long XK_dead_abovedot = 0xFE56 ;
    public static final long XK_dead_diaeresis = 0xFE57 ;
    public static final long XK_dead_abovering = 0xFE58 ;
    public static final long XK_dead_doubleacute = 0xFE59 ;
    public static final long XK_dead_caron = 0xFE5A ;
    public static final long XK_dead_cedilla = 0xFE5B ;
    public static final long XK_dead_ogonek = 0xFE5C ;
    public static final long XK_dead_iota = 0xFE5D ;
    public static final long XK_dead_voiced_sound = 0xFE5E ;
    public static final long XK_dead_semivoiced_sound = 0xFE5F ;
    public static final long XK_dead_belowdot = 0xFE60 ;

    public static final long XK_First_Virtual_Screen = 0xFED0 ;
    public static final long XK_Prev_Virtual_Screen = 0xFED1 ;
    public static final long XK_Next_Virtual_Screen = 0xFED2 ;
    public static final long XK_Last_Virtual_Screen = 0xFED4 ;
    public static final long XK_Terminate_Server = 0xFED5 ;

    public static final long XK_AccessX_Enable = 0xFE70 ;
    public static final long XK_AccessX_Feedback_Enable = 0xFE71 ;
    public static final long XK_RepeatKeys_Enable = 0xFE72 ;
    public static final long XK_SlowKeys_Enable = 0xFE73 ;
    public static final long XK_BounceKeys_Enable = 0xFE74 ;
    public static final long XK_StickyKeys_Enable = 0xFE75 ;
    public static final long XK_MouseKeys_Enable = 0xFE76 ;
    public static final long XK_MouseKeys_Accel_Enable = 0xFE77 ;
    public static final long XK_Overlay1_Enable = 0xFE78 ;
    public static final long XK_Overlay2_Enable = 0xFE79 ;
    public static final long XK_AudibleBell_Enable = 0xFE7A ;

    public static final long XK_Pointer_Left = 0xFEE0 ;
    public static final long XK_Pointer_Right = 0xFEE1 ;
    public static final long XK_Pointer_Up = 0xFEE2 ;
    public static final long XK_Pointer_Down = 0xFEE3 ;
    public static final long XK_Pointer_UpLeft = 0xFEE4 ;
    public static final long XK_Pointer_UpRight = 0xFEE5 ;
    public static final long XK_Pointer_DownLeft = 0xFEE6 ;
    public static final long XK_Pointer_DownRight = 0xFEE7 ;
    public static final long XK_Pointer_Button_Dflt = 0xFEE8 ;
    public static final long XK_Pointer_Button1 = 0xFEE9 ;
    public static final long XK_Pointer_Button2 = 0xFEEA ;
    public static final long XK_Pointer_Button3 = 0xFEEB ;
    public static final long XK_Pointer_Button4 = 0xFEEC ;
    public static final long XK_Pointer_Button5 = 0xFEED ;
    public static final long XK_Pointer_DblClick_Dflt = 0xFEEE ;
    public static final long XK_Pointer_DblClick1 = 0xFEEF ;
    public static final long XK_Pointer_DblClick2 = 0xFEF0 ;
    public static final long XK_Pointer_DblClick3 = 0xFEF1 ;
    public static final long XK_Pointer_DblClick4 = 0xFEF2 ;
    public static final long XK_Pointer_DblClick5 = 0xFEF3 ;
    public static final long XK_Pointer_Drag_Dflt = 0xFEF4 ;
    public static final long XK_Pointer_Drag1 = 0xFEF5 ;
    public static final long XK_Pointer_Drag2 = 0xFEF6 ;
    public static final long XK_Pointer_Drag3 = 0xFEF7 ;
    public static final long XK_Pointer_Drag4 = 0xFEF8 ;
    public static final long XK_Pointer_Drag5 = 0xFEFD ;

    public static final long XK_Pointer_EnableKeys = 0xFEF9 ;
    public static final long XK_Pointer_Accelerate = 0xFEFA ;
    public static final long XK_Pointer_DfltBtnNext = 0xFEFB ;
    public static final long XK_Pointer_DfltBtnPrev = 0xFEFC ;

    /*
     *  Latin 1
     *  Byte 3 = 0
     */
    public static final long XK_space = 0x020 ;
    public static final long XK_exclam = 0x021 ;
    public static final long XK_quotedbl = 0x022 ;
    public static final long XK_numbersign = 0x023 ;
    public static final long XK_dollar = 0x024 ;
    public static final long XK_percent = 0x025 ;
    public static final long XK_ampersand = 0x026 ;
    public static final long XK_apostrophe = 0x027 ;
    public static final long XK_quoteright = 0x027 ; /* deprecated */
    public static final long XK_parenleft = 0x028 ;
    public static final long XK_parenright = 0x029 ;
    public static final long XK_asterisk = 0x02a ;
    public static final long XK_plus = 0x02b ;
    public static final long XK_comma = 0x02c ;
    public static final long XK_minus = 0x02d ;
    public static final long XK_period = 0x02e ;
    public static final long XK_slash = 0x02f ;
    public static final long XK_0 = 0x030 ;
    public static final long XK_1 = 0x031 ;
    public static final long XK_2 = 0x032 ;
    public static final long XK_3 = 0x033 ;
    public static final long XK_4 = 0x034 ;
    public static final long XK_5 = 0x035 ;
    public static final long XK_6 = 0x036 ;
    public static final long XK_7 = 0x037 ;
    public static final long XK_8 = 0x038 ;
    public static final long XK_9 = 0x039 ;
    public static final long XK_colon = 0x03a ;
    public static final long XK_semicolon = 0x03b ;
    public static final long XK_less = 0x03c ;
    public static final long XK_equal = 0x03d ;
    public static final long XK_greater = 0x03e ;
    public static final long XK_question = 0x03f ;
    public static final long XK_at = 0x040 ;
    public static final long XK_A = 0x041 ;
    public static final long XK_B = 0x042 ;
    public static final long XK_C = 0x043 ;
    public static final long XK_D = 0x044 ;
    public static final long XK_E = 0x045 ;
    public static final long XK_F = 0x046 ;
    public static final long XK_G = 0x047 ;
    public static final long XK_H = 0x048 ;
    public static final long XK_I = 0x049 ;
    public static final long XK_J = 0x04a ;
    public static final long XK_K = 0x04b ;
    public static final long XK_L = 0x04c ;
    public static final long XK_M = 0x04d ;
    public static final long XK_N = 0x04e ;
    public static final long XK_O = 0x04f ;
    public static final long XK_P = 0x050 ;
    public static final long XK_Q = 0x051 ;
    public static final long XK_R = 0x052 ;
    public static final long XK_S = 0x053 ;
    public static final long XK_T = 0x054 ;
    public static final long XK_U = 0x055 ;
    public static final long XK_V = 0x056 ;
    public static final long XK_W = 0x057 ;
    public static final long XK_X = 0x058 ;
    public static final long XK_Y = 0x059 ;
    public static final long XK_Z = 0x05a ;
    public static final long XK_bracketleft = 0x05b ;
    public static final long XK_backslash = 0x05c ;
    public static final long XK_bracketright = 0x05d ;
    public static final long XK_asciicircum = 0x05e ;
    public static final long XK_underscore = 0x05f ;
    public static final long XK_grave = 0x060 ;
    public static final long XK_quoteleft = 0x060 ; /* deprecated */
    public static final long XK_a = 0x061 ;
    public static final long XK_b = 0x062 ;
    public static final long XK_c = 0x063 ;
    public static final long XK_d = 0x064 ;
    public static final long XK_e = 0x065 ;
    public static final long XK_f = 0x066 ;
    public static final long XK_g = 0x067 ;
    public static final long XK_h = 0x068 ;
    public static final long XK_i = 0x069 ;
    public static final long XK_j = 0x06a ;
    public static final long XK_k = 0x06b ;
    public static final long XK_l = 0x06c ;
    public static final long XK_m = 0x06d ;
    public static final long XK_n = 0x06e ;
    public static final long XK_o = 0x06f ;
    public static final long XK_p = 0x070 ;
    public static final long XK_q = 0x071 ;
    public static final long XK_r = 0x072 ;
    public static final long XK_s = 0x073 ;
    public static final long XK_t = 0x074 ;
    public static final long XK_u = 0x075 ;
    public static final long XK_v = 0x076 ;
    public static final long XK_w = 0x077 ;
    public static final long XK_x = 0x078 ;
    public static final long XK_y = 0x079 ;
    public static final long XK_z = 0x07a ;
    public static final long XK_braceleft = 0x07b ;
    public static final long XK_bar = 0x07c ;
    public static final long XK_braceright = 0x07d ;
    public static final long XK_asciitilde = 0x07e ;

    public static final long XK_nobreakspace = 0x0a0 ;
    public static final long XK_exclamdown = 0x0a1 ;
    public static final long XK_cent = 0x0a2 ;
    public static final long XK_sterling = 0x0a3 ;
    public static final long XK_currency = 0x0a4 ;
    public static final long XK_yen = 0x0a5 ;
    public static final long XK_brokenbar = 0x0a6 ;
    public static final long XK_section = 0x0a7 ;
    public static final long XK_diaeresis = 0x0a8 ;
    public static final long XK_copyright = 0x0a9 ;
    public static final long XK_ordfeminine = 0x0aa ;
    public static final long XK_guillemotleft = 0x0ab ; /* left angle quotation mark */
    public static final long XK_notsign = 0x0ac ;
    public static final long XK_hyphen = 0x0ad ;
    public static final long XK_registered = 0x0ae ;
    public static final long XK_macron = 0x0af ;
    public static final long XK_degree = 0x0b0 ;
    public static final long XK_plusminus = 0x0b1 ;
    public static final long XK_twosuperior = 0x0b2 ;
    public static final long XK_threesuperior = 0x0b3 ;
    public static final long XK_acute = 0x0b4 ;
    public static final long XK_mu = 0x0b5 ;
    public static final long XK_paragraph = 0x0b6 ;
    public static final long XK_periodcentered = 0x0b7 ;
    public static final long XK_cedilla = 0x0b8 ;
    public static final long XK_onesuperior = 0x0b9 ;
    public static final long XK_masculine = 0x0ba ;
    public static final long XK_guillemotright = 0x0bb ; /* right angle quotation mark */
    public static final long XK_onequarter = 0x0bc ;
    public static final long XK_onehalf = 0x0bd ;
    public static final long XK_threequarters = 0x0be ;
    public static final long XK_questiondown = 0x0bf ;
    public static final long XK_Agrave = 0x0c0 ;
    public static final long XK_Aacute = 0x0c1 ;
    public static final long XK_Acircumflex = 0x0c2 ;
    public static final long XK_Atilde = 0x0c3 ;
    public static final long XK_Adiaeresis = 0x0c4 ;
    public static final long XK_Aring = 0x0c5 ;
    public static final long XK_AE = 0x0c6 ;
    public static final long XK_Ccedilla = 0x0c7 ;
    public static final long XK_Egrave = 0x0c8 ;
    public static final long XK_Eacute = 0x0c9 ;
    public static final long XK_Ecircumflex = 0x0ca ;
    public static final long XK_Ediaeresis = 0x0cb ;
    public static final long XK_Igrave = 0x0cc ;
    public static final long XK_Iacute = 0x0cd ;
    public static final long XK_Icircumflex = 0x0ce ;
    public static final long XK_Idiaeresis = 0x0cf ;
    public static final long XK_ETH = 0x0d0 ;
    public static final long XK_Eth = 0x0d0 ; /* deprecated */
    public static final long XK_Ntilde = 0x0d1 ;
    public static final long XK_Ograve = 0x0d2 ;
    public static final long XK_Oacute = 0x0d3 ;
    public static final long XK_Ocircumflex = 0x0d4 ;
    public static final long XK_Otilde = 0x0d5 ;
    public static final long XK_Odiaeresis = 0x0d6 ;
    public static final long XK_multiply = 0x0d7 ;
    public static final long XK_Ooblique = 0x0d8 ;
    public static final long XK_Ugrave = 0x0d9 ;
    public static final long XK_Uacute = 0x0da ;
    public static final long XK_Ucircumflex = 0x0db ;
    public static final long XK_Udiaeresis = 0x0dc ;
    public static final long XK_Yacute = 0x0dd ;
    public static final long XK_THORN = 0x0de ;
    public static final long XK_Thorn = 0x0de ; /* deprecated */
    public static final long XK_ssharp = 0x0df ;
    public static final long XK_agrave = 0x0e0 ;
    public static final long XK_aacute = 0x0e1 ;
    public static final long XK_acircumflex = 0x0e2 ;
    public static final long XK_atilde = 0x0e3 ;
    public static final long XK_adiaeresis = 0x0e4 ;
    public static final long XK_aring = 0x0e5 ;
    public static final long XK_ae = 0x0e6 ;
    public static final long XK_ccedilla = 0x0e7 ;
    public static final long XK_egrave = 0x0e8 ;
    public static final long XK_eacute = 0x0e9 ;
    public static final long XK_ecircumflex = 0x0ea ;
    public static final long XK_ediaeresis = 0x0eb ;
    public static final long XK_igrave = 0x0ec ;
    public static final long XK_iacute = 0x0ed ;
    public static final long XK_icircumflex = 0x0ee ;
    public static final long XK_idiaeresis = 0x0ef ;
    public static final long XK_eth = 0x0f0 ;
    public static final long XK_ntilde = 0x0f1 ;
    public static final long XK_ograve = 0x0f2 ;
    public static final long XK_oacute = 0x0f3 ;
    public static final long XK_ocircumflex = 0x0f4 ;
    public static final long XK_otilde = 0x0f5 ;
    public static final long XK_odiaeresis = 0x0f6 ;
    public static final long XK_division = 0x0f7 ;
    public static final long XK_oslash = 0x0f8 ;
    public static final long XK_ugrave = 0x0f9 ;
    public static final long XK_uacute = 0x0fa ;
    public static final long XK_ucircumflex = 0x0fb ;
    public static final long XK_udiaeresis = 0x0fc ;
    public static final long XK_yacute = 0x0fd ;
    public static final long XK_thorn = 0x0fe ;
    public static final long XK_ydiaeresis = 0x0ff ;
    // vendor-specific keys from ap_keysym.h, DEC/Sun/HPkeysym.h

    public static final long apXK_Copy = 0x1000FF02;
    public static final long apXK_Cut = 0x1000FF03;
    public static final long apXK_Paste = 0x1000FF04;

    public static final long DXK_ring_accent = 0x1000FEB0;
    public static final long DXK_circumflex_accent = 0x1000FE5E;
    public static final long DXK_cedilla_accent = 0x1000FE2C;
    public static final long DXK_acute_accent = 0x1000FE27;
    public static final long DXK_grave_accent = 0x1000FE60;
    public static final long DXK_tilde = 0x1000FE7E;
    public static final long DXK_diaeresis = 0x1000FE22;

    public static final long hpXK_ClearLine  = 0x1000FF6F;
    public static final long hpXK_InsertLine  = 0x1000FF70;
    public static final long hpXK_DeleteLine  = 0x1000FF71;
    public static final long hpXK_InsertChar  = 0x1000FF72;
    public static final long hpXK_DeleteChar  = 0x1000FF73;
    public static final long hpXK_BackTab  = 0x1000FF74;
    public static final long hpXK_KP_BackTab  = 0x1000FF75;
    public static final long hpXK_Modelock1  = 0x1000FF48;
    public static final long hpXK_Modelock2  = 0x1000FF49;
    public static final long hpXK_Reset  = 0x1000FF6C;
    public static final long hpXK_System  = 0x1000FF6D;
    public static final long hpXK_User  = 0x1000FF6E;
    public static final long hpXK_mute_acute  = 0x100000A8;
    public static final long hpXK_mute_grave  = 0x100000A9;
    public static final long hpXK_mute_asciicircum  = 0x100000AA;
    public static final long hpXK_mute_diaeresis  = 0x100000AB;
    public static final long hpXK_mute_asciitilde  = 0x100000AC;
    public static final long hpXK_lira  = 0x100000AF;
    public static final long hpXK_guilder  = 0x100000BE;
    public static final long hpXK_Ydiaeresis  = 0x100000EE;
    public static final long hpXK_IO   = 0x100000EE;
    public static final long hpXK_longminus  = 0x100000F6;
    public static final long hpXK_block  = 0x100000FC;


    public static final long osfXK_Copy  = 0x1004FF02;
    public static final long osfXK_Cut  = 0x1004FF03;
    public static final long osfXK_Paste  = 0x1004FF04;
    public static final long osfXK_BackTab  = 0x1004FF07;
    public static final long osfXK_BackSpace  = 0x1004FF08;
    public static final long osfXK_Clear  = 0x1004FF0B;
    public static final long osfXK_Escape  = 0x1004FF1B;
    public static final long osfXK_AddMode  = 0x1004FF31;
    public static final long osfXK_PrimaryPaste  = 0x1004FF32;
    public static final long osfXK_QuickPaste  = 0x1004FF33;
    public static final long osfXK_PageLeft  = 0x1004FF40;
    public static final long osfXK_PageUp  = 0x1004FF41;
    public static final long osfXK_PageDown  = 0x1004FF42;
    public static final long osfXK_PageRight  = 0x1004FF43;
    public static final long osfXK_Activate  = 0x1004FF44;
    public static final long osfXK_MenuBar  = 0x1004FF45;
    public static final long osfXK_Left  = 0x1004FF51;
    public static final long osfXK_Up  = 0x1004FF52;
    public static final long osfXK_Right  = 0x1004FF53;
    public static final long osfXK_Down  = 0x1004FF54;
    public static final long osfXK_EndLine  = 0x1004FF57;
    public static final long osfXK_BeginLine  = 0x1004FF58;
    public static final long osfXK_EndData  = 0x1004FF59;
    public static final long osfXK_BeginData  = 0x1004FF5A;
    public static final long osfXK_PrevMenu  = 0x1004FF5B;
    public static final long osfXK_NextMenu  = 0x1004FF5C;
    public static final long osfXK_PrevField  = 0x1004FF5D;
    public static final long osfXK_NextField  = 0x1004FF5E;
    public static final long osfXK_Select  = 0x1004FF60;
    public static final long osfXK_Insert  = 0x1004FF63;
    public static final long osfXK_Undo  = 0x1004FF65;
    public static final long osfXK_Menu  = 0x1004FF67;
    public static final long osfXK_Cancel = 0x1004FF69;
    public static final long osfXK_Help = 0x1004FF6A;
    public static final long osfXK_Delete = 0x1004FFFF;
    public static final long osfXK_Prior = 0x1004FF55;
    public static final long osfXK_Next = 0x1004FF56;




    public static final long  SunXK_FA_Grave  = 0x1005FF00;
    public static final long  SunXK_FA_Circum  = 0x1005FF01;
    public static final long  SunXK_FA_Tilde  = 0x1005FF02;
    public static final long  SunXK_FA_Acute  = 0x1005FF03;
    public static final long  SunXK_FA_Diaeresis  = 0x1005FF04;
    public static final long  SunXK_FA_Cedilla  = 0x1005FF05;

    public static final long  SunXK_F36  = 0x1005FF10; /* Labeled F11 */
    public static final long  SunXK_F37  = 0x1005FF11; /* Labeled F12 */

    public static final long SunXK_Sys_Req     = 0x1005FF60;
    public static final long SunXK_Print_Screen  = 0x0000FF61; /* Same as XK_Print */

    public static final long SunXK_Compose  = 0x0000FF20; /* Same as XK_Multi_key */
    public static final long SunXK_AltGraph  = 0x0000FF7E; /* Same as XK_Mode_switch */

    public static final long SunXK_PageUp  = 0x0000FF55;  /* Same as XK_Prior */
    public static final long SunXK_PageDown  = 0x0000FF56; /* Same as XK_Next */

    public static final long SunXK_Undo  = 0x0000FF65; /* Same as XK_Undo */
    public static final long SunXK_Again  = 0x0000FF66; /* Same as XK_Redo */
    public static final long SunXK_Find  = 0x0000FF68; /* Same as XK_Find */
    public static final long SunXK_Stop  = 0x0000FF69; /* Same as XK_Cancel */
    public static final long SunXK_Props  = 0x1005FF70;
    public static final long SunXK_Front  = 0x1005FF71;
    public static final long SunXK_Copy  = 0x1005FF72;
    public static final long SunXK_Open  = 0x1005FF73;
    public static final long SunXK_Paste  = 0x1005FF74;
    public static final long SunXK_Cut  = 0x1005FF75;

    public static final long SunXK_PowerSwitch  = 0x1005FF76;
    public static final long SunXK_AudioLowerVolume  = 0x1005FF77;
    public static final long SunXK_AudioMute   = 0x1005FF78;
    public static final long SunXK_AudioRaiseVolume  = 0x1005FF79;
    public static final long SunXK_VideoDegauss  = 0x1005FF7A;
    public static final long SunXK_VideoLowerBrightness  = 0x1005FF7B;
    public static final long SunXK_VideoRaiseBrightness  = 0x1005FF7C;
    public static final long SunXK_PowerSwitchShift  = 0x1005FF7D;

    static {
        // The following has been copied from XKeysym.java
        xkbKeymap.put( Long.valueOf(XK_a),     new KeyDescriptor(java.awt.event.KeyEvent.VK_A, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_b),     new KeyDescriptor(java.awt.event.KeyEvent.VK_B, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_c),     new KeyDescriptor(java.awt.event.KeyEvent.VK_C, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_d),     new KeyDescriptor(java.awt.event.KeyEvent.VK_D, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_e),     new KeyDescriptor(java.awt.event.KeyEvent.VK_E, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_f),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_g),     new KeyDescriptor(java.awt.event.KeyEvent.VK_G, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_h),     new KeyDescriptor(java.awt.event.KeyEvent.VK_H, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_i),     new KeyDescriptor(java.awt.event.KeyEvent.VK_I, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_j),     new KeyDescriptor(java.awt.event.KeyEvent.VK_J, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_k),     new KeyDescriptor(java.awt.event.KeyEvent.VK_K, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_l),     new KeyDescriptor(java.awt.event.KeyEvent.VK_L, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_m),     new KeyDescriptor(java.awt.event.KeyEvent.VK_M, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_n),     new KeyDescriptor(java.awt.event.KeyEvent.VK_N, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_o),     new KeyDescriptor(java.awt.event.KeyEvent.VK_O, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_p),     new KeyDescriptor(java.awt.event.KeyEvent.VK_P, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_q),     new KeyDescriptor(java.awt.event.KeyEvent.VK_Q, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_r),     new KeyDescriptor(java.awt.event.KeyEvent.VK_R, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_s),     new KeyDescriptor(java.awt.event.KeyEvent.VK_S, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_t),     new KeyDescriptor(java.awt.event.KeyEvent.VK_T, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_u),     new KeyDescriptor(java.awt.event.KeyEvent.VK_U, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_v),     new KeyDescriptor(java.awt.event.KeyEvent.VK_V, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_w),     new KeyDescriptor(java.awt.event.KeyEvent.VK_W, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_x),     new KeyDescriptor(java.awt.event.KeyEvent.VK_X, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_y),     new KeyDescriptor(java.awt.event.KeyEvent.VK_Y, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_z),     new KeyDescriptor(java.awt.event.KeyEvent.VK_Z, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* TTY Function keys */
        xkbKeymap.put( Long.valueOf(XK_BackSpace),     new KeyDescriptor(java.awt.event.KeyEvent.VK_BACK_SPACE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Tab),     new KeyDescriptor(java.awt.event.KeyEvent.VK_TAB, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_ISO_Left_Tab),     new KeyDescriptor(java.awt.event.KeyEvent.VK_TAB, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Clear),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CLEAR, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Return),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Linefeed),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Pause),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAUSE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F21),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAUSE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_R1),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAUSE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Scroll_Lock),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SCROLL_LOCK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F23),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SCROLL_LOCK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_R3),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SCROLL_LOCK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Escape),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Other vendor-specific versions of TTY Function keys */
        xkbKeymap.put( Long.valueOf(osfXK_BackSpace),     new KeyDescriptor(java.awt.event.KeyEvent.VK_BACK_SPACE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Clear),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CLEAR, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Escape),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Modifier keys */
        xkbKeymap.put( Long.valueOf(XK_Shift_L),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SHIFT, java.awt.event.KeyEvent.KEY_LOCATION_LEFT));
        xkbKeymap.put( Long.valueOf(XK_Shift_R),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SHIFT, java.awt.event.KeyEvent.KEY_LOCATION_RIGHT));
        xkbKeymap.put( Long.valueOf(XK_Control_L),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.KEY_LOCATION_LEFT));
        xkbKeymap.put( Long.valueOf(XK_Control_R),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.KEY_LOCATION_RIGHT));
        xkbKeymap.put( Long.valueOf(XK_Alt_L),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ALT, java.awt.event.KeyEvent.KEY_LOCATION_LEFT));
        xkbKeymap.put( Long.valueOf(XK_Alt_R),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ALT, java.awt.event.KeyEvent.KEY_LOCATION_RIGHT));
        xkbKeymap.put( Long.valueOf(XK_Meta_L),     new KeyDescriptor(java.awt.event.KeyEvent.VK_META, java.awt.event.KeyEvent.KEY_LOCATION_LEFT));
        xkbKeymap.put( Long.valueOf(XK_Meta_R),     new KeyDescriptor(java.awt.event.KeyEvent.VK_META, java.awt.event.KeyEvent.KEY_LOCATION_RIGHT));
        xkbKeymap.put( Long.valueOf(XK_Caps_Lock),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CAPS_LOCK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Shift_Lock),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CAPS_LOCK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Misc Functions */
        xkbKeymap.put( Long.valueOf(XK_Print),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PRINTSCREEN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F22),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PRINTSCREEN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_R2),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PRINTSCREEN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Cancel),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CANCEL, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Help),     new KeyDescriptor(java.awt.event.KeyEvent.VK_HELP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Num_Lock),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUM_LOCK, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));

        /* Other vendor-specific versions of Misc Functions */
        xkbKeymap.put( Long.valueOf(osfXK_Cancel),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CANCEL, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Help),     new KeyDescriptor(java.awt.event.KeyEvent.VK_HELP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Rectangular Navigation Block */
        xkbKeymap.put( Long.valueOf(XK_Home),     new KeyDescriptor(java.awt.event.KeyEvent.VK_HOME, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_R7),     new KeyDescriptor(java.awt.event.KeyEvent.VK_HOME, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Page_Up),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Prior),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_R9),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Page_Down),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Next),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_R15),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_End),     new KeyDescriptor(java.awt.event.KeyEvent.VK_END, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_R13),     new KeyDescriptor(java.awt.event.KeyEvent.VK_END, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Insert),     new KeyDescriptor(java.awt.event.KeyEvent.VK_INSERT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Delete),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DELETE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Keypad equivalents of Rectangular Navigation Block */
        xkbKeymap.put( Long.valueOf(XK_KP_Home),     new KeyDescriptor(java.awt.event.KeyEvent.VK_HOME, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Page_Up),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Prior),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Page_Down),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Next),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_End),     new KeyDescriptor(java.awt.event.KeyEvent.VK_END, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Insert),     new KeyDescriptor(java.awt.event.KeyEvent.VK_INSERT, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Delete),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DELETE, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));

        /* Other vendor-specific Rectangular Navigation Block */
        xkbKeymap.put( Long.valueOf(osfXK_PageUp),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Prior),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_PageDown),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Next),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PAGE_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_EndLine),     new KeyDescriptor(java.awt.event.KeyEvent.VK_END, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Insert),     new KeyDescriptor(java.awt.event.KeyEvent.VK_INSERT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Delete),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DELETE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Triangular Navigation Block */
        xkbKeymap.put( Long.valueOf(XK_Left),     new KeyDescriptor(java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Up),     new KeyDescriptor(java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Right),     new KeyDescriptor(java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Down),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Keypad equivalents of Triangular Navigation Block */
        xkbKeymap.put( Long.valueOf(XK_KP_Left),     new KeyDescriptor(java.awt.event.KeyEvent.VK_KP_LEFT, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Up),     new KeyDescriptor(java.awt.event.KeyEvent.VK_KP_UP, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Right),     new KeyDescriptor(java.awt.event.KeyEvent.VK_KP_RIGHT, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Down),     new KeyDescriptor(java.awt.event.KeyEvent.VK_KP_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));

        /* Other vendor-specific Triangular Navigation Block */
        xkbKeymap.put( Long.valueOf(osfXK_Left),     new KeyDescriptor(java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Up),     new KeyDescriptor(java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Right),     new KeyDescriptor(java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Down),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DOWN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Remaining Cursor control & motion */
        xkbKeymap.put( Long.valueOf(XK_Begin),     new KeyDescriptor(java.awt.event.KeyEvent.VK_BEGIN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_KP_Begin),     new KeyDescriptor(java.awt.event.KeyEvent.VK_BEGIN, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));

        xkbKeymap.put( Long.valueOf(XK_0),     new KeyDescriptor(java.awt.event.KeyEvent.VK_0, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_1),     new KeyDescriptor(java.awt.event.KeyEvent.VK_1, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_2),     new KeyDescriptor(java.awt.event.KeyEvent.VK_2, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_3),     new KeyDescriptor(java.awt.event.KeyEvent.VK_3, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_4),     new KeyDescriptor(java.awt.event.KeyEvent.VK_4, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_5),     new KeyDescriptor(java.awt.event.KeyEvent.VK_5, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_6),     new KeyDescriptor(java.awt.event.KeyEvent.VK_6, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_7),     new KeyDescriptor(java.awt.event.KeyEvent.VK_7, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_8),     new KeyDescriptor(java.awt.event.KeyEvent.VK_8, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_9),     new KeyDescriptor(java.awt.event.KeyEvent.VK_9, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        xkbKeymap.put( Long.valueOf(XK_space),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SPACE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_exclam),     new KeyDescriptor(java.awt.event.KeyEvent.VK_EXCLAMATION_MARK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_quotedbl),     new KeyDescriptor(java.awt.event.KeyEvent.VK_QUOTEDBL, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_numbersign),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMBER_SIGN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dollar),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DOLLAR, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_ampersand),     new KeyDescriptor(java.awt.event.KeyEvent.VK_AMPERSAND, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_apostrophe),     new KeyDescriptor(java.awt.event.KeyEvent.VK_QUOTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_parenleft),     new KeyDescriptor(java.awt.event.KeyEvent.VK_LEFT_PARENTHESIS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_parenright),     new KeyDescriptor(java.awt.event.KeyEvent.VK_RIGHT_PARENTHESIS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_asterisk),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ASTERISK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_plus),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PLUS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_comma),     new KeyDescriptor(java.awt.event.KeyEvent.VK_COMMA, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_minus),     new KeyDescriptor(java.awt.event.KeyEvent.VK_MINUS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_period),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PERIOD, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_slash),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SLASH, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        xkbKeymap.put( Long.valueOf(XK_colon),     new KeyDescriptor(java.awt.event.KeyEvent.VK_COLON, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_semicolon),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SEMICOLON, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_less),     new KeyDescriptor(java.awt.event.KeyEvent.VK_LESS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_equal),     new KeyDescriptor(java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_greater),     new KeyDescriptor(java.awt.event.KeyEvent.VK_GREATER, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        xkbKeymap.put( Long.valueOf(XK_at),     new KeyDescriptor(java.awt.event.KeyEvent.VK_AT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        xkbKeymap.put( Long.valueOf(XK_bracketleft),     new KeyDescriptor(java.awt.event.KeyEvent.VK_OPEN_BRACKET, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_backslash),     new KeyDescriptor(java.awt.event.KeyEvent.VK_BACK_SLASH, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_bracketright),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CLOSE_BRACKET, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_asciicircum),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CIRCUMFLEX, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_underscore),     new KeyDescriptor(java.awt.event.KeyEvent.VK_UNDERSCORE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Super_L),     new KeyDescriptor(java.awt.event.KeyEvent.VK_WINDOWS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Super_R),     new KeyDescriptor(java.awt.event.KeyEvent.VK_WINDOWS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Menu),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CONTEXT_MENU, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_grave),     new KeyDescriptor(java.awt.event.KeyEvent.VK_BACK_QUOTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        xkbKeymap.put( Long.valueOf(XK_braceleft),     new KeyDescriptor(java.awt.event.KeyEvent.VK_BRACELEFT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_braceright),     new KeyDescriptor(java.awt.event.KeyEvent.VK_BRACERIGHT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        xkbKeymap.put( Long.valueOf(XK_exclamdown),     new KeyDescriptor(java.awt.event.KeyEvent.VK_INVERTED_EXCLAMATION_MARK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Remaining Numeric Keypad Keys */
        xkbKeymap.put( Long.valueOf(XK_KP_0),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD0, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_1),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD1, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_2),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD2, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_3),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD3, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_4),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD4, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_5),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD5, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_6),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD6, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_7),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD7, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_8),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD8, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_9),     new KeyDescriptor(java.awt.event.KeyEvent.VK_NUMPAD9, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Space),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SPACE, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Tab),     new KeyDescriptor(java.awt.event.KeyEvent.VK_TAB, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Enter),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Equal),     new KeyDescriptor(java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_R4),     new KeyDescriptor(java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Multiply),     new KeyDescriptor(java.awt.event.KeyEvent.VK_MULTIPLY, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_F26),     new KeyDescriptor(java.awt.event.KeyEvent.VK_MULTIPLY, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_R6),     new KeyDescriptor(java.awt.event.KeyEvent.VK_MULTIPLY, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Add),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ADD, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Separator),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SEPARATOR, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Subtract),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SUBTRACT, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_F24),     new KeyDescriptor(java.awt.event.KeyEvent.VK_SUBTRACT, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Decimal),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DECIMAL, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_KP_Divide),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DIVIDE, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_F25),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DIVIDE, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));
        xkbKeymap.put( Long.valueOf(XK_R5),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DIVIDE, java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD));

        /* Function Keys */
        xkbKeymap.put( Long.valueOf(XK_F1),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F1, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F2),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F2, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F3),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F3, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F4),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F4, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F5),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F5, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F6),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F6, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F7),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F7, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F8),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F8, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F9),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F9, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F10),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F10, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F11),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F11, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_F12),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F12, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Sun vendor-specific version of F11 and F12 */
        xkbKeymap.put( Long.valueOf(SunXK_F36),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F11, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_F37),     new KeyDescriptor(java.awt.event.KeyEvent.VK_F12, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* X11 keysym names for input method related keys don't always
         * match keytop engravings or Java virtual key names, so here we
         * only map constants that we've found on real keyboards.
         */
        /* Type 5c Japanese keyboard: kakutei */
        xkbKeymap.put( Long.valueOf(XK_Execute),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ACCEPT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        /* Type 5c Japanese keyboard: henkan */
        xkbKeymap.put( Long.valueOf(XK_Kanji),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CONVERT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        /* Type 5c Japanese keyboard: nihongo */
        xkbKeymap.put( Long.valueOf(XK_Henkan_Mode),     new KeyDescriptor(java.awt.event.KeyEvent.VK_INPUT_METHOD_ON_OFF, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Eisu_Shift   ), new KeyDescriptor(java.awt.event.KeyEvent.VK_ALPHANUMERIC       , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Eisu_toggle  ), new KeyDescriptor(java.awt.event.KeyEvent.VK_ALPHANUMERIC       , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Zenkaku      ), new KeyDescriptor(java.awt.event.KeyEvent.VK_FULL_WIDTH         , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Hankaku      ), new KeyDescriptor(java.awt.event.KeyEvent.VK_HALF_WIDTH         , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Hiragana     ), new KeyDescriptor(java.awt.event.KeyEvent.VK_HIRAGANA           , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Katakana     ), new KeyDescriptor(java.awt.event.KeyEvent.VK_KATAKANA           , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Romaji       ), new KeyDescriptor(java.awt.event.KeyEvent.VK_JAPANESE_ROMAN     , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Kana_Shift   ), new KeyDescriptor(java.awt.event.KeyEvent.VK_KANA               , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Kana_Lock    ), new KeyDescriptor(java.awt.event.KeyEvent.VK_KANA_LOCK          , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Muhenkan     ), new KeyDescriptor(java.awt.event.KeyEvent.VK_NONCONVERT         , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Zen_Koho     ), new KeyDescriptor(java.awt.event.KeyEvent.VK_ALL_CANDIDATES     , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Kanji_Bangou ), new KeyDescriptor(java.awt.event.KeyEvent.VK_CODE_INPUT         , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Mae_Koho     ), new KeyDescriptor(java.awt.event.KeyEvent.VK_PREVIOUS_CANDIDATE , java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));


        /* VK_KANA_LOCK is handled separately because it generates the
         * same keysym as ALT_GRAPH in spite of its different behavior.
         */

        xkbKeymap.put( Long.valueOf(XK_Multi_key),     new KeyDescriptor(java.awt.event.KeyEvent.VK_COMPOSE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Mode_switch),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ALT_GRAPH, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_ISO_Level3_Shift),     new KeyDescriptor(java.awt.event.KeyEvent.VK_ALT_GRAPH, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Editing block */
        xkbKeymap.put( Long.valueOf(XK_Redo),     new KeyDescriptor(java.awt.event.KeyEvent.VK_AGAIN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        // XXX XK_L2 == F12; TODO: add code to use only one of them depending on the keyboard type. For now, restore
        // good PC behavior and bad but old Sparc behavior.
        // keysym2JavaKeycodeHash.put( Long.valueOf(XK_L2),     new Keysym2JavaKeycode(java.awt.event.KeyEvent.VK_AGAIN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Undo),     new KeyDescriptor(java.awt.event.KeyEvent.VK_UNDO, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_L4),     new KeyDescriptor(java.awt.event.KeyEvent.VK_UNDO, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_L6),     new KeyDescriptor(java.awt.event.KeyEvent.VK_COPY, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_L8),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PASTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_L10),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CUT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_Find),     new KeyDescriptor(java.awt.event.KeyEvent.VK_FIND, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_L9),     new KeyDescriptor(java.awt.event.KeyEvent.VK_FIND, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_L3),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PROPS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        // XXX XK_L1 == F11; TODO: add code to use only one of them depending on the keyboard type. For now, restore
        // good PC behavior and bad but old Sparc behavior.
        // keysym2JavaKeycodeHash.put( Long.valueOf(XK_L1),     new Keysym2JavaKeycode(java.awt.event.KeyEvent.VK_STOP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Sun vendor-specific versions for editing block */
        xkbKeymap.put( Long.valueOf(SunXK_Again),     new KeyDescriptor(java.awt.event.KeyEvent.VK_AGAIN, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_Undo),     new KeyDescriptor(java.awt.event.KeyEvent.VK_UNDO, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_Copy),     new KeyDescriptor(java.awt.event.KeyEvent.VK_COPY, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_Paste),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PASTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_Cut),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CUT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_Find),     new KeyDescriptor(java.awt.event.KeyEvent.VK_FIND, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_Props),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PROPS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_Stop),     new KeyDescriptor(java.awt.event.KeyEvent.VK_STOP, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Apollo (HP) vendor-specific versions for editing block */
        xkbKeymap.put( Long.valueOf(apXK_Copy),     new KeyDescriptor(java.awt.event.KeyEvent.VK_COPY, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(apXK_Cut),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CUT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(apXK_Paste),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PASTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Other vendor-specific versions for editing block */
        xkbKeymap.put( Long.valueOf(osfXK_Copy),     new KeyDescriptor(java.awt.event.KeyEvent.VK_COPY, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Cut),     new KeyDescriptor(java.awt.event.KeyEvent.VK_CUT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Paste),     new KeyDescriptor(java.awt.event.KeyEvent.VK_PASTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(osfXK_Undo),     new KeyDescriptor(java.awt.event.KeyEvent.VK_UNDO, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Dead key mappings (for European keyboards) */
        xkbKeymap.put( Long.valueOf(XK_dead_grave),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_GRAVE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_acute),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_ACUTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_circumflex),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_CIRCUMFLEX, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_tilde),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_TILDE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_macron),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_MACRON, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_breve),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_BREVE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_abovedot),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_ABOVEDOT, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_diaeresis),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_DIAERESIS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_abovering),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_ABOVERING, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_doubleacute),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_DOUBLEACUTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_caron),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_CARON, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_cedilla),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_CEDILLA, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_ogonek),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_OGONEK, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_iota),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_IOTA, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_voiced_sound),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_VOICED_SOUND, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(XK_dead_semivoiced_sound),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_SEMIVOICED_SOUND, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Sun vendor-specific dead key mappings (for European keyboards) */
        xkbKeymap.put( Long.valueOf(SunXK_FA_Grave),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_GRAVE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_FA_Circum),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_CIRCUMFLEX, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_FA_Tilde),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_TILDE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_FA_Acute),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_ACUTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_FA_Diaeresis),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_DIAERESIS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(SunXK_FA_Cedilla),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_CEDILLA, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* DEC vendor-specific dead key mappings (for European keyboards) */
        xkbKeymap.put( Long.valueOf(DXK_ring_accent),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_ABOVERING, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(DXK_circumflex_accent),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_CIRCUMFLEX, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(DXK_cedilla_accent),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_CEDILLA, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(DXK_acute_accent),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_ACUTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(DXK_grave_accent),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_GRAVE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(DXK_tilde),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_TILDE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(DXK_diaeresis),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_DIAERESIS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        /* Other vendor-specific dead key mappings (for European keyboards) */
        xkbKeymap.put( Long.valueOf(hpXK_mute_acute),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_ACUTE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(hpXK_mute_grave),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_GRAVE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(hpXK_mute_asciicircum),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_CIRCUMFLEX, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(hpXK_mute_diaeresis),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_DIAERESIS, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));
        xkbKeymap.put( Long.valueOf(hpXK_mute_asciitilde),     new KeyDescriptor(java.awt.event.KeyEvent.VK_DEAD_TILDE, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD));

        xkbKeymap.put( 0L,     new KeyDescriptor(java.awt.event.KeyEvent.VK_UNDEFINED, java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN));
    };

}
