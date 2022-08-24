package com.jetbrains.hotkey;

import java.awt.event.KeyEvent;
import java.util.Set;

public record Hotkey(int keyCode, Set<Modifier> modifiers) {
    public enum Modifier {
        ALT,
        CONTROL,
        SHIFT,
        SUPER,
    }

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
