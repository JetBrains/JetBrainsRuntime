package com.jetbrains.hotkey;

public interface GlobalHotkeyProvider {
    boolean tryRegisterHotkey(Hotkey hotkey, HotkeyListener listener);
    void unregisterHotkey(Hotkey hotkey);
}
