package com.jetbrains.hotkey;

import java.awt.event.KeyEvent;
import java.util.Set;

/**
 * This class represents a hotkey (keyboard shortcut) as a combination of a virtual key code
 * and modifier keys (Ctrl, Alt, Shift, Super / Win / Meta).
 */
public record Hotkey(int keyCode, Set<Modifier> modifiers) {
    public enum Modifier {
        ALT,
        CONTROL,
        SHIFT,
        SUPER,
    }

    /**
     * Returns a user-readable hotkey name, such as "Ctrl+Alt+P".
     */
    @Override
    public String toString() {
        var result = new StringBuilder();

        if (modifiers.contains(Modifier.SUPER)) {
            result.append("Super+");
        }

        if (modifiers.contains(Modifier.CONTROL)) {
            result.append("Ctrl+");
        }

        if (modifiers.contains(Modifier.ALT)) {
            result.append("Alt+");
        }

        if (modifiers.contains(Modifier.SHIFT)) {
            result.append("Shift+");
        }

        result.append(KeyEvent.getKeyText(keyCode));
        return result.toString();
    }
}
