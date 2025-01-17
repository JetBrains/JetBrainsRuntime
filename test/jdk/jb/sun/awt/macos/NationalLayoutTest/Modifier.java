/*
 * Copyright 2000-2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/*
 * Enumerates modifier combinations covered by this test
 */
public enum Modifier {

    E(),
    A(KeyEvent.VK_ALT),
    S(KeyEvent.VK_SHIFT),
    SA(KeyEvent.VK_SHIFT, KeyEvent.VK_ALT),

    // Shortcuts

    M(KeyEvent.VK_META),
    C(KeyEvent.VK_CONTROL),

    //CS(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT),
    //CA(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT),
    //CM(KeyEvent.VK_CONTROL, KeyEvent.VK_META),
    //MS(KeyEvent.VK_META, KeyEvent.VK_SHIFT),
    //MA(KeyEvent.VK_META, KeyEvent.VK_ALT),

    //CMS(KeyEvent.VK_CONTROL, KeyEvent.VK_META, KeyEvent.VK_SHIFT),
    //CMA(KeyEvent.VK_CONTROL, KeyEvent.VK_META, KeyEvent.VK_ALT),
    //CSA(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT),
    //MSA(KeyEvent.VK_META, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT),

    //CMSA(KeyEvent.VK_CONTROL, KeyEvent.VK_META, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT),

    ;

    // Holds array of int modifier values, where every int value corresponds to modifier KeyEvent.VK_ code
    private int[] modifiers;

    // Creates empty Modifier
    Modifier() {
        this(new int[0]);
    }

    // Creates Modifier using given KeyEvent.VK_ codes
    Modifier(int... modifiers) {
        for(int key : modifiers) {
            if ((key != KeyEvent.VK_CONTROL) && (key != KeyEvent.VK_META)
                    && (key != KeyEvent.VK_SHIFT) && (key != KeyEvent.VK_ALT)) {
                throw new IllegalArgumentException("Bad modifier: " + key);
            }
        }
        this.modifiers = modifiers;
    }

    // Returns array of int modifier values, where every int value corresponds to modifier KeyEvent.VK_ code
    int[] getModifiers() {
        return modifiers;
    }

    // Checks if no modifier is set
    boolean isEmpty() {
        return (modifiers.length == 0);
    }

    // Checks if modifier is Alt
    boolean isAlt() {
        return ((modifiers.length == 1) && (modifiers[0] == KeyEvent.VK_ALT));
    }

    // Checks if modifier is Shift
    boolean isShift() {
        return ((modifiers.length == 1) && (modifiers[0] == KeyEvent.VK_SHIFT));
    }

    // Checks if modifier is Alt+Shift
    boolean isAltShift() {
        List<Integer> list = Arrays.stream(modifiers).boxed().collect(Collectors.toList());
        return ((modifiers.length == 2) && list.contains(KeyEvent.VK_ALT) && list.contains(KeyEvent.VK_SHIFT));
    }

    boolean isControl() {
        return ((modifiers.length == 1) && (modifiers[0] == KeyEvent.VK_CONTROL));
    }

    boolean isCommand() {
        return ((modifiers.length == 1) && (modifiers[0] == KeyEvent.VK_META));
    }

    @Override
    public String toString() {
        if (modifiers.length == 0) {
            return "no";
        }
        return Arrays.stream(modifiers).boxed().map(i -> KeyEvent.getKeyText(i)).collect(Collectors.joining(" "));
    }

    public String toPlaintextString() {
        if (modifiers.length == 0) {
            return "no";
        }

        var result = new StringBuilder();
        var list = Arrays.stream(modifiers).boxed().toList();
        if (list.contains(KeyEvent.VK_CONTROL)) result.append("Control");
        if (list.contains(KeyEvent.VK_ALT)) result.append("Option");
        if (list.contains(KeyEvent.VK_SHIFT)) result.append("Shift");
        if (list.contains(KeyEvent.VK_META)) result.append("Command");
        return result.toString();
    }
}
