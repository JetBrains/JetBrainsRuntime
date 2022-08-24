package com.jetbrains.hotkey;

import java.util.Set;

public record Hotkey(int keyCode, Set<Modifier> modifiers) {
    public enum Modifier {
        ALT,
        CONTROL,
        SHIFT,
        SUPER,
    }
}
