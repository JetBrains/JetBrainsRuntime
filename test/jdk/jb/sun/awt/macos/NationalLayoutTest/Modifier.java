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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/*
 * Enumerates modifier combinations covered by this test
 */
public enum Modifier {

    E(),
    //A(KeyEvent.VK_ALT),
    //S(KeyEvent.VK_SHIFT),
    //SA(KeyEvent.VK_SHIFT, KeyEvent.VK_ALT),

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

    @Override
    public String toString() {
        if (modifiers.length == 0) {
            return "no";
        }
        return Arrays.stream(modifiers).boxed().map(i -> KeyEvent.getKeyText(i)).collect(Collectors.joining(" "));
    }
}
